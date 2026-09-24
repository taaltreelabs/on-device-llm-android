//
//  Spike03CancelSemantics.kt — PROVISIONAL register item 3
//
//  THE QUESTION, and why it is not academic. Our contract says
//  `RequestOptions.signal` "must stop real work". If cancelling the coroutine
//  only stops DELIVERY, then an abandoned request keeps a system service busy,
//  keeps draining the battery, and keeps counting against
//  `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` — while the caller believes it stopped.
//  `ErrorCode.CANCELLED` existing is encouraging but it is inference, not
//  evidence (D4's sibling caveat).
//
//  THE MEASUREMENT. Nothing in userspace can watch AICore, so the question is
//  answered by proxy, with two independent signals:
//
//   (a) DELIVERY — do deltas keep arriving after `cancel()`? Every callback is
//       timestamped, and the last arrival relative to the cancel instant is the
//       measurement. Anything beyond a few hundred milliseconds means delivery
//       itself was not even stopped.
//   (b) DURATION — how long until the `stream()` call actually returns? A
//       cooperative cancellation that reaches the SDK returns almost at once. A
//       call that returns only after roughly as long as an UNCANCELLED
//       generation of the same size took (item 4 measures exactly that, and the
//       number is reused when available) ran the inference to completion and
//       merely threw the output away. That is `stops-delivery-only`.
//
//  A long generation is used so there is plenty of time to cancel in the middle,
//  and the cancel is fired from inside the first delta callback — the earliest
//  possible moment, and the moment a real `AbortSignal` would most plausibly
//  fire.
//
//  Neither signal can prove inference stopped. The verdict says which of the
//  three interpretations the timings support and records the numbers so the
//  judgement is auditable rather than asserted.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import expo.modules.ondevicellmandroid.core.BridgeStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike03CancelSemantics {

  private companion object {
    const val PROMPT =
      "Write a detailed essay of at least six paragraphs about the history of the bicycle, " +
        "covering the draisine, the penny-farthing, the safety bicycle and the derailleur."
    const val MAX_TOKENS = 512

    /** How long to keep listening after the cancel, to catch stragglers. */
    const val OBSERVE_AFTER_CANCEL_MS = 4_000L

    /** How long to wait for `stream()` to return after the cancel. */
    const val JOIN_BUDGET_MS = 30_000L

    /** A return this fast after cancel is only explicable by cooperative teardown. */
    const val PROMPT_RETURN_MS = 1_500L

    /** Deltas arriving later than this after the cancel mean delivery did not stop. */
    const val DELIVERY_GRACE_MS = 500L
  }

  @Test
  fun cancelSemantics() = runSpike("03") { ctx ->
    val engine = ctx.requireBridge() ?: return@runSpike

    val startedAt = System.currentTimeMillis()
    fun elapsed() = System.currentTimeMillis() - startedAt

    val deltas = AtomicInteger(0)
    val deltasAfterCancel = AtomicInteger(0)
    val terminals = AtomicInteger(0)
    val firstDeltaMs = AtomicLong(-1L)
    val lastDeltaMs = AtomicLong(-1L)
    val cancelMs = AtomicLong(-1L)
    val completedMs = AtomicLong(-1L)
    val terminal = AtomicReference("none")
    val jobRef = AtomicReference<Job?>(null)

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val job = scope.launch(start = CoroutineStart.LAZY) {
      engine.stream(
        SpikeBridge.request(
          messages = listOf(SpikeBridge.user(PROMPT)),
          maxOutputTokens = MAX_TOKENS,
        ),
      ) { event ->
        val now = elapsed()
        when (event) {
          is BridgeStreamEvent.Delta -> {
            deltas.incrementAndGet()
            lastDeltaMs.set(now)
            if (firstDeltaMs.compareAndSet(-1L, now)) {
              // Cancel at the earliest moment a caller could have known the
              // stream had started. Doing it from the callback thread is
              // deliberate: it is where a real `AbortSignal` would land.
              cancelMs.set(elapsed())
              jobRef.get()?.cancel()
            } else if (cancelMs.get() >= 0L) {
              deltasAfterCancel.incrementAndGet()
            }
          }

          is BridgeStreamEvent.Finish -> {
            terminals.incrementAndGet()
            terminal.set("finish(${event.result.finishReason})@${now}ms")
          }

          is BridgeStreamEvent.Failure -> {
            terminals.incrementAndGet()
            terminal.set("failure(${event.payload.code})@${now}ms")
          }
        }
      }
    }
    jobRef.set(job)
    job.invokeOnCompletion { completedMs.set(elapsed()) }
    job.start()

    // ---- wait for something to cancel -------------------------------------
    withTimeoutOrNull(SpikeBudget.generateMs) {
      while (cancelMs.get() < 0L && job.isActive) delay(20)
    }

    if (cancelMs.get() < 0L) {
      ctx.put("deltas", deltas.get())
      ctx.put("terminalEvent", terminal.get())
      ctx.put("jobActive", job.isActive)
      job.cancel()
      ctx.verdict(
        Verdict.BLOCKED,
        "no delta ever arrived, so there was nothing to cancel after — terminal event was " +
          "'${terminal.get()}'. Cancellation is unmeasured on this device.",
      )
      return@runSpike
    }

    // ---- listen for stragglers, then wait for the call to return ----------
    delay(OBSERVE_AFTER_CANCEL_MS)
    val returned = withTimeoutOrNull(JOIN_BUDGET_MS) { job.join() } != null

    val cancelAt = cancelMs.get()
    val lastDelta = lastDeltaMs.get()
    val completedAt = completedMs.get()
    val deliveryTailMs = if (lastDelta >= 0L) lastDelta - cancelAt else -1L
    val returnAfterCancelMs = if (completedAt >= 0L) completedAt - cancelAt else -1L

    ctx.put("firstDeltaMs", firstDeltaMs.get())
    ctx.put("cancelAtMs", cancelAt)
    ctx.put("deltasTotal", deltas.get())
    ctx.put("deltasAfterCancel", deltasAfterCancel.get())
    ctx.put("deliveryTailMsAfterCancel", deliveryTailMs)
    ctx.put("streamReturnedAfterCancelMs", returnAfterCancelMs)
    ctx.put("streamReturnedWithinBudget", returned)
    ctx.put("terminalEvent", terminal.get())
    ctx.put("terminalEventCount", terminals.get())
    ctx.put("uncancelledStreamMsFromItem04", SpikeState.fullStreamMs)
    ctx.put("thresholds", mapOf("promptReturnMs" to PROMPT_RETURN_MS, "deliveryGraceMs" to DELIVERY_GRACE_MS))

    if (terminals.get() > 1) {
      ctx.bug("the bridge emitted ${terminals.get()} terminal events; D20 guarantees exactly one")
    }

    val reference = SpikeState.fullStreamMs
    val looksLikeFullRun = reference != null && returnAfterCancelMs >= (reference * 0.6).toLong()

    val semantics: String
    val verdict: Verdict
    val detail: String
    when {
      deltasAfterCancel.get() > 0 && deliveryTailMs > DELIVERY_GRACE_MS -> {
        semantics = "no-stop-observed"
        verdict = Verdict.REFUTED
        detail = "${deltasAfterCancel.get()} deltas arrived up to ${deliveryTailMs}ms AFTER " +
          "cancel(): cancellation stopped neither inference nor delivery in time. The " +
          "contract's `signal` guarantee is not met by coroutine cancellation alone."
      }

      !returned -> {
        semantics = "inconclusive-no-return"
        verdict = Verdict.INCONCLUSIVE
        detail = "delivery went silent ${deliveryTailMs}ms after cancel() but stream() had " +
          "still not returned after ${JOIN_BUDGET_MS}ms — a wedged native frame, which is " +
          "neither of the two answers and is itself worth knowing."
      }

      returnAfterCancelMs in 0..PROMPT_RETURN_MS -> {
        semantics = "stops-inference"
        verdict = Verdict.CONFIRMED
        detail = "delivery stopped within ${deliveryTailMs}ms and stream() returned " +
          "${returnAfterCancelMs}ms after cancel(), with terminal event '${terminal.get()}'. " +
          "A return that fast is only explicable by the cancellation reaching the SDK, so the " +
          "contract's requirement is met as far as userspace can observe. NOTE: no userspace " +
          "signal can see AICore itself; this is the strongest available proxy, not proof."
      }

      looksLikeFullRun -> {
        semantics = "stops-delivery-only"
        verdict = Verdict.REFUTED
        detail = "delivery stopped promptly but stream() took ${returnAfterCancelMs}ms to " +
          "return — comparable to the ${reference}ms an uncancelled stream of the same shape " +
          "took in item 4. The inference ran on and the output was discarded."
      }

      else -> {
        semantics = "inconclusive"
        verdict = Verdict.INCONCLUSIVE
        detail = "delivery stopped ${deliveryTailMs}ms after cancel and stream() returned " +
          "${returnAfterCancelMs}ms after it — too slow to be cooperative teardown, too fast " +
          "to be a full run" +
          (if (reference == null) " (item 4's uncancelled duration was not measured in this run, which would settle it)" else "") +
          ". Re-run items 3 and 4 together."
      }
    }

    ctx.put("semantics", semantics)
    ctx.verdict(verdict, detail)
  }
}
