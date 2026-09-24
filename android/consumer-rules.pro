# ============================================================================
#  R8 / ProGuard keep rules for @taaltreelabs/on-device-llm-android
#  Shipped to consumers via `consumerProguardFiles` — see android/build.gradle.
# ============================================================================
#
#  TRIPWIRE T1 (DECISIONS.md). The dependency firewall (D2) is built out of two
#  reflective lookups by NAME:
#
#     MlKitPresence.isAvailable   -> Class.forName("com.google.mlkit.genai.prompt.Generation")
#     MlKitPresence.createEngine  -> Class.forName("expo.modules.ondevicellmandroid.GenAiEngine")
#
#  R8 cannot see either one. Without the rules below, a minified release build
#  fails in two different and equally bad ways:
#
#   1. `GenAiEngine` is referenced by no bytecode anywhere — only by that string
#      — so R8 SHRINKS IT AWAY. `createEngine()` catches the failure and returns
#      null, and the provider reports `unsupportedPlatform` on a device that was
#      perfectly capable. The firewall does not fail loudly; it fails as a
#      permanent, silent "not supported", which is the worst available outcome.
#   2. `Generation` survives (the engine uses it) but is RENAMED, so the
#      presence probe answers `false` for the same silent result.
#
#  These rules are therefore not an optimisation detail; they are what makes the
#  consumer's `implementation` line mean anything in a release build. They ship
#  with the library precisely so a consumer never has to discover them — T1 was
#  about whether the firewall could be defeated by R8, and shipping the rules is
#  the answer.
#
#  Verified by the wave-2 spike's `Spike11Firewall` instrumentation test, which
#  runs against both the debug and the minified release APK and reports which.

# --- 1. The reflectively constructed engine -------------------------------
# Name and no-argument constructor. Members may be renamed freely: every call
# into it goes through the `GenAiBridge` interface, which R8 rewrites
# consistently.
-keep class expo.modules.ondevicellmandroid.GenAiEngine {
  <init>();
}

# --- 2. The presence probe's target --------------------------------------
# Only the NAME is load-bearing: the probe asks whether the class exists and
# nothing more (`initialize = false`). `-keepnames` allows shrinking, which is
# correct — if the consumer never opted in, the class is genuinely absent and
# the probe should say so.
-keepnames class com.google.mlkit.genai.prompt.Generation

# --- 3. The Expo module ---------------------------------------------------
# `requireNativeModule('OnDeviceLlmAndroid')` resolves through the string in
# `Name(...)`, which R8 leaves alone, but expo-modules-core reads the module's
# function signatures reflectively to build its argument converters. Keeping the
# module class whole costs a few kilobytes and removes an entire category of
# release-only failure.
-keep class expo.modules.ondevicellmandroid.OnDeviceLlmAndroidModule { *; }

# --- 4. The bridge contract ----------------------------------------------
# `createEngine()` casts the reflectively built instance to this interface. R8
# keeps interfaces that are used, but the cast target is the one thing that must
# not be merged into something else by horizontal class merging.
-keep,allowobfuscation interface expo.modules.ondevicellmandroid.core.GenAiBridge { *; }

# --- 5. Not our dependency, but our problem if it warns ------------------
# `genai-prompt` is `compileOnly` here, so in a build where the consumer did NOT
# opt in, R8 sees references to classes that are not on the classpath. Those are
# exactly the references the firewall exists to guard, and they are unreachable
# at runtime, so the warnings are noise that would otherwise fail the build.
-dontwarn com.google.mlkit.genai.**
