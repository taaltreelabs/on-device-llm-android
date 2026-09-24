/**
 * Pure content-transform tests for the config plugin (plugin/gradle-transforms.js).
 * No Expo project, no `expo prebuild`, no filesystem — these call the same
 * functions `withOnDeviceLlmAndroid.js` wires into `withAppBuildGradle` /
 * `withGradleProperties`, directly on fixture strings/arrays, which is the
 * only thing a config plugin's content transform actually needs verified: it
 * is deterministic content-in/content-out.
 */

import { describe, expect, it } from 'vitest';

import {
  ML_KIT_COORDINATE_PREFIX,
  KOTLIN_METADATA_SKIP_MARKER,
  addMlKitDependency,
  applyKotlinMetadataSkip,
  raiseMinSdkVersion,
} from '../gradle-transforms';

const FRESH_APP_BUILD_GRADLE = `apply plugin: "com.android.application"

android {
  namespace 'com.example.app'
}

dependencies {
  implementation("com.facebook.react:react-android")
}
`;

describe('addMlKitDependency', () => {
  it('fresh content: inserts the pinned coordinate into the dependencies block', () => {
    const out = addMlKitDependency(FRESH_APP_BUILD_GRADLE, '1.0.0-beta4');
    expect(out).toContain(`implementation '${ML_KIT_COORDINATE_PREFIX}1.0.0-beta4'`);
    // Existing content survives untouched.
    expect(out).toContain('implementation("com.facebook.react:react-android")');
  });

  it('custom version option: pins whatever version the plugin option asks for', () => {
    const out = addMlKitDependency(FRESH_APP_BUILD_GRADLE, '1.0.0-beta5');
    expect(out).toContain(`implementation '${ML_KIT_COORDINATE_PREFIX}1.0.0-beta5'`);
    expect(out).not.toContain('1.0.0-beta4');
  });

  it('already-applied content: running twice adds nothing twice', () => {
    const once = addMlKitDependency(FRESH_APP_BUILD_GRADLE, '1.0.0-beta4');
    const twice = addMlKitDependency(once, '1.0.0-beta4');
    expect(twice).toBe(once);
    expect(twice.match(/com\.google\.mlkit:genai-prompt/g)).toHaveLength(1);
  });

  it('already-applied at a different (e.g. hand-pinned) version: does not fight it', () => {
    const handPinned = FRESH_APP_BUILD_GRADLE.replace(
      'dependencies {',
      "dependencies {\n    implementation 'com.google.mlkit:genai-prompt:1.0.0-beta7'"
    );
    const out = addMlKitDependency(handPinned, '1.0.0-beta4');
    expect(out).toBe(handPinned);
    expect(out).not.toContain('1.0.0-beta4');
  });

  it('throws a clear error when there is no dependencies block to append to', () => {
    expect(() => addMlKitDependency('android {\n}\n', '1.0.0-beta4')).toThrow(/dependencies/i);
  });
});

describe('applyKotlinMetadataSkip', () => {
  it('fresh content: appends the KotlinCompile task-configuration block', () => {
    const out = applyKotlinMetadataSkip(FRESH_APP_BUILD_GRADLE);
    expect(out).toContain(KOTLIN_METADATA_SKIP_MARKER);
    expect(out).toContain('tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile)');
  });

  it('already-applied content: running twice adds nothing twice', () => {
    const once = applyKotlinMetadataSkip(FRESH_APP_BUILD_GRADLE);
    const twice = applyKotlinMetadataSkip(once);
    expect(twice).toBe(once);
    expect(twice.match(/-Xskip-metadata-version-check/g)).toHaveLength(1);
  });

  it('recognises the flag even if something else already added it', () => {
    const preExisting = FRESH_APP_BUILD_GRADLE + "\n// someone else's block\nfreeCompilerArgs.add('-Xskip-metadata-version-check')\n";
    expect(applyKotlinMetadataSkip(preExisting)).toBe(preExisting);
  });
});

describe('raiseMinSdkVersion', () => {
  it('fresh content: adds the property when absent', () => {
    const out = raiseMinSdkVersion([], 26);
    expect(out).toEqual([{ type: 'property', key: 'android.minSdkVersion', value: '26' }]);
  });

  it('custom version option: honours a non-default minSdkVersion', () => {
    const out = raiseMinSdkVersion([], 28);
    expect(out).toEqual([{ type: 'property', key: 'android.minSdkVersion', value: '28' }]);
  });

  it('already-applied content: running twice adds nothing twice (idempotent, no duplicate entries)', () => {
    const once = raiseMinSdkVersion([], 26);
    const twice = raiseMinSdkVersion(once, 26);
    expect(twice).toEqual(once);
    expect(twice).toHaveLength(1);
  });

  it('raises a lower existing value up to the floor', () => {
    const existing = [{ type: 'property', key: 'android.minSdkVersion', value: '24' }];
    const out = raiseMinSdkVersion(existing, 26);
    expect(out).toEqual([{ type: 'property', key: 'android.minSdkVersion', value: '26' }]);
  });

  it('minSdk already >= 26: leaves a higher existing value untouched, never lowers it', () => {
    const existing = [{ type: 'property', key: 'android.minSdkVersion', value: '28' }];
    const out = raiseMinSdkVersion(existing, 26);
    expect(out).toEqual(existing);
  });

  it('minSdk already == the floor: leaves it untouched (still idempotent)', () => {
    const existing = [{ type: 'property', key: 'android.minSdkVersion', value: '26' }];
    const out = raiseMinSdkVersion(existing, 26);
    expect(out).toBe(existing);
  });

  it('preserves other, unrelated properties untouched', () => {
    const existing = [{ type: 'property', key: 'android.enableJetifier', value: 'true' }];
    const out = raiseMinSdkVersion(existing, 26);
    expect(out).toContainEqual(existing[0]);
    expect(out).toContainEqual({ type: 'property', key: 'android.minSdkVersion', value: '26' });
  });
});
