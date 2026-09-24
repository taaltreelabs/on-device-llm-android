//
//  SpikeRunner.kt — the wrapper that makes "always passes, always reports" true
//
//  Walk the failure modes this has to survive on a device nobody can watch:
//
//   * ML Kit classes absent (consumer opt-in missing, or R8 removed them)
//       -> `SpikeMlKitFactory` returns null, the test reports `blocked`.
//   * AICore absent / device not on the allowlist
//       -> `GenAiException`, caught, reported with its real code.
//   * A download that never finishes
//       -> the download loop has its own budget and reports `download-timeout`.
//   * A generation that throws
//       -> caught here, mapped through the REAL `ErrorMapping` for fidelity.
//   * A generation that neither returns nor throws — the wedged native call,
//     which §11.2 documents in the wild
//       -> the body runs in its own job; the runner abandons it at the budget
//          and still emits a verdict. This is why the body is not simply
//          `runBlocking { withTimeout { … } }`: `withTimeout` waits for its
//          child to acknowledge cancellation, and a wedged native frame never
//          does. `join()` with a timeout, then walk away.
//   * A linkage error from an unexpected class
//       -> caught, recorded in `SpikeWatch`, reported by item 11.
//
//  Anything left is a bug in this harness, and `SpikeHarnessBug` is how it says
//  so — after emitting its verdict, never instead of it.
//

package expo.modules.ondevicellmandroid.spike

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Mutable verdict-in-progress. One per test. */
class SpikeCtx(val item: String) {
  val data: LinkedHashMap<String, Any?> = LinkedHashMap()

  var verdict: Verdict = Verdict.INCONCLUSIVE
    private set

  var detail: String = "the test body recorded no verdict"
    private set

  fun put(key: String, value: Any?) {
    data[key] = value
  }

  fun verdict(verdict: Verdict, detail: String) {
    this.verdict = verdict
    this.detail = detail
  }

  /** Record a caught throwable as data without deciding the verdict. */
  fun note(key: String, throwable: Throwable) {
    SpikeWatch.record(item, throwable)
    put(key, throwableSummary(throwable))
  }

  fun bug(message: String): Nothing = throw SpikeHarnessBug("[$item] $message")
}

fun throwableSummary(throwable: Throwable): Map<String, Any?> = linkedMapOf(
  "class" to throwable.javaClass.name,
  "message" to (throwable.message ?: ""),
  "linkage" to SpikeWatch.isLinkage(throwable),
  "cause" to (throwable.cause?.javaClass?.name ?: ""),
)

/**
 * Run one register item and emit exactly one verdict.
 *
 * @param item the register number, zero-padded.
 * @param budgetMs the point at which the body is abandoned rather than waited
 *   for. Generous by default, because a blocked run is cheap and a lost run is
 *   not: the enemy is a suite that dies silently at test 3 of 13.
 */
fun runSpike(
  item: String,
  budgetMs: Long = SpikeBudget.testMs,
  body: suspend (SpikeCtx) -> Unit,
) {
  val ctx = SpikeCtx(item)
  val started = System.currentTimeMillis()
  var harnessBug: SpikeHarnessBug? = null

  try {
    runBlocking {
      // A scope that is NOT this coroutine's child, so abandoning it cannot
      // keep `runBlocking` from returning.
      val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      val work = scope.async { body(ctx) }
      val finished = withTimeoutOrNull(budgetMs) {
        runCatching { work.await() }
      }
      if (finished == null) {
        ctx.put("abandonedAtMs", budgetMs)
        work.cancel()
        if (ctx.verdict == Verdict.INCONCLUSIVE) {
          ctx.verdict(
            Verdict.BLOCKED,
            "abandoned after ${budgetMs}ms without a verdict — a native call did not " +
              "return; raise -e spikeTestBudgetMs if this device is merely slow",
          )
        }
      } else {
        finished.exceptionOrNull()?.let { thrown ->
          when (thrown) {
            is SpikeHarnessBug -> {
              // A harness bug still owes a verdict — it is emitted first and the
              // JUnit failure comes after, so the run never loses the record of
              // what the harness was doing when it decided it was broken.
              harnessBug = thrown
              ctx.put("harnessBug", thrown.message ?: "")
              ctx.verdict(
                Verdict.INCONCLUSIVE,
                "HARNESS BUG — this test also fails JUnit-style, which no device condition can " +
                  "cause: ${thrown.message}",
              )
            }
            else -> {
              ctx.note("uncaught", thrown)
              if (ctx.verdict == Verdict.INCONCLUSIVE) {
                ctx.verdict(
                  Verdict.BLOCKED,
                  "the test body threw ${thrown.javaClass.simpleName}: " +
                    (thrown.message ?: "(no message)"),
                )
              }
            }
          }
        }
      }
    }
  } catch (throwable: Throwable) {
    // runBlocking itself failing is not a thing we expect; report, never crash.
    ctx.note("runnerThrew", throwable)
    ctx.verdict(
      Verdict.BLOCKED,
      "the spike runner threw ${throwable.javaClass.simpleName}: ${throwable.message}",
    )
  }

  ctx.put("linkageErrorsSoFar", SpikeWatch.linkageCount())
  SpikeReport.emit(
    item = item,
    verdict = ctx.verdict,
    detail = ctx.detail,
    data = ctx.data,
    ms = System.currentTimeMillis() - started,
  )

  // The one permitted JUnit failure, and only after the verdict is safely out.
  val bug = harnessBug
  if (bug != null) throw bug
}

/**
 * Get the ML Kit access object, or emit a `blocked` verdict and return null.
 *
 * Centralised because "the dependency is not in this build" has to produce the
 * same clean verdict in all thirteen tests, and because on the T1 release run it
 * is the single most likely thing to happen.
 */
fun SpikeCtx.requireMlKit(): SpikeMlKit? {
  val mlKit = SpikeMlKitFactory.shared
  if (mlKit == null) {
    put("mlKitClassesPresent", SpikeMlKitFactory.classesPresent)
    put("mlKitFactoryError", SpikeMlKitFactory.lastError ?: "")
    verdict(
      Verdict.BLOCKED,
      if (SpikeMlKitFactory.classesPresent) {
        "ML Kit classes are present but the spike's SDK adapter could not be built — " +
          "see mlKitFactoryError (on a minified build, suspect R8)"
      } else {
        "com.google.mlkit.genai.prompt.Generation is not in this build: the app's " +
          "`implementation` line is missing, or R8 removed it"
      },
    )
    return null
  }
  return mlKit
}
