//
//  SpikeReport.kt — the wave-2 spike's only output channel
//
//  ############################################################################
//  # THE REPORTING CONTRACT                                                   #
//  #                                                                          #
//  # Every test in this suite ALWAYS PASSES as a JUnit test and says what it   #
//  # found in a structured verdict instead. That is not laziness; it is the    #
//  # only design that works here.                                             #
//  #                                                                          #
//  # The suite runs on Firebase Test Lab physical devices. Nobody can attach   #
//  # a debugger, re-run one line, or iterate: a run costs minutes of a 30      #
//  # minute/day free allowance and arrives as a logcat dump hours after it was #
//  # designed. A JUnit failure in that setting communicates one bit — "the     #
//  # wall was hit" — and destroys the rest of the run's information. A device  #
//  # without AICore is not a test failure; it is a MEASUREMENT, and the        #
//  # harness's job is to report reality, including the reality that reality    #
//  # could not be reached.                                                    #
//  #                                                                          #
//  # So: verdicts, not assertions. `blocked` is a first-class, expected,       #
//  # perfectly clean outcome.                                                 #
//  #                                                                          #
//  # The ONE exception is `SpikeHarnessBug`, thrown for states this harness    #
//  # believes impossible (two terminal stream events, say). Those are bugs in  #
//  # the spike, not findings about the device, and they fail JUnit-style so    #
//  # they cannot be mistaken for data — but only AFTER the verdict has been    #
//  # emitted.                                                                 #
//  ############################################################################
//
//  Two sinks, because Test Lab collects two kinds of artifact and either one
//  can be the one that survives:
//
//   1. `Log.i("SPIKE_RESULT", <one-line JSON>)`. Streamed, so it survives a run
//      that is killed by the overall `--timeout`, a device reboot, or a crash in
//      a later test. This is the primary channel. Truncated to stay under
//      logcat's per-line limit.
//   2. `<app external files dir>/spike-results.jsonl`, one untruncated JSON
//      object per line, pulled with `--directories-to-pull`. This is the
//      complete record, and the one to read when a verdict's `data` matters.
//
//  A failure to write the file never affects a verdict: a harness that cannot
//  report its findings because the reporting failed is the one outcome worse
//  than a blocked verdict.
//

package expo.modules.ondevicellmandroid.spike

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The four answers a spike item can give. Deliberately not five: every outcome
 * this harness can reach maps onto one of these, which is what makes a run
 * greppable.
 *
 * - [CONFIRMED] — the PROVISIONAL assumption held, and the data says how.
 * - [REFUTED] — the assumption is wrong, and the data says what happened
 *   instead. This is the most valuable verdict in the suite.
 * - [BLOCKED] — the measurement could not be taken: no ML Kit classes, no
 *   AICore, model not downloaded, budget exhausted. Says nothing about the
 *   assumption. **Expected on a farm device without Gemini Nano.**
 * - [INCONCLUSIVE] — the measurement ran but does not decide the question
 *   (the model could not do the control task, one sample, ambiguous timings).
 */
enum class Verdict(val wire: String) {
  CONFIRMED("confirmed"),
  REFUTED("refuted"),
  BLOCKED("blocked"),
  INCONCLUSIVE("inconclusive"),
}

/** Thrown only for impossible states — see the header. */
class SpikeHarnessBug(message: String) : AssertionError(message)

object SpikeReport {
  const val TAG: String = "SPIKE_RESULT"

  /**
   * Logcat drops or truncates very long lines (the binary buffer's per-entry
   * payload is 4 KiB including the tag and priority byte). Long strings are cut
   * for the log only; the file always gets the whole thing.
   */
  private const val LOGCAT_BUDGET = 3_000

  private const val FILE_NAME = "spike-results.jsonl"

  private val lock = Any()

  @Volatile
  private var headerWritten = false

  /**
   * Emit one verdict. Never throws.
   *
   * @param item the register item, zero-padded (`"00"`… `"12"`), so a shell
   *   sort over the logcat grep reads in register order.
   */
  fun emit(
    item: String,
    verdict: Verdict,
    detail: String,
    data: Map<String, Any?> = emptyMap(),
    ms: Long = -1L,
  ) {
    writeHeaderOnce()
    val json = try {
      JSONObject().apply {
        put("item", item)
        put("verdict", verdict.wire)
        put("detail", detail)
        put("data", toJson(data))
        put("ms", ms)
      }.toString()
    } catch (throwable: Throwable) {
      // Even the JSON encoder is not allowed to lose a verdict.
      "{\"item\":\"$item\",\"verdict\":\"${verdict.wire}\"," +
        "\"detail\":\"report encoding failed: ${throwable.javaClass.simpleName}\"," +
        "\"data\":{},\"ms\":$ms}"
    }
    log(json)
    appendToFile(json)
  }

  /**
   * A progress line — the same tag and the same JSON shape, with the item
   * suffixed `#progress` so a parser can drop them and a human can read them.
   *
   * Item 0's download is the reason this exists. A ten-minute budget with
   * nothing on the wire is indistinguishable from a hung harness, and on Test
   * Lab nobody can look. Progress lines every fifteen seconds turn "we learned
   * nothing" into "it moved 180 MB in nine minutes and was still going".
   */
  fun progress(item: String, message: String) {
    emit(
      item = "$item#progress",
      verdict = Verdict.INCONCLUSIVE,
      detail = message,
      data = emptyMap(),
      ms = -1L,
    )
  }

  /**
   * One line per run identifying the device, written before the first verdict.
   *
   * Without it a pulled results file is unattributable, and on a farm that is
   * fatal: "the download timed out" means something entirely different on a
   * Pixel 9 than on a device that was never on the allowlist.
   */
  private fun writeHeaderOnce() {
    synchronized(lock) {
      if (headerWritten) return
      headerWritten = true
    }
    val data = linkedMapOf<String, Any?>(
      "model" to Build.MODEL,
      "device" to Build.DEVICE,
      "manufacturer" to Build.MANUFACTURER,
      "fingerprint" to Build.FINGERPRINT,
      "sdkInt" to Build.VERSION.SDK_INT,
      "release" to Build.VERSION.RELEASE,
      "debuggable" to SpikeEnv.debuggable,
      "variantHint" to SpikeEnv.variantHint,
      "appPackage" to SpikeEnv.appPackage,
      "resultsFile" to (resultsFile()?.absolutePath ?: "unavailable"),
      "mlKitClassesPresent" to SpikeMlKitFactory.classesPresent,
    )
    val json = try {
      JSONObject().apply {
        put("item", "_run")
        put("verdict", Verdict.INCONCLUSIVE.wire)
        put("detail", "run header — device identity, not a verdict")
        put("data", toJson(data))
        put("ms", 0)
      }.toString()
    } catch (throwable: Throwable) {
      "{\"item\":\"_run\",\"verdict\":\"inconclusive\",\"detail\":\"header encoding failed\"," +
        "\"data\":{},\"ms\":0}"
    }
    log(json)
    appendToFile(json)
  }

  private fun log(json: String) {
    try {
      Log.i(TAG, if (json.length <= LOGCAT_BUDGET) json else truncatedLine(json))
    } catch (throwable: Throwable) {
      // Nothing left to do with a logging failure.
    }
  }

  /**
   * A truncated line stays valid JSON — a half-written object in the middle of a
   * logcat dump is the kind of thing that breaks a parser hours later, on the
   * far side of an expensive run.
   */
  private fun truncatedLine(json: String): String = try {
    JSONObject().apply {
      put("item", JSONObject(json).optString("item"))
      put("verdict", JSONObject(json).optString("verdict"))
      put("detail", "TRUNCATED FOR LOGCAT — read spike-results.jsonl for the full record")
      put("data", JSONObject().put("head", json.take(LOGCAT_BUDGET)))
      put("ms", JSONObject(json).optLong("ms", -1L))
    }.toString()
  } catch (throwable: Throwable) {
    json.take(LOGCAT_BUDGET)
  }

  private fun appendToFile(json: String) {
    try {
      val file = resultsFile() ?: return
      synchronized(lock) {
        file.parentFile?.mkdirs()
        file.appendText(json + "\n")
      }
    } catch (throwable: Throwable) {
      // Scoped storage, a read-only sdcard, a device with no external volume at
      // all: logcat is still carrying every verdict.
    }
  }

  /**
   * The app's own external files directory — `/sdcard/Android/data/<pkg>/files`
   * — which needs no permission on any API level and is exactly what Test Lab's
   * `--directories-to-pull` can fetch. Falls back to internal storage, which
   * Test Lab cannot pull but `adb` can on a local device.
   */
  fun resultsFile(): File? = try {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    File(dir, FILE_NAME)
  } catch (throwable: Throwable) {
    null
  }

  private fun toJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { obj ->
      value.forEach { (k, v) -> obj.put(k.toString(), toJson(v)) }
    }
    is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is IntArray -> JSONArray().also { arr -> value.forEach { arr.put(it) } }
    is LongArray -> JSONArray().also { arr -> value.forEach { arr.put(it) } }
    is String, is Number, is Boolean -> value
    else -> value.toString()
  }
}

/** Device/build facts the verdicts are read against. */
object SpikeEnv {
  /**
   * Debug APK or release APK.
   *
   * The Expo template debug-signs the release build, which is what makes the
   * T1 run installable on a farm device — so the signature cannot tell the two
   * apart, but the debuggable flag can, and unlike `BuildConfig` it cannot be
   * renamed by R8.
   */
  val debuggable: Boolean by lazy {
    try {
      val info = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
      (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (throwable: Throwable) {
      true
    }
  }

  /** `-e spikeVariant release-minified`, for labelling a run unambiguously. */
  val variantHint: String by lazy { SpikeBudget.stringArg("spikeVariant", "") }

  val appPackage: String by lazy {
    try {
      InstrumentationRegistry.getInstrumentation().targetContext.packageName
    } catch (throwable: Throwable) {
      "unknown"
    }
  }

  /** `true` when the app APK is a non-debuggable (release) build. */
  val likelyMinified: Boolean
    get() = !debuggable
}

/**
 * Time budgets. Every one is overridable with an instrumentation argument so a
 * farm run can be widened without rebuilding an APK — which matters when a
 * rebuild-and-upload cycle is the slowest step in the loop.
 */
object SpikeBudget {
  /** Register item 0's download budget: ten minutes, as the mandate specifies. */
  val downloadMs: Long = longArg("spikeDownloadBudgetMs", 600_000L)

  /** How often the download loop reports progress. */
  val downloadProgressEveryMs: Long = longArg("spikeDownloadProgressMs", 15_000L)

  /** Whole-test budget. Every test is abandoned, with a verdict, at this point. */
  val testMs: Long = longArg("spikeTestBudgetMs", 180_000L)

  /** A single generation. */
  val generateMs: Long = longArg("spikeGenerateBudgetMs", 90_000L)

  /** Test 00's whole budget has to cover the download. */
  val probeMs: Long = longArg("spikeProbeBudgetMs", downloadMs + 120_000L)

  fun longArg(name: String, fallback: Long): Long = try {
    InstrumentationRegistry.getArguments()?.getString(name)?.trim()?.toLongOrNull() ?: fallback
  } catch (throwable: Throwable) {
    fallback
  }

  fun stringArg(name: String, fallback: String): String = try {
    InstrumentationRegistry.getArguments()?.getString(name) ?: fallback
  } catch (throwable: Throwable) {
    fallback
  }
}

/**
 * The suite-wide `NoClassDefFoundError` watch (register item 11).
 *
 * D2's firewall rests on every ML Kit reference being confined to one class. If
 * that rule were ever broken — or if R8 removed something the reflection needs —
 * the symptom is a linkage error from an unexpected place, and it could surface
 * in any test. So every test records them here and item 11 reports the lot.
 */
object SpikeWatch {
  private val lock = Any()
  private val linkage = mutableListOf<String>()
  private val all = mutableListOf<String>()

  fun record(item: String, throwable: Throwable) {
    val entry = "$item:${throwable.javaClass.name}:${(throwable.message ?: "").take(160)}"
    synchronized(lock) {
      all += entry
      if (isLinkage(throwable)) linkage += entry
    }
  }

  fun linkageErrors(): List<String> = synchronized(lock) { linkage.toList() }

  fun linkageCount(): Int = synchronized(lock) { linkage.size }

  fun allErrors(): List<String> = synchronized(lock) { all.toList() }

  /**
   * The runtime symptoms of a shape mismatch: a class that is not there, a
   * method that is not there, a field that moved. These are what register items
   * 10 (`-Xskip-metadata-version-check` holding at runtime) and 11 (the firewall
   * surviving R8) actually look like when they fail.
   */
  fun isLinkage(throwable: Throwable): Boolean = throwable is NoClassDefFoundError ||
    throwable is ClassNotFoundException ||
    throwable is NoSuchMethodError ||
    throwable is NoSuchFieldError ||
    throwable is AbstractMethodError ||
    throwable is IncompatibleClassChangeError ||
    throwable is ExceptionInInitializerError ||
    throwable is UnsatisfiedLinkError ||
    throwable is VerifyError
}
