//
//  BridgeTypes.kt
//  OnDeviceLlm — Android
//
//  The value types that cross the JS <-> Kotlin boundary, expressed as plain
//  Kotlin. Nothing in `core/` imports Expo, Android, **or ML Kit**: that is the
//  same separation `ios/Core/*.swift` keeps, and here it buys two extra things
//  the Apple side did not need:
//
//  1. **Testability without a device.** AICore runs on no emulator
//     (docs/research/android-genai.md §6), so the only logic that can ever be
//     verified on CI is the logic that does not touch the SDK. Everything in
//     this package is a pure function over plain data and is covered by JVM
//     unit tests in `android/src/test`.
//  2. **The dependency firewall (DECISIONS.md D33).** `com.google.mlkit:genai-prompt`
//     is a `compileOnly` dependency — it is *not* packaged into a consumer's
//     APK. A class that references an absent type fails verification when it is
//     loaded, so every ML Kit reference is confined to `GenAiEngine.kt`, which
//     is only ever constructed after a reflection probe has confirmed the SDK
//     is present. Files in this package can be loaded unconditionally.
//
//  The wire shapes mirror `ios/Core/BridgeTypes.swift` field for field, because
//  `src/apple/native/types.ts` is the contract the TypeScript half decodes with
//  (docs/research/android-genai.md §9: D17's wire shape transfers unchanged).
//

package expo.modules.ondevicellmandroid.core

/** The message roles `src/core`'s `Message` can carry. Mirrors `MessageRole` in `src/core/messages.ts`. */
enum class BridgeRole(val wire: String) {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant");

  companion object {
    fun fromWire(value: String): BridgeRole? = entries.firstOrNull { it.wire == value }
  }
}

/** One message from the JS conversation list. */
data class BridgeMessage(val role: BridgeRole, val content: String)

/**
 * The sampling options this provider supports.
 *
 * Deliberately only the two fields `GenerateRequest` exposes. The Prompt API
 * also offers `seed`, `topK` and `candidateCount` (and has **no** `topP` —
 * docs/research/android-genai.md §1); none has a counterpart in the frozen
 * `core` request type, so none is plumbed. `candidateCount > 1` is documented
 * as incompatible with streaming, which is a second reason to leave it alone.
 */
data class BridgeGenerationOptions(
  val temperature: Double? = null,
  val maxOutputTokens: Int? = null,
)

/** A complete generation request as it arrives from JavaScript. */
data class BridgeRequest(
  val messages: List<BridgeMessage>,
  val options: BridgeGenerationOptions,
) {
  companion object {
    /**
     * Parse the wire form (`[{"role": "user", "content": "…"}, …]`).
     *
     * The TypeScript wrapper validates roles before calling, so an unknown role
     * here means a bridge bug or a hand-rolled caller; either way it is an
     * `invalidRequest`, not something to silently coerce to `user`. Same
     * backstop posture as `BridgeRequest.parse` in Swift.
     *
     * **No `schemaJson` and no `tools` parameter (DECISIONS.md D6).** The
     * single-package wave 1 accepted both and rejected them here, because the
     * module answered to the same registered name as the Apple one and a single
     * TypeScript caller had to pass the same argument list to both. That is gone:
     * this module has its own name and its own TypeScript half, and `src/wire.ts`
     * refuses `schema` and `tools` at the call site, before the bridge hop, with
     * the same `invalidRequest` code and a longer message than a native backstop
     * could carry. Two gates for a request that no caller can now construct was
     * dead code pretending to be defence in depth; the surviving gate is the one
     * the developer actually sees.
     */
    fun parse(
      messages: List<Map<String, String>>,
      temperature: Double?,
      maxOutputTokens: Int?,
    ): BridgeRequest {
      val parsed = messages.mapIndexed { index, raw ->
        val rawRole = raw["role"]
          ?: throw BridgeException.invalidRequest("messages[$index] has no \"role\"")
        val role = BridgeRole.fromWire(rawRole)
          ?: throw BridgeException.invalidRequest(
            "messages[$index] has an unsupported role \"$rawRole\""
          )
        val content = raw["content"]
          ?: throw BridgeException.invalidRequest("messages[$index] has no \"content\"")
        BridgeMessage(role, content)
      }

      return BridgeRequest(
        messages = parsed,
        options = BridgeGenerationOptions(
          temperature = temperature,
          maxOutputTokens = maxOutputTokens,
        ),
      )
    }
  }
}

// MARK: - Result

/**
 * Token usage, shaped like `src/core`'s `TokenUsage`.
 *
 * Every field is absent on Android today: `GenerateContentResponse` carries
 * `candidates` and `thoughtProcess` and nothing else
 * (docs/research/android-genai.md §1). Absent means "not reported", never zero
 * — `src/core/generation.ts` is explicit that hardcoded zeros are
 * indistinguishable from a real measurement, which is the bug D1 called out in
 * `@react-native-ai/apple`.
 */
data class BridgeUsage(
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cachedInputTokens: Int? = null,
  val reasoningTokens: Int? = null,
) {
  val isEmpty: Boolean
    get() = inputTokens == null && outputTokens == null &&
      cachedInputTokens == null && reasoningTokens == null

  fun toMap(): Map<String, Any?> = buildMap {
    inputTokens?.let { put("inputTokens", it) }
    outputTokens?.let { put("outputTokens", it) }
    cachedInputTokens?.let { put("cachedInputTokens", it) }
    reasoningTokens?.let { put("reasoningTokens", it) }
  }
}

/**
 * A finished generation, shaped like `src/core`'s `GenerateResult` minus
 * `providerId` (which only the TypeScript side knows).
 */
data class BridgeResult(
  val text: String,
  /** One of `src/core`'s `FinishReason` strings. */
  val finishReason: String,
  val usage: BridgeUsage = BridgeUsage(),
) {
  fun toMap(): Map<String, Any?> = buildMap {
    put("text", text)
    put("finishReason", finishReason)
    if (!usage.isEmpty) put("usage", usage.toMap())
  }
}

// MARK: - Errors

/**
 * A failure already mapped onto `src/core`'s `LLMErrorCode` taxonomy.
 *
 * Every field beyond `code`/`message` is optional and corresponds to a field of
 * one `LLMErrorDetails` variant, so the TypeScript side rebuilds a properly
 * typed `LLMError` without a second mapping table.
 *
 * Fields present on the Apple payload and never populated here:
 * - `contextSize` / `tokenCount` — `REQUEST_TOO_LARGE` carries no numbers.
 * - `locale` — there is no locale error and no locale enumeration API (§4).
 * - `rawContent` — no structured-output path exists to fail parsing.
 * They stay in the shape anyway so one TypeScript decoder serves both
 * platforms.
 */
data class BridgeErrorPayload(
  /**
   * An `LLMErrorCode` value: `unavailable`, `contextOverflow`, `guardrail`,
   * `unsupportedLocale`, `rateLimited`, `cancelled`, `network`,
   * `invalidRequest`, `unknown`.
   */
  val code: String,
  /** Human-readable, never containing prompt or response content. */
  val message: String,
  // `unavailable`
  val reason: String? = null,
  // `contextOverflow`
  val contextSize: Int? = null,
  val tokenCount: Int? = null,
  // `unsupportedLocale`
  val locale: String? = null,
  /** `rateLimited` — milliseconds since the epoch, so JS can `new Date(n)`. */
  val resetDate: Double? = null,
  // `unknown`
  val transient: Boolean? = null,
  // Diagnostics. DECISIONS.md D9: losing the native domain and code is what
  // makes an unknown failure unreportable.
  val nativeDomain: String? = null,
  val nativeCode: Int? = null,
  val nativeDetail: String? = null,
) {
  fun toMap(): Map<String, Any?> = buildMap {
    put("code", code)
    put("message", message)
    reason?.let { put("reason", it) }
    contextSize?.let { put("contextSize", it) }
    tokenCount?.let { put("tokenCount", it) }
    locale?.let { put("locale", it) }
    resetDate?.let { put("resetDate", it) }
    transient?.let { put("transient", it) }
    nativeDomain?.let { put("nativeDomain", it) }
    nativeCode?.let { put("nativeCode", it) }
    nativeDetail?.let { put("nativeDetail", it) }
  }
}

/**
 * Errors this bridge raises itself, as opposed to ones ML Kit throws. Carried
 * through `mapNativeThrowable` unchanged.
 */
class BridgeException(val payload: BridgeErrorPayload) : Exception(payload.message) {
  companion object {
    fun invalidRequest(message: String) =
      BridgeException(BridgeErrorPayload(code = "invalidRequest", message = message))

    fun unavailable(reason: String, message: String) =
      BridgeException(
        BridgeErrorPayload(code = "unavailable", message = message, reason = reason)
      )
  }
}

// MARK: - Stream events

/**
 * What the engine hands back, one per native event sent to JS.
 *
 * Shorter than the Apple enum by two cases: there is no `objectSnapshot`
 * (structured output is unreachable) and no `toolCall` (no tool surface).
 */
sealed class BridgeStreamEvent {
  /**
   * Text produced since the previous delta.
   *
   * `reset` exists for wire compatibility with `NativeStreamEvent` and carries
   * the same meaning it does on Apple (DECISIONS.md D18): *this delta did not
   * simply extend what came before*. On Apple that is the snapshot-diff
   * fallback; on Android it is the tripwire described in
   * `StreamAccumulator` — see there for why forwarding is still as-is.
   */
  data class Delta(val text: String, val reset: Boolean) : BridgeStreamEvent()

  data class Finish(val result: BridgeResult) : BridgeStreamEvent()

  data class Failure(val payload: BridgeErrorPayload) : BridgeStreamEvent()

  /** The `type` discriminant JavaScript switches on. */
  val type: String
    get() = when (this) {
      is Delta -> "delta"
      is Finish -> "finish"
      is Failure -> "error"
    }

  fun toMap(requestId: String): Map<String, Any?> = buildMap {
    put("requestId", requestId)
    put("type", type)
    when (val event = this@BridgeStreamEvent) {
      is Delta -> {
        put("delta", event.text)
        put("reset", event.reset)
      }
      is Finish -> put("result", event.result.toMap())
      is Failure -> put("error", event.payload.toMap())
    }
  }
}
