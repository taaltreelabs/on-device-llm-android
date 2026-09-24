//
//  Spike08CountTokens.kt — PROVISIONAL register item 8
//
//  THE QUESTION. `countTokens(request)` is documented as exact and as counting
//  INPUT ONLY, with the reference doc stating the invariant that matters:
//  *"The input size returned by countTokens plus the output size specified by
//  GenerateContentRequest.maxOutputTokens should be no larger than the limit
//  returned by this method."* Is the number consistent with what generation
//  actually consumes — frame included?
//
//  "Frame included" is the load-bearing phrase. D4's caveat is that the count is
//  exact for THE REQUEST WE ACTUALLY BUILD, invented `User:`/`Model:` labels and
//  all. So this test counts the same conversation twice by two different routes
//  and checks they agree:
//
//   (a) through the bridge — `GenAiBridge.countTokens(BridgeRequest)`, which runs
//       `PromptEncoding` and counts the encoded result. This is what the Phase 2
//       context manager will call.
//   (b) past the bridge — `countTokens` on the strings `PromptEncoding` produced,
//       handed straight to the SDK.
//
//  They must be identical. If they are not, the bridge is counting something
//  other than what it sends, which would be the worst possible bug in a budget
//  calculation and is invisible to every unit test we have.
//
//  Then the invariant itself, which is the only part that needs a real
//  generation: size an input near the limit and ask for maxOutputTokens that
//  deliberately overshoots by a few tokens. The documented rule says that should
//  be refused. Whether it IS refused decides how much safety margin the context
//  manager needs.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.core.PromptEncoding
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike08CountTokens {

  private companion object {
    /** Overshoot the documented budget by this many tokens. Small on purpose. */
    const val OVERSHOOT = 8

    /** Room left for output when sizing the input, so the probe stays quick. */
    const val OUTPUT_ROOM = 64
  }

  @Test
  fun countTokens() = runSpike("08") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike
    val mlKit = SpikeMlKitFactory.shared
    val systemSupported = mlKit?.let { SpikeState.systemPromptSupport(it) } ?: false

    // ---- (a) and (b): two routes to the same number ------------------------
    val conversation = listOf(
      SpikeBridge.system("You are a concise assistant."),
      SpikeBridge.user("My dog is named Baxter and he is a border collie."),
      SpikeBridge.model("Got it — Baxter the border collie."),
      SpikeBridge.user("What breed is he? Answer in one word."),
    )
    val encoded = PromptEncoding.encode(conversation, systemSupported, requirePrompt = true)
    ctx.put(
      "encoding",
      mapOf(
        "framed" to encoded.framed,
        "systemInstruction" to (encoded.systemInstruction ?: ""),
        "contents" to encoded.contents,
      ),
    )
    val encodedChars = encoded.contents.sumOf { it.length } + (encoded.systemInstruction?.length ?: 0)
    val rawChars = conversation.sumOf { it.content.length }
    ctx.put("encodedChars", encodedChars)
    ctx.put("rawChars", rawChars)
    ctx.put("frameOverheadChars", encodedChars - rawChars)

    val viaBridge = try {
      engine.countTokens(SpikeBridge.request(conversation))
    } catch (throwable: Throwable) {
      ctx.recordError("bridgeCountError", mlKit, engine, throwable)
      null
    }
    val viaSdk = if (mlKit == null) null else try {
      mlKit.countTokens(encoded.contents, encoded.systemInstruction)
    } catch (throwable: Throwable) {
      ctx.recordError("sdkCountError", mlKit, engine, throwable)
      null
    }
    ctx.put("countViaBridge", viaBridge)
    ctx.put("countViaSdkOnEncodedStrings", viaSdk)
    ctx.put("routesAgree", viaBridge != null && viaBridge == viaSdk)
    val rawWords = conversation.sumOf { it.content.split(" ").size }
    ctx.put("rawWords", rawWords)
    if (viaBridge != null && viaBridge > 0) {
      ctx.put("charsPerToken", encodedChars.toDouble() / viaBridge)
      ctx.put("tokensPerWord", viaBridge.toDouble() / rawWords)
    }

    if (viaBridge == null) {
      ctx.verdict(
        Verdict.BLOCKED,
        "countTokens() could not be called through the bridge; the library's documented " +
          "behaviour is to throw rather than estimate, so Phase 2 would widen its safety " +
          "margin from 64 to 256 tokens here (D10/D27) — which is the correct response",
      )
      return@runSpike
    }

    // ---- the documented invariant -----------------------------------------
    val limit = SpikeState.tokenLimit ?: (
      if (mlKit == null) null else try {
        mlKit.tokenLimit()
      } catch (throwable: Throwable) {
        ctx.recordError("tokenLimitError", mlKit, engine, throwable)
        null
      }
      )
    ctx.put("tokenLimit", limit)
    ctx.put("tokenLimitFromItem05", SpikeState.tokenLimit != null)

    if (limit == null || limit <= 0) {
      ctx.verdict(
        Verdict.INCONCLUSIVE,
        "the two counting routes ${if (viaBridge == viaSdk) "agree" else "DISAGREE"} at " +
          "$viaBridge / $viaSdk tokens, but with no token limit the documented " +
          "countTokens + maxOutputTokens invariant cannot be probed",
      )
      return@runSpike
    }

    // Reuse item 5's sizing when it ran; size afresh otherwise, so the test is
    // meaningful when run alone.
    val nearText = SpikeState.nearLimitText
    val nearTokens = SpikeState.nearLimitTokens
    val probeText: String
    val probeTokens: Int
    if (nearText != null && nearTokens != null && nearTokens <= limit - OVERSHOOT) {
      probeText = nearText
      probeTokens = nearTokens
      ctx.put("sizingSource", "item 05")
    } else {
      val sized = try {
        SpikeSizing.sizeTo(engine, limit - OUTPUT_ROOM)
      } catch (throwable: Throwable) {
        ctx.recordError("sizingError", mlKit, engine, throwable)
        null
      }
      if (sized == null) {
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "the two counting routes ${if (viaBridge == viaSdk) "agree" else "DISAGREE"} at " +
            "$viaBridge / $viaSdk tokens, but an input could not be sized to probe the " +
            "invariant against a limit of $limit",
        )
        return@runSpike
      }
      probeText = sized.text
      probeTokens = sized.tokens
      ctx.put("sizingSource", "sized here")
      ctx.put("sizing", sized.summary())
    }

    // Deliberately `OVERSHOOT` tokens over what the documented rule permits.
    val requestedOutput = (limit - probeTokens + OVERSHOOT).coerceAtLeast(1)
    ctx.put("probeInputTokens", probeTokens)
    ctx.put("requestedMaxOutputTokens", requestedOutput)
    ctx.put("sumVsLimit", "$probeTokens + $requestedOutput vs $limit")

    val result = ctx.generated("invariantProbe", mlKit, engine) {
      SpikeBridge.generate(
        engine,
        listOf(SpikeBridge.user(probeText)),
        maxOutputTokens = requestedOutput,
      )
    }
    val timedOut = ctx.timedOut("invariantProbe")
    val refused = result == null && !timedOut
    ctx.put("overBudgetRequestRefused", refused)
    ctx.put("invariantProbeTimedOut", timedOut)

    val routesAgree = viaBridge == viaSdk
    when {
      !routesAgree && viaSdk != null ->
        ctx.verdict(
          Verdict.REFUTED,
          "the bridge counted $viaBridge tokens for a conversation whose encoded strings the " +
            "SDK counts as $viaSdk. The bridge is not counting what it sends, so every budget " +
            "built on countTokens() is wrong by ${viaBridge - viaSdk} tokens.",
        )

      timedOut ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "counting is self-consistent at $viaBridge tokens (${"%.2f".format(encodedChars.toDouble() / viaBridge)} " +
            "chars/token, frame overhead ${encodedChars - rawChars} chars), but the invariant " +
            "probe timed out rather than answering, so whether countTokens+maxOutputTokens>limit " +
            "is enforced remains unknown",
        )

      refused ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "counting is self-consistent at $viaBridge tokens by both routes " +
            "(${"%.2f".format(encodedChars.toDouble() / viaBridge)} chars/token, frame overhead " +
            "${encodedChars - rawChars} chars), and the documented invariant is ENFORCED: " +
            "$probeTokens input + $requestedOutput requested output (limit $limit) was refused. " +
            "The context manager must keep the sum under the limit, not merely the input.",
        )

      else ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "counting is self-consistent at $viaBridge tokens by both routes " +
            "(${"%.2f".format(encodedChars.toDouble() / viaBridge)} chars/token), but the " +
            "documented invariant is NOT enforced: $probeTokens input + $requestedOutput " +
            "requested output exceeds the limit of $limit and the request generated anyway " +
            "(finishReason ${result?.finishReason}). Treat the limit as advisory for output and " +
            "keep the margin.",
        )
    }
  }
}
