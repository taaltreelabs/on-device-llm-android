# SPIKE.md — the wave-2 hardware spike

**What this is.** An instrumentation-test suite that answers DECISIONS.md's
[PROVISIONAL register](DECISIONS.md#provisional-register) items 0–12 and
tripwires T1/T2 on real hardware, designed to run on **Firebase Test Lab
physical devices** because AICore is a preinstalled system service that exists on
no emulator image (`docs/research/android-genai.md` §6). There is no CI path and
no laptop dev loop for any of it.

**What it is not.** It has never been run. Every line of it was written blind:
the harness compiles (debug and minified release, both APK pairs), the library's
60 JVM tests and the 73 TypeScript tests still pass, and the R8 keep rules are
verifiably applied — but **no verdict in this suite has ever been produced by a
device.** Treat the first run as the experiment it is.

---

## The reporting contract — read this before reading a single result

**Every test always passes as a JUnit test.** Findings arrive as structured
verdicts, not assertions:

```json
{"item":"04","verdict":"confirmed","detail":"delta-per-callback confirmed: 37 chunks…","data":{…},"ms":4120}
```

| verdict | means |
|---|---|
| `confirmed` | the PROVISIONAL assumption held, and `data` says how |
| `refuted` | the assumption is wrong, and `data` says what happened instead — **the most valuable verdict here** |
| `blocked` | the measurement could not be taken (no ML Kit, no AICore, model not downloaded, budget spent). Says nothing about the assumption. **Expected on a farm device without Gemini Nano.** |
| `inconclusive` | the measurement ran but does not decide the question (the control task failed, one sample, ambiguous timings) |

A JUnit **failure** means one thing only: a bug in the harness itself
(`SpikeHarnessBug` — two terminal stream events, an encoder rejecting a valid
conversation). Those fail *after* emitting their verdict. If the run is green and
every verdict is `blocked`, the suite worked perfectly and the device could not
answer.

Two sinks, because either may be the one that survives:

1. `Log.i("SPIKE_RESULT", <one-line JSON>)` — streamed, so it survives a run
   killed by the overall timeout. Truncated to stay inside logcat's per-line
   limit (still valid JSON when truncated).
2. `/sdcard/Android/data/expo.modules.ondevicellmandroid.example/files/spike-results.jsonl`
   — one untruncated object per line, plus a `_run` header identifying the
   device. Pull it with `--directories-to-pull`.

Lines whose `item` ends in `#progress` are progress reports (the download loop),
not verdicts.

---

## 0. Prerequisites, once

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# from the repo root
npm install
npm run build                      # the example app imports the built JS

cd example
npm install
npx expo prebuild --platform android     # regenerates android/ (gitignored)
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

`expo prebuild` runs `plugins/with-spike-harness.js`, which appends one line to
the generated `android/app/build.gradle`:

```groovy
apply from: new File(rootDir.parentFile, "spike/spike.gradle")
```

Everything else lives in tracked files outside the generated tree:
`example/spike/spike.gradle` (build wiring), `example/spike/src/androidTest/…`
(the suite), `example/spike/proguard-spike*.pro` (harness keep rules). So
`expo prebuild --clean` is safe, and the suite is not stored inside a gitignored
directory.

### The consumer opt-in is three lines, not one — a finding from building this

D2 describes the opt-in as one `implementation` line. Building the example app
with the dependency actually present proves it is three, and
`example/spike/spike.gradle` carries all three with the evidence in comments:

1. `implementation 'com.google.mlkit:genai-prompt:1.0.0-beta4'` — as documented.
2. **`minSdk 26`.** `genai-prompt`'s AAR manifest declares `minSdkVersion 26`;
   the Expo/RN template defaults to 24, and the manifest merger refuses the build
   outright. (`tools:overrideLibrary` would let the app install on devices the
   SDK's own floor says cannot work — a worse trade than a raised floor.)
3. **`-Xskip-metadata-version-check` on the app module's own Kotlin compile
   tasks.** The dependency puts `kotlin-stdlib:2.3.21` and three
   `@Metadata 2.3.0` artifacts on the *app's* compile classpath; the Kotlin
   compiler reads `META-INF/*.kotlin_module` eagerly for every entry, so
   `:app:compileDebugKotlin` fails on `MainActivity.kt` — a file that has never
   heard of ML Kit — with `Module was compiled with an incompatible version of
   Kotlin … expected version is 2.1.0` and then an internal compiler error in the
   FIR type checker. Every Expo and RN template has Kotlin in `app/`, so **every
   consumer hits this.**

That is **tripwire T2 partly answered, at build time**: the beta SDK's Kotlin
version does leak out of this package and into the consumer's build. It does not
yet constrain which Expo version is supportable — the flag contains it — but the
containment is now the consumer's problem too, and the README's opt-in section
needs all three lines before this package is usable.

---

## 1. Build the APK pairs

Test Lab's `instrumentation` type takes an **app APK plus a test APK**, which is
why the suite is an androidTest suite in the example app.

```bash
cd example/android

# debug pair
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# release pair — TRIPWIRE T1: the same suite against a MINIFIED app
./gradlew -Pandroid.enableMinifyInReleaseBuilds=true -PspikeTestBuildType=release \
          :app:assembleRelease :app:assembleReleaseAndroidTest
#   app/build/outputs/apk/release/app-release.apk
#   app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
```

`-PspikeTestBuildType=release` points AGP's `testBuildType` at the release
variant. The Expo template already debug-signs release builds, which is what
makes the release APK installable on a farm device without a keystore.

**Verify the keep rules reached R8** (cheap, local, and the static half of T1):

```bash
grep '^expo.modules.ondevicellmandroid.GenAiEngine ->' \
     app/build/outputs/mapping/release/mapping.txt
# expo.modules.ondevicellmandroid.GenAiEngine -> expo.modules.ondevicellmandroid.GenAiEngine:
grep '^com.google.mlkit.genai.prompt.Generation ->' \
     app/build/outputs/mapping/release/mapping.txt
# com.google.mlkit.genai.prompt.Generation -> com.google.mlkit.genai.prompt.Generation:
```

Both names unrenamed means `android/consumer-rules.pro` was applied (it is also
quoted verbatim in `mapping/release/configuration.txt`). If either name moves,
`Spike11Firewall` will report `refuted` and the firewall is broken in every
consumer release build.

Other gates, unchanged by this work:

```bash
cd example/android && ./gradlew :taaltreelabs-on-device-llm-android:testDebugUnitTest  # 60 tests
cd ../..           && npm run typecheck && npm run lint && npm test && npm run check:pack
```

---

## 2. Pick a device

Eligibility is an **allowlist, not a hardware spec** (§6): a 12 GB OnePlus 12
returns "Feature Unavailable" while meeting every visible bar. The nano-v3/v4
route on Test Lab is the Pixel 9/10/11 family.

```bash
gcloud firebase test android models list --filter="form=PHYSICAL AND brand=Google"
gcloud firebase test android models describe <MODEL_ID>     # supported OS version ids
```

Choose, in order of preference: the **newest Pixel** available (later Nano
variant, likelier to carry assets), then the newest OS version id it supports.
Record `MODEL_ID` and `VERSION` — a verdict is meaningless without them, which is
why the `_run` header records `Build.MODEL` and the fingerprint too.

A farm device is not a phone in a drawer: it may have no Gemini Nano assets, may
be region- or account-gated, and its bootloader state may disqualify it outright.
**That is what test 00 is for.**

---

## 3. Run the probe FIRST — it decides whether the rest is worth anything

> **FINDING (2026-09-23): Firebase Test Lab is not viable for this suite.**
> The probe was run on three physical generations — Pixel 9 Pro (`caiman`,
> API 35), Pixel 10 (`frankel`, API 36), Pixel 11 (`cubs`, API 37) — and all
> three returned `checkStatus() = UNAVAILABLE` in under 100ms. Uniformity
> across allowlisted models and OS levels means this is a fleet property,
> not device eligibility: Test Lab units evidently lack the Google-account /
> Play provisioning AICore requires. Nothing was previously documented about
> this anywhere public; treat it as settled unless Google changes the fleet.
> What the runs DID confirm on real hardware: the consumer-supplied
> `implementation` + `compileOnly` firewall loads ML Kit on-device (register
> item 11, first half), real SDK calls dispatch through the metadata
> workaround (item 10, partially), and the four `FeatureStatus` constants
> match the bytecode read (0/1/2/3). Executing the remainder of the register
> requires a personally-provisioned allowlisted device (base Pixel 9 is the
> cheapest unambiguous choice; verify a-series models against Google's live
> supported-devices list before buying one).

```bash
cd example/android
PKG=expo.modules.ondevicellmandroid.example

gcloud firebase test android run \
  --type instrumentation \
  --app  app/build/outputs/apk/debug/app-debug.apk \
  --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=$MODEL_ID,version=$VERSION,locale=en_US,orientation=portrait \
  --test-targets "class expo.modules.ondevicellmandroid.spike.Spike00ViabilityProbe" \
  --timeout 15m \
  --directories-to-pull /sdcard/Android/data/$PKG/files \
  --results-dir "spike-probe-$(date +%Y%m%d-%H%M)"
```

`Spike00ViabilityProbe` reports one of four outcomes in `data.outcome`:

| outcome | verdict | what to do |
|---|---|---|
| `farm-viable` | `confirmed` | run the full suite |
| `needs-download-and-downloaded` | `confirmed` | run the full suite, and budget the download **again** — Test Lab does not keep device state between runs |
| `download-timeout` | `blocked` | raise `-e spikeDownloadBudgetMs` once; if it happens twice, this is §11.2's "stuck downloading indefinitely" in the wild and the farm cannot answer the register |
| `unavailable` | `blocked` | try another model; if every Pixel says this, Test Lab is not a route to these answers and the register needs purchased hardware |

**Free tier: 30 physical-device minutes per day** (and a handful of devices). The
probe costs 1–3 minutes if the model is resident and up to ~12 if it has to
download. The full suite can spend the rest of the allowance in one run, which is
exactly why the probe goes first and alone.

---

## 4. Run the full suite

```bash
gcloud firebase test android run \
  --type instrumentation \
  --app  app/build/outputs/apk/debug/app-debug.apk \
  --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=$MODEL_ID,version=$VERSION,locale=en_US,orientation=portrait \
  --test-targets "package expo.modules.ondevicellmandroid.spike" \
  --timeout 30m \
  --directories-to-pull /sdcard/Android/data/$PKG/files \
  --results-dir "spike-full-$(date +%Y%m%d-%H%M)"
```

Budget overrides are instrumentation arguments, so they need no rebuild:

```bash
  --environment-variables spikeTestBudgetMs=300000,spikeGenerateBudgetMs=180000,spikeVariant=debug
```

| argument | default | raise it when |
|---|---|---|
| `spikeDownloadBudgetMs` | 600000 | the probe reports `download-timeout` |
| `spikeTestBudgetMs` | 180000 | a verdict says "abandoned after …ms" |
| `spikeGenerateBudgetMs` | 90000 | items 5/8/9 report `timedOut` instead of refused |
| `spikeProbeBudgetMs` | download + 120000 | only with the above |
| `spikeVariant` | *(empty)* | always — label the run (`debug` / `release-minified`) |

### The T1 (R8) run

Identical, with the release pair and a label:

```bash
gcloud firebase test android run \
  --type instrumentation \
  --app  app/build/outputs/apk/release/app-release.apk \
  --test app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --device model=$MODEL_ID,version=$VERSION,locale=en_US,orientation=portrait \
  --test-targets "class expo.modules.ondevicellmandroid.spike.Spike11Firewall" \
  --environment-variables spikeVariant=release-minified \
  --timeout 10m \
  --directories-to-pull /sdcard/Android/data/$PKG/files \
  --results-dir "spike-t1-$(date +%Y%m%d-%H%M)"
```

Item 11 alone answers T1 and costs a minute. Run the whole package against the
release pair only if item 11 comes back `confirmed` and you want the behavioural
items re-confirmed under minification.

### A truer cold sample for item 7

```bash
  --test-targets "class expo.modules.ondevicellmandroid.spike.Spike07Warmup" \
  --use-orchestrator
```

`--use-orchestrator` gives each test its own process. **It also clears package
data between tests, which deletes `spike-results.jsonl` as the run proceeds** —
with the orchestrator, logcat is the only reliable sink. Use it only for this.

### On a local device, if one is ever in hand

```bash
cd example/android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=expo.modules.ondevicellmandroid.spike.Spike00ViabilityProbe
adb logcat -d -s SPIKE_RESULT
adb pull /sdcard/Android/data/expo.modules.ondevicellmandroid.example/files/spike-results.jsonl
```

---

## 5. Read the results

1. **Console.** The run prints a Firebase console URL; open it for pass/fail per
   test (all should pass) and the raw artifacts.
2. **Logcat, the primary channel.** In the results bucket:

   ```bash
   gsutil ls gs://<bucket>/<results-dir>/**/logcat
   gsutil cat gs://<bucket>/<results-dir>/**/logcat | grep SPIKE_RESULT | sort
   ```

   Verdicts sort into register order because the item is zero-padded. To see only
   what moved:

   ```bash
   … | grep SPIKE_RESULT | grep -E '"verdict":"(refuted|inconclusive)"'
   ```
3. **The results file, the complete record.** Pulled artifacts land under the
   results dir; the file is `spike-results.jsonl`.

   ```bash
   gsutil cp 'gs://<bucket>/<results-dir>/**/spike-results.jsonl' .
   jq -c 'select(.item|test("#progress")|not) | {item, verdict, detail}' spike-results.jsonl
   jq '.data' <(grep '"item":"04"' spike-results.jsonl)   # the full distribution
   ```
4. **Write the answers into DECISIONS.md.** Each register item's verdict either
   retires a PROVISIONAL marker or changes a decision. A `refuted` on item 1
   (role encoding) or item 3 (cancel semantics) is a design change, not a bug fix.

---

## 6. What each test measures

All thirteen are in `example/spike/src/androidTest/java/expo/modules/ondevicellmandroid/spike/`.
Every file's header carries the question, the design and the reasoning; this is
the index.

| item | class | measurement | `refuted` means |
|---|---|---|---|
| 00 | `Spike00ViabilityProbe` | `checkStatus` → optional `download()` with a 10-min budget and 15-s progress lines → one "Say OK" generation through the real bridge | — (`blocked` outcomes only) |
| 01 | `Spike01RoleEncoding` | a fact planted in an earlier turn, asked for in the last; three arms — framed multi-turn (the shipped path), **unlabelled** multi-turn, single-turn control — plus frame echo | the `User:`/`Model:` frame is not read as turn attribution (D4 needs rewriting) |
| 02 | `Spike02SystemInstruction` | `isSystemPromptAvailable()`, then uppercase-adherence ratios across four arms including a **forced fold path** (D4's never-executed branch) | system instructions reach the model by neither route |
| 03 | `Spike03CancelSemantics` | cancel from inside the first delta callback; measures (a) delivery tail after cancel, (b) how long `stream()` takes to return, compared against item 4's uncancelled duration | cancellation stops delivery only — our contract's `signal` guarantee is unmet |
| 04 | `Spike04DeltaGranularity` | per-chunk length + inter-arrival distribution for ~200 tokens; the engine's `StreamAccumulator` `reset` flag **and** an independent accumulator; `finish.text` vs concatenated deltas | the stream is cumulative, not delta-per-callback (Apple's D5 problem, on Android) |
| 05 | `Spike05TokenLimit` | `getTokenLimit()`, then an input sized to 95% of it **using `countTokens`**, then 120% | the reported limit is not the enforced limit |
| 06 | `Spike06ModelIdentity` | `getBaseModelName()` + `nano-vN`, three `checkStatus` samples, the four `FeatureStatus` constant **values**, three feature probes | `checkStatus` returned a fifth state |
| 07 | `Spike07Warmup` | fresh client, cold first-token → `warmup()` → warm first-token; **and** whether `warmup()` moves DOWNLOADABLE → DOWNLOADING | `warmup()` buys nothing — or, worse, starts a download and breaks D3's promise for `prewarm` |
| 08 | `Spike08CountTokens` | the same conversation counted through the bridge and past it (must agree), plus the documented `countTokens + maxOutputTokens ≤ limit` invariant | the bridge counts something other than what it sends |
| 09 | `Spike09ErrorArrival` | forced overflow → raw code vs what `ErrorMapping` made of it; a **mild, benign** safety probe (text in the source) → prose refusal or typed error; `getRetryDelay()` | the 21-code table mismaps a real code |
| 10 | `Spike10MetadataDispatch` | one of each *kind* of SDK surface — int constants, companion constants, builder property setters, suspend calls — classifying **linkage** failures apart from `GenAiException`s | `-Xskip-metadata-version-check` is hiding a real shape difference (T2) |
| 11 | `Spike11Firewall` | the presence probe, reflective engine construction, `availability()`, the suite-wide linkage watch, **and a discriminator that tells "dependency absent" from "R8 renamed it"** | **T1: R8 defeats the firewall** |
| 12 | `Spike12Registration` | the generated `ExpoModulesPackageList.getModulesMap()` keys, and the name `Module.definition()` declares | autolinking did not register the module, or the name is not `OnDeviceLlmAndroid` |

### Things this suite deliberately does not do

- **`requireNativeModule('OnDeviceLlmAndroid')` from JavaScript.** Instrumentation
  has no JS runtime. Item 12 checks both halves a device *can* answer
  (registration, and the declared name) and reports the JS hop as still owed.
  Discharge it with `npx expo run:android` and watch `availability()` answer.
- **Anything about the *unopted-in* app.** The firewall's other half — that an app
  without the `implementation` line ships no Play Services and no Firebase
  datatransport — is a dex scan, already done in wave 1, and this app is the
  opposite case by construction.
- **Repeat sampling.** Every timing here is n=1 or n=2 on a farm device of unknown
  thermal state. Directions are findings; magnitudes are indicative. The verdicts
  say so where it matters.
