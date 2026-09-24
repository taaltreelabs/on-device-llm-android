//
//  ErrorMapping.kt
//  OnDeviceLlm — Android
//
//  One place where every way the Prompt API can fail becomes one of
//  `src/core`'s `LLMErrorCode`s. The Phase 4 router branches on those codes, so
//  an unmapped throw is not cosmetic: it is a request that cannot be retried,
//  failed over, or reported.
//
//  ML Kit has exactly one exception type and it is richer than Apple's:
//
//  ```java
//  public class GenAiException extends Exception {
//    public int getErrorCode();
//    public java.time.Duration getRetryDelay();
//  }
//  ```
//
//  21 error codes, read from the shipping artifact
//  (docs/research/android-genai.md §5). `getErrorCode()` returns a **bare int**,
//  so this file is written against a symbolic enum and `GenAiEngine` does the
//  int -> enum translation using the SDK's own constants. The numbers therefore
//  live in exactly one place (the SDK), a renamed constant is a compile error
//  rather than a silent mismapping, and this file stays free of ML Kit imports
//  so the whole table is unit-testable on a machine with no Android SDK.
//
//  ---------------------------------------------------------------------------
//  THE GUARDRAIL ASYMMETRY (DECISIONS.md D36)
//  ---------------------------------------------------------------------------
//  **There is no `guardrail` code on Android, and there cannot be one.** No
//  member of `GenAiException.ErrorCode` denotes a safety refusal. Safety is
//  implemented as *prompt text*: the bytecode of the internal `zzys` adapter
//  contains the literal string "Do NOT generate unsafe content" appended to the
//  system instruction (§3/§5). A blocked response therefore most likely arrives
//  as ordinary generated text, or at worst as `RESPONSE_GENERATION_ERROR`.
//
//  The consequence is a real cross-platform behaviour difference, not a gap in
//  this table: DECISIONS.md D30 makes `guardrail` the one error code that does
//  **not** fall through to a cloud provider by default, precisely so that
//  content the on-device model refused is not quietly re-sent somewhere else.
//  On Android that policy is unenforceable — the refusal never reaches the
//  taxonomy — so an app relying on it behaves differently per platform. Nothing
//  in this file can fix that; it is recorded here, in D36, and in the
//  PROVISIONAL register so it is a known asymmetry rather than a surprise.
//

package expo.modules.ondevicellmandroid.core

/**
 * The 21 members of `GenAiException.ErrorCode`, symbolically.
 *
 * Mirrors the artifact's constant list exactly (§5). `GenAiEngine.errorCodeOf`
 * maps the SDK's ints onto these using `GenAiException.ErrorCode.*`; an int
 * this enum does not cover (a code added in a future beta) maps to `null` and
 * lands in the transient-unknown lane with its raw value attached.
 */
enum class GenAiErrorCode {
  UNKNOWN,
  REQUEST_PROCESSING_ERROR,
  CANCELLED,
  NOT_AVAILABLE,
  BUSY,
  RESPONSE_PROCESSING_ERROR,
  REQUEST_TOO_LARGE,
  REQUEST_TOO_SMALL,
  RESPONSE_GENERATION_ERROR,
  NOT_SUPPORTED,
  PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
  BACKGROUND_USE_BLOCKED,
  NOT_ENOUGH_DISK_SPACE,
  NEEDS_SYSTEM_UPDATE,
  AICORE_INCOMPATIBLE,
  INVALID_INPUT_IMAGE,
  CACHE_PROCESSING_ERROR,
  STRUCTURED_OUTPUT_REQUEST_ERROR,
  STRUCTURED_OUTPUT_RESPONSE_ERROR,
  STRUCTURED_OUTPUT_MAX_TOKENS_ERROR,
  AUDIO_BUFFER_OVERFLOW,
}

object ErrorMapping {
  /** `nativeDomain` for anything that arrived as a `GenAiException`. */
  const val GEN_AI_DOMAIN: String = "com.google.mlkit.genai.common.GenAiException"

  /** `nativeDomain` for a raw `Throwable` that was not a `GenAiException`. */
  const val THROWABLE_DOMAIN: String = "java.lang.Throwable"

  /** The payload every cancellation produces, wherever it is detected. */
  fun cancelled(): BridgeErrorPayload =
    BridgeErrorPayload(code = "cancelled", message = "The request was cancelled")

  /**
   * The full table.
   *
   * | `ErrorCode` | `LLMError` | carried |
   * |---|---|---|
   * | `CANCELLED` | `cancelled` | — |
   * | `REQUEST_TOO_LARGE` | `contextOverflow` | — |
   * | `STRUCTURED_OUTPUT_MAX_TOKENS_ERROR` | `contextOverflow` | — |
   * | `NOT_AVAILABLE` | `unavailable` | `reason: modelNotReady` |
   * | `NOT_ENOUGH_DISK_SPACE` | `unavailable` | `reason: modelNotReady` |
   * | `AICORE_INCOMPATIBLE` | `unavailable` | `reason: deviceNotEligible` |
   * | `NEEDS_SYSTEM_UPDATE` | `unavailable` | `reason: unsupportedPlatform` |
   * | `BUSY` | `rateLimited` | `resetDate` |
   * | `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` | `rateLimited` | `resetDate` |
   * | `REQUEST_TOO_SMALL` | `invalidRequest` | — |
   * | `NOT_SUPPORTED` | `invalidRequest` | — |
   * | `INVALID_INPUT_IMAGE` | `invalidRequest` | — |
   * | `STRUCTURED_OUTPUT_REQUEST_ERROR` | `invalidRequest` | — |
   * | `BACKGROUND_USE_BLOCKED` | `unknown` | `transient: true` |
   * | `REQUEST_PROCESSING_ERROR` | `unknown` | `transient: true` |
   * | `RESPONSE_PROCESSING_ERROR` | `unknown` | `transient: true` |
   * | `RESPONSE_GENERATION_ERROR` | `unknown` | `transient: true` |
   * | `CACHE_PROCESSING_ERROR` | `unknown` | `transient: true` |
   * | `STRUCTURED_OUTPUT_RESPONSE_ERROR` | `unknown` | `transient: true` |
   * | `AUDIO_BUFFER_OVERFLOW` | `unknown` | `transient: true` |
   * | `UNKNOWN` | `unknown` | `transient` unset |
   * | anything else | `unknown` | `transient: true` |
   *
   * Four rows deserve their reasons in writing:
   *
   * - **`getRetryDelay()` -> `resetDate`.** Apple's `RateLimited.resetDate` has
   *   a direct Android counterpart, so `RateLimitedErrorDetails.resetDate` is
   *   populatable on both platforms — and D30 makes `rateLimited` a fallback
   *   trigger, so the number is load-bearing rather than decorative. It is
   *   converted to epoch milliseconds here (the wire form `new Date(n)` wants)
   *   from a *relative* duration, which means it is computed against the clock
   *   at the moment the failure was mapped.
   * - **`BUSY` and `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` are Android-only
   *   failure modes with no iOS analogue** (§11.6). A long chat can be
   *   throttled by the OS mid-conversation; `getRetryDelay()` is what makes
   *   that survivable.
   * - **The D9 lane carries the bulk of it.** `REQUEST_PROCESSING_ERROR`,
   *   `RESPONSE_PROCESSING_ERROR`, `RESPONSE_GENERATION_ERROR` and
   *   `CACHE_PROCESSING_ERROR` are the documented shape of "inference failed on
   *   healthy, eligible hardware" — AICore's own `2-INFERENCE_ERROR` /
   *   `29-INTERNAL_ERROR` surfaces (§11.1), and the SDK's own bytecode carries
   *   the string "Inference failed with prefix cache, retry without cache",
   *   i.e. Google treats first-attempt failure as expected. D9's
   *   transient-`unknown` lane is not optional here; it is load-bearing from
   *   day one, and it is what lets the Phase 4 router fail over
   *   (`unknownTransient`, on by default per D30).
   * - **`UNKNOWN` leaves `transient` unset** rather than guessing `true`. D30
   *   is explicit that "don't know" shares the non-retryable switch, because
   *   treating every mystery as retryable makes each one cost two generations
   *   and two bills. A *named* failure we have classified as transient is a
   *   different statement from the SDK's own "unknown".
   *
   * @param code the symbolic code, or `null` for an int this build does not
   *   recognise.
   * @param rawCode the int as the SDK reported it, always attached as
   *   `nativeCode`.
   * @param message `GenAiException.getMessage()`, attached as `nativeDetail`.
   * @param retryDelayMs `getRetryDelay()` in milliseconds, or `null`.
   * @param nowMs the clock, injectable so the `resetDate` arithmetic is
   *   testable.
   */
  fun mapGenAiError(
    code: GenAiErrorCode?,
    rawCode: Int,
    message: String?,
    retryDelayMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
  ): BridgeErrorPayload {
    val detail = message?.takeIf { it.isNotBlank() }

    fun payload(
      llmCode: String,
      text: String,
      reason: String? = null,
      transient: Boolean? = null,
      resetDate: Double? = null,
    ) = BridgeErrorPayload(
      code = llmCode,
      message = text,
      reason = reason,
      resetDate = resetDate,
      transient = transient,
      nativeDomain = GEN_AI_DOMAIN,
      nativeCode = rawCode,
      nativeDetail = detail,
    )

    fun rateLimited(text: String) = payload(
      llmCode = "rateLimited",
      text = text,
      resetDate = retryDelayMs?.let { (nowMs + it).toDouble() },
    )

    fun transientUnknown(text: String) = payload("unknown", text, transient = true)

    return when (code) {
      GenAiErrorCode.CANCELLED ->
        cancelled().copy(nativeDomain = GEN_AI_DOMAIN, nativeCode = rawCode, nativeDetail = detail)

      GenAiErrorCode.REQUEST_TOO_LARGE ->
        payload("contextOverflow", "The request exceeds the model's context window")

      GenAiErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR ->
        payload("contextOverflow", "The response exceeded the model's output budget")

      GenAiErrorCode.NOT_AVAILABLE ->
        payload(
          "unavailable",
          "The on-device model is not available",
          reason = "modelNotReady",
        )

      GenAiErrorCode.NOT_ENOUGH_DISK_SPACE ->
        payload(
          "unavailable",
          "There is not enough free disk space for the on-device model",
          // `modelNotReady` rather than a permanent reason: the user can free
          // space, so a caller should re-check rather than write the device off.
          reason = "modelNotReady",
        )

      GenAiErrorCode.AICORE_INCOMPATIBLE ->
        payload(
          "unavailable",
          "This device cannot run the on-device model",
          reason = "deviceNotEligible",
        )

      GenAiErrorCode.NEEDS_SYSTEM_UPDATE ->
        payload(
          "unavailable",
          "A system update is required before the on-device model can run",
          reason = "unsupportedPlatform",
        )

      GenAiErrorCode.BUSY ->
        rateLimited("The on-device model is busy")

      GenAiErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
        rateLimited("This app has exceeded its on-device model battery quota")

      GenAiErrorCode.REQUEST_TOO_SMALL ->
        payload("invalidRequest", "The request is too small for the model to act on")

      GenAiErrorCode.NOT_SUPPORTED ->
        payload("invalidRequest", "The model does not support something this request asked for")

      GenAiErrorCode.INVALID_INPUT_IMAGE ->
        payload("invalidRequest", "The request contains an image the model cannot accept")

      GenAiErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR ->
        // Unreachable: the bridge rejects a schema before the SDK ever sees one
        // (docs/research/android-genai.md §3). Mapped anyway — `invalidRequest`
        // is never retried and never failed over, which is right for a request
        // shape that will be just as wrong at the next provider.
        payload("invalidRequest", "The model rejected the structured-output request")

      GenAiErrorCode.BACKGROUND_USE_BLOCKED ->
        transientUnknown("The system blocked on-device generation from the background")

      GenAiErrorCode.REQUEST_PROCESSING_ERROR ->
        transientUnknown("The model failed while processing the request")

      GenAiErrorCode.RESPONSE_PROCESSING_ERROR ->
        transientUnknown("The model failed while processing its response")

      GenAiErrorCode.RESPONSE_GENERATION_ERROR ->
        transientUnknown("The model failed while generating a response")

      GenAiErrorCode.CACHE_PROCESSING_ERROR ->
        transientUnknown("The model failed while using its prefix cache")

      GenAiErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR ->
        transientUnknown("The model's structured output could not be parsed")

      GenAiErrorCode.AUDIO_BUFFER_OVERFLOW ->
        // Belongs to a sibling API this bridge never calls. Listed so the enum
        // stays exhaustive against the artifact rather than silently short.
        transientUnknown("An audio buffer overflowed")

      GenAiErrorCode.UNKNOWN ->
        payload("unknown", detail ?: "The model failed for an unknown reason")

      null ->
        // A code added by a future beta. The SDK ships breaking changes roughly
        // every six weeks (§11.4), so this branch is expected to fire, and it
        // must degrade rather than crash the request path.
        transientUnknown(
          detail ?: "The model failed with an error code this version does not recognise"
        )
    }
  }

  /**
   * A raw `Throwable` that was never a `GenAiException`.
   *
   * `unknown` + `transient: true`, with the class name and message attached —
   * the Android form of D9's untyped-`NSError` branch. A `NoClassDefFoundError`
   * from a half-present SDK, an `IllegalStateException` from ML Kit's internal
   * initialisation, an `IllegalArgumentException` from a request shape the
   * builder rejects: none of them is classifiable, all of them must be
   * reportable, and none of them may take the process down.
   *
   * Takes strings rather than a `Throwable` so the function stays pure and the
   * table is testable; `GenAiEngine` does the unwrapping.
   */
  fun mapUnclassified(throwableClassName: String, message: String?): BridgeErrorPayload =
    BridgeErrorPayload(
      code = "unknown",
      message = message?.takeIf { it.isNotBlank() }
        ?: "The on-device model failed for an unknown reason ($throwableClassName)",
      transient = true,
      nativeDomain = throwableClassName,
      nativeDetail = message,
    )
}
