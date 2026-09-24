/**
 * Native events -> `AsyncIterable<StreamEvent>`.
 *
 * This is `the main package's src/apple/stream-bridge.ts`'s bridge, duplicated rather than
 * shared, with the tool-calling machinery removed. See the "shared bridge"
 * note below for why duplication won over extraction.
 *
 * The native side pushes; `for await` pulls. Bridging the two needs a buffer
 * (the model can outrun a consumer that awaits between chunks) and a way to
 * notice the consumer leaving early, because leaving early has to stop real
 * work on the device — not merely stop listening.
 *
 * Three properties this file exists to guarantee, identically to Apple's:
 *
 * 1. **No lost events.** The listener is attached *before* `startStream` is
 *    called, and everything that arrives is queued whether or not the
 *    consumer is currently awaiting.
 * 2. **No crossed streams.** Every payload carries its `requestId` and
 *    anything else is ignored, so two concurrent streams over the one shared
 *    `onStreamEvent` channel never interleave.
 * 3. **Cancellation on every exit path.** `return`, `break`, `throw` in the
 *    consumer's loop, and an `AbortSignal` all end up calling native
 *    `cancel(requestId)`. Whether that actually stops AICore inference or
 *    only stops delivery is unverified without hardware
 *    (docs/research/android-genai.md §2) — flagged for the wave-2 spike.
 *
 * **Shared-bridge decision.** The task allowed extracting this into a small
 * internal `src/native-shared/` module imported by both `the main package's src/apple` and
 * this package, or duplicating the ~100 lines with a linking comment (this
 * one). Duplication won: Android's event union has no `objectSnapshot` or
 * `toolCall` cases (docs/research/android-genai.md §3), so a shared module
 * would need generic type parameters over the event union plus an
 * optional-tools code path purely to serve Apple's variant — which turns a
 * ~100-line file legible in one read into a parameterized abstraction with a
 * "does this platform have tools" branch threaded through it, for a platform
 * that structurally cannot. The two files should also be free to diverge
 * independently as each platform's native half evolves (Apple's tool
 * protocol, Android's eventual role-encoding fix); a shared module would
 * couple those changes for no shared benefit. If a *third* platform ever
 * needs this exact queue/demux/cancel shape, that is the point to extract it
 * — two instances is not yet a pattern.
 */

import {
  LLMError,
  toLLMError,
  type GenerateResult,
  type StreamEvent,
} from '@taaltreelabs/on-device-llm/core';

import { toLLMErrorFromNative } from './errors';
import type {
  AndroidNativeModule,
  AndroidNativeStreamEvent,
  AndroidNativeSubscription,
} from './native/types';
import { toFinishReason, toTokenUsage } from './wire';

/**
 * Unbounded FIFO with an async `next()`.
 *
 * Unbounded on purpose: the alternative is dropping deltas or blocking the
 * native emitter, and one response is bounded by the model's context window
 * (a few thousand tokens), so the buffer cannot grow without limit in
 * practice. A consumer that never drains is a consumer that will be
 * garbage-collected along with its queue.
 */
class EventQueue {
  private readonly buffer: AndroidNativeStreamEvent[] = [];
  private waiting: ((event: AndroidNativeStreamEvent) => void) | undefined;

  push(event: AndroidNativeStreamEvent): void {
    const resolve = this.waiting;
    if (resolve !== undefined) {
      this.waiting = undefined;
      resolve(event);
      return;
    }
    this.buffer.push(event);
  }

  async next(): Promise<AndroidNativeStreamEvent> {
    const buffered = this.buffer.shift();
    if (buffered !== undefined) return buffered;
    return new Promise<AndroidNativeStreamEvent>((resolve) => {
      this.waiting = resolve;
    });
  }
}

/** Inputs for {@link bridgeNativeStream}. */
export interface StreamBridgeOptions {
  readonly native: AndroidNativeModule;
  readonly requestId: string;
  readonly providerId: string;
  /** Kicks off the native stream. Called after the listener is attached. */
  readonly start: () => Promise<void>;
  readonly signal?: AbortSignal;
}

/**
 * Turn one native stream into an `AsyncGenerator<StreamEvent>`.
 *
 * Terminates when a `finish` event arrives (yielded as `StreamEvent`
 * `finish`) or an `error` event arrives (thrown as a typed `LLMError`). The
 * native side is expected to guarantee exactly one terminal event per
 * request, so this does not need a timeout of its own — a caller who wants
 * one passes an `AbortSignal`.
 *
 * Being an async *generator*, the body below — listener included — does not
 * run until the first `next()`. That is the right laziness for a provider:
 * building a stream object costs nothing and starts no generation, so a
 * caller who never iterates never occupies AICore. It does mean `start()` is
 * called from inside the first pull, not from `stream()`.
 */
export async function* bridgeNativeStream(
  options: StreamBridgeOptions
): AsyncGenerator<StreamEvent, void, undefined> {
  const { native, requestId, providerId, start, signal } = options;

  if (signal?.aborted === true) {
    throw new LLMError({ code: 'cancelled' }, { providerId, cause: signal.reason });
  }

  const queue = new EventQueue();
  let terminated = false;

  const subscription: AndroidNativeSubscription = native.addListener(
    'onStreamEvent',
    (event: AndroidNativeStreamEvent) => {
      // Demultiplex: this listener sees every stream's events.
      if (event.requestId !== requestId) return;
      queue.push(event);
    }
  );

  const cancelNative = (): void => {
    native.cancel(requestId).catch(() => {
      // The request may already have finished; a failed cancel of a request
      // that no longer exists is not worth surfacing.
    });
  };

  const onAbort = (): void => {
    cancelNative();
  };
  signal?.addEventListener('abort', onAbort, { once: true });

  try {
    try {
      await start();
    } catch (err) {
      // Only reachable if the bridge call itself fails (a malformed argument,
      // a module torn down mid-call). Generation failures arrive as events.
      throw toLLMError(err, { providerId, transient: true });
    }

    while (!terminated) {
      const event = await queue.next();
      switch (event.type) {
        case 'delta':
          if (event.delta !== '') {
            yield { type: 'textDelta', delta: event.delta };
          }
          break;
        case 'finish': {
          terminated = true;
          const usage = toTokenUsage(event.result.usage);
          const result: GenerateResult = {
            text: event.result.text,
            finishReason: toFinishReason(event.result.finishReason),
            ...(usage !== undefined ? { usage } : {}),
            providerId,
          };
          yield { type: 'finish', result };
          return;
        }
        case 'error': {
          terminated = true;
          throw toLLMErrorFromNative(event.error, providerId);
        }
      }
    }
  } finally {
    subscription.remove();
    signal?.removeEventListener('abort', onAbort);
    if (!terminated) {
      // We are leaving without a terminal event: the consumer broke out of
      // its loop, threw, or was aborted. `finally` is the only place that
      // sees all three, and stopping delivery is not the same as stopping
      // native work — this is what actually asks the native side to.
      cancelNative();
    }
  }
}
