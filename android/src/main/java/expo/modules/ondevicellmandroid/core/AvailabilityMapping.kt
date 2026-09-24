//
//  AvailabilityMapping.kt
//  OnDeviceLlm — Android
//
//  `FeatureStatus` -> `src/core`'s `Availability`, plus the two states that are
//  decided before the SDK is ever called: the API-level floor and the
//  dependency firewall.
//
//  ML Kit reports four states; our taxonomy has four reasons, three of which
//  Android can produce (docs/research/android-genai.md §5). The fourth,
//  `notEnabled`, exists because Apple has a user-facing Apple Intelligence
//  toggle. **AICore has no equivalent opt-in, so this provider never returns
//  `notEnabled`.** That is deliberate; nobody should "fix" the apparent
//  omission.
//
//  Everything here is a pure function over ints and strings so the whole table
//  is unit-testable on a machine with no Android SDK — which is the only kind
//  of verification available, since AICore runs on no emulator (§6).
//

package expo.modules.ondevicellmandroid.core

/** The four members of `com.google.mlkit.genai.common.FeatureStatus`, symbolically. */
enum class FeatureStatusCode {
  UNAVAILABLE,
  DOWNLOADABLE,
  DOWNLOADING,
  AVAILABLE,
}

/** `src/core`'s `Availability`, flattened onto the wire exactly as Apple sends it. */
data class BridgeAvailability(
  val available: Boolean,
  /** `'deviceNotEligible' | 'notEnabled' | 'modelNotReady' | 'unsupportedPlatform'`. */
  val reason: String? = null,
  val detail: String? = null,
) {
  fun toMap(): Map<String, Any?> = buildMap {
    put("available", available)
    reason?.let { put("reason", it) }
    detail?.let { put("detail", it) }
  }
}

object AvailabilityMapping {
  /**
   * `genai-prompt`'s own `minSdkVersion`, read from the AAR manifest (§0).
   * Only a compile floor — real usability is gated at runtime by AICore device
   * eligibility, which is an allowlist no spec predicts (§6).
   */
  const val MIN_SDK_INT: Int = 26

  /**
   * The Maven coordinate a consumer must add. Referenced from the `detail`
   * string so the remedy travels with the diagnosis.
   */
  const val DEPENDENCY_COORDINATE: String = "com.google.mlkit:genai-prompt:1.0.0-beta4"

  /**
   * Below the SDK floor. `null` when the device is new enough.
   *
   * `unsupportedPlatform` is the taxonomy's own words for "no such capability
   * on this platform or OS version" (`src/core/availability.ts`), and unlike
   * the other three reasons it is permanent for this device.
   */
  fun belowSdkFloor(sdkInt: Int): BridgeAvailability? =
    if (sdkInt >= MIN_SDK_INT) {
      null
    } else {
      BridgeAvailability(
        available = false,
        reason = "unsupportedPlatform",
        detail = "Android API $sdkInt is below the on-device model's floor of API $MIN_SDK_INT.",
      )
    }

  /**
   * The ML Kit GenAI SDK is not in this build (DECISIONS.md D33).
   *
   * **Why `unsupportedPlatform` and not `notEnabled`.** Argued from the two
   * reasons' documented semantics in `src/core/availability.ts`:
   *
   * - `notEnabled` is "capable hardware, but **the user** has not turned the
   *   feature on (Apple: `appleIntelligenceNotEnabled`)". It is a statement
   *   about a device setting a *user* can change, and UI that branches on it
   *   says so — "turn on Apple Intelligence in Settings". A missing Gradle
   *   dependency is a *developer build configuration* fact; no user can act on
   *   it, and an app showing a settings prompt for it would be telling the user
   *   a lie. Reusing the code would also break the invariant §5 establishes,
   *   that `notEnabled` has no Android analogue and this provider never returns
   *   it.
   * - `unsupportedPlatform` is "no such capability on this platform or OS
   *   version — e.g. Android, web, or below the OS floor", and its docblock is
   *   explicit that it covers the case Apple's enum cannot express: **the
   *   framework is not there at all.** With the SDK compiled `compileOnly`,
   *   that is literally true — the classes are absent from the APK. It is also
   *   permanent for the build in hand, which matches the code's non-retryable
   *   semantics, whereas `modelNotReady` would invite a caller to poll forever.
   *
   * The thing `unsupportedPlatform` loses is specificity: a developer sees the
   * same code the TypeScript layer emits on web. That is exactly what `detail`
   * is for — "an optional human-readable diagnostic for logs and dev UI ...
   * because 'modelNotReady' alone is not enough to debug a support ticket" — so
   * the detail names the missing artifact and the one line that fixes it.
   */
  fun missingDependency(): BridgeAvailability = BridgeAvailability(
    available = false,
    reason = "unsupportedPlatform",
    detail = "The ML Kit GenAI Prompt SDK is not present in this app. " +
      "This package compiles against it but never bundles it, so no consumer pays for " +
      "Play Services and the Firebase datatransport pipeline unless they opt in. " +
      "Add `implementation(\"$DEPENDENCY_COORDINATE\")` to your app's " +
      "android/app/build.gradle to enable the Android on-device provider.",
  )

  /**
   * `FeatureStatus` -> `Availability` (DECISIONS.md D34).
   *
   * | Android signal | ours |
   * |---|---|
   * | `AVAILABLE` | `{ available: true }` |
   * | `DOWNLOADING` | `modelNotReady` |
   * | `DOWNLOADABLE` | `modelNotReady` |
   * | `UNAVAILABLE` | `deviceNotEligible` |
   * | anything else | `modelNotReady` |
   *
   * **`DOWNLOADABLE`: report, do not download (D34).** `Flow<DownloadStatus>
   * download()` is right there and it is tempting to have `availability()`
   * start it. Three reasons not to:
   *
   * 1. **`availability()` is a question, not a command.** The Phase 4 router
   *    calls it on every route decision (behind a 5-second TTL cache, D28), and
   *    `useAvailability` calls it on mount. Starting a multi-hundred-megabyte,
   *    possibly metered download as a side effect of *asking* is the kind of
   *    surprise a library must never spring — and the caller never consented,
   *    because they think they asked a question.
   * 2. **There is nowhere to report progress.** `Availability` is
   *    `{ available, reason?, detail? }`. A download we started but cannot
   *    describe is strictly worse than one the caller started deliberately: §11
   *    records models stuck downloading indefinitely with nothing reported to
   *    the app, so an invisible download is a plausible permanent hang.
   * 3. **`modelNotReady` already says the true thing.** It is the recoverable
   *    reason; a caller re-checks and it flips to `available` once assets land.
   *
   * So `availability()` reports `modelNotReady` and names the remedy in
   * `detail`. Triggering the download belongs in an explicit, caller-initiated
   * API with a progress channel — a wave-2+ addition, deliberately not invented
   * here.
   *
   * **An unrecognised int does not crash us.** `checkStatus()` returns a bare
   * `Int`, not an enum (§5), and a fifth state in beta5 is entirely plausible
   * for an API that has shipped four betas in eight months. It degrades to
   * `modelNotReady`: of the reasons available it is the only recoverable one,
   * so a caller re-checks later instead of permanently writing the device off.
   */
  fun fromFeatureStatus(status: FeatureStatusCode?, rawStatus: Int): BridgeAvailability =
    when (status) {
      FeatureStatusCode.AVAILABLE ->
        // D9 still applies, on both platforms: `available` means "nothing known
        // is blocking me", not "the next request will succeed". §11.1 records
        // `Feature not available` crashes on real, AICore-equipped Pixel
        // hardware while status said otherwise.
        BridgeAvailability(available = true)

      FeatureStatusCode.DOWNLOADING ->
        BridgeAvailability(
          available = false,
          reason = "modelNotReady",
          detail = "The on-device model is still downloading.",
        )

      FeatureStatusCode.DOWNLOADABLE ->
        BridgeAvailability(
          available = false,
          reason = "modelNotReady",
          detail = "This device is eligible but the on-device model has not been downloaded " +
            "yet. availability() deliberately does not start the download.",
        )

      FeatureStatusCode.UNAVAILABLE ->
        BridgeAvailability(
          available = false,
          reason = "deviceNotEligible",
          detail = "AICore reports this device cannot run the on-device model. Eligibility is " +
            "an allowlist, not a hardware specification.",
        )

      null ->
        BridgeAvailability(
          available = false,
          reason = "modelNotReady",
          detail = "checkStatus() returned $rawStatus, which this version does not recognise.",
        )
    }

  /**
   * `checkStatus()` itself threw.
   *
   * A failure to *ask* is not the same as an answer, so this never reports
   * `available`. When the mapped error is already an `unavailable` with a
   * reason — `AICORE_INCOMPATIBLE`, `NEEDS_SYSTEM_UPDATE`, `NOT_AVAILABLE`,
   * `NOT_ENOUGH_DISK_SPACE` — that reason is used verbatim, because the error
   * table has already made exactly this judgement. Anything else becomes
   * `modelNotReady`: recoverable, so the caller re-checks rather than writing
   * the device off over one transient failure.
   */
  fun fromError(payload: BridgeErrorPayload): BridgeAvailability {
    val reason = payload.reason?.takeIf { payload.code == "unavailable" } ?: "modelNotReady"
    return BridgeAvailability(
      available = false,
      reason = reason,
      detail = payload.nativeDetail?.takeIf { it.isNotBlank() } ?: payload.message,
    )
  }
}
