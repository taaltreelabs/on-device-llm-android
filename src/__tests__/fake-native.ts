/**
 * A scriptable stand-in for the Kotlin module.
 *
 * The provider takes its native resolver by injection (`new AndroidProvider(config, resolver)`)
 * rather than reaching for a module-mocking framework, so these tests exercise
 * the real code path — including the `requestId` demultiplexing and the
 * cancellation calls, which a `vi.mock` of the module would hide behind a
 * stub. Mirrors `the main package's src/apple/__tests__/fake-native.ts`, minus everything
 * tool-related.
 */

import type {
  AndroidNativeModule,
  AndroidNativeAvailability,
  AndroidNativeCapabilities,
  AndroidNativeCountTokensOutcome,
  AndroidNativeGenerateOutcome,
  AndroidNativeStreamEvent,
  AndroidNativeSubscription,
} from '../native/types';

export class FakeNativeModule implements AndroidNativeModule {
  availabilityResult: AndroidNativeAvailability = { available: true };
  capabilitiesResult: AndroidNativeCapabilities = {
    contextWindow: 4096,
    modelLabel: 'nano-v3',
  };
  generateResult: AndroidNativeGenerateOutcome = {
    ok: true,
    result: { text: 'hello', finishReason: 'stop', usage: { inputTokens: 7, outputTokens: 2 } },
  };

  /** Throw from a given method instead of resolving. */
  throwFrom: Partial<Record<'availability' | 'capabilities', Error>> = {};

  countTokensResult: AndroidNativeCountTokensOutcome = { ok: true, count: 42 };

  readonly calls: {
    generate: unknown[][];
    startStream: unknown[][];
    cancel: string[];
    prewarm: unknown[];
    countTokens: unknown[];
  } = {
    generate: [],
    startStream: [],
    cancel: [],
    prewarm: [],
    countTokens: [],
  };

  /** Resolves when `startStream` has been called. */
  startStreamCalled: Promise<void>;
  private resolveStartStreamCalled!: () => void;

  private listeners = new Set<(event: AndroidNativeStreamEvent) => void>();

  constructor() {
    this.startStreamCalled = new Promise<void>((resolve) => {
      this.resolveStartStreamCalled = resolve;
    });
  }

  /** Number of listeners currently attached — proves `remove()` is called. */
  get listenerCount(): number {
    return this.listeners.size;
  }

  async availability(): Promise<AndroidNativeAvailability> {
    if (this.throwFrom.availability) throw this.throwFrom.availability;
    return this.availabilityResult;
  }

  async capabilities(): Promise<AndroidNativeCapabilities> {
    if (this.throwFrom.capabilities) throw this.throwFrom.capabilities;
    return this.capabilitiesResult;
  }

  async generate(
    requestId: string,
    messages: readonly { readonly role: string; readonly content: string }[],
    temperature: number | null,
    maxOutputTokens: number | null
  ): Promise<AndroidNativeGenerateOutcome> {
    this.calls.generate.push([requestId, messages, temperature, maxOutputTokens]);
    return this.generateResult;
  }

  async startStream(
    requestId: string,
    messages: readonly { readonly role: string; readonly content: string }[],
    temperature: number | null,
    maxOutputTokens: number | null
  ): Promise<void> {
    this.calls.startStream.push([requestId, messages, temperature, maxOutputTokens]);
    this.resolveStartStreamCalled();
  }

  async cancel(requestId: string): Promise<boolean> {
    this.calls.cancel.push(requestId);
    return true;
  }

  async prewarm(
    messages: readonly { readonly role: string; readonly content: string }[] | null
  ): Promise<boolean> {
    this.calls.prewarm.push(messages);
    return true;
  }

  async countTokens(
    messages: readonly { readonly role: string; readonly content: string }[]
  ): Promise<AndroidNativeCountTokensOutcome> {
    this.calls.countTokens.push(messages);
    return this.countTokensResult;
  }

  addListener(
    _eventName: 'onStreamEvent',
    listener: (event: AndroidNativeStreamEvent) => void
  ): AndroidNativeSubscription {
    this.listeners.add(listener);
    return {
      remove: () => {
        this.listeners.delete(listener);
      },
    };
  }

  /** Push an event as the native side would. */
  emit(event: AndroidNativeStreamEvent): void {
    for (const listener of [...this.listeners]) listener(event);
  }

  /** The id of the most recent `startStream` call. */
  get lastStreamRequestId(): string {
    const last = this.calls.startStream[this.calls.startStream.length - 1];
    return last?.[0] as string;
  }
}
