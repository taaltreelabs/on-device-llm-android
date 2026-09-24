/**
 * `AndroidProvider` — an `LLMProvider` backed by the Kotlin module wrapping
 * Google's ML Kit GenAI Prompt API / AICore
 * (docs/research/android-genai.md).
 *
 * Fully unit-testable against a scriptable fake of the native module (see
 * `__tests__/fake-native.ts`), which is not a convenience but the only
 * verification available: AICore is a preinstalled system service present on no
 * emulator image (docs/research/android-genai.md §6, §12), so nothing below has
 * ever spoken to a real bridge. `resolveNative()` degrades to
 * `unsupportedPlatform` on every platform that lacks the module, which today
 * includes any Android build whose app has not opted in to the ML Kit dependency
 * (DECISIONS.md D2).
 *
 * Follows the architecture and wire conventions of the main package's Apple
 * provider (docs/research/android-genai.md §9 — "D17's wire shape … transfers to
 * Android unchanged"), with the gaps §10 of that document calls hard:
 * **no structured output, no tool calling, no locale enumeration.** Requests
 * carrying `schema` or `tools` are rejected up front by `buildNativeRequest`
 * (`./wire.ts`) rather than silently answered without them — and, since
 * DECISIONS.md D6, that is the *only* place they are rejected: the bridge no
 * longer accepts a `schemaJson` argument to refuse.
 */

import {
  LLMError,
  normalizeContextWindow,
  toLLMError,
  UNKNOWN,
  type Availability,
  type Capabilities,
  type GenerateRequest,
  type GenerateResult,
  type LLMProvider,
  type Message,
  type RequestOptions,
  type StreamEvent,
} from '@taaltreelabs/on-device-llm/core';

import { toLLMErrorFromNative, toUnavailableReason } from './errors';
import { resolveNativeModule } from './native/resolve';
import type { AndroidNativeModule } from './native/types';
import { bridgeNativeStream } from './stream-bridge';
import { buildNativeRequest, nextRequestId, toFinishReason, toTokenUsage } from './wire';

/** How the native module is obtained. Swapped in tests; not part of the public API. */
export type NativeResolver = () => AndroidNativeModule | undefined;

/**
 * Configuration for {@link createAndroidProvider}. Deliberately smaller than
 * `AppleProviderConfig`: there is no `locale` option here.
 *
 * `SystemLanguageModel.supportsLocale` has no Android counterpart — there is
 * no locale enumeration API at all (docs/research/android-genai.md §4:
 * `genai-common`'s `SapiLanguage` is an empty marker annotation with no
 * members). `capabilities().locales` is therefore always `UNKNOWN`, D7's
 * pre-check is permanently inert on this provider, and a locale failure can
 * only ever be discovered — never predicted — at generation time. Accepting
 * a `locale` option here would either be a silent no-op (worse than not
 * offering it: a caller who set it would reasonably expect it to do
 * something) or would have to throw for a configuration `AppleProviderConfig`
 * happily accepts, which is its own surprise. So the option is dropped
 * entirely rather than kept as a documented no-op; revisit only if a future
 * SDK version adds a real locale surface to check against.
 */
export interface AndroidProviderConfig {
  /** Stable provider id surfaced on results and errors. Defaults to `'android'`. */
  readonly id?: string;
}

const PLATFORM_DETAIL =
  'The ML Kit GenAI Prompt API / AICore bridge is not available in this process. Expected on ' +
  'iOS, on web, under Node, and on any Android build without the native module or without ' +
  'AICore itself (docs/research/android-genai.md §6 — AICore is a preinstalled system service, ' +
  'not present on any emulator image).';

export class AndroidProvider implements LLMProvider {
  readonly id: string;

  private readonly resolveNative: NativeResolver;

  /**
   * @param config - see {@link AndroidProviderConfig}. Only `id` exists today
   * and is read once, here, so nothing is retained beyond it.
   * @param resolveNative - injection seam for tests. Production code uses the
   * default, which resolves lazily and never throws (`./native/resolve`).
   */
  constructor(
    config: AndroidProviderConfig = {},
    resolveNative: NativeResolver = resolveNativeModule
  ) {
    this.id = config.id ?? 'android';
    this.resolveNative = resolveNative;
  }

  /**
   * Availability, with reason codes.
   *
   * Two sources, in order:
   *
   * 1. **No native module** -> `unsupportedPlatform`. The module is resolved
   *    inside this method, in a `try`/`catch`, never at import time, so the
   *    package root stays importable everywhere.
   * 2. **`FeatureStatus`, already mapped** by the Kotlin side onto our three
   *    usable reasons (docs/research/android-genai.md §5's table). There is
   *    no locale step here — see {@link AndroidProviderConfig}.
   */
  async availability(): Promise<Availability> {
    const native = this.resolveNative();
    if (native === undefined) {
      return { available: false, reason: 'unsupportedPlatform', detail: PLATFORM_DETAIL };
    }

    let nativeAvailability;
    try {
      nativeAvailability = await native.availability();
    } catch (err) {
      // A bridge that resolved but cannot answer is not a platform problem —
      // report it as the transient system failure it is (mirrors Apple's D9
      // handling; docs/research/android-genai.md §11 makes the same lane
      // load-bearing on Android).
      return {
        available: false,
        reason: 'modelNotReady',
        detail:
          err instanceof Error ? err.message : 'The native module failed to report availability.',
      };
    }

    if (!nativeAvailability.available) {
      return {
        available: false,
        reason: toUnavailableReason(nativeAvailability.reason),
        ...(nativeAvailability.detail !== undefined ? { detail: nativeAvailability.detail } : {}),
      };
    }

    return { available: true };
  }

  /**
   * What this provider can do *today*.
   *
   * `structuredOutput` and `tools` are hardcoded `false` — never derived from
   * anything the native side reports — because the Prompt API has no route to
   * either (docs/research/android-genai.md §3). `locales` is hardcoded
   * `UNKNOWN` for the same reason as the dropped `locale` config option.
   * `tokenCounting` is `'exact'` when `countTokens` is present:
   * `GenerativeModel.countTokens(request)` is the model's own accounting, not
   * an estimate — the same trust Apple's `tokenCount(for:)` gets, with the
   * caveat that its honesty inherits whatever role encoding the Kotlin side
   * ends up using (docs/research/android-genai.md §3, §10).
   */
  async capabilities(): Promise<Capabilities> {
    const unavailable: Capabilities = {
      contextWindow: UNKNOWN,
      streaming: false,
      structuredOutput: false,
      tools: false,
      tokenCounting: 'none',
      locales: UNKNOWN,
    };

    const native = this.resolveNative();
    if (native === undefined) return unavailable;

    let nativeCapabilities;
    try {
      nativeCapabilities = await native.capabilities();
    } catch {
      return unavailable;
    }

    // `0` (or anything non-positive) means the framework could not tell us.
    // Becomes the typed `UNKNOWN`, mirroring Apple's D9 guard.
    const contextWindow = normalizeContextWindow(nativeCapabilities.contextWindow);

    return {
      contextWindow,
      streaming: true,
      structuredOutput: false,
      tools: false,
      tokenCounting: typeof native.countTokens === 'function' ? 'exact' : 'none',
      locales: UNKNOWN,
      ...(nativeCapabilities.modelLabel !== undefined
        ? { modelLabel: nativeCapabilities.modelLabel }
        : {}),
    };
  }

  /**
   * Hint that a request is coming (`GenerativeModel.warmup()`). Never throws;
   * resolves `false` when the hint could not be delivered.
   *
   * Not a performance contract, exactly like Apple's: callers should treat it
   * as free and optional.
   */
  async prewarm(messages?: readonly Message[]): Promise<boolean> {
    const native = this.resolveNative();
    if (native?.prewarm === undefined) return false;
    try {
      return await native.prewarm(
        messages !== undefined
          ? messages.map((message) => ({ role: message.role, content: message.content }))
          : null
      );
    } catch {
      return false;
    }
  }

  /**
   * Exact token count for these messages, via
   * `GenerativeModel.countTokens(request)`.
   *
   * Throws rather than falling back to an estimate — the same contract
   * `LLMProvider.countTokens` documents and Apple's implementation honours.
   * `createMeasure` catches the throw, estimates instead, and widens its
   * safety margin accordingly.
   */
  async countTokens(messages: readonly Message[]): Promise<number> {
    const native = this.requireNative();
    if (native.countTokens === undefined) {
      throw new LLMError(
        { code: 'unknown', transient: false },
        {
          message:
            'This build of the native module does not implement token counting. ' +
            '`capabilities().tokenCounting` reports `none`, so callers should estimate.',
          providerId: this.id,
        }
      );
    }
    const outcome = await native.countTokens(
      messages.map((message) => ({ role: message.role, content: message.content }))
    );
    if (!outcome.ok) throw toLLMErrorFromNative(outcome.error, this.id);
    if (!Number.isFinite(outcome.count) || outcome.count < 0) {
      throw new LLMError(
        { code: 'unknown', transient: true },
        {
          message: `The native token count was not a usable number (${outcome.count}).`,
          providerId: this.id,
        }
      );
    }
    return outcome.count;
  }

  async generate(request: GenerateRequest, options?: RequestOptions): Promise<GenerateResult> {
    const native = this.requireNative();
    const signal = options?.signal;
    this.throwIfAborted(signal);

    // Validates and rejects `schema`/`tools` up front — before the bridge
    // hop, not after a wasted generation.
    const args = buildNativeRequest(request, this.id);
    const requestId = nextRequestId();

    const onAbort = (): void => {
      native.cancel(requestId).catch(() => {
        // Already finished, most likely; nothing useful to do.
      });
    };
    signal?.addEventListener('abort', onAbort, { once: true });

    try {
      const outcome = await native.generate(
        requestId,
        args.messages,
        args.temperature,
        args.maxOutputTokens
      );
      if (!outcome.ok) throw toLLMErrorFromNative(outcome.error, this.id);
      // The abort may have lost the race: native can finish normally between
      // `abort()` firing and `cancel()` landing. A result the caller has
      // already said they do not want is discarded here rather than returned.
      this.throwIfAborted(signal);

      const usage = toTokenUsage(outcome.result.usage);
      return {
        text: outcome.result.text,
        finishReason: toFinishReason(outcome.result.finishReason),
        ...(usage !== undefined ? { usage } : {}),
        providerId: this.id,
      };
    } catch (err) {
      // An abort that raced the response: the native side may have answered
      // normally before the cancel landed. The caller asked to stop, so
      // report `cancelled` either way.
      if (signal?.aborted === true) {
        throw new LLMError({ code: 'cancelled' }, { providerId: this.id, cause: signal.reason });
      }
      throw toLLMError(err, { providerId: this.id, transient: true });
    } finally {
      signal?.removeEventListener('abort', onAbort);
    }
  }

  stream(request: GenerateRequest, options?: RequestOptions): AsyncIterable<StreamEvent> {
    // Not an `async function*` itself: argument validation (including the
    // `schema`/`tools` rejection) must reject before the first `next()`, not
    // lazily at it, so a bad request fails where the caller made it.
    const native = this.requireNative();
    const args = buildNativeRequest(request, this.id);
    const requestId = nextRequestId();

    return bridgeNativeStream({
      native,
      requestId,
      providerId: this.id,
      start: () =>
        native.startStream(requestId, args.messages, args.temperature, args.maxOutputTokens),
      ...(options?.signal !== undefined ? { signal: options.signal } : {}),
    });
  }

  // ---- helpers -----------------------------------------------------------

  private requireNative(): AndroidNativeModule {
    const native = this.resolveNative();
    if (native === undefined) {
      throw new LLMError(
        { code: 'unavailable', reason: 'unsupportedPlatform' },
        { providerId: this.id, message: PLATFORM_DETAIL }
      );
    }
    return native;
  }

  private throwIfAborted(signal: AbortSignal | undefined): void {
    if (signal?.aborted === true) {
      throw new LLMError({ code: 'cancelled' }, { providerId: this.id, cause: signal.reason });
    }
  }
}
