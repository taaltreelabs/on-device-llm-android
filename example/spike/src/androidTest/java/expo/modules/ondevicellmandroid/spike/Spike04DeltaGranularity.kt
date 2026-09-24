//
//  Spike04DeltaGranularity.kt — PROVISIONAL register item 4
//
//  THE QUESTION. The delta finding is STATIC: the internal adapter's
//  `onNewText(String)` wraps the incoming string into a fresh `Candidate` and
//  pushes it to a channel, with no `StringBuilder` and no accumulator field
//  anywhere in the method (research §2). So the stream *should* be
//  delta-per-callback, unlike Apple's cumulative snapshots. Should is not is,
//  and the granularity — token, word, sentence, or one chunk for the whole
//  answer — is not visible in bytecode at all.
//
//  THE MEASUREMENT. Stream one ~200-token generation and record, per chunk, its
//  length and its inter-arrival gap. Then two independent cumulative checks:
//
//   (a) THE TRIPWIRE ON THE WIRE. `GenAiEngine` already runs every chunk through
//       `StreamAccumulator` and puts its answer on each delta as `reset` — the
//       one piece of parity-shaped surface D6 deliberately kept. If any `reset`
//       arrives, the stream is cumulative and the assumption is refuted without
//       anything having been rewritten. That flag is read here straight off the
//       bridge.
//   (b) A SECOND ACCUMULATOR, owned by this test, fed the same chunks. If the
//       two disagree, the engine's per-request accumulator handling is wrong —
//       a different bug from a cumulative stream, and one worth separating.
//
//  Plus a convergence check that costs nothing and settles D18's Android form:
//  is `finish.text` (the SDK's own final text) equal to our concatenation of the
//  deltas? If it is not, a consumer that renders deltas and then swaps in the
//  final text will see a jump, and that is a documented behaviour rather than a
//  surprise.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.core.BridgeStreamEvent
import expo.modules.ondevicellmandroid.core.StreamAccumulator
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike04DeltaGranularity {

  private companion object {
    const val PROMPT =
      "Write about 150 words describing the sea at dawn. Use ordinary prose, no lists."
    const val MAX_TOKENS = 256
  }

  @Test
  fun deltaGranularity() = runSpike("04") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike

    val lock = Any()
    val lengths = mutableListOf<Int>()
    val gaps = mutableListOf<Long>()
    val resetFlags = mutableListOf<Int>()
    val mine = StreamAccumulator()
    var terminals = 0
    var terminal = "none"
    var finishText: String? = null
    var failureCode: String? = null

    val startedAt = System.currentTimeMillis()
    var lastArrival = startedAt
    var firstDeltaMs = -1L

    val completed = withTimeoutOrNull(SpikeBudget.generateMs) {
      engine.stream(
        SpikeBridge.request(
          messages = listOf(SpikeBridge.user(PROMPT)),
          maxOutputTokens = MAX_TOKENS,
        ),
      ) { event ->
        synchronized(lock) {
          val now = System.currentTimeMillis()
          when (event) {
            is BridgeStreamEvent.Delta -> {
              if (firstDeltaMs < 0) firstDeltaMs = now - startedAt
              lengths += event.text.length
              gaps += now - lastArrival
              if (event.reset) resetFlags += lengths.size - 1
              mine.accept(event.text)
              lastArrival = now
            }

            is BridgeStreamEvent.Finish -> {
              terminals += 1
              terminal = "finish(${event.result.finishReason})"
              finishText = event.result.text
            }

            is BridgeStreamEvent.Failure -> {
              terminals += 1
              terminal = "failure(${event.payload.code})"
              failureCode = event.payload.code
              ctx.put("failurePayload", event.payload.toMap())
            }
          }
        }
      }
      true
    }

    val totalMs = System.currentTimeMillis() - startedAt
    val count = lengths.size
    val joined = mine.joined

    ctx.put("chunkCount", count)
    ctx.put("totalMs", totalMs)
    ctx.put("firstDeltaMs", firstDeltaMs)
    ctx.put("terminalEvent", terminal)
    ctx.put("terminalEventCount", terminals)
    ctx.put("streamCallReturned", completed != null)
    ctx.put("joinedChars", joined.length)
    ctx.put(
      "chunkLengths",
      mapOf(
        "min" to (lengths.minOrNull() ?: -1),
        "median" to SpikeText.median(lengths.map { it.toLong() }),
        "mean" to SpikeText.mean(lengths.map { it.toLong() }),
        "p90" to SpikeText.percentile(lengths.map { it.toLong() }, 0.9),
        "max" to (lengths.maxOrNull() ?: -1),
        // The first 40 raw lengths, because a distribution summary hides the
        // shape that matters most: a big first chunk followed by small ones.
        "first40" to lengths.take(40),
      ),
    )
    ctx.put(
      "interArrivalMs",
      mapOf(
        "min" to (gaps.minOrNull() ?: -1L),
        "median" to SpikeText.median(gaps),
        "mean" to SpikeText.mean(gaps),
        "p90" to SpikeText.percentile(gaps, 0.9),
        "max" to (gaps.maxOrNull() ?: -1L),
        "first40" to gaps.take(40),
      ),
    )
    ctx.put("resetFlagsFromBridge", resetFlags)
    ctx.put("independentAccumulatorSuspectedSnapshot", mine.suspectedSnapshotStream)
    ctx.put("independentAccumulatorChunkCount", mine.chunkCount)

    val finish = finishText
    if (finish != null) {
      ctx.put("finishTextChars", finish.length)
      ctx.put("deltasEqualFinishText", finish == joined)
      ctx.put(
        "convergence",
        if (finish == joined) {
          "concatenated deltas match finish.text exactly"
        } else {
          "concatenated deltas (${joined.length} chars) differ from finish.text " +
            "(${finish.length} chars) — a consumer swapping in the final text will see a jump"
        },
      )
    }

    // The two accumulators must agree: they are fed the same chunks, one inside
    // the engine and one here. Disagreement is a harness-or-engine bug rather
    // than a device finding, so it is the one thing here that fails JUnit-style.
    val bridgeSawReset = resetFlags.isNotEmpty()
    if (count > 0 && bridgeSawReset != mine.suspectedSnapshotStream) {
      ctx.bug(
        "the engine's StreamAccumulator reported reset=$bridgeSawReset but an identical " +
          "accumulator fed the same chunks reported ${mine.suspectedSnapshotStream}",
      )
    }
    if (count > 0 && mine.chunkCount != count) {
      ctx.bug("counted $count deltas but the accumulator saw ${mine.chunkCount}")
    }

    val granularity = when {
      count == 0 -> "no-chunks"
      count == 1 -> "single-chunk-whole-response"
      else -> when (SpikeText.median(lengths.map { it.toLong() })) {
        in 0L..6L -> "token-ish"
        in 7L..30L -> "word-or-phrase"
        in 31L..120L -> "sentence"
        else -> "block"
      }
    }
    ctx.put("granularity", granularity)

    // Published for item 3 ONLY when the call actually returned: a duration that
    // is really a timeout would make item 3's "did it run to completion?"
    // comparison confidently wrong, and a missing number there is handled
    // gracefully while a wrong one is not.
    if (count > 0 && completed != null) {
      SpikeState.fullStreamMs = totalMs
      SpikeState.fullStreamChunks = count
    }

    when {
      count == 0 && failureCode != null ->
        ctx.verdict(
          Verdict.BLOCKED,
          "the stream produced no deltas and failed with '$failureCode' — granularity is " +
            "unmeasured on this device",
        )

      count == 0 ->
        ctx.verdict(
          Verdict.BLOCKED,
          "the stream produced no deltas (terminal event '$terminal', returned=" +
            "${completed != null}) — granularity is unmeasured",
        )

      bridgeSawReset ->
        ctx.verdict(
          Verdict.REFUTED,
          "the cumulative tripwire FIRED: $count chunks, with `reset` set on chunks " +
            "$resetFlags. The stream is snapshot-shaped, not delta-per-callback, so the " +
            "bytecode reading in research §2 does not hold end to end and the provider must " +
            "diff after all (Apple's D5 problem, on Android).",
        )

      count == 1 ->
        ctx.verdict(
          Verdict.INCONCLUSIVE,
          "exactly one chunk carried the whole ${joined.length}-character response in " +
            "${totalMs}ms. Nothing was cumulative, but nothing streamed either: a consumer " +
            "gets one delta and then the finish, which is technically correct and useless for " +
            "progressive rendering.",
        )

      else ->
        ctx.verdict(
          Verdict.CONFIRMED,
          "delta-per-callback confirmed: $count chunks, no `reset` flag, granularity " +
            "'$granularity' (median ${SpikeText.median(lengths.map { it.toLong() })} chars, " +
            "median gap ${SpikeText.median(gaps)}ms), total ${totalMs}ms, first delta at " +
            "${firstDeltaMs}ms" +
            (if (finish != null && finish != joined) ". NOTE: finish.text does not equal the concatenated deltas" else "") +
            // A stream that delivered plenty of chunks but never returned is a
            // valid granularity measurement and an invalid duration one, and item
            // 3 compares its cancel timing against that duration.
            (if (completed == null) ". NOTE: the stream() call did NOT return within " +
              "${SpikeBudget.generateMs}ms — the chunk distribution is sound but totalMs is a " +
              "floor, not a duration, and item 3's comparison against it is unsafe" else ""),
        )
    }
  }
}
