//
//  GenAiEngine.kt
//  OnDeviceLlm — Android
//
//  **The only file in this module that imports `com.google.mlkit.genai.*`.**
//
//  That is a hard rule, not a style preference. `genai-prompt` is a
//  `compileOnly` dependency (DECISIONS.md D33), so on a device whose app did
//  not opt in, these classes do not exist. A class that references an absent
//  type fails when it is loaded, so every such reference is confined here and
//  this class is constructed only through `MlKitPresence.createEngine()`, which
//  probes first and instantiates by name. Keep it that way: one ML Kit import
//  in any other file turns a clean `unsupportedPlatform` into a crash.
//
//  Everything here is glue. The decisions — the role encoding, the error table,
//  the availability mapping, the delta tripwire — live in `core/`, are pure,
//  and are unit-tested. This file translates them into SDK calls and back,
//  because that half can only be verified on hardware we do not have (§6).
//
//  API surface used, verified by `javap` against the shipping 1.0.0-beta4 AARs:
//
//  ```java
//  Generation.getClient(GenerationConfig): GenerativeModel
//  GenerativeModel#checkStatus(): Int                      // FeatureStatus
//  GenerativeModel#getTokenLimit(): Int
//  GenerativeModel#getBaseModelName(): String              // e.g. "nano-v3"
//  GenerativeModel#isSystemPromptAvailable(): Boolean
//  GenerativeModel#warmup()
//  GenerativeModel#countTokens(GenerateContentRequest): CountTokensResponse
//  GenerativeModel#generateContent(GenerateContentRequest): GenerateContentResponse
//  GenerativeModel#generateContent(GenerateContentRequest, StreamingCallback): GenerateContentResponse
//  GenerativeModel#close()
//  ```
//

package expo.modules.ondevicellmandroid

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import expo.modules.ondevicellmandroid.core.AvailabilityMapping
import expo.modules.ondevicellmandroid.core.BridgeAvailability
import expo.modules.ondevicellmandroid.core.BridgeErrorPayload
import expo.modules.ondevicellmandroid.core.BridgeRequest
import expo.modules.ondevicellmandroid.core.BridgeResult
import expo.modules.ondevicellmandroid.core.BridgeStreamEvent
import expo.modules.ondevicellmandroid.core.BridgeUsage
import expo.modules.ondevicellmandroid.core.EncodedPrompt
import expo.modules.ondevicellmandroid.core.ErrorMapping
import expo.modules.ondevicellmandroid.core.FeatureStatusCode
import expo.modules.ondevicellmandroid.core.GenAiBridge
import expo.modules.ondevicellmandroid.core.GenAiErrorCode
import expo.modules.ondevicellmandroid.core.PromptEncoding
import expo.modules.ondevicellmandroid.core.StreamAccumulator
import kotlinx.coroutines.CancellationException

@Suppress("unused") // Instantiated reflectively by MlKitPresence.createEngine().
class GenAiEngine : GenAiBridge {

  /**
   * One client for the module's lifetime.
   *
   * `GenerativeModel` is a connection to a system service, not a conversation:
   * it holds no history (`contents` is passed per request), so there is nothing
   * stateful to isolate and the per-request rebuild the Apple provider does
   * for `LanguageModelSession` has no counterpart. Building one per request
   * would only re-bind to AICore each time.
   *
   * **`ModelReleaseStage.STABLE` is set explicitly.** The ML Kit GenAI
   * Additional Terms state: *"You may not use any Services identified as
   * 'Preview' or 'Experimental Access' … for production use"* (§7). Relying on
   * the SDK's default to be STABLE would put a licence term at the mercy of a
   * beta's default value.
   *
   * Note the doc-vs-artifact conflict resolved in §1: Google's Gemma 4 blog
   * uses `ModelConfig.releaseTrack` / `ModelReleaseTrack`. The shipping
   * artifact has `releaseStage` / `ModelReleaseStage`. The artifact wins.
   */
  private val client: GenerativeModel by lazy {
    val modelConfig = ModelConfig.Builder()
      .apply { releaseStage = ModelReleaseStage.STABLE }
      .build()
    val config = GenerationConfig.Builder()
      .apply { this.modelConfig = modelConfig }
      .build()
    Generation.getClient(config)
  }

  /**
   * Cached answer to `isSystemPromptAvailable()`.
   *
   * System instructions are beta and gated per resident model (§1), and the
   * answer decides whether system messages become a `SystemInstruction` or are
   * folded into the first `Content` (D35). It cannot change while the process
   * runs short of a model OTA, and asking per request would add a round trip to
   * every single generation.
   *
   * `null` means "not asked yet". A probe that *fails* is cached as `false`,
   * which is the safe direction: folding always delivers the instructions,
   * where trusting an unverified `true` could drop them.
   */
  @Volatile
  private var systemPromptSupported: Boolean? = null

  @Volatile
  private var closed: Boolean = false

  // MARK: - Availability

  override suspend fun availability(): BridgeAvailability =
    try {
      val raw = client.checkStatus()
      AvailabilityMapping.fromFeatureStatus(featureStatusOf(raw), raw)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (throwable: Throwable) {
      AvailabilityMapping.fromError(payloadFor(throwable))
    }

  /**
   * `FeatureStatus` int -> our symbolic enum.
   *
   * `checkStatus()` returns a bare `Int`, so an unrecognised value is not only
   * possible but likely eventually — four betas shipped in eight months. It
   * becomes `null` and `AvailabilityMapping` degrades it to `modelNotReady`.
   */
  private fun featureStatusOf(raw: Int): FeatureStatusCode? = when (raw) {
    FeatureStatus.AVAILABLE -> FeatureStatusCode.AVAILABLE
    FeatureStatus.DOWNLOADING -> FeatureStatusCode.DOWNLOADING
    FeatureStatus.DOWNLOADABLE -> FeatureStatusCode.DOWNLOADABLE
    FeatureStatus.UNAVAILABLE -> FeatureStatusCode.UNAVAILABLE
    else -> null
  }

  // MARK: - Capabilities

  /**
   * Capability discovery, mapped onto `NativeCapabilities`
   * (`src/apple/native/types.ts`) so one TypeScript decoder serves both
   * platforms.
   *
   * - **`contextWindow`** <- `getTokenLimit()`, a per-device runtime figure and
   *   the only trustworthy number: no Google source corroborates a per-variant
   *   window split, and the docs give one uniform ~4,096-token ceiling (§4).
   *   Guarded at `<= 0` and reported as `0`, which TypeScript turns into
   *   `UNKNOWN` via `normalizeContextWindow` (D9/D11). The budget is combined
   *   input+output, the same semantics as Apple's `contextSize`, so the Phase 2
   *   formula transfers unchanged.
   * - **`locales`** is always empty. There is no enumeration API:
   *   `genai-common` carries `internal.SapiLanguage`, and `javap` shows it is
   *   an **empty marker annotation with no members** (§4). Empty means UNKNOWN,
   *   not "supports nothing" — a real regression against Apple's 24 enumerated
   *   tags, and the reason D7's pre-check and D30's `unsupportedLocale`
   *   fallback trigger are both inert on Android.
   * - **`modelLabel`** <- `getBaseModelName()` (`"nano-v3"`). Variants move
   *   under devices via OTA, so it is read, never cached across process runs
   *   and never inferred from the device name (§6).
   * - **The four `supports*` flags are hard `false`**, and unlike Apple this is
   *   not "the bridge has not got to it yet". `supportsGuidedGeneration`:
   *   structured output terminates in a `KClass` produced by a KSP compiler
   *   plugin at build time, and our contract hands a JSON Schema across the
   *   bridge at runtime — there is no class to point `outputClass` at, and no
   *   public API accepts a schema document (§3). `supportsToolCalling`: no
   *   `Tool`, `FunctionDeclaration`, `FunctionCall` or `FunctionResponse` type
   *   exists anywhere in the artifact. Reporting the SDK's own
   *   `isStructuredOutputFeatureAvailable()` here would be actively harmful —
   *   a `true` the bridge can never honour sends the Phase 4 router toward a
   *   provider that is about to fail, which is precisely what D27 forbids.
   * - **`systemPromptAvailable`** is an Android-only extra, reported because it
   *   changes how the request is built (D35) and is therefore worth surfacing
   *   to anyone debugging a prompt.
   */
  override suspend fun capabilities(): Map<String, Any?> {
    val tokenLimit = probe { client.getTokenLimit() } ?: 0
    val modelLabel = probe { client.getBaseModelName() }
    val systemPrompt = resolveSystemPromptSupport()

    return buildMap {
      put("contextWindow", if (tokenLimit > 0) tokenLimit else 0)
      put("locales", emptyList<String>())
      modelLabel?.takeIf { it.isNotBlank() }?.let { put("modelLabel", it) }
      put("supportsVision", false)
      put("supportsGuidedGeneration", false)
      put("supportsToolCalling", false)
      put("supportsReasoning", false)
      put("systemPromptAvailable", systemPrompt)
    }
  }

  private suspend fun resolveSystemPromptSupport(): Boolean {
    systemPromptSupported?.let { return it }
    val answer = probe { client.isSystemPromptAvailable() } ?: false
    systemPromptSupported = answer
    return answer
  }

  /**
   * Run a capability probe, swallowing failure.
   *
   * D9 in Android dress: a device can report `AVAILABLE` and still fail every
   * call (§11.1). One wedged probe must degrade that one field rather than make
   * `capabilities()` throw — a provider that cannot describe itself is still
   * usable, and `contextWindow: 0` is a typed "unknown" the context manager
   * already handles (D11).
   */
  private suspend fun <T> probe(block: suspend () -> T): T? =
    try {
      block()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (throwable: Throwable) {
      null
    }

  // MARK: - Generation

  override suspend fun generate(request: BridgeRequest): BridgeResult {
    val nativeRequest = buildRequest(request, requirePrompt = true)
    val response = client.generateContent(nativeRequest)
    return resultFrom(response, request)
  }

  /**
   * Streaming.
   *
   * Uses `generateContent(request, StreamingCallback)` rather than
   * `generateContentStream(request): Flow`. Both are the same machinery
   * underneath — the `Flow` is a `callbackFlow` over this very callback (§2) —
   * but the callback overload *also* returns the complete
   * `GenerateContentResponse` when it finishes. That settles the one question
   * D18 had to answer by convention on Apple: **`finish.text` is the SDK's own
   * final text**, not our concatenation of deltas, so a consumer that renders
   * deltas live and then swaps in the final text always converges even if our
   * delta assumption turns out to be wrong.
   *
   * Deltas are forwarded **as-is** — see `StreamAccumulator` for why, and for
   * the tripwire that reports it on the wire if the assumption ever breaks.
   */
  override suspend fun stream(request: BridgeRequest, emit: (BridgeStreamEvent) -> Unit) {
    val accumulator = StreamAccumulator()
    val lock = Any()

    try {
      val nativeRequest = buildRequest(request, requirePrompt = true)
      val callback = StreamingCallback { chunk ->
        // `onNewText` is delivered on the SDK's worker executor. Serialising
        // here keeps the accumulator's read-modify-write atomic and keeps the
        // events in the order the model produced them, which is the one
        // ordering guarantee a delta stream has to make.
        val reset = synchronized(lock) { accumulator.accept(chunk) }
        emit(BridgeStreamEvent.Delta(chunk, reset))
      }
      val response = client.generateContent(nativeRequest, callback)
      emit(BridgeStreamEvent.Finish(resultFrom(response, request)))
    } catch (cancellation: CancellationException) {
      // The contract says an abort surfaces as an `LLMError` with code
      // `cancelled`, never as a successful result (D21's Android form).
      emit(BridgeStreamEvent.Failure(ErrorMapping.cancelled()))
    } catch (throwable: Throwable) {
      emit(BridgeStreamEvent.Failure(payloadFor(throwable)))
    }
  }

  // MARK: - Prewarm

  /**
   * `warmup()`.
   *
   * Takes no messages, so the conversation a caller passes is ignored — the
   * expensive part of a first request is loading the model, and that is what
   * this triggers. (`Caches.create(...)` / `PromptPrefix` can warm a specific
   * prefix and is arguably richer than Apple's `prewarm(promptPrefix:)`, but
   * the SDK's own bytecode carries the string *"Inference failed with prefix
   * cache, retry without cache"* — adopting the prefix cache means inheriting
   * that failure mode, §11.3 — so it is deliberately out of wave 1.)
   *
   * Never throws: a hint that could not be delivered is not a failure worth
   * reporting, because the caller has nothing to do about it and the next real
   * request will report the same problem properly (D26).
   */
  override suspend fun prewarm(): Boolean =
    try {
      client.warmup()
      true
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (throwable: Throwable) {
      false
    }

  // MARK: - Token counting

  /**
   * `countTokens(request)`.
   *
   * Takes a whole request, not a string — the same design choice our
   * `countTokens(messages)` made, and for the same reason: per-message framing
   * overhead is the provider's business. It counts **input only**, and
   * `countTokens(request) + maxOutputTokens` must stay under `getTokenLimit()`
   * (§3).
   *
   * The count is exact, with one honest caveat: it is exact for *the request we
   * actually build*, which includes the invented role frame (D35). Change the
   * frame and the number changes.
   *
   * Throws rather than guessing, so the Phase 2 `createMeasure` can record
   * `estimatorAfterCounterFailure` and widen its safety margin from 64 tokens
   * to 256 (D10/D27). A silent estimate here would keep the narrow margin under
   * an exact-looking number, which is how a "measured" budget overflows.
   */
  override suspend fun countTokens(request: BridgeRequest): Int {
    val encoded = encode(request, requirePrompt = false)
    if (encoded.contents.isEmpty() && encoded.systemInstruction == null) return 0
    return client.countTokens(nativeRequestFrom(encoded, request)).totalTokens
  }

  // MARK: - Request building

  private suspend fun encode(request: BridgeRequest, requirePrompt: Boolean): EncodedPrompt =
    PromptEncoding.encode(
      messages = request.messages,
      systemPromptSupported = resolveSystemPromptSupport(),
      requirePrompt = requirePrompt,
    )

  private suspend fun buildRequest(
    request: BridgeRequest,
    requirePrompt: Boolean,
  ): GenerateContentRequest = nativeRequestFrom(encode(request, requirePrompt), request)

  /**
   * `EncodedPrompt` -> `GenerateContentRequest`.
   *
   * Mechanical by design: every judgement about what the strings should say was
   * made in `PromptEncoding`, where it is pure and tested. This function only
   * wraps them.
   */
  private fun nativeRequestFrom(
    encoded: EncodedPrompt,
    request: BridgeRequest,
  ): GenerateContentRequest {
    val contents = encoded.contents.map { text ->
      Content.Builder().text(text).build()
    }
    val builder = GenerateContentRequest.Builder(contents)
    encoded.systemInstruction?.let { builder.systemInstruction = SystemInstruction(it) }
    request.options.temperature?.let { builder.temperature = it.toFloat() }
    request.options.maxOutputTokens?.let { builder.maxOutputTokens = it }
    return builder.build()
  }

  // MARK: - Response mapping

  private fun resultFrom(
    response: GenerateContentResponse,
    @Suppress("UNUSED_PARAMETER") request: BridgeRequest,
  ): BridgeResult {
    val candidate = response.candidates.firstOrNull()
    return BridgeResult(
      text = candidate?.text ?: "",
      finishReason = finishReasonOf(candidate),
      // `GenerateContentResponse` reports no token usage at all, and absent
      // means "not reported", never zero (`src/core/generation.ts`).
      usage = BridgeUsage(),
    )
  }

  /**
   * `Candidate.FinishReason` -> `src/core`'s `FinishReason`.
   *
   * `STOP` -> `stop`, `MAX_TOKENS` -> `length`, `OTHER` -> `other`. Unlike
   * Apple — where the framework reports nothing and the Swift side has to infer
   * `length` from the token count — the SDK says so directly.
   *
   * A `null` finish reason becomes `other` rather than `stop`: `other` is the
   * escape hatch `src/core` provides precisely "so providers never have to lie
   * about `stop`".
   */
  private fun finishReasonOf(candidate: Candidate?): String =
    when (candidate?.finishReason) {
      Candidate.FinishReason.STOP -> "stop"
      Candidate.FinishReason.MAX_TOKENS -> "length"
      else -> "other"
    }

  // MARK: - Errors

  override fun mapThrowable(throwable: Throwable): BridgeErrorPayload? {
    val genAi = findGenAiException(throwable) ?: return null
    return ErrorMapping.mapGenAiError(
      code = errorCodeOf(genAi.errorCode),
      rawCode = genAi.errorCode,
      message = genAi.message,
      retryDelayMs = retryDelayMsOf(genAi),
    )
  }

  private fun payloadFor(throwable: Throwable): BridgeErrorPayload =
    mapThrowable(throwable)
      ?: ErrorMapping.mapUnclassified(throwable.javaClass.name, throwable.message)

  /**
   * ML Kit wraps its own exception on some paths (the coroutine adapters
   * rethrow from `ExecutionException`), so the cause chain is walked rather
   * than type-checked once. Bounded, because a cyclic `cause` is possible and a
   * `while (true)` here would hang the request path.
   */
  private fun findGenAiException(throwable: Throwable): GenAiException? {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < 8) {
      if (current is GenAiException) return current
      current = current.cause?.takeIf { it !== current }
      depth += 1
    }
    return null
  }

  /**
   * `getRetryDelay(): java.time.Duration` -> milliseconds.
   *
   * `java.time.Duration` is API 26+, which matches `genai-prompt`'s own
   * `minSdkVersion` exactly, so there is no desugaring question here. Guarded
   * anyway: the getter is not documented as non-null, and a beta that starts
   * returning `null` must not turn a rate limit into a crash.
   */
  private fun retryDelayMsOf(exception: GenAiException): Long? =
    try {
      @Suppress("USELESS_ELVIS")
      exception.retryDelay?.toMillis()?.takeIf { it > 0 }
    } catch (throwable: Throwable) {
      null
    }

  /**
   * `GenAiException.getErrorCode()` int -> our symbolic enum.
   *
   * The numbers live in the SDK and nowhere else: a constant renamed or removed
   * in a future beta is a compile error here rather than a silent mismapping
   * downstream. An int this build does not recognise becomes `null`, which
   * `ErrorMapping` lands in the transient-unknown lane with the raw value
   * attached.
   */
  private fun errorCodeOf(raw: Int): GenAiErrorCode? = when (raw) {
    GenAiException.ErrorCode.UNKNOWN -> GenAiErrorCode.UNKNOWN
    GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> GenAiErrorCode.REQUEST_PROCESSING_ERROR
    GenAiException.ErrorCode.CANCELLED -> GenAiErrorCode.CANCELLED
    GenAiException.ErrorCode.NOT_AVAILABLE -> GenAiErrorCode.NOT_AVAILABLE
    GenAiException.ErrorCode.BUSY -> GenAiErrorCode.BUSY
    GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> GenAiErrorCode.RESPONSE_PROCESSING_ERROR
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> GenAiErrorCode.REQUEST_TOO_LARGE
    GenAiException.ErrorCode.REQUEST_TOO_SMALL -> GenAiErrorCode.REQUEST_TOO_SMALL
    GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> GenAiErrorCode.RESPONSE_GENERATION_ERROR
    GenAiException.ErrorCode.NOT_SUPPORTED -> GenAiErrorCode.NOT_SUPPORTED
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
      GenAiErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> GenAiErrorCode.BACKGROUND_USE_BLOCKED
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> GenAiErrorCode.NOT_ENOUGH_DISK_SPACE
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> GenAiErrorCode.NEEDS_SYSTEM_UPDATE
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> GenAiErrorCode.AICORE_INCOMPATIBLE
    GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> GenAiErrorCode.INVALID_INPUT_IMAGE
    GenAiException.ErrorCode.CACHE_PROCESSING_ERROR -> GenAiErrorCode.CACHE_PROCESSING_ERROR
    GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR ->
      GenAiErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR
    GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR ->
      GenAiErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR
    GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR ->
      GenAiErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR
    GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW -> GenAiErrorCode.AUDIO_BUFFER_OVERFLOW
    else -> null
  }

  // MARK: - Teardown

  override fun close() {
    if (closed) return
    closed = true
    try {
      client.close()
    } catch (throwable: Throwable) {
      // Teardown runs on a JS reload. Nothing is listening and there is nothing
      // to do about a failed close.
    }
  }
}
