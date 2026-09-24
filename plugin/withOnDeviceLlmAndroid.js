// ============================================================================
//  withOnDeviceLlmAndroid.js — the Expo config plugin for the three-line
//  consumer opt-in (SPIKE.md: "The consumer opt-in is three lines, not one")
// ============================================================================
//
//  README.md's opt-in section documents three manual Gradle edits a consumer
//  must otherwise make by hand every time `expo prebuild` regenerates
//  `android/`:
//
//    (a) `implementation 'com.google.mlkit:genai-prompt:<version>'` in the
//        app module's `build.gradle` — the opt-in itself (DECISIONS.md D2).
//    (b) `minSdkVersion` >= 26 — `genai-prompt`'s AAR manifest declares
//        `minSdkVersion 26`; the Expo/RN template defaults to 24, and the
//        manifest merger refuses the build outright otherwise.
//    (c) `-Xskip-metadata-version-check` on the app module's own Kotlin
//        compile tasks — the dependency's Kotlin metadata version outruns
//        the template's pinned compiler, and without this flag
//        `:app:compileDebugKotlin` fails on files that never mention ML Kit.
//
//  This plugin does all three, for an Expo/CNG app, as the one-line
//  alternative: `"plugins": [["@taaltreelabs/on-device-llm-android", {...}]]`.
//
//  Each edit's actual text transform lives in `./gradle-transforms.js`,
//  which is plain content-in/content-out and is what
//  `plugin/__tests__/gradle-transforms.test.js` tests directly — no Expo
//  project, no prebuild, no filesystem. This file is just the wiring: which
//  Expo config-plugin modifier reaches which generated file.
//
//  Deliberately plain CommonJS, no build step — the same shape as
//  `example/plugins/with-spike-harness.js` in this same repository. Expo
//  resolves a package's config plugin by loading `app.plugin.js` at the
//  package root (see `../app.plugin.js`, which just re-exports this file);
//  a compiled TypeScript step would be one more thing to keep in sync with
//  no benefit, since this file has no types to check against in the first
//  place (Expo's own config-plugin types are the only ones it could use, and
//  `@expo/config-plugins` is a devDependency-only, not something worth
//  depending on at runtime just for annotations).
// ============================================================================

const { withAppBuildGradle, withGradleProperties } = require('expo/config-plugins');
const {
  addMlKitDependency,
  applyKotlinMetadataSkip,
  raiseMinSdkVersion,
} = require('./gradle-transforms');

// Keep in step with `example/spike/spike.gradle`'s
// `ext.mlKitGenAiPromptVersion` and the coordinate in README.md and
// DECISIONS.md D2. Pinned, never a range — the API is beta and says so.
const DEFAULT_ML_KIT_GENAI_PROMPT_VERSION = '1.0.0-beta4';

// The AAR manifest's own floor (SPIKE.md's "consumer opt-in is three lines"
// finding). Never lowered by this plugin if a consumer's build already asks
// for something higher.
const DEFAULT_MIN_SDK_VERSION = 26;

/**
 * @param {import('expo/config-plugins').ExpoConfig} config
 * @param {{ mlKitGenAiPromptVersion?: string, minSdkVersion?: number }} [options]
 */
function withOnDeviceLlmAndroid(config, options = {}) {
  const mlKitGenAiPromptVersion =
    options.mlKitGenAiPromptVersion ?? DEFAULT_ML_KIT_GENAI_PROMPT_VERSION;
  const minSdkVersion = options.minSdkVersion ?? DEFAULT_MIN_SDK_VERSION;

  // (a) + (c): both land in the APP module's build.gradle, so one modifier,
  // two independent — each separately idempotent — text transforms.
  config = withAppBuildGradle(config, (cfg) => {
    if (cfg.modResults.language !== 'groovy') {
      throw new Error(
        'withOnDeviceLlmAndroid expects a Groovy app/build.gradle; got ' +
          cfg.modResults.language +
          ' (Kotlin DSL app/build.gradle.kts is not yet supported by this plugin)'
      );
    }
    let contents = cfg.modResults.contents;
    contents = addMlKitDependency(contents, mlKitGenAiPromptVersion);
    contents = applyKotlinMetadataSkip(contents);
    cfg.modResults.contents = contents;
    return cfg;
  });

  // (b): gradle.properties, not the root build.gradle's `ext` block — see
  // `raiseMinSdkVersion`'s own docblock in gradle-transforms.js for why.
  config = withGradleProperties(config, (cfg) => {
    cfg.modResults = raiseMinSdkVersion(cfg.modResults, minSdkVersion);
    return cfg;
  });

  return config;
}

module.exports = withOnDeviceLlmAndroid;
module.exports.withOnDeviceLlmAndroid = withOnDeviceLlmAndroid;
module.exports.DEFAULT_ML_KIT_GENAI_PROMPT_VERSION = DEFAULT_ML_KIT_GENAI_PROMPT_VERSION;
module.exports.DEFAULT_MIN_SDK_VERSION = DEFAULT_MIN_SDK_VERSION;
