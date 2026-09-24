//
//  OnDeviceLlmAndroidModule.kt
//  @taaltreelabs/on-device-llm-android
//
//  The only file in this module that imports `expo.modules.kotlin`. Everything
//  it calls lives in `core/` (pure, Expo-free, ML-Kit-free, and unit-tested on
//  the JVM) or behind the `GenAiBridge` seam.
//
//  ---------------------------------------------------------------------------
//  WAVE 1 STATUS — READ THIS BEFORE TRUSTING ANY OF IT
//  ---------------------------------------------------------------------------
//  Nothing below has ever run against a model. **AICore is a preinstalled
//  system service and is on no AVD image**, so there is no emulator path and no
//  laptop dev loop (docs/research/android-genai.md §6). What is proven here is
//  that it *compiles* against the real 1.0.0-beta4 artifacts and that the
//  device-independent logic passes its JVM tests. Every device-dependent
//  assumption is marked PROVISIONAL at its site and listed in one place in
//  DECISIONS.md for the wave-2 hardware spike to walk.
//
//  ---------------------------------------------------------------------------
//  THE BRIDGE PROTOCOL
//  ---------------------------------------------------------------------------
//  The module registers as **`OnDeviceLlmAndroid`** — its own name, not the
//  Apple module's. That is DECISIONS.md D1 (the split) in one line of code: when
//  both providers lived in one npm package under one registered name, the two
//  native halves answered to `OnDeviceLlm` and `src/android/native/resolve.ts`
//  had to tell them apart by platform, because either could be the thing Expo
//  handed back. A distinct name ends that masquerade: there is exactly one
//  implementation of `OnDeviceLlmAndroid` and it is this file.
//
//  The wire *shape* still mirrors the Apple module's (payloads are returned, not
//  thrown; stream events carry their `requestId`), because that shape is good and
//  `src/native/types.ts` is the contract the TypeScript half decodes with. The
//  wire *surface* no longer does — see D6.
//
//  - `availability()`  -> { available, reason?, detail? }
//  - `capabilities()`  -> { contextWindow, locales, modelLabel?, supports* }
//  - `prewarm(messages?)` -> Bool
//  - `countTokens(messages)` -> { ok: true, count } | { ok: false, error }
//  - `generate(requestId, messages, temperature?, maxOutputTokens?)`
//        -> { ok: true, result } | { ok: false, error }
//  - `startStream(requestId, messages, temperature?, maxOutputTokens?)` -> Void
//        every outcome arrives as an `onStreamEvent` event carrying `requestId`
//  - `cancel(requestId)` -> Bool
//
//  **There is no `resolveToolCall`, no `schemaJson` argument, and no
//  `supportsLocale`** (DECISIONS.md D6). All three existed in the single-package
//  wave 1 purely so one TypeScript caller could serve both platforms under one
//  registered module name; the split removed that constraint, so they are gone
//  rather than kept as always-true stubs and accepted-then-rejected arguments
//  that no caller passes. Structured output and tool calling are still refused —
//  `capabilities()` reports `structuredOutput: false` / `tools: false` and
//  `src/wire.ts` rejects a request carrying `schema` or `tools` as
//  `invalidRequest` before it reaches the bridge (§3). The refusal simply lives
//  in one place now instead of two.
//
//  Failures are *returned*, not thrown (D20). Expo's exception channel carries
//  a code and a message; our taxonomy also carries `resetDate`, `transient` and
//  the native code that D9 says we must never lose, and none of that survives
//  a `CodedException`.
//

package expo.modules.ondevicellmandroid

import android.os.Build
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.ondevicellmandroid.core.AvailabilityMapping
import expo.modules.ondevicellmandroid.core.BridgeErrorPayload
import expo.modules.ondevicellmandroid.core.BridgeException
import expo.modules.ondevicellmandroid.core.BridgeRequest
import expo.modules.ondevicellmandroid.core.BridgeStreamEvent
import expo.modules.ondevicellmandroid.core.ErrorMapping
import expo.modules.ondevicellmandroid.core.GenAiBridge
import expo.modules.ondevicellmandroid.core.RequestRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Name of the single event every streaming request multiplexes over. Each
 * payload carries its `requestId`, so concurrent streams never interleave into
 * the wrong consumer.
 */
private const val STREAM_EVENT_NAME = "onStreamEvent"

class OnDeviceLlmAndroidModule : Module() {
  private val requests = RequestRegistry()

  /**
   * Requests run here, not on the caller's coroutine.
   *
   * `SupervisorJob` so one failed generation does not cancel its neighbours,
   * and a scope of our own so `cancel(requestId)` has a `Job` to cancel that is
   * not tied to Expo's call plumbing. `Dispatchers.Default` because every call
   * into the SDK is a binder round trip that must not touch the main thread.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * `null` when the ML Kit GenAI SDK is not in this build (DECISIONS.md D33).
   *
   * Resolved once, lazily, through a single guarded reflection probe. The
   * module itself loads and works on every Android device; only the provider
   * reports unavailable.
   */
  private val engine: GenAiBridge? by lazy { MlKitPresence.createEngine() }

  override fun definition() = ModuleDefinition {
    Name("OnDeviceLlmAndroid")

    Events(STREAM_EVENT_NAME)

    // MARK: Availability, capabilities, locales

    AsyncFunction("availability") Coroutine { ->
      availabilityMap()
    }

    AsyncFunction("capabilities") Coroutine { ->
      engine?.capabilities() ?: unavailableCapabilities()
    }

    // NOTE (DECISIONS.md D6): there is deliberately no `supportsLocale` here.
    // The single-package wave 1 exposed one that returned `true` unconditionally,
    // so a TypeScript half written against the Apple native shape would not hit
    // `undefined is not a function`. Android has nothing to answer it with —
    // there is no locale enumeration API and no locale test, `SapiLanguage` in
    // `genai-common` being an empty marker annotation with no members (§4) — and
    // a method whose only possible answer is `true` is worse than an absent one:
    // a caller cannot tell "supported" from "cannot say". The TypeScript half is
    // this package's alone now and never calls it, so it is gone.
    // `capabilities().locales` is `UNKNOWN`, which is the honest answer, and D7's
    // pre-check is documented as inert on this provider.

    // MARK: Generate

    AsyncFunction("generate") Coroutine {
        requestId: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxOutputTokens: Int?,
      ->
      runGenerate(requestId, messages, temperature, maxOutputTokens)
    }

    // MARK: Stream + cancellation

    AsyncFunction("startStream") Coroutine {
        requestId: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxOutputTokens: Int?,
      ->
      startStream(requestId, messages, temperature, maxOutputTokens)
    }

    AsyncFunction("cancel") { requestId: String -> requests.cancel(requestId) }

    // MARK: Prewarming

    AsyncFunction("prewarm") Coroutine { _: List<Map<String, String>>? ->
      // `warmup()` takes no messages (§1), so the conversation is ignored. The
      // argument stays in the signature for wire compatibility with Apple,
      // where the prompt prefix genuinely matters.
      engine?.prewarm() ?: false
    }

    // MARK: Token counting

    AsyncFunction("countTokens") Coroutine { messages: List<Map<String, String>> ->
      countTokens(messages)
    }

    OnDestroy {
      // A JS reload tears the module down while generations may still be
      // running. Nothing is listening for their events any more, so stop them
      // rather than leave AICore busy — which on a device with a per-app
      // battery quota is a cost, not just waste.
      requests.cancelAll()
      scope.cancel()
      engine?.close()
    }
  }

  // MARK: - Implementation

  private suspend fun availabilityMap(): Map<String, Any?> {
    AvailabilityMapping.belowSdkFloor(Build.VERSION.SDK_INT)?.let { return it.toMap() }
    val engine = this.engine ?: return AvailabilityMapping.missingDependency().toMap()
    return engine.availability().toMap()
  }

  /**
   * What `capabilities()` reports when there is no SDK to ask.
   *
   * `contextWindow: 0` is the typed "unknown" `normalizeContextWindow` expects
   * (D9/D11) — not a guess, and not a number anybody can budget against.
   */
  private fun unavailableCapabilities(): Map<String, Any?> = mapOf(
    "contextWindow" to 0,
    "locales" to emptyList<String>(),
    "supportsVision" to false,
    "supportsGuidedGeneration" to false,
    "supportsToolCalling" to false,
    "supportsReasoning" to false,
    "systemPromptAvailable" to false,
  )

  private suspend fun runGenerate(
    requestId: String,
    messages: List<Map<String, String>>,
    temperature: Double?,
    maxOutputTokens: Int?,
  ): Map<String, Any?> {
    val engine = this.engine ?: return failure(missingDependencyError())

    val request = try {
      BridgeRequest.parse(messages, temperature, maxOutputTokens)
    } catch (throwable: Throwable) {
      return failure(mapThrowable(throwable))
    }

    // Started lazily and registered *before* it runs, so a `cancel()` arriving
    // immediately after this call always finds a handle — and so completion can
    // never race the registration and leave a dead entry behind.
    val work = scope.async(start = CoroutineStart.LAZY) { engine.generate(request) }
    requests.register(requestId, work)
    return try {
      work.start()
      success(work.await().toMap())
    } catch (throwable: Throwable) {
      failure(mapThrowable(throwable))
    } finally {
      requests.finish(requestId)
    }
  }

  private fun startStream(
    requestId: String,
    messages: List<Map<String, String>>,
    temperature: Double?,
    maxOutputTokens: Int?,
  ) {
    val engine = this.engine
    if (engine == null) {
      send(BridgeStreamEvent.Failure(missingDependencyError()), requestId)
      return
    }

    val request = try {
      BridgeRequest.parse(messages, temperature, maxOutputTokens)
    } catch (throwable: Throwable) {
      // Emitted, not thrown: the TypeScript generator has subscribed by now and
      // has exactly one place that turns an event into an `LLMError`.
      send(BridgeStreamEvent.Failure(mapThrowable(throwable)), requestId)
      return
    }

    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        engine.stream(request) { event -> send(event, requestId) }
      } catch (cancellation: CancellationException) {
        send(BridgeStreamEvent.Failure(ErrorMapping.cancelled()), requestId)
      } catch (throwable: Throwable) {
        // `stream` promises never to throw; this is the backstop that keeps the
        // "exactly one terminal event" guarantee true even if it breaks.
        send(BridgeStreamEvent.Failure(mapThrowable(throwable)), requestId)
      }
    }
    requests.register(requestId, job)
    job.invokeOnCompletion { requests.finish(requestId) }
    job.start()

    // `startStream` deliberately does not await the job: it returns as soon as
    // the request is registered, so JavaScript can start consuming events (and
    // can cancel) while generation is still running.
  }

  private suspend fun countTokens(messages: List<Map<String, String>>): Map<String, Any?> {
    val engine = this.engine ?: return failure(missingDependencyError())
    return try {
      mapOf("ok" to true, "count" to engine.countTokens(BridgeRequest.parse(messages, null, null)))
    } catch (throwable: Throwable) {
      failure(mapThrowable(throwable))
    }
  }

  private fun send(event: BridgeStreamEvent, requestId: String) {
    sendEvent(STREAM_EVENT_NAME, event.toMap(requestId))
  }

  private fun success(result: Map<String, Any?>): Map<String, Any?> =
    mapOf("ok" to true, "result" to result)

  private fun failure(payload: BridgeErrorPayload): Map<String, Any?> =
    mapOf("ok" to false, "error" to payload.toMap())

  private fun missingDependencyError(): BridgeErrorPayload {
    val availability = AvailabilityMapping.missingDependency()
    return BridgeErrorPayload(
      code = "unavailable",
      message = availability.detail ?: "The Android on-device provider is unavailable",
      reason = availability.reason,
    )
  }

  /**
   * Anything thrown, onto the taxonomy.
   *
   * Order matters: our own `BridgeException` already carries a decided payload,
   * a `CancellationException` is the caller's own abort and must never be
   * dressed up as a failure, and only then is the SDK asked whether it
   * recognises the throwable. The last line is D9's Android form — an
   * unclassified `Throwable` becomes `unknown` with `transient: true` and its
   * class and message attached, so it is reportable rather than opaque, and
   * retryable rather than permanent.
   */
  private fun mapThrowable(throwable: Throwable): BridgeErrorPayload = when {
    throwable is BridgeException -> throwable.payload
    throwable is CancellationException -> ErrorMapping.cancelled()
    else -> engine?.mapThrowable(throwable)
      ?: ErrorMapping.mapUnclassified(throwable.javaClass.name, throwable.message)
  }
}
