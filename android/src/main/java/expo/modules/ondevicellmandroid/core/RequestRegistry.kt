//
//  RequestRegistry.kt
//  OnDeviceLlm — Android
//
//  Cancellation keyed by the request id JavaScript generated — the counterpart
//  of `ios/Core/RequestRegistry.swift`.
//
//  ML Kit offers no `stop()`. `generateContent` is a `suspend fun` and
//  `generateContentStream` returns a `Flow` built over a channel, so the only
//  lever is idiomatic coroutine cancellation: cancel the `Job` driving the call
//  and the suspension point throws `CancellationException`
//  (docs/research/android-genai.md §2). The bridge therefore has to hold that
//  `Job` for as long as the request is in flight.
//
//  ############################################################################
//  # PROVISIONAL — PENDING HARDWARE                                           #
//  #                                                                          #
//  # Whether cancelling the Job actually stops AICore inference, or merely    #
//  # stops delivery while the NPU keeps working, is **unverified**. Our       #
//  # contract (`RequestOptions.signal`, "must stop real work") requires the   #
//  # former. `GenAiException.ErrorCode.CANCELLED` existing at all is          #
//  # encouraging — it implies the service has a notion of a cancelled request #
//  # rather than a client that stopped listening — but that is inference, not #
//  # evidence. Device test required (§2).                                     #
//  ############################################################################
//
//  A `synchronized` map rather than an actor: Kotlin has no actors, concurrent
//  streams and their cancellations mutate this from arbitrary dispatcher
//  threads, and the three operations that matter (`register`, `cancel`,
//  `finish`) must each be atomic with respect to the others. `ConcurrentHashMap`
//  alone would not do — `register` has to cancel a displaced entry, which is a
//  read-modify-write.
//

package expo.modules.ondevicellmandroid.core

import kotlinx.coroutines.Job

class RequestRegistry {
  private val lock = Any()
  private val jobs = mutableMapOf<String, Job>()

  /**
   * Register the job driving `requestId`.
   *
   * If the id is already registered — a caller reusing an id — the previous
   * request is cancelled first. Leaking a running generation would keep the NPU
   * busy with work nobody is listening to, and on a device with a battery quota
   * (`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`) that is not a theoretical cost.
   */
  fun register(requestId: String, job: Job) {
    val displaced = synchronized(lock) {
      val previous = jobs[requestId]
      jobs[requestId] = job
      previous
    }
    displaced?.cancel()
  }

  /**
   * Cancel a request. Returns `false` when the id is unknown, which is the
   * normal outcome of a cancel that races a natural completion — not an error.
   */
  fun cancel(requestId: String): Boolean {
    val job = synchronized(lock) { jobs.remove(requestId) } ?: return false
    job.cancel()
    return true
  }

  /** Drop a finished request without cancelling it. */
  fun finish(requestId: String) {
    synchronized(lock) { jobs.remove(requestId) }
  }

  /**
   * Cancel everything. Used when the module is torn down: a JS reload leaves
   * generations running otherwise, and nothing is listening for their events.
   */
  fun cancelAll() {
    val all = synchronized(lock) {
      val snapshot = jobs.values.toList()
      jobs.clear()
      snapshot
    }
    all.forEach { it.cancel() }
  }

  val activeCount: Int
    get() = synchronized(lock) { jobs.size }
}
