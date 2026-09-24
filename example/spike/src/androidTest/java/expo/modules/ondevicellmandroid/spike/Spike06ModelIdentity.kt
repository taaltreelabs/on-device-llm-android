//
//  Spike06ModelIdentity.kt — PROVISIONAL register item 6
//
//  THE QUESTION. Two halves: what does `getBaseModelName()` return, and does
//  `checkStatus()` ever hand back a value outside its four documented states?
//
//  Why the second half is not paranoia. `checkStatus()` returns a bare `Int`, not
//  an enum (research §5). Four betas shipped in eight months with an explicit
//  no-backward-compatibility promise, and `AvailabilityMapping` already degrades
//  an unrecognised value to `modelNotReady` with the raw number in `detail`
//  rather than crashing or lying. This test is what would tell us the degrade
//  path had started firing — and the four constants are recorded as VALUES, so a
//  renumbering (which no amount of `when` matching would notice) is visible too.
//
//  Why the first half matters. The artifact carries a `nano-v(\d+)` regex used
//  together with `getBaseModelName()` to gate features by model version, and
//  variants move under devices by OTA: the Aug 2025 blog put Pixel 9 Pro on
//  nano-v2 where today's page has the whole Pixel 9 family on nano-v3 (§6). So
//  the model label is the only honest identifier, and `capabilities().modelLabel`
//  is where a consumer reads it.
//
//  The three other feature probes (`caching`, `structuredOutput`, `thinking`) are
//  free to ask and worth recording: the library hard-codes `supportsGuidedGeneration
//  = false` regardless of what the SDK says (D2's capability reasoning — a `true`
//  the bridge cannot honour would send the router at a provider about to fail), so
//  a device answering `true` is a documented divergence, not a bug.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike06ModelIdentity {

  private companion object {
    const val SAMPLES = 3
    const val SAMPLE_GAP_MS = 750L
    val KNOWN_STATES = setOf("UNAVAILABLE", "DOWNLOADABLE", "DOWNLOADING", "AVAILABLE")
  }

  @Test
  fun modelIdentity() = runSpike("06") { ctx ->
    val mlKit = ctx.requireMlKit() ?: return@runSpike
    val engine = SpikeBridge.engine

    ctx.put("featureStatusConstants", mlKit.featureStatusConstants())

    // ---- getBaseModelName --------------------------------------------------
    val name = try {
      mlKit.baseModelName()
    } catch (throwable: Throwable) {
      ctx.recordError("getBaseModelNameError", mlKit, engine, throwable)
      null
    }
    ctx.put("getBaseModelName", name ?: "")
    val nanoVersion = name?.let { Regex("nano-v(\\d+)").find(it)?.groupValues?.getOrNull(1) }
    ctx.put("nanoVersion", nanoVersion ?: "")

    val label = try {
      engine?.capabilities()?.get("modelLabel")
    } catch (throwable: Throwable) {
      ctx.recordError("capabilitiesError", mlKit, engine, throwable)
      null
    }
    ctx.put("capabilitiesModelLabel", label ?: "")
    ctx.put("labelMatchesBaseModelName", name != null && label == name)

    // ---- checkStatus, sampled ----------------------------------------------
    val observed = mutableListOf<Any>()
    val names = mutableListOf<String>()
    repeat(SAMPLES) { index ->
      try {
        val raw = mlKit.checkStatusRaw()
        observed += raw
        names += mlKit.statusName(raw)
      } catch (throwable: Throwable) {
        ctx.recordError("checkStatusError$index", mlKit, engine, throwable)
        observed += "threw:${throwable.javaClass.simpleName}"
        names += "THREW"
      }
      if (index < SAMPLES - 1) delay(SAMPLE_GAP_MS)
    }
    ctx.put("checkStatusSamples", observed)
    ctx.put("checkStatusNames", names)
    ctx.put("checkStatusStable", names.distinct().size == 1)

    val unexpected = names.filter { it !in KNOWN_STATES && it != "THREW" }
    ctx.put("unexpectedStatusValues", unexpected)

    // ---- the free feature probes ------------------------------------------
    val flags: Map<String, Any?> = try {
      mlKit.featureFlags()
    } catch (throwable: Throwable) {
      ctx.recordError("featureFlagsError", mlKit, engine, throwable)
      emptyMap()
    }
    ctx.put("featureFlags", flags)
    ctx.put(
      "libraryReportsStructuredOutput",
      "false (hard-coded; D2 — a capability the bridge cannot honour must not be advertised)",
    )

    when {
      // Every probe threw: this is a device with no AICore to ask, not a finding
      // about the model label. Without this branch the blank-name branch below
      // would report `inconclusive` and quietly imply the question was measured.
      names.all { it == "THREW" } && name == null ->
        ctx.verdict(
          Verdict.BLOCKED,
          "every checkStatus() sample and getBaseModelName() threw — AICore cannot be reached " +
            "on this device, so neither half of item 6 is measured. See the recorded errors.",
        )

      unexpected.isNotEmpty() ->
        ctx.verdict(
          Verdict.REFUTED,
          "checkStatus() returned $unexpected — outside the four documented FeatureStatus " +
            "states. AvailabilityMapping's degrade-to-modelNotReady path is now live code, " +
            "and the mapping table needs a fifth row.",
        )

      name.isNullOrBlank() ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "getBaseModelName() returned ${if (name == null) "an error" else "a blank string"}; " +
            "the library omits modelLabel entirely in that case, which is correct but means a " +
            "consumer cannot tell which Nano variant answered. checkStatus() stayed inside the " +
            "four known states ($names).",
        )

      else ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "getBaseModelName()='$name'" +
            (nanoVersion?.let { " (nano-v$it)" } ?: " (no nano-vN match — the artifact's own " +
              "regex would not recognise this label)") +
            ", capabilities().modelLabel agrees=${label == name}, and checkStatus() stayed " +
            "inside the four known states across $SAMPLES samples ($names).",
        )
    }
  }
}
