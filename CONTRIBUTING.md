# Contributing

[Back to the README](README.md)

## JavaScript and package checks

```bash
npm install
npm run typecheck
npm run lint
npm test
npm run build
npm run check:pack
```

The shared package is installed from npm as a development dependency. Tests cover
the provider contract against a fake native bridge and the Expo plugin's content
transforms. The packaging check verifies compiled output, native sources, the config
plugin, and consumer rules while excluding tests and build artifacts.

## Native checks

Generate the example's Android project and configure your local Android SDK first.
Follow [the example guidance](example/AGENTS.md) when modifying the app.

From `example/android`:

```bash
./gradlew :taaltreelabs-on-device-llm-android:testDebugUnitTest
./gradlew assembleDebug
```

JVM tests cover availability mapping, error mapping, prompt encoding, request
tracking, and stream accumulation. For the instrumentation harness and result
collection, see [SPIKE.md](SPIKE.md). Use an eligible device for model inference.

## Reporting an issue

Include your package versions, Expo / React Native versions, Android version,
device, and a minimal reproduction. Avoid including private prompts or credentials.

## Publishing the prepared 1.0.0 release

After committing and reviewing the release changes, tag that commit `v1.0.0` and
push the branch and tag. The release workflow checks the version, runs its gates,
and publishes to npm under `latest` with provenance.

The Actions release button increments the version before publishing. Do not use
it to publish the already prepared 1.0.0 version, or it will bump the version again.
