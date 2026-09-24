//
//  MlKitPresence.kt
//  OnDeviceLlm — Android
//
//  The runtime half of the dependency firewall (DECISIONS.md D33).
//
//  `com.google.mlkit:genai-prompt` is declared `compileOnly` in
//  `android/build.gradle`, so this module compiles against it and **ships
//  none of it**. An app that only ever uses the Apple provider but publishes an
//  Android build pays nothing: no Play Services, no ML Kit common, no Firebase
//  datatransport telemetry pipeline, no 1.1 MB AAR. Expo autolinking includes
//  every `android/` module of every dependency unconditionally and offers no
//  per-provider opt-out (docs/research/android-genai.md §9), so `compileOnly`
//  is the only lever that makes the default free.
//
//  The cost is that the classes may genuinely be absent at runtime, which this
//  file detects — once, cheaply, and without ever letting a `Throwable` escape.
//
//  Note that this file, like `core/`, contains **no ML Kit imports**. It
//  reaches `GenAiEngine` by name so that the module's bytecode never references
//  it: a class is only loaded and verified when something touches it, and
//  nothing touches `GenAiEngine` until the probe below has said yes.
//

package expo.modules.ondevicellmandroid

import expo.modules.ondevicellmandroid.core.GenAiBridge

object MlKitPresence {
  /**
   * The load-bearing entry class.
   *
   * `Generation` is the Prompt API's only entry point —
   * `Generation.getClient()` is how every request starts — so its absence is
   * exactly the condition we care about, and its presence implies the rest of
   * `genai-prompt` came with it (an AAR arrives whole or not at all).
   */
  private const val ENTRY_CLASS = "com.google.mlkit.genai.prompt.Generation"

  private const val ENGINE_CLASS = "expo.modules.ondevicellmandroid.GenAiEngine"

  /**
   * One probe, memoised.
   *
   * `initialize = false`: this asks whether the class *exists*, and running a
   * static initialiser as a side effect of asking would be both slower and a
   * second, unrelated way to fail. The class is initialised later, on first
   * real use, in the ordinary way.
   *
   * The catch is deliberately `Throwable`, not `Exception`:
   * `ClassNotFoundException` is the expected answer, but a partially resolved
   * classpath throws `NoClassDefFoundError`, and either way the honest result
   * is "not present" rather than a crashed app.
   */
  val isAvailable: Boolean by lazy {
    try {
      Class.forName(ENTRY_CLASS, false, MlKitPresence::class.java.classLoader)
      true
    } catch (throwable: Throwable) {
      false
    }
  }

  /**
   * Build the engine, or `null` when the SDK is not in this build.
   *
   * Reflective instantiation, so the caller's bytecode never names
   * `GenAiEngine` and therefore never risks pulling it — and its ML Kit
   * references — into a classloader that cannot resolve them.
   */
  fun createEngine(): GenAiBridge? {
    if (!isAvailable) return null
    return try {
      Class.forName(ENGINE_CLASS)
        .getDeclaredConstructor()
        .newInstance() as GenAiBridge
    } catch (throwable: Throwable) {
      // The probe passed but construction failed: a half-present classpath, or
      // an SDK whose shape changed under us (the API is beta with an explicit
      // no-compatibility promise, §11.4). Report unavailable rather than crash.
      null
    }
  }
}
