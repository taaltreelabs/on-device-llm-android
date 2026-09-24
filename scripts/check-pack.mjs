#!/usr/bin/env node
/**
 * Verifies the published npm tarball contains exactly what a consumer needs and
 * nothing else:
 *
 *   present  — the compiled JS + type declarations, the Kotlin module under
 *              android/src/main, expo-module.config.json, README.md, LICENSE.
 *   absent   — android/src/test (60 JVM tests that Gradle runs from the example
 *              app and no consumer ever compiles), the example app, docs/, src/,
 *              scripts/, .github/, and any test file.
 *
 * The two `absent` entries that matter most:
 *
 *   - **android/src/test/** is excluded by `!android/src/test` in package.json
 *     "files" *and* by .npmignore. Shipping it would put JUnit-dependent Kotlin
 *     into a consumer's Gradle source set — the module's `testImplementation
 *     'junit:junit'` is not on their classpath, so at best it is dead weight in
 *     every install and at worst it breaks their build.
 *   - **android/build/** — a local Gradle output directory is untracked and
 *     gitignored but `files: ["android"]` would otherwise sweep it into the
 *     tarball wholesale. This is a real regression the main package hit once.
 *
 * Runs `npm pack --json --dry-run`, which produces the exact file list npm would
 * tar up (package.json "files" + npm's built-in rules + .npmignore), and asserts
 * against it. It does not build a tarball or touch the filesystem.
 *
 * Plain Node, zero dependencies.
 */

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

const failures = [];

function fail(message) {
  failures.push(message);
}

function run() {
  const raw = execFileSync('npm', ['pack', '--json', '--dry-run'], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 1024 * 1024 * 32,
  });
  // `npm pack` can print npm-lifecycle noise before the JSON array on some npm
  // versions/configs; the payload is the last top-level `[...]` in stdout.
  const start = raw.indexOf('[');
  const end = raw.lastIndexOf(']');
  if (start === -1 || end === -1 || end < start) {
    console.error('check:pack FAILED\n\n  - no JSON in `npm pack --json --dry-run` output:\n');
    console.error(raw);
    process.exit(1);
  }
  try {
    return JSON.parse(raw.slice(start, end + 1))[0];
  } catch (err) {
    console.error('check:pack FAILED\n\n  - could not parse `npm pack --json --dry-run`:\n');
    console.error(raw);
    console.error(err);
    process.exit(1);
  }
}

const pack = run();
const files = pack.files.map((f) => f.path);
const fileSet = new Set(files);

// ---- name / version ---------------------------------------------------------

if (pack.name !== '@taaltreelabs/on-device-llm-android') {
  fail(`unexpected package name: ${pack.name}`);
}
if (!pack.version || typeof pack.version !== 'string') {
  fail('package version is missing from `npm pack` output');
}

// ---- present: compiled output ----------------------------------------------

for (const p of ['build/index.js', 'build/index.d.ts']) {
  if (!fileSet.has(p)) fail(`missing required file: ${p} (compiled output for the package entry)`);
}
// The provider is not one file: if any of these went missing the entry point
// would still be present and the package would fail at require time instead.
for (const mod of [
  'provider',
  'wire',
  'errors',
  'stream-bridge',
  'native/resolve',
  'native/types',
]) {
  if (!fileSet.has(`build/${mod}.js`)) fail(`missing required file: build/${mod}.js`);
}

// ---- present: the Kotlin module under android/src/main ----------------------

if (!fileSet.has('expo-module.config.json')) {
  fail('missing required file: expo-module.config.json (Expo autolinking finds the module by it)');
}
if (!fileSet.has('android/build.gradle')) {
  fail('missing required file: android/build.gradle (carries the compileOnly firewall)');
}
if (!fileSet.has('android/src/main/AndroidManifest.xml')) {
  fail('missing required file: android/src/main/AndroidManifest.xml');
}
// The R8 keep rules are load-bearing, not housekeeping (tripwire T1). Without
// them in the tarball, a consumer's MINIFIED release build shrinks `GenAiEngine`
// away and renames `Generation`, both of which the firewall reaches only by
// name — so the provider reports `unsupportedPlatform` on perfectly capable
// hardware, silently and permanently. A packaging change that dropped this file
// would be invisible in every debug build and in all 60 JVM tests.
if (!fileSet.has('android/consumer-rules.pro')) {
  fail(
    'missing required file: android/consumer-rules.pro (the R8 keep rules that keep the ' +
      'reflection-based firewall alive in a consumer release build — see tripwire T1)'
  );
}

const KOTLIN_PREFIX = 'android/src/main/java/expo/modules/ondevicellmandroid/';
const REQUIRED_KOTLIN = [
  'OnDeviceLlmAndroidModule.kt',
  'GenAiEngine.kt',
  'MlKitPresence.kt',
  'core/AvailabilityMapping.kt',
  'core/BridgeTypes.kt',
  'core/ErrorMapping.kt',
  'core/GenAiBridge.kt',
  'core/PromptEncoding.kt',
  'core/RequestRegistry.kt',
  'core/StreamAccumulator.kt',
];
for (const name of REQUIRED_KOTLIN) {
  if (!fileSet.has(`${KOTLIN_PREFIX}${name}`)) {
    fail(`missing required Kotlin source: ${KOTLIN_PREFIX}${name}`);
  }
}

// ---- present: README + LICENSE ---------------------------------------------
//
// Not optional here in the way it is for a normal package: README.md carries the
// Privacy & terms section, which is the whole reason this provider ships
// separately (DECISIONS.md D1). A tarball without it is a tarball that hides the
// thing a consumer most needs to read.

for (const p of ['README.md', 'LICENSE']) {
  if (!fileSet.has(p)) fail(`missing required file: ${p}`);
}

// ---- present: the Expo config plugin ----------------------------------------
//
// `app.plugin.js` at the package root is how Expo *finds* a package's config
// plugin — it is checked before `main` — so its absence would silently break
// every `"plugins": ["@taaltreelabs/on-device-llm-android"]` consumer even
// though `require('@taaltreelabs/on-device-llm-android')` still works fine.
// `plugin/gradle-transforms.js` is the pure transform module the plugin (and
// its vitest tests) both depend on; without it the plugin throws at prebuild
// time for every consumer, immediately.

for (const p of [
  'app.plugin.js',
  'plugin/withOnDeviceLlmAndroid.js',
  'plugin/gradle-transforms.js',
]) {
  if (!fileSet.has(p)) fail(`missing required file: ${p} (the Expo config plugin)`);
}

// ---- absent: the JVM test source set ---------------------------------------

for (const f of files) {
  if (f.startsWith('android/src/test/') || f.startsWith('android/src/androidTest/')) {
    fail(`forbidden path present: ${f} (a consumer must never compile our JUnit test sources)`);
  }
}

// ---- absent: example/, docs/, src/, scripts/, .github/ ---------------------

const FORBIDDEN_PREFIXES = ['example/', 'docs/', 'src/', 'scripts/', '.github/'];
for (const f of files) {
  for (const prefix of FORBIDDEN_PREFIXES) {
    if (f === prefix.slice(0, -1) || f.startsWith(prefix)) {
      fail(`forbidden path present: ${f} (matches disallowed prefix "${prefix}")`);
    }
  }
}

// ---- absent: any test file --------------------------------------------------

for (const f of files) {
  const base = path.basename(f);
  if (/\.test\.[^.]+$/.test(base) || /Test\.kt$/.test(base) || f.split('/').includes('__tests__')) {
    fail(`forbidden test file present: ${f}`);
  }
}

// ---- absent: stray native build output -------------------------------------

for (const f of files) {
  const segments = f.split('/');
  if (segments.length > 1 && segments[1] === 'build' && segments[0] !== 'build') {
    fail(
      `forbidden generated-build path present: ${f} (stray native build output under "${segments[0]}/build/")`
    );
  }
}

// ---- report -----------------------------------------------------------------

if (failures.length > 0) {
  console.error(`check:pack FAILED against ${pack.name}@${pack.version} (${files.length} files)\n`);
  for (const failure of failures) console.error(`  - ${failure}`);
  console.error(`\n${failures.length} pack violation(s) found.`);
  process.exit(1);
}

console.log(
  `check:pack OK — ${pack.name}@${pack.version}: ${files.length} files, all required paths present, no forbidden paths.`
);
