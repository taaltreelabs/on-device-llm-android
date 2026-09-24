# @taaltreelabs/on-device-llm-android

The **Android on-device provider** for [`@taaltreelabs/on-device-llm`](https://github.com/taaltreelabs/on-device-llm) — an `LLMProvider` backed by Google's ML Kit GenAI Prompt API and AICore (Gemini Nano).

> ## ⚠️ Pre-release. Nothing here has ever run against a model.
>
> AICore is a preinstalled Android system service that exists on **no emulator image**, so there is no CI path, no laptop dev loop, and no way for this package's author to have run it. What *is* proven is that the Kotlin half compiles against the real `com.google.mlkit:genai-prompt:1.0.0-beta4` artifacts, that its device-independent logic passes 60 JVM unit tests, and that the TypeScript half — including this package's Expo config plugin, which is pure Node and needs no device either — passes 93 tests (73 provider tests against a scriptable fake of the bridge, plus 20 config-plugin content-transform tests) with vitest.
>
> Every device-dependent assumption is marked PROVISIONAL at its site in the code and listed once in [DECISIONS.md](./DECISIONS.md#provisional-register). **Do not ship this to users until someone has run it on an allowlisted phone.** The underlying Google API is itself beta and states that it may break backward compatibility.

## Why this is a separate package

The main package's name promises on-device, and for its Apple provider that means exactly what you would hope: nothing leaves the phone.

This provider is different in one specific, disclosable way. Inference does run on the device — but the ML Kit APIs **send usage and performance metrics to Google**, and Google's terms make informing your own users about that *your* legal duty. That asterisk does not belong in a footnote of a package whose name says "on-device". It belongs on a package you have to install deliberately, with a README that leads with it.

See [Privacy & terms](#privacy--terms) below. It is the most important section here, and it is why you are reading a second README instead of a subsection of the first one.

## Install — two deliberate steps

### 1. Both npm packages

```sh
npm install @taaltreelabs/on-device-llm @taaltreelabs/on-device-llm-android
```

`@taaltreelabs/on-device-llm` is a peer dependency, not a bundled one: this package holds the Android provider, that one holds the contract (`LLMProvider`, the error taxonomy, the router, the context manager) and the Apple and OpenAI providers. One copy of the contract, shared.

### 2. Google's SDK, in *your* app's Gradle files — three lines, not one

Earlier drafts of this README (and DECISIONS.md D2) described this as one `implementation` line. Building the example app with the dependency actually present ([SPIKE.md](./SPIKE.md#the-consumer-opt-in-is-three-lines-not-one--a-finding-from-building-this)) proved that wrong: it is **three**, and all three are required before this provider will generate anything.

**(a) The ML Kit dependency itself:**

```gradle
// android/app/build.gradle
dependencies {
  implementation 'com.google.mlkit:genai-prompt:1.0.0-beta4'
}
```

This package declares `com.google.mlkit:genai-prompt` as `compileOnly`: it compiles against Google's SDK and **ships none of it**. At normal `implementation` scope that one coordinate transitively drags in Play Services (`play-services-basement`, `-tasks`), `com.google.mlkit:common`, `genai-common`, `genai-schema`, three `datatransport` artifacts, two `firebase-encoders` artifacts, Guava's `listenablefuture` and three `kotlinx-coroutines` artifacts — a Play Services dependency plus a **Firebase datatransport telemetry pipeline** — into the APK of everyone who installs this package. Expo autolinking includes every dependency's `android/` module unconditionally, with no per-provider opt-out, so `compileOnly` is the only lever that makes the default cost zero. Verified, not asserted: all 20 dex files of the example app were scanned, and the only `com.google.mlkit` strings present are the type references inside our own `GenAiEngine` class. No ML Kit, Play Services, Firebase or datatransport **class** is in the APK.

Adding this line is how you consent, in writing, in your own repository, to shipping Google's SDK and everything in the [Privacy & terms](#privacy--terms) section. Without it, `availability()` returns `unavailable` with reason `unsupportedPlatform` and a `detail` naming the exact coordinate to add — the package loads and answers, it just never generates.

No extra Maven repository is needed: `google()` already proxies `dl.google.com/android/maven2` in every Expo and React Native template.

**(b) `minSdkVersion` must be 26 or higher:**

`genai-prompt`'s own AAR manifest declares `minSdkVersion 26`. Every Expo and React Native template defaults to 24, and **the manifest merger refuses the build outright** rather than silently raising the floor — Android's merger treats a library's `minSdkVersion` as a hard lower bound on any app that includes it. (The alternative, `tools:overrideLibrary`, would let the app *install* on devices the SDK's own floor says cannot work — a worse trade than raising the floor.) Set it wherever your template reads it from, e.g. `gradle.properties`:

```properties
# android/gradle.properties
android.minSdkVersion=26
```

**(c) `-Xskip-metadata-version-check` on your app module's own Kotlin compile tasks:**

```gradle
// android/app/build.gradle
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
  compilerOptions {
    freeCompilerArgs.add('-Xskip-metadata-version-check')
  }
}
```

`genai-prompt` puts `kotlin-stdlib:2.3.21` and three `@Metadata 2.3.0` artifacts on the *app's* compile classpath once (a) is added, and the Kotlin compiler reads `META-INF/*.kotlin_module` eagerly for every classpath entry — so `:app:compileDebugKotlin` fails on a file that has never heard of ML Kit (e.g. `MainActivity.kt`) with `Module was compiled with an incompatible version of Kotlin` and then an internal FIR-type-checker error. Every Expo and RN template has Kotlin in `app/`, so every consumer hits this without the flag. **Rejected alternative:** forcing a newer Kotlin version on the whole consumer build — see [DECISIONS.md D2](./DECISIONS.md#d2-was-d33-genai-prompt-is-compileonly-a-missing-dependency-reports-unsupportedplatform), which chose the flag specifically because a library does not get to make that call for its host app. This module's own build already carries the identical flag, scoped to itself, for the identical reason.

### The one-line alternative, for Expo/CNG apps

If your app uses [Continuous Native Generation](https://docs.expo.dev/workflow/continuous-native-generation/) (`android/` is regenerated by `expo prebuild` and none of the three edits above survive it), this package ships a [config plugin](./plugin/withOnDeviceLlmAndroid.js) that applies all three automatically:

```json
{
  "expo": {
    "plugins": [
      [
        "@taaltreelabs/on-device-llm-android",
        {
          "mlKitGenAiPromptVersion": "1.0.0-beta4",
          "minSdkVersion": 26
        }
      ]
    ]
  }
}
```

Both options are optional and default to the values shown — pin the version explicitly if you want your `app.json` to be the record of what's pinned rather than this package's default. Never use a version range: the API is beta, says so in its own terms, and has shipped four betas in eight months. Running `expo prebuild` more than once, or listing the plugin twice, adds nothing twice — every edit the plugin makes checks for its own already-applied marker first.

The plugin edits `android/gradle.properties` (for (b)) and the generated `android/app/build.gradle` (for (a) and (c)) — the same three edits above, written by code instead of by hand. See its source for why `gradle.properties` and not a regex over the root `build.gradle`'s `ext` block: every Expo/RN template already reads that property with its own fallback, so this plugin is using the template's own extension point rather than pattern-matching Groovy that has changed shape across SDK versions.

## Privacy & terms

**Read this before you install. Then tell your users what it says.**

### On-device inference, but metrics leave the device

Generation happens on the phone. Your users' prompts are not sent to Google to be answered. But the SDK is instrumented, and Google's [ML Kit GenAI Additional Terms of Service](https://developers.google.com/ml-kit/genai-terms) state, verbatim:

> "The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google"

and, immediately relevant to you:

> "You are responsible for informing users of your app about Google's processing of ML Kit metrics data as required by applicable law."

In plain words: **on-device inference, but metrics leave the device.** "On-device" here does not mean "no data leaves the device" the way it does for the Apple provider, which sends nothing at all. If your privacy policy says otherwise, this provider makes it inaccurate the day you ship it.

That second clause is a **pass-through disclosure duty**. Google does not discharge it for you and neither does this package: adding the Gradle line makes it yours. Disclose it in your privacy policy, in your app-store data-safety declaration, and wherever else applicable law puts it.

### 18+ age gate

> "You must be 18 years of age or older to use the APIs, and you won't use the Services as part of a website, application, or other service directed towards or likely to be accessed by individuals under the age of 18."

This is a hard constraint on *your* app, not just on you as a developer, and the second half is broader than the first: **"likely to be accessed by"** catches a great many consumer apps that are not aimed at minors. An education, language-learning, family or general-audience app is very plausibly in breach. This package cannot enforce the gate and does not try to.

### Other clauses worth your attention

- **Documented features only** — "You will only use the Services to facilitate the features described in the ML Kit GenAI API documentation."
- **No competing models** — "You may not use the Services to develop models or products that compete with the Services (e.g., Gemini Nano or the ML Kit GenAI API)." Note that a cross-platform router is plausibly adjacent to this clause. It has not been tested.
- **No `Preview`/`Experimental Access` in production** — "You may not use any Services identified as 'Preview' or 'Experimental Access' … for production use." This provider does not select a preview model release stage.
- **Medical exclusion** — "You may not use the Services in clinical practice, to provide medical advice…"

One clause could **not** be resolved from the live terms in the research pass behind this package: whether a redistributable package may ship Google's SDK inside it. This package's `compileOnly` arrangement means we never redistribute it, so the question does not arise for us — but it is also the reason the arrangement will not change. Quotations above were read from the live terms on 2026-09-23; they are Google's to revise, so check the source, and treat none of this as legal advice.

### There is no `guardrail` error on Android

A cross-platform behaviour difference worth knowing about. The main package's error taxonomy has a `guardrail` code for a model that refused on safety grounds, and the router deliberately does **not** fall back to a cloud provider on it — so content the on-device model declined is not quietly re-sent elsewhere.

**On Android that policy is unenforceable.** No ML Kit error code denotes a safety refusal; safety is implemented as prompt text (the SDK appends the literal string "Do NOT generate unsafe content" to the system instruction), so a blocked response most likely arrives as ordinary generated text, or at worst as a transient generation error. The refusal never reaches the taxonomy, so a `guardrail`-dependent policy behaves differently per platform. A test asserts that no code ever maps to `guardrail`, so this stays deliberate rather than becoming an oversight.

## Device eligibility

**Eligibility is an allowlist, not a hardware specification.** You cannot predict it from RAM or chipset; a 12 GB OnePlus 12 has been reported returning "Feature Unavailable" while meeting every apparent bar. Always call `availability()`. Devices with an unlocked bootloader are documented as unsupported.

As of 2026-09 the AICore allowlist covers, by model variant:

- **nano-v3** — Pixel 9 and 10 families (incl. Pro / Pro XL / Pro Fold); Honor Magic 8 Pro; iQOO 15; Lenovo Idea Tab Pro Gen 2, Legion Tab Gen 5; Motorola Signature; OnePlus 15 / 15R; OPPO Find X8 / X9 (+Pro), Reno 14 / 15 Pro series; realme GT 7T; Samsung Galaxy S26 / S26+ / S26 Ultra; Sharp AQUOS R11; Sony Xperia 1 VIII; vivo X200 (/T/Pro), X300 (/Pro).
- **nano-v4** — Pixel 11 family; Samsung Galaxy Z Flip8, Z Fold8, Z Fold8 Ultra.

Reach is much wider than "Pixel only" — but note that **Pixel 8 is absent entirely**; the floor is Pixel 9. Variants also move under devices via OTA, so never cache a variant assumption.

### No emulator. Definitively.

AICore is a preinstalled system service: the AAR queries the `com.google.android.aicore` package and binds `com.google.android.apps.aicore.service.BIND_SERVICE`. Neither exists on any public AVD system image, and AICore is not installable from Play, so there is nothing for the SDK to bind to. No Google source claims emulator support at any API level; the direct question stands unanswered on Google's own developer forum; Google's sibling MediaPipe stack states outright that it "does not reliably support device emulators."

**Consequence: there is no CI coverage and no laptop dev loop for anything that touches the model.** Every behavioural question about this provider is answered by hand, on purchased hardware, or not at all. That is the fact behind this README's pre-release banner.

## Usage

The provider is an ordinary `LLMProvider`, so it goes in a router next to the main package's providers and the router picks per request:

```ts
import { createRouter } from '@taaltreelabs/on-device-llm/core';
import { createAppleProvider } from '@taaltreelabs/on-device-llm/apple';
import { createOpenAIProvider } from '@taaltreelabs/on-device-llm/openai';
import { createAndroidProvider } from '@taaltreelabs/on-device-llm-android';

const router = createRouter({
  providers: [
    createAppleProvider(),
    createAndroidProvider(),
    createOpenAIProvider({ apiKey: process.env.OPENAI_API_KEY! }),
  ],
});

// Whichever on-device provider is actually available answers; the cloud
// provider catches the rest. No `Platform.OS` branching in your own code.
for await (const event of router.stream({
  messages: [{ role: 'user', content: 'Explain the Dutch perfect tense.' }],
})) {
  if (event.type === 'textDelta') process.stdout.write(event.delta);
}
```

**Listing this provider on every platform is safe and is the intended shape.** Importing this package never touches the native module, and on iOS, on web, under Node, and on an Android build without the Gradle line, `createAndroidProvider()` returns a provider that reports `unavailable` / `unsupportedPlatform` — which the router skips. No `Platform.OS === 'android'` guard is needed at your call site.

Or use it directly:

```ts
const android = createAndroidProvider();

const availability = await android.availability();
if (!availability.available) {
  console.log(availability.reason, availability.detail);
}
```

### What this provider does not do

`capabilities()` reports these honestly, so the router routes around them rather than failing mid-request:

| | |
|---|---|
| `structuredOutput` | **`false`.** The Prompt API's typed path terminates in a compile-time Kotlin class generated by a KSP plugin; a JSON Schema handed across the bridge at runtime cannot be honoured. A request carrying `schema` is rejected as `invalidRequest` at the call site rather than answered in prose. |
| `tools` | **`false`.** No tool-calling surface exists anywhere in the SDK. A request carrying `tools` is rejected the same way — a model that was supposed to look something up inventing it instead is the one outcome worse than an error. |
| `locales` | **`UNKNOWN`.** There is no locale enumeration API and no `supportsLocale` equivalent, so there is no `locale` config option either — a locale failure can only be discovered at generation time, never predicted. |
| `streaming` | **`true`** — and true deltas, not cumulative snapshots. |
| `tokenCounting` | **`'exact'`**, via the model's own `countTokens`. Exact for the request we actually build, role frame included. |

## Status and what the hardware spike must settle

This package is **experimental / PROVISIONAL**. [DECISIONS.md](./DECISIONS.md) carries the full register; the short version of what only a real device can answer:

1. **The role encoding.** ML Kit's `Content` has no role field — the strings `role`, `user`, `model` and `assistant` appear nowhere in the SDK's 1,473 classes — so multi-turn attribution had to be *invented*. We frame each turn as `User:` / `Model:` and emit a lone user message unframed. Whether that works at all, and which shape works better, is unverified.
2. **System prompts.** Whether `SystemInstruction` changes behaviour, and what `isSystemPromptAvailable()` actually returns. The fold-into-first-content fallback has never run.
3. **Cancellation.** Whether cancelling the coroutine stops AICore inference or only stops delivery. Our contract requires the former.
4. **Streaming granularity.** Delta-per-callback is read from the bytecode; token vs. word vs. sentence vs. one-chunk is unknown. A tripwire flags a cumulative stream rather than silently mis-rendering it.
5. **The real `getTokenLimit()`**, `getBaseModelName()`, and whether `checkStatus()` ever returns a fifth state.
6. **The error table end to end** — in particular whether a safety refusal really arrives as plain text, and whether `getRetryDelay()` is ever populated.
7. **The firewall on a device**, including a **release build with R8 enabled**: shrinking can strip or rename the classes the reflection probe depends on, and any keep-rules a consumer needs are ours to discover and document.
8. **`-Xskip-metadata-version-check`.** The SDK is compiled with Kotlin 2.3.21 and Expo SDK 57 pins the compiler at 2.1.0, so this module (only this module) reads the newer metadata rather than verifying it. A genuinely incompatible declaration shape would surface at runtime instead of at compile time. The flag goes as soon as the toolchain catches up.

## Development

```sh
npm install          # also builds the peer package from git
npm run typecheck
npm run lint         # includes the no-react rule (see eslint.config.cjs)
npm test             # 93 tests, vitest: 73 provider + 20 config-plugin transform tests
npm run check:pack   # asserts the tarball ships build/ + android/src/main + the config plugin, and nothing else
```

The Kotlin half is compiled and tested through the example app, which exists for exactly that:

```sh
cd example/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # gitignored
./gradlew :taaltreelabs-on-device-llm-android:testDebugUnitTest   # 60 JVM tests
./gradlew assembleDebug
```

The JVM tests cover `android/src/main/.../core/` — the availability table, the 21-code error table, the role encoding, the request registry and the stream accumulator — which is deliberately every part of the module that does not touch the SDK, because it is the only part any machine without an allowlisted phone can verify.

## License

MIT. See [LICENSE](./LICENSE).

Google's ML Kit GenAI SDK is **not** distributed with this package and is not covered by that license; it is governed by Google's own terms, linked above.
