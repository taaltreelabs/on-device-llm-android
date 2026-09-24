//
//  Spike01RoleEncoding.kt — PROVISIONAL register item 1 (DECISIONS.md D4)
//
//  THE QUESTION. `Content` has no role field: `javap -p` finds one private
//  `List` on it, and the strings `role`/`user`/`model`/`assistant` appear nowhere
//  in the 1,473 classes of `genai-prompt` (research §1). So multi-turn history
//  can only be expressed by inventing a textual frame, and D4 invented
//  `User: …` / `Model: …`. Does it work, does the model even need it, and does it
//  leak into the output?
//
//  THE MEASUREMENT. A fact is planted in an EARLIER turn and asked for in the
//  last one — a dog's name, because a proper noun is unambiguous to check and
//  impossible to infer. Three arms, and the comparison between them is the whole
//  test:
//
//   A. framed multi-turn, through the bridge: the shipped path, `PromptEncoding`
//      producing `User: …` / `Model: …` in three `Content`s.
//   B. unlabelled multi-turn, past the bridge: the same three `Content`s with no
//      labels at all. If B recalls and A does not, the frame is NOISE and D4's
//      central guess is actively harmful.
//   C. single-turn control: fact and question in one bare `Content`, the shape
//      every documented Google sample uses. If C fails too, the model cannot do
//      the task and A's failure says nothing about the encoding — that is what
//      `inconclusive` is for.
//
//  Frame echo is checked on every arm: a model that answers "Model: Baxter" is
//  telling us the frame reached it and that the provider needs to strip it.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.core.PromptEncoding
import expo.modules.ondevicellmandroid.core.RoleFrame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike01RoleEncoding {

  private companion object {
    const val FACT = "My dog is named Baxter and he is a border collie."
    const val ACK = "Got it — Baxter the border collie."
    const val QUESTION = "What is my dog's name? Reply with just the name."
    const val NEEDLE = "Baxter"
    const val MAX_TOKENS = 24
  }

  @Test
  fun roleEncoding() = runSpike("01") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike
    val mlKit = SpikeMlKitFactory.shared
    val systemSupported = mlKit?.let { SpikeState.systemPromptSupport(it) } ?: false
    ctx.put("systemPromptSupported", systemSupported)
    ctx.put(
      "frame",
      mapOf(
        "user" to RoleFrame.USER_LABEL,
        "model" to RoleFrame.MODEL_LABEL,
        "separator" to RoleFrame.SEPARATOR,
      ),
    )

    val history = listOf(
      SpikeBridge.user(FACT),
      SpikeBridge.model(ACK),
      SpikeBridge.user(QUESTION),
    )

    // What the shipped encoder makes of it — recorded whatever happens next, so
    // a blocked run still documents the exact strings that were going to be sent.
    val encoded = try {
      PromptEncoding.encode(history, systemSupported, requirePrompt = true)
    } catch (throwable: Throwable) {
      ctx.note("encodeThrew", throwable)
      ctx.bug("PromptEncoding.encode rejected a valid user-terminated conversation")
    }
    ctx.put(
      "encoding",
      linkedMapOf<String, Any?>(
        "framed" to encoded.framed,
        "contentCount" to encoded.contents.size,
        "contents" to encoded.contents,
        "systemInstruction" to (encoded.systemInstruction ?: ""),
      ),
    )
    if (!encoded.framed) {
      ctx.bug("a three-message conversation encoded unframed; D4 says framed whenever turns > 1")
    }

    // ---- A. framed multi-turn, the shipped path ---------------------------
    val framed = ctx.generated("armA_framedMultiTurn", mlKit, engine) {
      SpikeBridge.generate(engine, history, maxOutputTokens = MAX_TOKENS)
    }

    // ---- B. unlabelled multi-turn, past the bridge -------------------------
    val unlabelled = if (mlKit == null) null else {
      ctx.generated("armB_unlabelledMultiTurn", mlKit, engine) {
        mlKit.generate(listOf(FACT, ACK, QUESTION), null, null, MAX_TOKENS)
      }
    }

    // ---- C. single-turn control, the documented shape ----------------------
    val single = ctx.generated("armC_singleTurnControl", mlKit, engine) {
      SpikeBridge.generate(
        engine,
        listOf(SpikeBridge.user("$FACT $QUESTION")),
        maxOutputTokens = MAX_TOKENS,
      )
    }

    fun recalls(generation: SpikeGeneration?): Boolean =
      generation != null && generation.text.contains(NEEDLE, ignoreCase = true)

    val framedRecall = recalls(framed)
    val unlabelledRecall = recalls(unlabelled)
    val singleRecall = recalls(single)
    val echo = listOfNotNull(framed, unlabelled, single).any { SpikeText.echoesFrame(it.text) }

    ctx.put("recall", mapOf("framed" to framedRecall, "unlabelled" to unlabelledRecall, "singleTurn" to singleRecall))
    ctx.put("frameEcho", echo)
    ctx.put(
      "frameEchoDetail",
      listOfNotNull(framed, unlabelled, single)
        .filter { SpikeText.echoesFrame(it.text) }
        .map { it.text.take(120) },
    )

    when {
      framed == null && single == null ->
        ctx.verdict(
          Verdict.BLOCKED,
          "no generation completed — see the recorded errors; the encoding is unmeasured",
        )

      framedRecall ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "the framed multi-turn encoding carried a fact from an earlier turn into the " +
            "answer" +
            (if (echo) ", BUT the frame was echoed into the output — the provider will need to strip it" else "") +
            (if (unlabelled != null && !unlabelledRecall) "; the unlabelled control did NOT recall, so the labels are doing the work" else "") +
            (if (unlabelledRecall) "; note the unlabelled control recalled too, so positional order may be enough on its own" else ""),
        )

      singleRecall || unlabelledRecall ->
        ctx.verdict(
          Verdict.REFUTED,
          "the model can do the task (single-turn=$singleRecall, unlabelled multi-turn=" +
            "$unlabelledRecall) but the framed multi-turn encoding did NOT recall the fact — " +
            "D4's `User:`/`Model:` frame is not being read as turn attribution on this device",
        )

      else ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "no arm recalled the fact, including the single-turn control, so this measures the " +
            "model's ability rather than the encoding. Re-run with a simpler needle before " +
            "concluding anything about D4.",
        )
    }
  }
}
