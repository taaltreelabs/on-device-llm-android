//
//  Spike12Registration.kt — PROVISIONAL register item 12 (D1)
//
//  THE QUESTION. Does `requireNativeModule('OnDeviceLlmAndroid')` resolve on a
//  real build? D1 renamed the module from `OnDeviceLlm`, and the rename is the one
//  thing in this package that no existing test or build covers, because
//  autolinking and Expo's module registry exist only at runtime on a device.
//
//  WHAT CANNOT BE TESTED HERE, PLAINLY. `requireNativeModule` is a JavaScript
//  call into a running React Native host. An instrumentation test has no JS
//  runtime and no React context, and standing one up would mean launching the
//  example app's activity, waiting for the bridge, and evaluating JS through a dev
//  channel — hours of fragile work for one string comparison, with a fragility
//  that would infect every future run of this suite. The mandate said not to sink
//  hours here and that is the right call.
//
//  WHAT CAN BE TESTED, AND IS. The lookup has exactly two halves that can fail,
//  and both are reachable from Kotlin without a JS host:
//
//   1. IS THE MODULE REGISTERED? Autolinking generates
//      `expo.modules.ExpoModulesPackageList`, whose `getModulesMap()` keys are the
//      module classes the app will instantiate. If our class is not a key, no JS
//      lookup by any name can succeed, and the fault is in
//      `expo-module.config.json` or autolinking — not in the name.
//   2. IS THE NAME WHAT JS ASKS FOR? `Module.definition()` carries the string that
//      `Name(...)` set, and that string is the key JS looks up. Building the
//      definition also proves the module class constructs and its DSL runs, which
//      is the other way a registration can fail.
//
//  Together those two cover everything except the JS hop itself, which the
//  verdict says is still owed — and how to discharge it: run the example app and
//  watch it call `availability()`.
//
//  All of this is done by REFLECTION, deliberately. It keeps the test compiling
//  without an expo-modules-core dependency in the test APK, and it degrades to an
//  honest `inconclusive` under R8 rather than failing to load.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike12Registration {

  private companion object {
    const val MODULE_CLASS = "expo.modules.ondevicellmandroid.OnDeviceLlmAndroidModule"
    const val PACKAGE_LIST_CLASS = "expo.modules.ExpoModulesPackageList"
    const val EXPECTED_NAME = "OnDeviceLlmAndroid"

    /** D1's other half: the main package registers this, and the two must not collide. */
    const val SIBLING_NAME = "OnDeviceLlm"
  }

  @Test
  fun registration() = runSpike("12") { ctx ->
    ctx.put("expectedJsName", EXPECTED_NAME)
    ctx.put("siblingNameFromMainPackage", SIBLING_NAME)

    // ---- 1. is the module in the generated registry? -----------------------
    val registered = mutableListOf<String>()
    val registryOutcome = try {
      val listClass = Class.forName(PACKAGE_LIST_CLASS)
      val instance = listClass.getDeclaredConstructor().newInstance()
      val map = listClass.getMethod("getModulesMap").invoke(instance) as? Map<*, *>
      map?.keys?.forEach { key ->
        registered += (key as? Class<*>)?.name ?: key.toString()
      }
      "read ${registered.size} registered modules"
    } catch (throwable: Throwable) {
      SpikeWatch.record("12", throwable)
      "could not read $PACKAGE_LIST_CLASS: ${throwable.javaClass.simpleName}: ${throwable.message}"
    }
    ctx.put("registryOutcome", registryOutcome)
    ctx.put("registeredModuleClasses", registered)
    val inRegistry = registered.any { it == MODULE_CLASS }
    ctx.put("thisModuleRegistered", inRegistry)
    ctx.put("siblingModulePresent", registered.any { it.endsWith(".OnDeviceLlmModule") })

    // ---- 2. what name does the module declare? -----------------------------
    var declaredName: String? = null
    val definitionOutcome = try {
      val moduleClass = Class.forName(MODULE_CLASS)
      val module = moduleClass.getDeclaredConstructor().newInstance()
      val definition = moduleClass.getMethod("definition").invoke(module)
      declaredName = readName(definition)
      if (declaredName == null) {
        "definition() built, but its name could not be read reflectively " +
          "(${definition?.javaClass?.name})"
      } else {
        "definition() built and declares '$declaredName'"
      }
    } catch (throwable: Throwable) {
      SpikeWatch.record("12", throwable)
      "definition() could not be built: ${throwable.javaClass.simpleName}: ${throwable.message}"
    }
    ctx.put("definitionOutcome", definitionOutcome)
    ctx.put("declaredName", declaredName ?: "")
    ctx.put("nameMatches", declaredName == EXPECTED_NAME)
    ctx.put(
      "stillOwed",
      "the JavaScript hop itself: requireNativeModule('$EXPECTED_NAME') from a running RN host. " +
        "Discharge it by running the example app (npx expo run:android) and watching " +
        "availability() answer — an instrumentation test has no JS runtime.",
    )
    ctx.put("minifiedGuess", SpikeEnv.likelyMinified)

    when {
      inRegistry && declaredName == EXPECTED_NAME ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "autolinking registered $MODULE_CLASS and the module declares the name " +
            "'$EXPECTED_NAME', which is exactly the string requireNativeModule looks up. Both " +
            "halves a device can answer are green; only the JS hop is still owed (see stillOwed).",
        )

      inRegistry && declaredName != null ->
        ctx.verdict(
          Verdict.REFUTED,
          "the module is registered but declares the name '$declaredName' rather than " +
            "'$EXPECTED_NAME'. requireNativeModule('$EXPECTED_NAME') would fail. D1's rename is " +
            "incomplete.",
        )

      !inRegistry && registered.isNotEmpty() ->
        ctx.verdict(
          Verdict.REFUTED,
          "autolinking registered ${registered.size} modules and $MODULE_CLASS is NOT among " +
            "them: $registered. No JS lookup can succeed — check expo-module.config.json's " +
            "android.modules entry and the autolinking nativeModulesDir.",
        )

      else ->
        ctx.verdict(
          Verdict.BLOCKED,
          "neither half could be read on this build ($registryOutcome; $definitionOutcome)" +
            (if (SpikeEnv.likelyMinified) " — on a minified APK, reflective reads of generated " +
              "glue are the first casualty, so this verdict says nothing about the registration " +
              "itself; read the debug run's verdict instead" else "") +
            ". The JS hop remains the real check either way.",
        )
    }
  }

  /**
   * `ModuleDefinitionData.name`, without compiling against expo-modules-core.
   *
   * Tries the Kotlin property getter first, then the backing field, because which
   * of the two survives depends on the Expo version and on R8 — and being unable
   * to read the name is an `inconclusive`, never a crash.
   */
  private fun readName(definition: Any?): String? {
    if (definition == null) return null
    val type = definition.javaClass
    return try {
      type.getMethod("getName").invoke(definition) as? String
    } catch (throwable: Throwable) {
      try {
        type.getDeclaredField("name").also { it.isAccessible = true }.get(definition) as? String
      } catch (nested: Throwable) {
        null
      }
    }
  }
}
