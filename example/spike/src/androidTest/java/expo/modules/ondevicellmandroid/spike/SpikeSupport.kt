//
//  SpikeSupport.kt — the real bridge, shared measurements, text metrics
//
//  `SpikeBridge` is the important half of this file. Wherever the bridge can
//  answer a register question, the spike asks the bridge — `MlKitPresence`
//  ->`GenAiEngine` -> `PromptEncoding` / `ErrorMapping` / `StreamAccumulator`,
//  the same objects, in the same order, with the same arguments the shipped
//  TypeScript half would produce. That is what makes a verdict here a verdict
//  about the shipped provider rather than about a test's private copy of it.
//
//  The one thing deliberately NOT driven through the bridge is anything the
//  module does not do: `download()` (D3 — `availability()` is a question, not a
//  command) and raw status ints. Those go through `SpikeMlKit`.
//

package expo.modules.ondevicellmandroid.spike

import expo.modules.ondevicellmandroid.MlKitPresence
import expo.modules.ondevicellmandroid.core.BridgeGenerationOptions
import expo.modules.ondevicellmandroid.core.BridgeMessage
import expo.modules.ondevicellmandroid.core.BridgeRequest
import expo.modules.ondevicellmandroid.core.BridgeRole
import expo.modules.ondevicellmandroid.core.GenAiBridge

object SpikeBridge {
  @Volatile
  var lastError: String? = null
    private set

  /**
   * The real engine, built the real way.
   *
   * `MlKitPresence.createEngine()` is the firewall: one guarded
   * `Class.forName` probe, then reflective construction. A null here means the
   * shipped provider would report `unsupportedPlatform` on this device, which is
   * register item 11's whole question — so it is reported, never worked around.
   */
  val engine: GenAiBridge? by lazy {
    try {
      MlKitPresence.createEngine()
    } catch (throwable: Throwable) {
      SpikeWatch.record("bridge", throwable)
      lastError = "${throwable.javaClass.name}: ${throwable.message}"
      null
    }
  }

  fun user(text: String): BridgeMessage = BridgeMessage(BridgeRole.USER, text)

  fun model(text: String): BridgeMessage = BridgeMessage(BridgeRole.ASSISTANT, text)

  fun system(text: String): BridgeMessage = BridgeMessage(BridgeRole.SYSTEM, text)

  fun request(
    messages: List<BridgeMessage>,
    temperature: Double? = null,
    maxOutputTokens: Int? = null,
  ): BridgeRequest = BridgeRequest(
    messages = messages,
    options = BridgeGenerationOptions(
      temperature = temperature,
      maxOutputTokens = maxOutputTokens,
    ),
  )

  /** `GenAiBridge.generate`, timed, described in the same shape as the SDK path. */
  suspend fun generate(
    engine: GenAiBridge,
    messages: List<BridgeMessage>,
    temperature: Double? = null,
    maxOutputTokens: Int? = null,
  ): SpikeGeneration {
    val started = System.currentTimeMillis()
    val result = engine.generate(request(messages, temperature, maxOutputTokens))
    return SpikeGeneration(
      text = result.text,
      finishReason = result.finishReason,
      ms = System.currentTimeMillis() - started,
      firstChunkMs = null,
      chunks = 0,
    )
  }
}

/**
 * Run one generation, record it or record why it did not happen, and never
 * throw.
 *
 * Returning null rather than throwing is what lets a test take three
 * measurements and still form a verdict when the second one failed — which on a
 * device that throttles by battery quota (`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`,
 * an Android-only failure mode with no iOS analogue, §11.6) is a realistic
 * middle of a run.
 */
suspend fun SpikeCtx.generated(
  key: String,
  mlKit: SpikeMlKit?,
  engine: GenAiBridge?,
  budgetMs: Long = SpikeBudget.generateMs,
  block: suspend () -> SpikeGeneration,
): SpikeGeneration? {
  return try {
    val result = kotlinx.coroutines.withTimeoutOrNull(budgetMs) { block() }
    if (result == null) {
      put(key, mapOf("timedOutAfterMs" to budgetMs))
      null
    } else {
      put(key, result.summary())
      result
    }
  } catch (throwable: Throwable) {
    recordError("$key.error", mlKit, engine, throwable)
    null
  }
}

/**
 * Get the real engine, or emit a `blocked` verdict and return null.
 *
 * Note which facts this distinguishes, because on a farm device they are the
 * three likeliest outcomes and they mean entirely different things: the SDK is
 * not in the build at all; the SDK is in the build but reflection could not
 * reach the engine (on a minified APK, that is R8 and it is tripwire T1 firing);
 * or everything loaded and the device simply has no AICore (which is not this
 * function's business — that surfaces later, as a `GenAiException`).
 */
fun SpikeCtx.requireBridge(): GenAiBridge? {
  val engine = SpikeBridge.engine
  if (engine == null) {
    put("mlKitPresenceProbe", MlKitPresence.isAvailable)
    put("bridgeError", SpikeBridge.lastError ?: "")
    put("minifiedGuess", SpikeEnv.likelyMinified)
    verdict(
      Verdict.BLOCKED,
      if (MlKitPresence.isAvailable) {
        "the presence probe passed but MlKitPresence.createEngine() returned null — " +
          "GenAiEngine could not be constructed by name" +
          if (SpikeEnv.likelyMinified) " (release APK: suspect R8, see consumer-rules.pro)" else ""
      } else {
        "MlKitPresence.isAvailable is false: com.google.mlkit.genai.prompt.Generation is " +
          "not in this build"
      },
    )
    return null
  }
  return engine
}

/**
 * Record a throwable twice: as the SDK reports it, and as the shipped taxonomy
 * sees it.
 *
 * Both halves matter and they answer different questions. The SDK half
 * (`rawCode`, `codeName`, `retryDelayMs`) is register item 9's literal
 * question — *do the error codes arrive as the table expects?* The taxonomy half
 * runs the very same throwable back through `GenAiEngine.mapThrowable` ->
 * `ErrorMapping`, so the verdict records what a JS caller would actually have
 * received. A disagreement between the two is the most actionable thing this
 * suite can find.
 */
fun SpikeCtx.recordError(
  key: String,
  mlKit: SpikeMlKit?,
  engine: GenAiBridge?,
  throwable: Throwable,
): SpikeErrorInfo? {
  SpikeWatch.record(item, throwable)
  val info = try {
    mlKit?.errorInfo(throwable)
  } catch (nested: Throwable) {
    SpikeWatch.record(item, nested)
    null
  }
  val mapped = try {
    engine?.mapThrowable(throwable)?.toMap()
  } catch (nested: Throwable) {
    SpikeWatch.record(item, nested)
    null
  }
  put(
    key,
    linkedMapOf<String, Any?>(
      "sdk" to (info?.summary() ?: throwableSummary(throwable)),
      "taxonomy" to (mapped ?: "mapThrowable returned null (not a GenAiException)"),
    ),
  )
  return info
}

/**
 * Did the generation recorded under [key] end in a timeout rather than a
 * refusal?
 *
 * The distinction decides verdicts. "The model refused a 5,000-token request" and
 * "our 90-second budget expired while it was still thinking about a 5,000-token
 * request" are opposite findings, and both arrive here as a null generation.
 */
fun SpikeCtx.timedOut(key: String): Boolean =
  (data[key] as? Map<*, *>)?.containsKey("timedOutAfterMs") == true

/**
 * Measurements one test takes that another can use.
 *
 * Every field is nullable and every reader treats null as "not measured", never
 * as a default. Test ordering inside an instrumentation run is not contractual
 * and `--test-targets` may run one class alone, so a test that *needed* an
 * earlier test's number would be a test that reports nonsense when run on its
 * own. They are used to sharpen an interpretation, never to form one.
 */
object SpikeState {
  @Volatile var statusName: String? = null
  @Volatile var statusRaw: Int? = null

  /** Item 0's verdict, so later tests can say "and 00 said the device was viable". */
  @Volatile var viable: Boolean? = null

  @Volatile var tokenLimit: Int? = null
  @Volatile var systemPromptSupported: Boolean? = null

  /** Item 4's unconstrained stream duration — item 3 compares its cancel timing to it. */
  @Volatile var fullStreamMs: Long? = null
  @Volatile var fullStreamChunks: Int? = null

  /** Item 5's near-limit input, reused by item 8 rather than sized twice. */
  @Volatile var nearLimitText: String? = null
  @Volatile var nearLimitTokens: Int? = null

  suspend fun systemPromptSupport(mlKit: SpikeMlKit): Boolean {
    systemPromptSupported?.let { return it }
    val answer = try {
      mlKit.isSystemPromptAvailable()
    } catch (throwable: Throwable) {
      SpikeWatch.record("sysprompt", throwable)
      // The library caches a failed probe as `false` — the safe direction,
      // because folding always delivers the instructions. Mirror it, so the
      // spike's encoding matches the provider's on this device.
      false
    }
    systemPromptSupported = answer
    return answer
  }
}

/** Text metrics. Pure, so the interpretation in each verdict is auditable. */
object SpikeText {
  /**
   * Fraction of cased letters that are upper case. The uppercase-only system
   * instruction in item 2 is measured, not eyeballed, because "mostly obeyed"
   * and "obeyed" are different findings and a human reading logcat weeks later
   * cannot re-derive the number.
   */
  fun uppercaseRatio(text: String): Double {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return -1.0
    return letters.count { it.isUpperCase() }.toDouble() / letters.length
  }

  /** Does the output contain the role frame it was fed? (Item 1, frame echo.) */
  fun echoesFrame(text: String): Boolean =
    text.contains("User:") || text.contains("Model:") || text.contains("System:")

  /**
   * A crude refusal detector for item 9's safety probe.
   *
   * Crude on purpose: the question is only whether a refusal arrives as ORDINARY
   * TEXT rather than as a typed error (D5 — there is no `guardrail` code on
   * Android and there cannot be one), so the bar is "does this read like a
   * decline", and the raw text is in the verdict for a human to check.
   */
  fun looksLikeRefusal(text: String): Boolean {
    val lower = text.lowercase()
    return listOf(
      "i can't", "i cannot", "i can not", "i won't", "i will not", "i'm not able",
      "i am not able", "i'm unable", "i am unable", "sorry", "apologi",
      "not appropriate", "inappropriate", "can't help", "cannot help",
      "not comfortable", "i'd rather not", "i would rather not",
    ).any { lower.contains(it) }
  }

  fun median(values: List<Long>): Long {
    if (values.isEmpty()) return -1L
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
  }

  fun percentile(values: List<Long>, p: Double): Long {
    if (values.isEmpty()) return -1L
    val sorted = values.sorted()
    val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
  }

  fun mean(values: List<Long>): Long =
    if (values.isEmpty()) -1L else values.sum() / values.size
}

/**
 * Size an input to a target token count using `countTokens`.
 *
 * Items 5 and 8 both need "an input worth roughly N tokens", and guessing from
 * character counts would make the measurement meaningless — the whole point of
 * item 8 is that we do not know the tokens-per-character ratio. So the sizing is
 * done with the counter itself, through the bridge, which also means the count
 * includes the role frame exactly as a real request would (D4's caveat: the
 * count is exact for the request we actually build).
 *
 * Bounded at [maxRounds] because every round is a real IPC round trip to a
 * system service, and an unbounded convergence loop against a beta SDK on a
 * device nobody can watch is how a 30-minute farm allowance disappears.
 */
object SpikeSizing {
  /** Deliberately ordinary prose: unusual tokens would skew the ratio. */
  const val UNIT: String = "The quick brown fox jumps over the lazy dog near the river bank. "

  data class Sized(
    val text: String,
    val repeats: Int,
    val tokens: Int,
    val rounds: Int,
    val tokensPerUnit: Double,
  ) {
    fun summary(): Map<String, Any?> = linkedMapOf(
      "repeats" to repeats,
      "tokens" to tokens,
      "chars" to text.length,
      "rounds" to rounds,
      "tokensPerUnit" to tokensPerUnit,
    )
  }

  suspend fun sizeTo(
    engine: GenAiBridge,
    targetTokens: Int,
    maxRounds: Int = 6,
    tolerance: Double = 0.03,
  ): Sized {
    var repeats = 8
    var tokens = count(engine, repeats)
    var tokensPerUnit = tokens.toDouble() / repeats
    var rounds = 1

    while (rounds < maxRounds) {
      if (tokensPerUnit <= 0.0) break
      val ideal = (targetTokens / tokensPerUnit).toInt().coerceAtLeast(1)
      if (ideal == repeats) break
      repeats = ideal
      tokens = count(engine, repeats)
      rounds += 1
      tokensPerUnit = tokens.toDouble() / repeats
      val error = kotlin.math.abs(tokens - targetTokens).toDouble() / targetTokens
      if (error <= tolerance) break
    }

    return Sized(
      text = UNIT.repeat(repeats),
      repeats = repeats,
      tokens = tokens,
      rounds = rounds,
      tokensPerUnit = tokensPerUnit,
    )
  }

  private suspend fun count(engine: GenAiBridge, repeats: Int): Int =
    engine.countTokens(SpikeBridge.request(listOf(SpikeBridge.user(UNIT.repeat(repeats)))))
}
