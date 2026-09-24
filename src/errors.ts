/**
 * Native error payload -> `LLMError`.
 *
 * Copied from `the main package's src/apple/errors.ts` rather than imported (file ownership
 * keeps this package from importing `the main package's src/apple`, and the isolation posture
 * for provider subpaths is per-platform, not shared) and adjusted for
 * Android's own error table: the mapping logic — rebuild the typed
 * `LLMErrorDetails` union from the flat wire payload — is identical, because
 * both platforms share the same taxonomy and the same wire contract
 * (docs/research/android-genai.md §9, §5's 21-code table). The heavy lifting
 * happens on the Kotlin side (the eventual
 * `android/.../ErrorMapping.kt`), which knows `GenAiException.ErrorCode`;
 * this file only reconstructs `LLMError` from what crosses the bridge.
 */

import {
  LLMError,
  type LLMErrorDetails,
  type UnavailableReason,
} from '@taaltreelabs/on-device-llm/core';

import type { AndroidNativeErrorPayload } from './native/types';

const UNAVAILABLE_REASONS: readonly UnavailableReason[] = [
  'deviceNotEligible',
  'notEnabled',
  'modelNotReady',
  'unsupportedPlatform',
];

/**
 * Validate a reason string coming across the bridge.
 *
 * An unrecognised reason becomes `modelNotReady`: of the codes we have, it is
 * the only recoverable one, so a caller re-checks later instead of writing
 * the device off permanently on the strength of a string it did not
 * understand. `'notEnabled'` stays in the union for type parity with Apple's
 * — AICore has no user-facing opt-in toggle, so the Kotlin side never sends
 * it (docs/research/android-genai.md §5) — but nothing here special-cases it.
 */
export function toUnavailableReason(value: string | undefined): UnavailableReason {
  return UNAVAILABLE_REASONS.includes(value as UnavailableReason)
    ? (value as UnavailableReason)
    : 'modelNotReady';
}

/**
 * The `cause` attached to every bridged error: the native diagnostics, kept
 * verbatim so a failure stays reportable. `GenAiException.getErrorCode()` and
 * its message are the minimum; the rest is best-effort diagnostics for the
 * untyped/`UNKNOWN` branch (docs/research/android-genai.md §11 item 1 — "D9's
 * transient-unknown lane is not optional on Android; it is load-bearing from
 * day one").
 *
 * Deliberately not an `Error`: it is data, and making it an `Error` would
 * invite it being thrown somewhere as if it were already classified.
 */
export interface NativeErrorCause {
  readonly nativeCode: string;
  readonly nativeMessage: string;
  readonly nativeDomain?: string;
  readonly nativeErrorCode?: number;
  readonly nativeDetail?: string;
}

function buildCause(payload: AndroidNativeErrorPayload): NativeErrorCause {
  return {
    nativeCode: payload.code,
    nativeMessage: payload.message,
    ...(payload.nativeDomain !== undefined ? { nativeDomain: payload.nativeDomain } : {}),
    ...(payload.nativeCode !== undefined ? { nativeErrorCode: payload.nativeCode } : {}),
    ...(payload.nativeDetail !== undefined ? { nativeDetail: payload.nativeDetail } : {}),
  };
}

function buildDetails(payload: AndroidNativeErrorPayload): LLMErrorDetails {
  switch (payload.code) {
    case 'unavailable':
      return { code: 'unavailable', reason: toUnavailableReason(payload.reason) };
    case 'contextOverflow':
      return {
        code: 'contextOverflow',
        ...(typeof payload.contextSize === 'number' ? { contextSize: payload.contextSize } : {}),
        ...(typeof payload.tokenCount === 'number' ? { tokenCount: payload.tokenCount } : {}),
      };
    case 'guardrail':
      // No `GenAiException.ErrorCode` maps here today
      // (docs/research/android-genai.md §5 — "There is no `guardrail` code").
      // Kept reachable in case a future SDK version adds one: the wire
      // contract is shared with Apple, and a code this JS does not expect
      // should still decode rather than fall through to `unknown`.
      return { code: 'guardrail' };
    case 'unsupportedLocale':
      // Likewise unreachable today (no locale surface at all,
      // docs/research/android-genai.md §4) but kept for wire parity.
      return {
        code: 'unsupportedLocale',
        ...(payload.locale !== undefined ? { locale: payload.locale } : {}),
      };
    case 'rateLimited':
      // `GenAiException.getRetryDelay()` -> `resetDate`, on the Kotlin side.
      return {
        code: 'rateLimited',
        ...(typeof payload.resetDate === 'number'
          ? { resetDate: new Date(payload.resetDate) }
          : {}),
      };
    case 'cancelled':
      return { code: 'cancelled' };
    case 'network':
      return { code: 'network' };
    case 'invalidRequest':
      return { code: 'invalidRequest' };
    case 'unknown':
      return {
        code: 'unknown',
        ...(typeof payload.transient === 'boolean' ? { transient: payload.transient } : {}),
      };
    default:
      // A code this version of the JavaScript does not know — a native
      // module newer than the JS half, or a beta5+ `ErrorCode` this package
      // has not been updated for yet (docs/research/android-genai.md §11
      // item 5). Transient is left unset: we genuinely do not know.
      return { code: 'unknown' };
  }
}

/** Rebuild a typed `LLMError` from what the Kotlin side sent. */
export function toLLMErrorFromNative(
  payload: AndroidNativeErrorPayload,
  providerId: string
): LLMError {
  return new LLMError(buildDetails(payload), {
    message: payload.message,
    providerId,
    cause: buildCause(payload),
  });
}
