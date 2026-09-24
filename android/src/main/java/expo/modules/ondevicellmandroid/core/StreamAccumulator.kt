//
//  StreamAccumulator.kt
//  OnDeviceLlm — Android
//
//  The delta tripwire — the Android counterpart of `ios/Core/SnapshotDiffer.swift`,
//  and deliberately *not* the same thing.
//
//  Apple's `ResponseStream` yields cumulative snapshots, so the Apple provider
//  must diff them (DECISIONS.md D5/D18). Android is the opposite and that is
//  the single best piece of news in the recon: the internal adapter `zzyp`
//  implements `StreamingCallback.onNewText(String)` and its bytecode wraps the
//  incoming `String` straight into a fresh `Candidate` and pushes it to a
//  channel — **no `StringBuilder`, no `append`, no accumulator field anywhere
//  in the method** (docs/research/android-genai.md §2). Each chunk is the newly
//  generated text.
//
//  So deltas are forwarded **as-is**. No diffing, no accumulation, no
//  transformation of any kind: the whole point of the finding is that the
//  conversion Apple needs is unnecessary here, and re-introducing it would be a
//  cost paid for nothing plus a second place for the two platforms to disagree.
//
//  ############################################################################
//  # PROVISIONAL — PENDING HARDWARE                                           #
//  #                                                                          #
//  # The finding is a *static* one, read from bytecode. It has never been     #
//  # observed end to end, because AICore runs on no emulator (§6). Chunk      #
//  # granularity — token, word, sentence, or one chunk for the whole response #
//  # — is also entirely unknown.                                              #
//  #                                                                          #
//  # This class therefore keeps the *posture* D18 takes without the           #
//  # behaviour: it watches for the signature of a cumulative stream and flags  #
//  # it on the wire as `reset`, exactly as D18 says such a case must stay      #
//  # observable rather than be swallowed. It never rewrites a chunk. If the    #
//  # flag ever fires on real hardware we will know within one request, from    #
//  # the payload the consumer already receives, instead of guessing at a       #
//  # duplicated-text bug report.                                              #
//  ############################################################################
//

package expo.modules.ondevicellmandroid.core

/**
 * Tracks what a stream has emitted so far, purely to answer one question:
 * *does this chunk look like a delta, or like a snapshot?*
 *
 * Not thread-safe by itself; `GenAiEngine` confines one instance to one
 * request and serialises callback delivery through it.
 */
class StreamAccumulator {
  private val concatenated = StringBuilder()

  /** `true` once a chunk has been seen that looks cumulative rather than incremental. */
  var suspectedSnapshotStream: Boolean = false
    private set

  /** Every chunk, joined. What the final text *would* be if the deltas are real deltas. */
  val joined: String
    get() = concatenated.toString()

  /** How many chunks have arrived. */
  var chunkCount: Int = 0
    private set

  /**
   * Record a chunk and report whether it should be flagged `reset` on the wire.
   *
   * A chunk that starts with everything emitted so far — where "so far" is
   * non-empty and the chunk is strictly longer — is the signature of a
   * cumulative snapshot: a true delta has no reason to repeat text the consumer
   * already has. It is not proof (a model could legitimately repeat itself), so
   * the chunk is still forwarded untouched; the flag is a report, not a repair.
   *
   * @return the value to put on the wire as `reset`.
   */
  fun accept(chunk: String): Boolean {
    val prior = concatenated.toString()
    val looksCumulative = prior.isNotEmpty() &&
      chunk.length > prior.length &&
      chunk.startsWith(prior)
    if (looksCumulative) {
      suspectedSnapshotStream = true
    }
    concatenated.append(chunk)
    chunkCount += 1
    return looksCumulative
  }
}
