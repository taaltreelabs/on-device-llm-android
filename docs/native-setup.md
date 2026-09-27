# Native setup

[Back to the README](../README.md)

For Expo projects using prebuild, use the bundled config plugin in the
[quick start](../README.md#2-configure-your-expo-app). It applies the three settings
below to the generated Android project. Manual edits may be overwritten by prebuild.

## 1. Add Google's SDK

In `android/app/build.gradle`:

```gradle
dependencies {
  implementation 'com.google.mlkit:genai-prompt:1.0.0-beta4'
}
```

The package uses `compileOnly`, so installing it alone does not add Google's SDK to
your app. The app's repositories must include `google()`. Review the [SDK data flow](privacy.md).

## 2. Set the minimum SDK

The ML Kit artifact requires API 26 or newer. In an Expo project whose build reads
`android.minSdkVersion`, set this in `android/gradle.properties`:

```properties
android.minSdkVersion=26
```

For other project layouts, set the app's `minSdk` to at least 26 in its Gradle configuration.

## 3. Configure Kotlin metadata handling

The pinned SDK contains Kotlin metadata newer than the compiler in the example
app's toolchain. Add this to the app module's `build.gradle`:

```gradle
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
  compilerOptions {
    freeCompilerArgs.add('-Xskip-metadata-version-check')
  }
}
```

This flag skips the compiler's metadata-version check; it does not upgrade Kotlin.
The native module applies the same flag to its own compile tasks. The app setting
is needed because the SDK also appears on the app's compile classpath.

## Rebuild

```bash
npx expo run:android --device
```

The package registers its consumer R8 rules automatically to preserve the classes
used by reflective SDK loading. No manual copying of those rules is needed.
