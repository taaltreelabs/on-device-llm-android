/**
 * A structural smoke test for the composed plugin. `withAppBuildGradle` /
 * `withGradleProperties` register a lazily-run modifier on the Expo config
 * rather than executing it immediately, so this cannot (and does not try to)
 * exercise the actual Gradle-file rewriting here — that is
 * `gradle-transforms.test.js`'s job, on the pure functions directly. What
 * this checks is the wiring: the plugin is callable on a bare Expo config
 * without a real project on disk, registers both expected mods, and threads
 * plugin options through without throwing.
 */

import { describe, expect, it } from 'vitest';

import withOnDeviceLlmAndroid, {
  DEFAULT_ML_KIT_GENAI_PROMPT_VERSION,
  DEFAULT_MIN_SDK_VERSION,
} from '../withOnDeviceLlmAndroid';

function bareConfig() {
  return { name: 'example', slug: 'example' };
}

describe('withOnDeviceLlmAndroid', () => {
  it('exports the same pinned ML Kit version as SPIKE.md / README / spike.gradle', () => {
    expect(DEFAULT_ML_KIT_GENAI_PROMPT_VERSION).toBe('1.0.0-beta4');
  });

  it('defaults minSdkVersion to the AAR manifest floor', () => {
    expect(DEFAULT_MIN_SDK_VERSION).toBe(26);
  });

  it('registers both an appBuildGradle mod and a gradleProperties mod, without touching a real project', () => {
    const config = withOnDeviceLlmAndroid(bareConfig());
    expect(typeof config.mods.android.appBuildGradle).toBe('function');
    expect(typeof config.mods.android.gradleProperties).toBe('function');
  });

  it('accepts custom options without throwing', () => {
    expect(() =>
      withOnDeviceLlmAndroid(bareConfig(), {
        mlKitGenAiPromptVersion: '1.0.0-beta5',
        minSdkVersion: 27,
      })
    ).not.toThrow();
  });

  it('is safe to apply twice to the same config (as if listed twice, or prebuilt twice)', () => {
    let config = bareConfig();
    config = withOnDeviceLlmAndroid(config);
    expect(() => withOnDeviceLlmAndroid(config)).not.toThrow();
  });
});
