//
//  Spike02SystemInstruction.kt — PROVISIONAL register item 2 (D4)
//
//  THE QUESTION. Three parts, and they are separable:
//   (a) what does `isSystemPromptAvailable()` actually return here?
//   (b) does a `SystemInstruction` change behaviour when it says yes?
//   (c) does the FOLD PATH work — the `System: …` prefix the encoder uses when
//       the answer is no? That path has never run anywhere.
//
//  THE MEASUREMENT. "Reply in uppercase only" is chosen because adherence is a
//  NUMBER (the fraction of cased letters that are upper case), not a judgement,
//  and because it is orthogonal to the content of the answer, so a model can obey
//  it while answering anything at all. Four arms:
//
//   A. bridge, with a system message — whichever path `isSystemPromptAvailable()`
//      selects. This is what a consumer gets.
//   B. bridge, no system message — the baseline. Without it, an uppercase answer
//      proves nothing: some models shout anyway.
//   C. forced `SystemInstruction`, past the bridge: the instruction in the request
//      field regardless of what the probe said.
//   D. forced FOLD: `PromptEncoding.encode(systemPromptSupported = false)` and
//      then sent with NO `SystemInstruction` at all, so the folded `System: …`
//      text is the only carrier. This exercises the untested path on every
//      device, whatever the probe answers.
//
//  Adherence is `uppercaseRatio > 0.9`, and the ratio itself is in the verdict so
//  a reader can disagree with the threshold.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.core.PromptEncoding
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike02SystemInstruction {

  private companion object {
    const val INSTRUCTION = "Reply in uppercase only."
    const val PROMPT = "Greet me in one short sentence."
    const val MAX_TOKENS = 32
    const val ADHERENCE = 0.9
  }

  @Test
  fun systemInstruction() = runSpike("02") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike
    val mlKit = SpikeMlKitFactory.shared

    // ---- (a) the probe -----------------------------------------------------
    val probed = if (mlKit == null) null else try {
      mlKit.isSystemPromptAvailable()
    } catch (throwable: Throwable) {
      ctx.recordError("probeError", mlKit, engine, throwable)
      null
    }
    ctx.put("isSystemPromptAvailable", probed)
    // The library's own view of the same fact, through `capabilities()`, because
    // that is the value a JS caller sees and a disagreement would be a bug.
    val capabilities: Map<String, Any?> = try {
      engine.capabilities()
    } catch (throwable: Throwable) {
      ctx.recordError("capabilitiesError", mlKit, engine, throwable)
      emptyMap()
    }
    ctx.put("capabilities", capabilities)
    ctx.put("capabilitiesSystemPromptAvailable", capabilities["systemPromptAvailable"])

    val supported = probed ?: (capabilities["systemPromptAvailable"] as? Boolean ?: false)
    SpikeState.systemPromptSupported = supported
    ctx.put("pathTaken", if (supported) "SystemInstruction" else "fold-into-first-content")

    val withSystem = listOf(SpikeBridge.system(INSTRUCTION), SpikeBridge.user(PROMPT))

    // Record the strings both paths produce, so a blocked run still documents
    // what would have been sent — including the fold, which is the shape nobody
    // has ever seen leave a device.
    ctx.put(
      "encodingWhenSupported",
      PromptEncoding.encode(withSystem, systemPromptSupported = true, requirePrompt = true).let {
        mapOf("systemInstruction" to (it.systemInstruction ?: ""), "contents" to it.contents, "framed" to it.framed)
      },
    )
    val folded = PromptEncoding.encode(withSystem, systemPromptSupported = false, requirePrompt = true)
    ctx.put(
      "encodingWhenFolded",
      mapOf("systemInstruction" to (folded.systemInstruction ?: ""), "contents" to folded.contents, "framed" to folded.framed),
    )
    if (folded.systemInstruction != null) {
      ctx.bug("the fold path produced a SystemInstruction; D4 says the text must be folded instead")
    }

    // ---- the four arms ----------------------------------------------------
    val armA = ctx.generated("armA_bridgeWithSystem", mlKit, engine) {
      SpikeBridge.generate(engine, withSystem, maxOutputTokens = MAX_TOKENS)
    }
    val armB = ctx.generated("armB_bridgeBaseline", mlKit, engine) {
      SpikeBridge.generate(engine, listOf(SpikeBridge.user(PROMPT)), maxOutputTokens = MAX_TOKENS)
    }
    val armC = if (mlKit == null) null else ctx.generated("armC_forcedSystemInstruction", mlKit, engine) {
      mlKit.generate(listOf(PROMPT), INSTRUCTION, null, MAX_TOKENS)
    }
    val armD = if (mlKit == null) null else ctx.generated("armD_forcedFold", mlKit, engine) {
      mlKit.generate(folded.contents, null, null, MAX_TOKENS)
    }

    fun ratio(generation: SpikeGeneration?): Double =
      if (generation == null) -1.0 else SpikeText.uppercaseRatio(generation.text)

    val ratios = linkedMapOf(
      "armA_bridgeWithSystem" to ratio(armA),
      "armB_baseline" to ratio(armB),
      "armC_forcedSystemInstruction" to ratio(armC),
      "armD_forcedFold" to ratio(armD),
    )
    ctx.put("uppercaseRatios", ratios)
    ctx.put("adherenceThreshold", ADHERENCE)

    fun adheres(value: Double) = value >= ADHERENCE
    val baseline = ratios["armB_baseline"] ?: -1.0
    val baselineShouts = adheres(baseline)
    ctx.put("baselineAlreadyUppercase", baselineShouts)

    val systemWorks = adheres(ratios["armA_bridgeWithSystem"] ?: -1.0) ||
      adheres(ratios["armC_forcedSystemInstruction"] ?: -1.0)
    val foldWorks = adheres(ratios["armD_forcedFold"] ?: -1.0)
    ctx.put("systemInstructionHonoured", systemWorks)
    ctx.put("foldHonoured", foldWorks)

    when {
      armA == null && armC == null && armD == null ->
        ctx.verdict(Verdict.BLOCKED, "no generation completed; see the recorded errors")

      baselineShouts ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "the baseline answer was already uppercase (ratio $baseline), so this prompt cannot " +
            "distinguish instruction-following from the model's default register. Change the " +
            "instruction before concluding anything.",
        )

      systemWorks || foldWorks ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "the instruction changed behaviour: isSystemPromptAvailable()=$probed, " +
            "SystemInstruction honoured=$systemWorks, fold path honoured=$foldWorks, " +
            "baseline ratio=$baseline. The fold path (D4's never-executed branch) " +
            (if (foldWorks) "works" else "did NOT take effect") + ".",
        )

      else ->
        ctx.verdict(
          Verdict.REFUTED,
          "neither a SystemInstruction nor the folded `System:` prefix changed the output's " +
            "case, with the probe reporting $probed. System instructions are not reaching " +
            "the model on this device by either route.",
        )
    }
  }
}
