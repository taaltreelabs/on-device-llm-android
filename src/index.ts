/**
 * `@taaltreelabs/on-device-llm-android`
 *
 * The Android on-device provider for `@taaltreelabs/on-device-llm`: an
 * `LLMProvider` backed by a Kotlin module wrapping Google's ML Kit GenAI Prompt
 * API / AICore (docs/research/android-genai.md).
 *
 * **This lives in its own package on purpose (DECISIONS.md D1).** The main
 * package's name promises on-device-and-private and delivers it. Google's terms
 * for this SDK do not, quite: inference runs on the device, and usage and
 * performance metrics are sent to Google. That asterisk is real, passing it on to
 * end users is a duty the terms place on the consumer, and it belongs on a
 * package whose README can say so in its own voice rather than in a subsection of
 * somebody else's. Read README.md § Privacy & terms before installing this.
 *
 * **Importing this package must never throw** — not on iOS, not on web, not
 * under Node, not on an Android build without the ML Kit dependency. A router
 * file naming both providers is evaluated on every platform the app ships to.
 * Nothing here touches the native module at load time; it is resolved inside
 * method bodies, in a `try`/`catch`, by `./native/resolve`, and a failure to
 * resolve is reported as `unavailable` with reason `unsupportedPlatform`.
 *
 * **Status: PROVISIONAL / experimental.** Nothing in this package has run
 * against a model. AICore is a preinstalled system service present on no
 * emulator image (docs/research/android-genai.md §6), so there is no CI path and
 * no laptop dev loop; what is proven is that the Kotlin half compiles against
 * the real 1.0.0-beta4 artifacts, that its device-independent logic passes its
 * JVM tests, and that this TypeScript half behaves correctly against a
 * scriptable fake of the bridge. DECISIONS.md's PROVISIONAL register lists every
 * assumption a hardware spike still has to settle.
 *
 * No structured output and no tool calling — see `./wire.ts`'s `rejectSchema`
 * and `rejectTools` — and no `locale` configuration option, unlike the main
 * package's Apple provider — see `AndroidProviderConfig`'s docblock.
 */

import type { LLMProvider } from '@taaltreelabs/on-device-llm/core';

import { AndroidProvider, type AndroidProviderConfig } from './provider';

export { AndroidProvider, type AndroidProviderConfig } from './provider';
export type {
  AndroidNativeModule,
  AndroidNativeAvailability,
  AndroidNativeCapabilities,
  AndroidNativeCountTokensOutcome,
  AndroidNativeErrorPayload,
  AndroidNativeGenerateOutcome,
  AndroidNativeResult,
  AndroidNativeStreamEvent,
  AndroidNativeUsage,
} from './native/types';

/**
 * Build an `LLMProvider` backed by the on-device Android model (ML Kit GenAI
 * Prompt API / AICore).
 *
 * ```ts
 * const android = createAndroidProvider();
 *
 * const availability = await android.availability();
 * if (availability.available) {
 *   for await (const event of android.stream({ messages })) {
 *     if (event.type === 'textDelta') process.stdout.write(event.delta);
 *   }
 * }
 * ```
 *
 * Safe to call on every platform: on anything without the native module the
 * returned provider reports `unavailable` / `unsupportedPlatform` and its
 * `generate`/`stream` throw the matching `LLMError` rather than crashing.
 */
export function createAndroidProvider(config: AndroidProviderConfig = {}): LLMProvider {
  return new AndroidProvider(config);
}
