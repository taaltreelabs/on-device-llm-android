//
//  Spike07Warmup.kt — PROVISIONAL register item 7
//
//  THE QUESTION. Two, and the second is a correctness question rather than a
//  performance one:
//   (a) does `warmup()` measurably reduce first-token latency?
//   (b) does calling it on a DOWNLOADABLE device START A DOWNLOAD? If it does,
//       D3's promise — that nothing in this provider begins a multi-hundred-
//       megabyte transfer as a side effect of a question — is false for
//       `prewarm()` too, and that is a bug we would have shipped.
//
//  THE MEASUREMENT, and its honest limits. A brand-new `GenerativeModel` client
//  is built for this test (`SpikeMlKitFactory.fresh()`), a short generation is
//  streamed and the time to the FIRST CHUNK recorded, then `warmup()` is called,
//  then the same generation is streamed again.
//
//  WHY THIS IS ONE SAMPLE, AND WHY THAT IS STILL WORTH HAVING. The model is
//  resident in AICore, a system service shared across apps; it does not become
//  cold because we built a new client, and any earlier test in the same run has
//  already warmed it. So the "cold" number here is a LOWER BOUND on the real cold
//  cost, and the comparison is a floor on the benefit, not an estimate of it. Two
//  runs of one prompt cannot separate warmup from variance either.
//
//  For a truer cold sample, run this class ALONE and first:
//    --test-targets "class …spike.Spike07Warmup"
//  optionally with `--use-orchestrator`, which gives each test its own process.
//  Both invocations are in SPIKE.md. The caveat travels inside the verdict's
//  `detail` so it cannot be read without it.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike07Warmup {

  private companion object {
    const val PROMPT = "Name three colours."
    const val MAX_TOKENS = 48

    /** A warm first token this much faster counts as a real effect. */
    const val IMPROVEMENT = 0.8
  }

  @Test
  fun warmup() = runSpike("07") { ctx ->
    // A fresh client, not the shared one: this is the only test that wants
    // client construction inside the measurement rather than amortised away.
    val mlKit = SpikeMlKitFactory.fresh()
    if (mlKit == null) {
      ctx.put("mlKitClassesPresent", SpikeMlKitFactory.classesPresent)
      ctx.put("mlKitFactoryError", SpikeMlKitFactory.lastError ?: "")
      ctx.verdict(
        Verdict.BLOCKED,
        "no ML Kit client could be built, so warmup cannot be measured",
      )
      return@runSpike
    }
    val engine = SpikeBridge.engine
    ctx.put("caveat", "single sample; the model is resident in a shared system service, so the " +
      "cold figure is a LOWER BOUND on real cold cost. Run this class alone for a truer one.")
    ctx.put("otherTestsRanFirst", SpikeState.statusName != null || SpikeState.fullStreamMs != null)

    val statusBefore = try {
      mlKit.statusName(mlKit.checkStatusRaw())
    } catch (throwable: Throwable) {
      ctx.recordError("statusBeforeError", mlKit, engine, throwable)
      "THREW"
    }
    ctx.put("statusBeforeWarmup", statusBefore)

    // ---- (a) cold -----------------------------------------------------------
    val cold = ctx.generated("coldRun", mlKit, engine) {
      mlKit.stream(listOf(PROMPT), null, MAX_TOKENS) { }
    }

    // ---- warmup() ----------------------------------------------------------
    val warmupStarted = System.currentTimeMillis()
    val warmupOk = try {
      mlKit.warmup()
      true
    } catch (throwable: Throwable) {
      ctx.recordError("warmupError", mlKit, engine, throwable)
      false
    }
    val warmupMs = System.currentTimeMillis() - warmupStarted
    ctx.put("warmupOk", warmupOk)
    ctx.put("warmupMs", warmupMs)

    // ---- (b) did warmup start a download? -----------------------------------
    val statusAfter = try {
      mlKit.statusName(mlKit.checkStatusRaw())
    } catch (throwable: Throwable) {
      ctx.recordError("statusAfterError", mlKit, engine, throwable)
      "THREW"
    }
    ctx.put("statusAfterWarmup", statusAfter)
    val startedADownload = statusBefore == "DOWNLOADABLE" && statusAfter == "DOWNLOADING"
    ctx.put("warmupTriggeredDownload", startedADownload)

    // ---- (a) warm -----------------------------------------------------------
    val warm = ctx.generated("warmRun", mlKit, engine) {
      mlKit.stream(listOf(PROMPT), null, MAX_TOKENS) { }
    }

    try {
      mlKit.close()
    } catch (throwable: Throwable) {
      ctx.note("closeThrew", throwable)
    }

    val coldFirst = cold?.firstChunkMs
    val warmFirst = warm?.firstChunkMs
    ctx.put("coldFirstChunkMs", coldFirst)
    ctx.put("warmFirstChunkMs", warmFirst)
    ctx.put("coldTotalMs", cold?.ms)
    ctx.put("warmTotalMs", warm?.ms)
    if (coldFirst != null && warmFirst != null && coldFirst > 0) {
      ctx.put("ratio", warmFirst.toDouble() / coldFirst)
      ctx.put("savedMs", coldFirst - warmFirst)
    }

    when {
      startedADownload ->
        ctx.verdict(
          Verdict.REFUTED,
          "warmup() moved checkStatus() from DOWNLOADABLE to DOWNLOADING: it STARTS A " +
            "DOWNLOAD. D3's no-side-effects promise does not extend to prewarm(), and the " +
            "provider must either gate prewarm on availability or document that it can begin a " +
            "large transfer. This is the finding of this test even if the timings below are noise.",
        )

      cold == null || warm == null ->
        ctx.verdict(
          Verdict.BLOCKED,
          "one of the two runs did not complete (cold=${cold != null}, warm=${warm != null}), " +
            "so no comparison exists. warmup() itself ${if (warmupOk) "succeeded in ${warmupMs}ms" else "failed"}, " +
            "and status went $statusBefore -> $statusAfter.",
        )

      coldFirst == null || warmFirst == null ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "both runs completed but at least one produced no streaming chunk, so first-token " +
            "latency is unmeasurable (cold total ${cold.ms}ms, warm total ${warm.ms}ms). " +
            "warmup() took ${warmupMs}ms.",
        )

      warmFirst <= coldFirst * IMPROVEMENT ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "first token ${coldFirst}ms cold -> ${warmFirst}ms after a ${warmupMs}ms warmup() " +
            "(${"%.2f".format(warmFirst.toDouble() / coldFirst)}x). SINGLE SAMPLE, and the cold " +
            "figure is a lower bound because the model is resident in a shared service — treat " +
            "the direction as the finding and the magnitude as indicative.",
        )

      else ->
        ctx.verdict(
          Verdict.REFUTED,
          "first token ${coldFirst}ms cold -> ${warmFirst}ms after a ${warmupMs}ms warmup(): no " +
            "improvement beyond the ${IMPROVEMENT} threshold. On this device warmup() costs " +
            "${warmupMs}ms and buys nothing measurable. SINGLE SAMPLE — and note that if an " +
            "earlier test already warmed the model, this is exactly the result to expect, which " +
            "is why the isolated invocation in SPIKE.md matters before acting on it.",
        )
    }
  }
}
