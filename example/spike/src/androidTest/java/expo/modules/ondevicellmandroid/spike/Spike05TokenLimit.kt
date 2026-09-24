//
//  Spike05TokenLimit.kt — PROVISIONAL register item 5
//
//  THE QUESTION. `getTokenLimit()` is the only trustworthy context number we
//  have: no Google source corroborates a per-variant window split, and the docs
//  offer one uniform ~4,096 figure that no per-variant page confirms (research
//  §4). What does it actually return here, and — the part a number alone cannot
//  answer — IS IT REAL? A limit that the SDK reports but does not enforce, or
//  enforces at a different size, would silently break the Phase 2 budget
//  formula (`window − reservedForOutput − safetyMargin`), which trusts it.
//
//  THE MEASUREMENT.
//   1. `getTokenLimit()`, and the `contextWindow` the library reports from it.
//   2. Size an input to ~95% of that limit USING `countTokens` — not a character
//      estimate, because the tokens-per-character ratio is exactly what item 8
//      is trying to establish. `SpikeSizing` converges in at most six counter
//      round trips.
//   3. Generate at that size with a tiny output budget. It should succeed. If it
//      fails with `REQUEST_TOO_LARGE`, the reported limit is NOT the real one and
//      every consumer of `contextWindow` is over-budget.
//   4. Generate at ~120% of the limit. It should fail, and the code it fails with
//      is register item 9's material too.
//
//  The arithmetic for step 4 deliberately does NOT call `countTokens` again: on
//  an over-limit request the counter may itself refuse, and burning a round trip
//  to discover that would cost the measurement.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike05TokenLimit {

  private companion object {
    const val TAIL = "\n\nReply with the single word OK."
    const val NEAR_FRACTION = 0.95
    const val OVER_FRACTION = 1.2
  }

  @Test
  fun tokenLimit() = runSpike("05") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike
    val mlKit = SpikeMlKitFactory.shared

    val limit = if (mlKit == null) null else try {
      mlKit.tokenLimit()
    } catch (throwable: Throwable) {
      ctx.recordError("getTokenLimitError", mlKit, engine, throwable)
      null
    }
    ctx.put("getTokenLimit", limit)
    SpikeState.tokenLimit = limit

    val reported = try {
      engine.capabilities()["contextWindow"]
    } catch (throwable: Throwable) {
      ctx.recordError("capabilitiesError", mlKit, engine, throwable)
      null
    }
    ctx.put("capabilitiesContextWindow", reported)
    ctx.put("documentedFigure", 4096)

    if (limit == null || limit <= 0) {
      ctx.verdict(
        Verdict.BLOCKED,
        "getTokenLimit() returned ${limit ?: "an error"} — with no limit there is nothing to " +
          "probe against, and the library degrades contextWindow to 0 (UNKNOWN) as designed",
      )
      return@runSpike
    }

    // ---- 2. size to ~95% of the claimed limit ------------------------------
    val sized = try {
      SpikeSizing.sizeTo(engine, (limit * NEAR_FRACTION).toInt())
    } catch (throwable: Throwable) {
      ctx.recordError("sizingError", mlKit, engine, throwable)
      null
    }
    if (sized == null) {
      ctx.verdict(
        Verdict.BLOCKED,
        "countTokens() could not be used to size an input, so the claimed limit of $limit " +
          "cannot be probed. The value is reported but unverified.",
      )
      return@runSpike
    }
    ctx.put("sizedNearLimit", sized.summary())

    val nearPrompt = sized.text + TAIL
    val nearCount = try {
      engine.countTokens(SpikeBridge.request(listOf(SpikeBridge.user(nearPrompt))))
    } catch (throwable: Throwable) {
      ctx.recordError("nearCountError", mlKit, engine, throwable)
      sized.tokens
    }
    ctx.put("nearLimitTokens", nearCount)
    ctx.put("nearLimitFractionOfLimit", nearCount.toDouble() / limit)
    SpikeState.nearLimitText = nearPrompt
    SpikeState.nearLimitTokens = nearCount

    // ---- 3. it should succeed ----------------------------------------------
    val near = ctx.generated("nearLimitGeneration", mlKit, engine) {
      SpikeBridge.generate(engine, listOf(SpikeBridge.user(nearPrompt)), maxOutputTokens = 16)
    }
    val nearSucceeded = near != null
    val nearTimedOut = ctx.timedOut("nearLimitGeneration")
    ctx.put("nearLimitSucceeded", nearSucceeded)
    ctx.put("nearLimitTimedOut", nearTimedOut)

    // ---- 4. and 120% should not --------------------------------------------
    val overRepeats = if (sized.tokensPerUnit > 0.0) {
      ((limit * OVER_FRACTION) / sized.tokensPerUnit).toInt().coerceAtLeast(sized.repeats + 1)
    } else {
      sized.repeats * 2
    }
    val overPrompt = SpikeSizing.UNIT.repeat(overRepeats) + TAIL
    ctx.put("overLimitRepeats", overRepeats)
    ctx.put("overLimitChars", overPrompt.length)
    ctx.put("overLimitEstimatedTokens", (overRepeats * sized.tokensPerUnit).toInt())

    val over = ctx.generated("overLimitGeneration", mlKit, engine) {
      SpikeBridge.generate(engine, listOf(SpikeBridge.user(overPrompt)), maxOutputTokens = 16)
    }
    val overTimedOut = ctx.timedOut("overLimitGeneration")
    val overRejected = over == null && !overTimedOut
    ctx.put("overLimitRejected", overRejected)
    ctx.put("overLimitTimedOut", overTimedOut)
    val overTaxonomy = (ctx.data["overLimitGeneration.error"] as? Map<*, *>)?.get("taxonomy")
    ctx.put("overLimitTaxonomy", overTaxonomy)

    when {
      nearTimedOut || overTimedOut ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "a probe generation exceeded its ${SpikeBudget.generateMs}ms budget rather than " +
            "returning or being refused (near=${nearTimedOut}, over=${overTimedOut}), so " +
            "'refused' and 'still working' cannot be told apart. Raise " +
            "-e spikeGenerateBudgetMs and re-run item 5 alone. getTokenLimit() reported $limit.",
        )

      nearSucceeded && overRejected ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "getTokenLimit()=$limit behaves as a real combined input+output budget: a " +
            "${nearCount}-token request (${"%.2f".format(nearCount.toDouble() / limit)} of the " +
            "limit) generated fine and a ~${(overRepeats * sized.tokensPerUnit).toInt()}-token " +
            "one was refused. The Phase 2 budget formula can trust this number.",
        )

      !nearSucceeded && overRejected ->
        ctx.verdict(
          Verdict.REFUTED,
          "a request measured at $nearCount tokens — INSIDE the reported limit of $limit — was " +
            "refused. The reported limit is not the enforced limit, so contextWindow is " +
            "optimistic and every budget derived from it will overflow. See " +
            "nearLimitGeneration.error for the code.",
        )

      nearSucceeded && !overRejected ->
        ctx.verdict(
          Verdict.REFUTED,
          "a request of roughly ${(overRepeats * sized.tokensPerUnit).toInt()} tokens — " +
            "${OVER_FRACTION}x the reported limit of $limit — generated WITHOUT error. Either " +
            "the limit is not enforced or the input was silently truncated, and silent " +
            "truncation is worse than a refusal because the caller cannot see it.",
        )

      else ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "neither the near-limit nor the over-limit generation completed; see the recorded " +
            "errors. getTokenLimit() reported $limit and remains unverified.",
        )
    }
  }
}
