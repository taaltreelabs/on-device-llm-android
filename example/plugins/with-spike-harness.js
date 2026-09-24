// ============================================================================
//  with-spike-harness.js — wave-2 hardware spike, build wiring
// ============================================================================
//
//  `example/android` is a *generated* directory (CNG / `expo prebuild`) and is
//  gitignored, so nothing that must survive a regeneration can live inside it.
//  This plugin appends exactly one line to the generated `app/build.gradle`:
//
//      apply from: new File(rootDir.parentFile, "spike/spike.gradle")
//
//  …and everything else — the ML Kit `implementation` line (the consumer
//  opt-in of DECISIONS.md D2, exercised here for real), the androidTest
//  dependencies, the androidTest source directory, the `testBuildType` switch
//  for tripwire T1, and the R8 keep-rule wiring — lives in the tracked file
//  `example/spike/spike.gradle`, which a human can read and edit without
//  re-running prebuild.
//
//  This is also the durable form of the opt-in that D2 describes in prose
//  (`withAppBuildGradle` from a local config plugin), so the spike doubles as
//  the first real test of that recipe.
//
const { withAppBuildGradle } = require('expo/config-plugins');

const MARKER = 'spike/spike.gradle';
const LINE = [
  '',
  '// --- wave-2 hardware spike harness (added by plugins/with-spike-harness.js) ---',
  'apply from: new File(rootDir.parentFile, "spike/spike.gradle")',
  '',
].join('\n');

module.exports = function withSpikeHarness(config) {
  return withAppBuildGradle(config, (cfg) => {
    if (cfg.modResults.language !== 'groovy') {
      throw new Error(
        'with-spike-harness expects a Groovy app/build.gradle; got ' + cfg.modResults.language
      );
    }
    if (!cfg.modResults.contents.includes(MARKER)) {
      cfg.modResults.contents += LINE;
    }
    return cfg;
  });
};
