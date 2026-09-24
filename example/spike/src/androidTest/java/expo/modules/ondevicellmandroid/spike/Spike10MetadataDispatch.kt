//
//  Spike10MetadataDispatch.kt — PROVISIONAL register item 10 / tripwire T2
//
//  THE QUESTION. `genai-prompt` 1.0.0-beta4 is compiled with Kotlin 2.3.21, so
//  its classes carry `@Metadata` version 2.3.0; the toolchain's Kotlin reads up
//  to 2.2.0 and otherwise refuses the artifact outright. The library passes
//  `-Xskip-metadata-version-check`, scoped to its own compile tasks. That flag
//  makes the compiler READ newer metadata rather than VERIFY it — so a genuinely
//  incompatible declaration shape would surface at runtime instead of at compile
//  time. Does every SDK call actually dispatch?
//
//  WHY THIS IS A REAL TEST AND NOT A TAUTOLOGY. It would be easy to say "item 10
//  is covered because test 00 generated something". It is not: a generation
//  exercises a handful of methods. A metadata mismatch is a mismatch in a
//  DECLARATION SHAPE, and the shapes that differ are exactly the ones a happy
//  path may never touch — a property that is a getter here and a field there, a
//  companion object that moved, a default argument's synthetic bridge, an
//  interface method the compiler thought was non-abstract. Their runtime symptoms
//  are `NoSuchMethodError`, `NoSuchFieldError`, `AbstractMethodError`,
//  `IncompatibleClassChangeError`, `VerifyError` — never a wrong answer.
//
//  So `dispatchProbe()` touches one of each KIND of surface the engine uses:
//  int constants on annotation interfaces (`FeatureStatus`, `GenAiException.ErrorCode`),
//  companion constants (`Candidate.FinishReason`), builder property setters
//  (`temperature`, `maxOutputTokens`, `systemInstruction`), and the suspend
//  functions themselves. Each is classified, and a `GenAiException` from a device
//  with no AICore is explicitly NOT a dispatch failure — that distinction is the
//  whole design of this test, because otherwise every farm device without Gemini
//  Nano would look like a toolchain problem.
//
//  This is also T2's evidence: if the flag were papering over a real difference,
//  it would show up here as a linkage error and the beta SDK would be holding the
//  toolchain hostage.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike10MetadataDispatch {

  @Test
  fun metadataDispatch() = runSpike("10") { ctx ->
    val mlKit = ctx.requireMlKit() ?: return@runSpike

    ctx.put("compilerFlag", "-Xskip-metadata-version-check (library compile tasks + this suite)")
    ctx.put("sdkKotlin", "2.3.21 (@Metadata 2.3.0)")

    val outcomes = try {
      mlKit.dispatchProbe()
    } catch (throwable: Throwable) {
      ctx.recordError("dispatchProbeError", mlKit, SpikeBridge.engine, throwable)
      emptyMap<String, String>()
    }
    ctx.put("surfaces", outcomes)

    if (outcomes.isEmpty()) {
      ctx.verdict(
        Verdict.BLOCKED,
        "the dispatch probe itself could not run, which on a build with the SDK present is " +
          "suspicious in exactly the way this test exists to detect — see dispatchProbeError",
      )
      return@runSpike
    }

    val linkage = outcomes.filterValues { it.startsWith("linkage:") }
    val deviceSaidNo = outcomes.filterValues { it.startsWith("genai:") }
    val other = outcomes.filterValues { it.startsWith("other:") }
    val ok = outcomes.filterValues { it.startsWith("ok:") }

    ctx.put("okCount", ok.size)
    ctx.put("linkageFailures", linkage)
    ctx.put("genAiRefusals", deviceSaidNo.keys.toList())
    ctx.put("otherFailures", other)
    ctx.put("suiteWideLinkageErrors", SpikeWatch.linkageErrors())

    when {
      linkage.isNotEmpty() ->
        ctx.verdict(
          Verdict.REFUTED,
          "${linkage.size} of ${outcomes.size} SDK surfaces failed with LINKAGE errors: " +
            "${linkage.keys}. `-Xskip-metadata-version-check` is hiding a real declaration-shape " +
            "difference, which is tripwire T2 firing: the flag must go and the toolchain's Kotlin " +
            "must catch up to the artifact's.",
        )

      ok.size == outcomes.size ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "all ${outcomes.size} probed SDK surfaces dispatched correctly — constants, builder " +
            "property setters and suspend functions alike. The metadata flag holds at runtime on " +
            "this device.",
        )

      other.isNotEmpty() && deviceSaidNo.isEmpty() ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "no linkage errors, but ${other.size} surfaces failed for other reasons " +
            "(${other.keys}). Dispatch looks sound; those failures need reading individually.",
        )

      else ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "no linkage errors anywhere: ${ok.size} surfaces dispatched and ${deviceSaidNo.size} " +
            "were refused by the device with a GenAiException (${deviceSaidNo.keys}), which is a " +
            "device-state answer and not a dispatch failure. The metadata flag holds at runtime.",
        )
    }
  }
}
