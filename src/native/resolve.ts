/**
 * Lazy native-module resolution.
 *
 * **Importing this package must never throw** — not on iOS, not on web, not in
 * a Node test runner, not on an Android build without the native module. An app
 * that builds for both platforms imports this module from a router file that is
 * evaluated on both, so a module-load-time `requireNativeModule` would crash
 * iOS at startup. Nothing is resolved until a provider method actually needs it,
 * resolution is wrapped in `try`/`catch`, and a failure is `undefined`, which
 * the caller reports as `unavailable` / `unsupportedPlatform`.
 *
 * **`requireNativeModule('OnDeviceLlmAndroid')` — the name is the point.**
 * DECISIONS.md D1: when the Android and Apple providers shipped in one npm
 * package, both native halves registered as `OnDeviceLlm`, so the name told you
 * nothing about which platform's implementation Expo was about to hand back, and
 * this file had to work that out by inspection. Duck-typing could not do it
 * either: this module's method set (`availability`, `capabilities`, `generate`,
 * `startStream`, `cancel`, `addListener`) is a *subset* of the Swift module's by
 * design, because both mirror the same wire shape
 * (docs/research/android-genai.md §9) — so an iOS device with the Swift module
 * loaded satisfies every check this file could make. There is now exactly one
 * implementation of `OnDeviceLlmAndroid` and it is the Kotlin one, so the name
 * alone is decisive.
 *
 * **The `Platform.OS === 'android'` gate stays anyway**, checked *first*, before
 * Expo is ever touched, because it is not the same question. The name says "if
 * something answers, it is ours"; the platform check says "do not go asking".
 * On iOS, web, Node and Deno there is nothing to find, and `requireNativeModule`
 * throws by design when a module is absent — cheap, but noisy in a debugger and
 * one `try`/`catch` away from a crash if a future Expo version changes that to
 * something unhandled. Asking a question whose answer is known is also how a
 * "not on this platform" turns into a logged error someone has to triage.
 *
 * `react-native` is required the same lazy, `try`/`catch`-guarded way `expo`
 * already is below: it throws under a plain Node test runner (there is no
 * `react-native` package built for Node — the source is Flow-typed and
 * unparsable outside Metro/Babel) and that throw is exactly the signal this
 * file wants there, too.
 */

import type { AndroidNativeModule } from './types';

/**
 * Present in CommonJS (the compiled output) and in Metro's module wrapper;
 * absent under a raw ESM loader. Declared rather than pulled from
 * `@types/node`, which this package does not depend on.
 */
declare const require: ((id: string) => unknown) | undefined;

interface ExpoModuleNamespace {
  requireNativeModule?: (name: string) => unknown;
}

interface ReactNativeNamespace {
  Platform?: { OS?: string };
}

/** `null` = resolution has been attempted and failed. `undefined` = not attempted. */
let cached: AndroidNativeModule | null | undefined;

/**
 * Did resolution hand back something usable at all?
 *
 * No longer load-bearing for *identification* — `OnDeviceLlmAndroid` has one
 * implementation (see the file docblock) — so this is the ordinary version guard:
 * npm makes a JavaScript half newer than the installed native half entirely
 * possible, and a provider that advertises a protocol the native side cannot
 * speak sends the router toward a provider about to fail (the same argument
 * `capabilities()` makes for its own conjunctions). The optional methods
 * (`prewarm`, `countTokens`) are checked at their call sites instead, because
 * their absence degrades one feature rather than invalidating the module.
 */
function isUsable(candidate: unknown): candidate is AndroidNativeModule {
  if (typeof candidate !== 'object' || candidate === null) return false;
  const module = candidate as Record<string, unknown>;
  return (
    typeof module['availability'] === 'function' &&
    typeof module['capabilities'] === 'function' &&
    typeof module['generate'] === 'function' &&
    typeof module['startStream'] === 'function' &&
    typeof module['cancel'] === 'function' &&
    typeof module['addListener'] === 'function'
  );
}

/**
 * `true` only when `react-native`'s `Platform.OS` says `'android'`.
 *
 * Never throws: a Node test runner and a raw ESM loader both fail to resolve
 * `react-native` at all, which this treats identically to "not Android".
 */
function isAndroidPlatform(): boolean {
  try {
    if (typeof require !== 'function') return false;
    const reactNative = require('react-native') as ReactNativeNamespace;
    return reactNative?.Platform?.OS === 'android';
  } catch {
    return false;
  }
}

/**
 * The Kotlin module, or `undefined` on any platform that does not have it: iOS,
 * web, Node, and any Android build whose app has not added the ML Kit GenAI
 * dependency (DECISIONS.md D2 — the module itself always loads; it is the
 * *engine* inside it that is absent, and `availability()` says so).
 *
 * Never throws. The result is cached, including the failure — a platform does
 * not grow a native module between calls, and retrying a `require` that
 * fails on every request is pure overhead.
 */
export function resolveNativeModule(): AndroidNativeModule | undefined {
  if (cached !== undefined) return cached ?? undefined;
  cached = null;
  try {
    if (!isAndroidPlatform()) return undefined;
    if (typeof require !== 'function') return undefined;
    const expo = require('expo') as ExpoModuleNamespace;
    if (typeof expo?.requireNativeModule !== 'function') return undefined;
    const candidate = expo.requireNativeModule('OnDeviceLlmAndroid');
    if (isUsable(candidate)) {
      cached = candidate;
      return candidate;
    }
  } catch {
    // Expected on iOS, web, Node, and any Android build without the module.
    // Deliberately silent: this is a supported state, not an error, and the
    // caller turns it into `unavailable`/`unsupportedPlatform`.
  }
  return undefined;
}

/**
 * Test seam. Not exported from `src/index.ts`, and not part of the
 * public API: unit tests import it from this path directly. Pass `undefined`
 * to simulate a platform without the module, or `null` to clear the cache and
 * resolve for real again.
 */
export function __setNativeModuleForTests(module: AndroidNativeModule | undefined | null): void {
  cached = module === null ? undefined : (module ?? null);
}
