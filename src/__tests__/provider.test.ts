/**
 * `AndroidProvider` against a scriptable fake of the Kotlin module.
 */

import {
  fitContext,
  isLLMError,
  UNKNOWN,
  type GenerateRequest,
} from '@taaltreelabs/on-device-llm/core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AndroidProvider } from '../provider';
import { FakeNativeModule } from './fake-native';

let native: FakeNativeModule;
const make = (config = {}): AndroidProvider => new AndroidProvider(config, () => native);

beforeEach(() => {
  native = new FakeNativeModule();
});

const ask: GenerateRequest = { messages: [{ role: 'user', content: 'hi' }] };

describe('availability', () => {
  it('passes through `available`', async () => {
    await expect(make().availability()).resolves.toEqual({ available: true });
  });

  it.each([
    ['deviceNotEligible', 'deviceNotEligible'],
    ['modelNotReady', 'modelNotReady'],
    ['unsupportedPlatform', 'unsupportedPlatform'],
  ])('maps the native reason %s', async (nativeReason, expected) => {
    native.availabilityResult = { available: false, reason: nativeReason, detail: 'because' };
    await expect(make().availability()).resolves.toEqual({
      available: false,
      reason: expected,
      detail: 'because',
    });
  });

  it('falls back to modelNotReady for a reason this version does not know', async () => {
    // A native module newer than the JS half (`checkStatus()` returning a
    // bare Int, docs/research/android-genai.md §5/§11 item 5). `modelNotReady`
    // is the only recoverable reason, so an unknown one must not permanently
    // write the device off.
    native.availabilityResult = { available: false, reason: 'somethingNew' };
    await expect(make().availability()).resolves.toMatchObject({ reason: 'modelNotReady' });
  });

  it('reports modelNotReady when the bridge itself fails', async () => {
    native.throwFrom.availability = new Error('bridge is wedged');
    await expect(make().availability()).resolves.toMatchObject({
      available: false,
      reason: 'modelNotReady',
      detail: 'bridge is wedged',
    });
  });

  it('has no locale step: availability never depends on any configured value', async () => {
    // AndroidProviderConfig has no `locale` option at all (no
    // supportsLocale-equivalent exists to check it against).
    await expect(make({ id: 'android' }).availability()).resolves.toEqual({ available: true });
  });
});

describe('capabilities', () => {
  it('reports what the bridge can do, with structuredOutput/tools/locales hardcoded', async () => {
    await expect(make().capabilities()).resolves.toEqual({
      contextWindow: 4096,
      streaming: true,
      structuredOutput: false,
      tools: false,
      tokenCounting: 'exact',
      locales: UNKNOWN,
      modelLabel: 'nano-v3',
    });
  });

  it('normalizes a zero context window to UNKNOWN (getTokenLimit() unknown)', async () => {
    native.capabilitiesResult = { ...native.capabilitiesResult, contextWindow: 0 };
    await expect(make().capabilities()).resolves.toMatchObject({ contextWindow: UNKNOWN });
  });

  it.each([-1, Number.NaN, 0.5])('normalizes %s to UNKNOWN or an integer', async (value) => {
    native.capabilitiesResult = { ...native.capabilitiesResult, contextWindow: value };
    const { contextWindow } = await make().capabilities();
    expect(contextWindow === UNKNOWN || Number.isInteger(contextWindow)).toBe(true);
  });

  it('always reports structuredOutput, tools false and locales UNKNOWN — nothing from the wire can flip them', async () => {
    // Unlike Apple's capabilities(), which follows the model's own
    // `LanguageModelCapabilities` flags, `AndroidNativeCapabilities` here has no
    // such flags at all: the Prompt API has no route to either
    // (docs/research/android-genai.md §3) and no locale enumeration
    // (§4), so these three are hardcoded regardless of what the native
    // side reports.
    await expect(make().capabilities()).resolves.toMatchObject({
      structuredOutput: false,
      tools: false,
      locales: UNKNOWN,
    });
  });

  it('always reports locales as UNKNOWN, since there is no enumeration API', async () => {
    await expect(make().capabilities()).resolves.toMatchObject({ locales: UNKNOWN });
  });

  it('reports tokenCounting none when the bridge has no countTokens', async () => {
    (native as { countTokens?: unknown }).countTokens = undefined;
    await expect(make().capabilities()).resolves.toMatchObject({ tokenCounting: 'none' });
  });

  it('degrades to an all-unknown shape when the bridge throws', async () => {
    native.throwFrom.capabilities = new Error('wedged');
    await expect(make().capabilities()).resolves.toMatchObject({
      contextWindow: UNKNOWN,
      streaming: false,
      tokenCounting: 'none',
    });
  });
});

describe('request validation (rejected before crossing the bridge)', () => {
  const rejects = async (request: GenerateRequest, pattern: RegExp): Promise<void> => {
    await expect(make().generate(request)).rejects.toSatisfy(
      (err: unknown) => isLLMError(err, 'invalidRequest') && pattern.test((err as Error).message)
    );
    expect(native.calls.generate).toHaveLength(0);
  };

  it('rejects a request carrying a schema', async () => {
    await rejects(
      { ...ask, schema: { type: 'object', properties: { a: { type: 'string' } } } },
      /structuredOutput/
    );
  });

  it('rejects a request carrying tools', async () => {
    await rejects(
      {
        ...ask,
        tools: [{ name: 'lookup', description: 'looks things up', parameters: { type: 'object' } }],
      },
      /tool calling/
    );
  });

  it('rejects an empty message list', async () => {
    await rejects({ messages: [] }, /empty/i);
  });

  it('rejects a system-only conversation', async () => {
    await rejects({ messages: [{ role: 'system', content: 'be nice' }] }, /only system messages/i);
  });

  it('does not require the conversation to end with a user message (role encoding is unresolved)', async () => {
    // Unlike the main package's src/apple's D17 rule: `Content` has no role field at all
    // (docs/research/android-genai.md §1), so this provider does not yet
    // assert a turn-taking discipline it cannot honour on the wire.
    await expect(
      make().generate({
        messages: [
          { role: 'user', content: 'hi' },
          { role: 'assistant', content: 'hello' },
        ],
      })
    ).resolves.toMatchObject({ text: 'hello' });
  });

  it('rejects a non-finite temperature', async () => {
    await rejects({ ...ask, temperature: Number.POSITIVE_INFINITY }, /finite/i);
  });

  it.each([0, -1, 1.5])('rejects maxOutputTokens=%s', async (value) => {
    await rejects({ ...ask, maxOutputTokens: value }, /positive integer/i);
  });
});

describe('generate', () => {
  it('returns a GenerateResult carrying the provider id', async () => {
    await expect(make().generate(ask)).resolves.toEqual({
      text: 'hello',
      finishReason: 'stop',
      usage: { inputTokens: 7, outputTokens: 2 },
      providerId: 'android',
    });
  });

  it('forwards sampling options, and null for the ones not set', async () => {
    await make().generate({ ...ask, temperature: 0.3 });
    expect(native.calls.generate[0]).toEqual([
      expect.stringMatching(/^android-/),
      [{ role: 'user', content: 'hi' }],
      0.3,
      null,
    ]);
  });

  it('omits usage when the native side reported none', async () => {
    native.generateResult = { ok: true, result: { text: 'x', finishReason: 'stop' } };
    await expect(make().generate(ask)).resolves.not.toHaveProperty('usage');
  });

  it('maps an unrecognised finish reason to `other`', async () => {
    native.generateResult = { ok: true, result: { text: 'x', finishReason: 'wat' } };
    await expect(make().generate(ask)).resolves.toMatchObject({ finishReason: 'other' });
  });

  it('rejects immediately when the signal is already aborted', async () => {
    await expect(make().generate(ask, { signal: AbortSignal.abort('stop it') })).rejects.toSatisfy(
      (err: unknown) => isLLMError(err, 'cancelled')
    );
    expect(native.calls.generate).toHaveLength(0);
  });

  it('cancels natively when the signal fires mid-request', async () => {
    const controller = new AbortController();
    let resolveGenerate!: () => void;
    const gate = new Promise<void>((resolve) => {
      resolveGenerate = resolve;
    });
    native.generate = async (requestId: string) => {
      native.calls.generate.push([requestId]);
      await gate;
      return native.generateResult;
    };

    const promise = make().generate(ask, { signal: controller.signal });
    await Promise.resolve();
    controller.abort();
    resolveGenerate();

    await expect(promise).rejects.toSatisfy((err: unknown) => isLLMError(err, 'cancelled'));
    expect(native.calls.cancel).toHaveLength(1);
  });
});

describe('native error payloads map onto the taxonomy (docs/research/android-genai.md §5)', () => {
  const failWith = async (error: Record<string, unknown>): Promise<unknown> => {
    native.generateResult = { ok: false, error: error as never };
    return make()
      .generate(ask)
      .then(
        () => {
          throw new Error('expected a rejection');
        },
        (err: unknown) => err
      );
  };

  it('unavailable carries the reason', async () => {
    const err = await failWith({ code: 'unavailable', message: 'assets', reason: 'modelNotReady' });
    expect(isLLMError(err, 'unavailable') && err.details.reason).toBe('modelNotReady');
  });

  it('contextOverflow carries contextSize and tokenCount (REQUEST_TOO_LARGE)', async () => {
    const err = await failWith({
      code: 'contextOverflow',
      message: 'too big',
      contextSize: 4096,
      tokenCount: 5200,
    });
    expect(isLLMError(err, 'contextOverflow') && err.details).toEqual({
      code: 'contextOverflow',
      contextSize: 4096,
      tokenCount: 5200,
    });
  });

  it('guardrail (reachable on the wire, even though no ErrorCode maps to it today)', async () => {
    expect(isLLMError(await failWith({ code: 'guardrail', message: 'blocked' }), 'guardrail')).toBe(
      true
    );
  });

  it('unsupportedLocale carries the locale (reachable on the wire; unreachable in practice today)', async () => {
    const err = await failWith({ code: 'unsupportedLocale', message: 'no', locale: 'pl' });
    expect(isLLMError(err, 'unsupportedLocale') && err.details.locale).toBe('pl');
  });

  it('rateLimited turns getRetryDelay()-derived millis into a Date (BUSY / PER_APP_BATTERY_USE_QUOTA_EXCEEDED)', async () => {
    const at = Date.UTC(2026, 8, 21, 12, 0, 0);
    const err = await failWith({ code: 'rateLimited', message: 'slow down', resetDate: at });
    expect(isLLMError(err, 'rateLimited') && err.details.resetDate?.getTime()).toBe(at);
  });

  it('cancelled (CANCELLED)', async () => {
    expect(isLLMError(await failWith({ code: 'cancelled', message: 'stopped' }), 'cancelled')).toBe(
      true
    );
  });

  it('invalidRequest (REQUEST_TOO_SMALL / NOT_SUPPORTED / INVALID_INPUT_IMAGE / STRUCTURED_OUTPUT_REQUEST_ERROR)', async () => {
    expect(
      isLLMError(await failWith({ code: 'invalidRequest', message: 'bad' }), 'invalidRequest')
    ).toBe(true);
  });

  it('unknown keeps the transient hint and the native diagnostics (BACKGROUND_USE_BLOCKED and friends)', async () => {
    const err = await failWith({
      code: 'unknown',
      message: 'Inference failed',
      transient: true,
      nativeDomain: 'GenAiException',
      nativeCode: 29,
      nativeDetail: '29-INTERNAL_ERROR: Inference failed',
    });
    expect(isLLMError(err, 'unknown') && err.details.transient).toBe(true);
    expect((err as Error).cause).toEqual({
      nativeCode: 'unknown',
      nativeMessage: 'Inference failed',
      nativeDomain: 'GenAiException',
      nativeErrorCode: 29,
      nativeDetail: '29-INTERNAL_ERROR: Inference failed',
    });
  });

  it('treats a code it does not recognise as unknown, without guessing transience (a beta5+ ErrorCode)', async () => {
    const err = await failWith({ code: 'AUDIO_BUFFER_OVERFLOW', message: '?' });
    expect(isLLMError(err, 'unknown') && err.details.transient).toBeUndefined();
  });
});

describe('prewarm', () => {
  it('never throws and forwards messages', async () => {
    await expect(make().prewarm([{ role: 'user', content: 'hi' }])).resolves.toBe(true);
    expect(native.calls.prewarm).toEqual([[{ role: 'user', content: 'hi' }]]);
  });

  it('passes null when no messages are given', async () => {
    await make().prewarm();
    expect(native.calls.prewarm).toEqual([null]);
  });

  it('resolves false rather than throwing when the bridge has no prewarm', async () => {
    (native as { prewarm?: unknown }).prewarm = undefined;
    await expect(make().prewarm()).resolves.toBe(false);
  });
});

describe('countTokens', () => {
  it('passes through the native count', async () => {
    await expect(make().countTokens([{ role: 'user', content: 'hi' }])).resolves.toBe(42);
  });

  it('throws the mapped LLMError when the native side reports a failure', async () => {
    native.countTokensResult = {
      ok: false,
      error: { code: 'unknown', message: 'wedged', transient: true },
    };
    await expect(make().countTokens([{ role: 'user', content: 'hi' }])).rejects.toSatisfy(
      (err: unknown) => isLLMError(err, 'unknown')
    );
  });

  it('throws unknown when the bridge has no countTokens', async () => {
    (native as { countTokens?: unknown }).countTokens = undefined;
    await expect(make().countTokens([{ role: 'user', content: 'hi' }])).rejects.toSatisfy(
      (err: unknown) => isLLMError(err, 'unknown')
    );
  });

  it('falls back to the estimator inside fitContext when the native counter fails (D9-style)', async () => {
    // Proves the context manager's contract holds for this provider too:
    // a thrown LLMError from countTokens degrades to an estimate rather than
    // aborting the trimming pass, with the fallback recorded in the metadata.
    native.countTokensResult = {
      ok: false,
      error: { code: 'unknown', message: 'Inference failed', transient: true },
    };
    const provider = make();
    const messages = [
      { role: 'user' as const, content: 'Hallo, hoe gaat het met je vandaag?' },
      { role: 'assistant' as const, content: 'Goed, dank je!' },
    ];
    const result = await fitContext(messages, { provider, contextWindow: 4096 });
    expect(result.measurement.source).toBe('estimatorAfterCounterFailure');
    expect(result.warnings.some((warning) => warning.code === 'tokenCounterFailed')).toBe(true);
    expect(result.messages).toEqual(messages);
  });
});
