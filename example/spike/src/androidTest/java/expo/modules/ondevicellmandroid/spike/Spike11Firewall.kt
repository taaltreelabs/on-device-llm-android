//
//  Spike11Firewall.kt — PROVISIONAL register item 11 / TRIPWIRE T1
//
//  THE QUESTION. Does the `compileOnly` arrangement work on a device once the
//  consumer adds the `implementation` line — the probe passing, the engine
//  loading, and no `NoClassDefFoundError` from anywhere else — INCLUDING in a
//  release build with R8 enabled?
//
//  WHY T1 IS THE MOST IMPORTANT THING IN THIS SUITE. The firewall is what makes
//  the consumer's Gradle line an act of consent: an app that has not opted in
//  ships no Play Services and no Firebase datatransport telemetry pipeline, and
//  the provider reports `unsupportedPlatform` cleanly. But the firewall is built
//  from two `Class.forName` lookups BY NAME, and R8 can see neither:
//
//    * `GenAiEngine` is referenced by no bytecode anywhere — only by a string — so
//      R8 shrinks it away and `createEngine()` returns null. The provider then
//      reports `unsupportedPlatform` ON A DEVICE THAT WAS PERFECTLY CAPABLE, with
//      no crash and no log: a silent, permanent wrong answer, which is the worst
//      failure shape available.
//    * `Generation` survives (the engine uses it) but is RENAMED, so the presence
//      probe answers false and produces the same silent result.
//
//  `android/consumer-rules.pro` ships the rules that prevent both, and shipping
//  them WITH the library is T1's answer: a consumer who never reads this file
//  still gets a working firewall. This test is what proves the rules do their job,
//  and it must be run against BOTH APKs — the debug pair and the minified release
//  pair (see SPIKE.md).
//
//  THE DISCRIMINATOR. "Absent" and "renamed" look identical from inside the
//  probe. So when the probe fails, this test builds the spike's own SDK adapter
//  WITHOUT the name check and calls a method on it. If that works, the classes are
//  present and only the NAME is gone — R8 defeated the firewall, and the verdict
//  says so in those words instead of blaming a missing dependency.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.MlKitPresence
import expo.modules.ondevicellmandroid.core.AvailabilityMapping
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike11Firewall {

  @Test
  fun firewall() = runSpike("11") { ctx ->
    ctx.put("appDebuggable", SpikeEnv.debuggable)
    ctx.put("likelyMinified", SpikeEnv.likelyMinified)
    ctx.put("variantHint", SpikeEnv.variantHint)
    ctx.put("expectedCoordinate", AvailabilityMapping.DEPENDENCY_COORDINATE)

    // A second library class, loaded the ordinary way — reading a constant off
    // `AvailabilityMapping` above is the load. `core/` must NOT reference ML Kit
    // (D2's confinement rule), so a class in it that fails to load while
    // `GenAiEngine` works means the rule has been broken and a clean
    // "unsupported" has become a crash. Reaching this line is the evidence.
    ctx.put("coreClassLoadedWithoutMlKit", true)

    val probe = MlKitPresence.isAvailable
    ctx.put("mlKitPresenceProbe", probe)

    val engine = SpikeBridge.engine
    ctx.put("engineConstructed", engine != null)
    ctx.put("engineClass", engine?.javaClass?.name ?: "")
    ctx.put("engineClassNamePreserved", engine?.javaClass?.name == "expo.modules.ondevicellmandroid.GenAiEngine")
    ctx.put("bridgeError", SpikeBridge.lastError ?: "")

    // ---- the discriminator, only when the probe failed ---------------------
    var renamedNotAbsent = false
    if (!probe) {
      val direct = SpikeMlKitFactory.forceFresh()
      ctx.put("directAdapterBuilt", direct != null)
      if (direct != null) {
        val outcome = try {
          "status=" + direct.statusName(direct.checkStatusRaw())
        } catch (throwable: Throwable) {
          SpikeWatch.record("11", throwable)
          val info = try {
            direct.errorInfo(throwable)
          } catch (nested: Throwable) {
            null
          }
          when {
            SpikeWatch.isLinkage(throwable) -> "linkage:${throwable.javaClass.simpleName}"
            info?.isGenAi == true -> "genai:${info.codeName}"
            else -> "other:${throwable.javaClass.simpleName}"
          }
        }
        ctx.put("directSdkCall", outcome)
        // A GenAiException, or an actual status, means the SDK classes ARE
        // loadable through direct references — so the name is what went missing.
        renamedNotAbsent = !outcome.startsWith("linkage:")
      }
      ctx.put("renamedRatherThanAbsent", renamedNotAbsent)
    }

    // ---- what a caller would actually get ---------------------------------
    if (engine != null) {
      val availability = try {
        engine.availability().toMap()
      } catch (throwable: Throwable) {
        ctx.recordError("availabilityError", SpikeMlKitFactory.shared, engine, throwable)
        null
      }
      ctx.put("availability", availability)
    } else {
      // The shipped answer on a build where the engine cannot be reached, so the
      // verdict records the exact string a developer would see in a support ticket.
      ctx.put("availabilityWithoutEngine", AvailabilityMapping.missingDependency().toMap())
    }

    ctx.put("suiteWideLinkageErrors", SpikeWatch.linkageErrors())
    ctx.put("suiteWideErrorsSeen", SpikeWatch.allErrors().size)
    ctx.put(
      "linkageWatchCaveat",
      "instrumentation test order is not contractual, so this list covers whatever ran before " +
        "this class in this invocation. The results file is the complete record.",
    )

    val linkage = SpikeWatch.linkageErrors()
    val minified = SpikeEnv.likelyMinified

    when {
      renamedNotAbsent ->
        ctx.verdict(
          Verdict.REFUTED,
          "TRIPWIRE T1 FIRED. The ML Kit classes are loadable through direct references " +
            "(${ctx.data["directSdkCall"]}) but Class.forName could not find " +
            "'com.google.mlkit.genai.prompt.Generation' — R8 renamed it, so the presence probe " +
            "reports false and the provider would answer unsupportedPlatform on a capable " +
            "device. consumer-rules.pro's -keepnames rule is missing or not being applied.",
        )

      !probe ->
        ctx.verdict(
          Verdict.BLOCKED,
          "the SDK is genuinely not in this build: the probe failed and a direct reference " +
            "failed too. This is the firewall behaving exactly as designed — a consumer who has " +
            "not opted in pays nothing — but it means item 11's on-device half is unverified " +
            "here. Add `implementation '${AvailabilityMapping.DEPENDENCY_COORDINATE}'` to the " +
            "app and re-run.",
        )

      engine == null ->
        ctx.verdict(
          Verdict.REFUTED,
          "TRIPWIRE T1 FIRED. The presence probe passed but MlKitPresence.createEngine() " +
            "returned null: GenAiEngine could not be constructed by name" +
            (if (minified) " on a MINIFIED build — R8 shrank the class, which is precisely what " +
              "consumer-rules.pro's -keep rule exists to prevent; check that the rule reached " +
              "this build" else " on a NON-minified build, which points at something other than R8") +
            ". The provider would report unsupportedPlatform on a capable device.",
        )

      linkage.isNotEmpty() ->
        ctx.verdict(
          Verdict.REFUTED,
          "the firewall loaded but ${linkage.size} linkage error(s) were recorded elsewhere in " +
            "this run: $linkage. D2's rule is that ML Kit references live in GenAiEngine ALONE; " +
            "a NoClassDefFoundError from any other class means that confinement is broken and a " +
            "clean 'unsupported' has become a crash.",
        )

      else ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "the firewall works" + (if (minified) " WITH R8/minification enabled" else " (debug, unminified)") +
            ": the presence probe passed, GenAiEngine was constructed by name as " +
            "'${engine.javaClass.name}', availability() answered, and no linkage error was " +
            "recorded anywhere in this run." +
            (if (minified) " consumer-rules.pro's keep rules are doing their job — that is T1's answer." else " Run the release pair to answer T1."),
        )
    }
  }
}
