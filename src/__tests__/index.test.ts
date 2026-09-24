/**
 * Lazy native-module resolution, on the platform this test runner actually
 * is. Node has no `react-native` module at all (see
 * `../native/resolve.ts`'s `isAndroidPlatform`), so running these tests
 * under vitest exercises exactly the same "not Android" path a real iOS,
 * web, or Android-without-the-module build takes. The package root must
 * import, the provider must construct, and every method must degrade to
 * `unavailable`/`unsupportedPlatform` rather than throw.
 */

import { isLLMError, UNKNOWN } from '@taaltreelabs/on-device-llm/core';
import { describe, expect, it } from 'vitest';

import { createAndroidProvider } from '../index';

describe('lazy native-module resolution under Node', () => {
  it('imports the package entry point without throwing', async () => {
    // A module-scope `requireNativeModule` anywhere in this subtree would take
    // the whole package down here — and in an app's router file on iOS, which is
    // where it would actually be noticed.
    await expect(import('../index')).resolves.toBeDefined();
  });

  it('resolves the peer package by its published subpath, not by a test-only alias', async () => {
    // `@taaltreelabs/on-device-llm/core` is how every source file in this package
    // reaches the contract, and it is a subpath *export* — there is no `core/`
    // directory in that package's tarball. Node, Metro and Vite each resolve it
    // through the exports map; tsc needs the `paths` entry in tsconfig.json
    // because its node10 resolver ignores `exports`. Two resolvers, one of which
    // is configured by hand, is exactly the arrangement that can silently
    // disagree — so this asserts the runtime one really works and really hands
    // back the contract, rather than trusting that a green typecheck implies it.
    const core = await import('@taaltreelabs/on-device-llm/core');
    expect(typeof core.isLLMError).toBe('function');
    expect(typeof core.normalizeContextWindow).toBe('function');
    expect(core.UNKNOWN).toBeDefined();
  });

  it('constructs a provider', () => {
    const provider = createAndroidProvider();
    expect(provider.id).toBe('android');
  });

  it('honours a configured id', () => {
    expect(createAndroidProvider({ id: 'on-device' }).id).toBe('on-device');
  });

  it('reports unsupportedPlatform rather than throwing', async () => {
    const availability = await createAndroidProvider().availability();
    expect(availability).toMatchObject({ available: false, reason: 'unsupportedPlatform' });
  });

  it('reports empty capabilities with an UNKNOWN context window', async () => {
    const capabilities = await createAndroidProvider().capabilities();
    expect(capabilities).toEqual({
      contextWindow: UNKNOWN,
      streaming: false,
      structuredOutput: false,
      tools: false,
      tokenCounting: 'none',
      locales: UNKNOWN,
    });
  });

  it('rejects generate with unavailable/unsupportedPlatform', async () => {
    const provider = createAndroidProvider();
    await expect(
      provider.generate({ messages: [{ role: 'user', content: 'hi' }] })
    ).rejects.toSatisfy((err: unknown) => {
      return (
        isLLMError(err, 'unavailable') &&
        err.details.reason === 'unsupportedPlatform' &&
        err.providerId === 'android'
      );
    });
  });

  it('rejects stream with unavailable/unsupportedPlatform, at the call, not at the first pull', () => {
    const provider = createAndroidProvider();
    expect(() => provider.stream({ messages: [{ role: 'user', content: 'hi' }] })).toThrow(
      /not available in this process/i
    );
  });

  it('resolves false rather than throwing for prewarm', async () => {
    await expect(createAndroidProvider().prewarm()).resolves.toBe(false);
  });

  it('throws unavailable/unsupportedPlatform for countTokens', async () => {
    await expect(
      createAndroidProvider().countTokens([{ role: 'user', content: 'hi' }])
    ).rejects.toSatisfy((err: unknown) => isLLMError(err, 'unavailable'));
  });
});
