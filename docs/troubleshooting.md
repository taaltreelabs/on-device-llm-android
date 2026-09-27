# Troubleshooting

[Back to the README](../README.md)

## The provider reports unsupportedPlatform

Check that the app contains the `OnDeviceLlmAndroid` native module and the ML Kit
runtime dependency. Expo Go cannot load the module. Follow [native setup](native-setup.md)
and rebuild the app; a JavaScript reload cannot add native classes.

This result is also expected on iOS, web, and Node.js. Use a router with another
provider if the app should work on those platforms.

## The device is unavailable

- `deviceNotEligible`: the device or feature is unsupported. Check AICore eligibility.
- `modelNotReady`: model preparation or a transient native status failure may be involved. Re-check later.

Read `availability().detail` during development for additional context. Show an
app-friendly message to users instead of raw native diagnostics. Availability is
not a guarantee that the next request will succeed; handle generation errors too.

## Manifest merger fails on the minimum SDK

The ML Kit artifact requires API 26 or newer. Raise the app's minimum SDK or use
the bundled plugin, then rebuild. See [minimum SDK setup](native-setup.md#2-set-the-minimum-sdk).

## Kotlin reports incompatible metadata

The pinned SDK includes newer Kotlin metadata than the example toolchain reads by
default. Apply the app-level flag in [Kotlin setup](native-setup.md#3-configure-kotlin-metadata-handling),
or let the config plugin apply it during prebuild.

## A minified build cannot load the SDK

The package includes consumer R8 rules for its reflectively loaded engine and SDK
presence probe. Confirm that those rules are present in the app's merged R8
configuration and that the app includes the ML Kit dependency.

## Cloud responses arrive all at once

Inject `expo/fetch` into `createOpenAIProvider()` in Expo. Without a streaming fetch,
the cloud provider returns a single aggregated text delta. The Android provider
streams over its native bridge and does not use that fetch implementation.
