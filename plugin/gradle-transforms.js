// ============================================================================
//  gradle-transforms.js — the pure, testable half of the config plugin
// ============================================================================
//
//  Everything a real `expo prebuild` run needs @expo/config-plugins for (a
//  live Expo project, a generated `android/` tree, an actual filesystem) is
//  kept out of this file on purpose. These three functions take the CONTENT
//  a modifier already handed back — a Groovy string for the two build.gradle
//  edits, the gradle.properties array for the third — and return the new
//  content. `withOnDeviceLlmAndroid.js` is the thin wiring that calls these
//  from inside `withAppBuildGradle`/`withGradleProperties`; this file is what
//  `plugin/__tests__/gradle-transforms.test.js` exercises directly, with no
//  Expo project, no filesystem and no prebuild in sight.
//
//  All three are idempotent by construction: each checks for its own already
//  applied marker before writing anything, so running `expo prebuild` twice
//  (or listing the plugin twice) never duplicates a line.
//
//  This mirrors DECISIONS.md's "consumer opt-in is three lines, not one"
//  finding (SPIKE.md, the T2 tripwire) — one function per line.
// ============================================================================

/** The coordinate's group:artifact, deliberately without a version, so an
 * already-present line at ANY version is recognised as "already applied"
 * rather than fought over — a consumer who pinned a newer beta by hand wins. */
const ML_KIT_COORDINATE_PREFIX = 'com.google.mlkit:genai-prompt:';

const KOTLIN_METADATA_SKIP_MARKER = '-Xskip-metadata-version-check';

const MIN_SDK_PROPERTY_KEY = 'android.minSdkVersion';

/**
 * (a) Appends the ML Kit `implementation` line to an app module's
 * `build.gradle` Groovy content, inside the first `dependencies { ... }`
 * block. A no-op if the coordinate (any version) is already present.
 */
function addMlKitDependency(contents, version) {
  if (contents.includes(ML_KIT_COORDINATE_PREFIX)) {
    return contents;
  }
  if (!/dependencies\s*\{/.test(contents)) {
    throw new Error(
      "withOnDeviceLlmAndroid: could not find a 'dependencies {' block in app/build.gradle"
    );
  }
  return contents.replace(
    /dependencies\s*\{/,
    `dependencies {\n    // on-device-llm-android: the consumer opt-in (DECISIONS.md D2) — required\n    // for the Android provider to report available instead of unsupportedPlatform.\n    implementation '${ML_KIT_COORDINATE_PREFIX}${version}'`
  );
}

/**
 * (c) Appends a `KotlinCompile` task-configuration block that adds
 * `-Xskip-metadata-version-check`, scoped to whichever module's build.gradle
 * this is applied to — which `withOnDeviceLlmAndroid.js` scopes to the app
 * module only. See SPIKE.md's "consumer opt-in is three lines" section for
 * why the app module needs this at all: `genai-prompt`'s Kotlin metadata
 * version outruns the Expo/RN template's pinned Kotlin compiler, and without
 * this flag `:app:compileDebugKotlin` fails on files that never mention ML
 * Kit. A no-op if the flag is already present anywhere in the file.
 */
function applyKotlinMetadataSkip(contents) {
  if (contents.includes(KOTLIN_METADATA_SKIP_MARKER)) {
    return contents;
  }
  const block = [
    '',
    '// on-device-llm-android: genai-prompt is compiled with a newer Kotlin than the',
    '// Expo/RN template pins, so the app module\'s own Kotlin compile tasks need to',
    '// read (not verify) its metadata. See DECISIONS.md D2 / tripwire T2.',
    'tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {',
    '  compilerOptions {',
    `    freeCompilerArgs.add('${KOTLIN_METADATA_SKIP_MARKER}')`,
    '  }',
    '}',
    '',
  ].join('\n');
  return contents + block;
}

/**
 * (b) Raises `android.minSdkVersion` to at least `minSdkVersion`, operating
 * on the array shape `withGradleProperties` hands back for
 * `android/gradle.properties` (`{ type: 'property'|'comment', key?, value? }`
 * entries). **Why gradle.properties and not a regex over
 * `android/build.gradle`'s `ext` block** (the other candidate, and the one
 * `expo-build-properties` itself does not use for this key): every Expo/RN
 * template already reads this exact property with a fallback —
 * `Integer.parseInt(findProperty('android.minSdkVersion') ?: '24')` — so
 * setting the property is reading the template's own extension point rather
 * than pattern-matching Groovy syntax that has changed shape across SDK
 * versions and would silently stop matching on the next one. It is also
 * strictly additive: a consumer's own `gradle.properties` override, or
 * another plugin's, composes with this one through ordinary "last write
 * wins" property semantics instead of two plugins racing to edit the same
 * `ext` block.
 *
 * Never lowers an existing value — a consumer or another plugin that already
 * asked for something higher than `minSdkVersion` wins.
 */
function raiseMinSdkVersion(properties, minSdkVersion) {
  const existing = properties.find(
    (p) => p.type === 'property' && p.key === MIN_SDK_PROPERTY_KEY
  );
  if (existing) {
    const current = parseInt(existing.value, 10);
    if (!Number.isNaN(current) && current >= minSdkVersion) {
      return properties;
    }
    return properties.map((p) =>
      p === existing ? { ...p, value: String(minSdkVersion) } : p
    );
  }
  return [...properties, { type: 'property', key: MIN_SDK_PROPERTY_KEY, value: String(minSdkVersion) }];
}

module.exports = {
  ML_KIT_COORDINATE_PREFIX,
  KOTLIN_METADATA_SKIP_MARKER,
  MIN_SDK_PROPERTY_KEY,
  addMlKitDependency,
  applyKotlinMetadataSkip,
  raiseMinSdkVersion,
};
