//
//  GenAiBridge.kt
//  OnDeviceLlm — Android
//
//  The seam that keeps the dependency firewall airtight (DECISIONS.md D33).
//
//  `OnDeviceLlmAndroidModule` talks only to this interface. Its one implementation,
//  `GenAiEngine`, is the single file in the module that imports
//  `com.google.mlkit.genai.*`, and it is reached exclusively through
//  `MlKitPresence.createEngine()` — by name, via reflection — so the module's
//  own bytecode contains no reference to it and nothing can cause it to be
//  loaded on a device whose app did not opt in to the SDK.
//
//  Every type in every signature below is either a Kotlin built-in or one of
//  ours. That is the rule that makes the seam work: a method signature
//  mentioning an ML Kit type would drag the class into the module's constant
//  pool and defeat the whole arrangement.
//

package expo.modules.ondevicellmandroid.core

interface GenAiBridge {
  /** `checkStatus()`, mapped. Never throws — a failure to ask becomes an unavailable answer. */
  suspend fun availability(): BridgeAvailability

  /**
   * `getTokenLimit()` / `getBaseModelName()` / `isSystemPromptAvailable()`,
   * mapped onto `NativeCapabilities`. Never throws; a probe that fails
   * degrades that one field.
   */
  suspend fun capabilities(): Map<String, Any?>

  /** `generateContent(request)`. Throws; the caller maps via [mapThrowable]. */
  suspend fun generate(request: BridgeRequest): BridgeResult

  /**
   * `generateContent(request, StreamingCallback)`.
   *
   * Never throws: every outcome — including cancellation and every SDK failure
   * — is delivered as a [BridgeStreamEvent.Finish] or
   * [BridgeStreamEvent.Failure]. Exactly one terminal event per call. A stream
   * that half-delivers and then rejects a promise is the shape that leaves JS
   * consumers hanging, so the bridge does not have that shape at all (D20).
   */
  suspend fun stream(request: BridgeRequest, emit: (BridgeStreamEvent) -> Unit)

  /** `warmup()`. Never throws; `false` means the hint could not be delivered. */
  suspend fun prewarm(): Boolean

  /** `countTokens(request)`. Throws; the caller maps via [mapThrowable]. */
  suspend fun countTokens(request: BridgeRequest): Int

  /**
   * Map a `GenAiException` onto the taxonomy, or return `null` when this
   * throwable is not one. Lives here because recognising the type requires the
   * SDK; the table it delegates to does not (`ErrorMapping`).
   */
  fun mapThrowable(throwable: Throwable): BridgeErrorPayload?

  /** `GenerativeModel.close()`. Idempotent. */
  fun close()
}
