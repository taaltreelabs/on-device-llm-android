//
//  SpikeMlKitImpl.kt
//
//  **The only file in the spike suite that imports `com.google.mlkit.genai.*`.**
//
//  Same hard rule, same reason, as `GenAiEngine.kt` in the library: this class
//  is instantiated by name through `SpikeMlKitFactory`, after a guarded probe,
//  so no test class ever risks failing to LOAD on a build without the SDK. One
//  ML Kit import in any other file in this directory turns thirteen clean
//  `blocked` verdicts into thirteen JUnit `initializationError`s with no data.
//
//  The client is configured exactly as `GenAiEngine` configures it —
//  `ModelReleaseStage.STABLE`, set explicitly because the ML Kit GenAI Additional
//  Terms forbid `Preview` services in production (research §7) and a beta's
//  default is not a licence guarantee. Same config, so the spike's timings and
//  behaviour transfer to the shipped provider.
//

package expo.modules.ondevicellmandroid.spike

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("unused") // Instantiated reflectively by SpikeMlKitFactory.
class SpikeMlKitImpl : SpikeMlKit {

  private val client: GenerativeModel by lazy {
    val modelConfig = ModelConfig.Builder()
      .apply { releaseStage = ModelReleaseStage.STABLE }
      .build()
    val config = GenerationConfig.Builder()
      .apply { this.modelConfig = modelConfig }
      .build()
    Generation.getClient(config)
  }

  // MARK: - Status

  override suspend fun checkStatusRaw(): Int = client.checkStatus()

  override fun featureStatusConstants(): Map<String, Int> = linkedMapOf(
    "UNAVAILABLE" to FeatureStatus.UNAVAILABLE,
    "DOWNLOADABLE" to FeatureStatus.DOWNLOADABLE,
    "DOWNLOADING" to FeatureStatus.DOWNLOADING,
    "AVAILABLE" to FeatureStatus.AVAILABLE,
  )

  override fun statusName(raw: Int): String = when (raw) {
    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
    FeatureStatus.AVAILABLE -> "AVAILABLE"
    // Register item 6's second half. `checkStatus()` returns a bare Int and four
    // betas shipped in eight months; a fifth state is not a hypothetical.
    else -> "UNKNOWN_STATE_$raw"
  }

  override suspend fun featureFlags(): Map<String, Any?> {
    val flags = LinkedHashMap<String, Any?>()
    flags["isSystemPromptAvailable"] = flag { client.isSystemPromptAvailable() }
    flags["isCachingFeatureAvailable"] = flag { client.isCachingFeatureAvailable() }
    flags["isStructuredOutputFeatureAvailable"] =
      flag { client.isStructuredOutputFeatureAvailable() }
    flags["isThinkingModeAvailable"] = flag { client.isThinkingModeAvailable() }
    return flags
  }

  private suspend fun flag(block: suspend () -> Boolean): Any = try {
    block()
  } catch (throwable: Throwable) {
    SpikeWatch.record("flags", throwable)
    "error:" + classify(throwable)
  }

  // MARK: - Download

  /** Thrown to leave `collect` once a terminal status has arrived. */
  private class DownloadTerminal : Exception()

  override suspend fun download(
    budgetMs: Long,
    progressEveryMs: Long,
    onProgress: (String) -> Unit,
  ): SpikeDownload {
    val started = System.currentTimeMillis()
    var events = 0
    var toDownload: Long? = null
    var downloaded: Long? = null
    var outcome = "no-terminal-event"
    var failure: String? = null
    var lastReport = 0L

    fun elapsed() = System.currentTimeMillis() - started

    try {
      val completed = withTimeoutOrNull(budgetMs) {
        client.download().collect { status ->
          events += 1
          when (status) {
            is DownloadStatus.DownloadStarted -> {
              toDownload = status.bytesToDownload
              lastReport = elapsed()
              onProgress("started bytesToDownload=${status.bytesToDownload} at ${elapsed()}ms")
            }

            is DownloadStatus.DownloadProgress -> {
              downloaded = status.totalBytesDownloaded
              // Every chunk would flood logcat on a multi-hundred-megabyte
              // download; every 15 seconds is enough to tell a slow download
              // from a stalled one, which is the distinction that matters
              // (research §11.2: models stuck downloading indefinitely).
              if (elapsed() - lastReport >= progressEveryMs) {
                lastReport = elapsed()
                onProgress(
                  "progress bytes=${status.totalBytesDownloaded}" +
                    (toDownload?.let { "/$it" } ?: "") + " at ${elapsed()}ms",
                )
              }
            }

            is DownloadStatus.DownloadCompleted -> {
              outcome = "completed"
              onProgress("completed at ${elapsed()}ms")
              throw DownloadTerminal()
            }

            is DownloadStatus.DownloadFailed -> {
              outcome = "failed"
              failure = errorInfo(status.e).let { "${it.codeName ?: it.rawCode}: ${it.message}" }
              onProgress("failed at ${elapsed()}ms: $failure")
              throw DownloadTerminal()
            }

            // The compiler warns that this `else` is redundant because
            // `DownloadStatus` is sealed and the four branches above are
            // exhaustive. It is redundant at COMPILE time and not at runtime: the
            // sealed hierarchy we compiled against is beta4's, four betas have
            // shipped in eight months, and the app is linked against whatever the
            // consumer resolved. A fifth subclass must be reported, not crash.
            else -> {
              onProgress("unrecognised status ${status.javaClass.name} at ${elapsed()}ms")
            }
          }
        }
      }
      if (completed == null && outcome == "no-terminal-event") {
        outcome = "timeout"
        onProgress("budget of ${budgetMs}ms exhausted with no terminal status")
      }
    } catch (terminal: DownloadTerminal) {
      // Expected: the only way out of a hot flow.
    } catch (throwable: Throwable) {
      SpikeWatch.record("download", throwable)
      outcome = "threw"
      failure = classify(throwable)
      onProgress("threw at ${elapsed()}ms: $failure")
    }

    return SpikeDownload(
      outcome = outcome,
      events = events,
      bytesToDownload = toDownload,
      bytesDownloaded = downloaded,
      ms = elapsed(),
      failure = failure,
    )
  }

  // MARK: - Capability values

  override suspend fun tokenLimit(): Int = client.getTokenLimit()

  override suspend fun baseModelName(): String = client.getBaseModelName()

  override suspend fun isSystemPromptAvailable(): Boolean = client.isSystemPromptAvailable()

  override suspend fun warmup() {
    client.warmup()
  }

  // MARK: - Requests

  override suspend fun countTokens(contents: List<String>, systemInstruction: String?): Int =
    client.countTokens(request(contents, systemInstruction, null, null)).totalTokens

  override suspend fun generate(
    contents: List<String>,
    systemInstruction: String?,
    temperature: Double?,
    maxOutputTokens: Int?,
  ): SpikeGeneration {
    val started = System.currentTimeMillis()
    val response = client.generateContent(
      request(contents, systemInstruction, temperature, maxOutputTokens),
    )
    return generation(response, System.currentTimeMillis() - started, null, 0)
  }

  override suspend fun stream(
    contents: List<String>,
    systemInstruction: String?,
    maxOutputTokens: Int?,
    onChunk: (String) -> Unit,
  ): SpikeGeneration {
    val started = System.currentTimeMillis()
    var firstChunkMs: Long? = null
    var chunks = 0
    val callback = StreamingCallback { chunk ->
      if (firstChunkMs == null) firstChunkMs = System.currentTimeMillis() - started
      chunks += 1
      onChunk(chunk)
    }
    val response = client.generateContent(
      request(contents, systemInstruction, null, maxOutputTokens),
      callback,
    )
    return generation(response, System.currentTimeMillis() - started, firstChunkMs, chunks)
  }

  /**
   * Built exactly as `GenAiEngine.nativeRequestFrom` builds it, so what the
   * spike measures is what the provider sends. The one difference is the
   * source of the strings: here they come straight from a test, there from
   * `PromptEncoding`. Tests that care about the encoding get their strings from
   * `PromptEncoding` too.
   */
  private fun request(
    contents: List<String>,
    systemInstruction: String?,
    temperature: Double?,
    maxOutputTokens: Int?,
  ): GenerateContentRequest {
    val parts = contents.map { Content.Builder().text(it).build() }
    val builder = GenerateContentRequest.Builder(parts)
    systemInstruction?.let { builder.systemInstruction = SystemInstruction(it) }
    temperature?.let { builder.temperature = it.toFloat() }
    maxOutputTokens?.let { builder.maxOutputTokens = it }
    return builder.build()
  }

  private fun generation(
    response: GenerateContentResponse,
    ms: Long,
    firstChunkMs: Long?,
    chunks: Int,
  ): SpikeGeneration {
    val candidate = response.candidates.firstOrNull()
    return SpikeGeneration(
      text = candidate?.text ?: "",
      finishReason = finishName(candidate),
      ms = ms,
      firstChunkMs = firstChunkMs,
      chunks = chunks,
    )
  }

  private fun finishName(candidate: Candidate?): String {
    val reason = candidate?.finishReason
    return when (reason) {
      null -> "none"
      Candidate.FinishReason.STOP -> "stop"
      Candidate.FinishReason.MAX_TOKENS -> "length"
      else -> "other:$reason"
    }
  }

  // MARK: - Errors

  override fun errorInfo(throwable: Throwable): SpikeErrorInfo {
    val genAi = findGenAi(throwable)
    val raw = genAi?.let { readErrorCode(it) }
    return SpikeErrorInfo(
      isGenAi = genAi != null,
      rawCode = raw,
      codeName = raw?.let { errorCodeNames[it] ?: "UNRECOGNISED_$it" },
      retryDelayMs = genAi?.let { readRetryDelayMs(it) },
      throwableClass = throwable.javaClass.name,
      message = (genAi ?: throwable).message,
      linkage = SpikeWatch.isLinkage(throwable),
    )
  }

  /** Bounded cause walk, as `GenAiEngine.findGenAiException` does it. */
  private fun findGenAi(throwable: Throwable): GenAiException? {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < 8) {
      if (current is GenAiException) return current
      current = current.cause?.takeIf { it !== current }
      depth += 1
    }
    return null
  }

  private fun readErrorCode(exception: GenAiException): Int? = try {
    exception.errorCode
  } catch (throwable: Throwable) {
    null
  }

  private fun readRetryDelayMs(exception: GenAiException): Long? = try {
    @Suppress("USELESS_ELVIS")
    exception.retryDelay?.toMillis()
  } catch (throwable: Throwable) {
    null
  }

  /**
   * int -> name, the inverse of `GenAiEngine.errorCodeOf`.
   *
   * Reading the constants (rather than hard-coding numbers) is deliberate: it
   * means a constant removed or renamed in a future beta is a compile error
   * here, exactly as in the library, and it makes this map a second, independent
   * reading of the same table that register item 9 is checking.
   */
  private val errorCodeNames: Map<Int, String> by lazy {
    linkedMapOf(
      GenAiException.ErrorCode.UNKNOWN to "UNKNOWN",
      GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR to "REQUEST_PROCESSING_ERROR",
      GenAiException.ErrorCode.CANCELLED to "CANCELLED",
      GenAiException.ErrorCode.NOT_AVAILABLE to "NOT_AVAILABLE",
      GenAiException.ErrorCode.BUSY to "BUSY",
      GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR to "RESPONSE_PROCESSING_ERROR",
      GenAiException.ErrorCode.REQUEST_TOO_LARGE to "REQUEST_TOO_LARGE",
      GenAiException.ErrorCode.REQUEST_TOO_SMALL to "REQUEST_TOO_SMALL",
      GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR to "RESPONSE_GENERATION_ERROR",
      GenAiException.ErrorCode.NOT_SUPPORTED to "NOT_SUPPORTED",
      GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to
        "PER_APP_BATTERY_USE_QUOTA_EXCEEDED",
      GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED to "BACKGROUND_USE_BLOCKED",
      GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE to "NOT_ENOUGH_DISK_SPACE",
      GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE to "NEEDS_SYSTEM_UPDATE",
      GenAiException.ErrorCode.AICORE_INCOMPATIBLE to "AICORE_INCOMPATIBLE",
      GenAiException.ErrorCode.INVALID_INPUT_IMAGE to "INVALID_INPUT_IMAGE",
      GenAiException.ErrorCode.CACHE_PROCESSING_ERROR to "CACHE_PROCESSING_ERROR",
      GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR to
        "STRUCTURED_OUTPUT_REQUEST_ERROR",
      GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR to
        "STRUCTURED_OUTPUT_RESPONSE_ERROR",
      GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR to
        "STRUCTURED_OUTPUT_MAX_TOKENS_ERROR",
      GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW to "AUDIO_BUFFER_OVERFLOW",
    )
  }

  private fun classify(throwable: Throwable): String {
    if (SpikeWatch.isLinkage(throwable)) {
      return "linkage:${throwable.javaClass.simpleName}:${(throwable.message ?: "").take(120)}"
    }
    val info = errorInfo(throwable)
    return if (info.isGenAi) {
      "genai:${info.codeName ?: info.rawCode}:${(info.message ?: "").take(120)}"
    } else {
      "other:${throwable.javaClass.simpleName}:${(throwable.message ?: "").take(120)}"
    }
  }

  // MARK: - Register item 10: does every call actually dispatch?

  override suspend fun dispatchProbe(): Map<String, String> {
    val out = LinkedHashMap<String, String>()

    // Compile-time constants. A metadata mismatch that changed a companion's
    // shape shows up here as NoSuchFieldError, not as a wrong answer.
    out["const.FeatureStatus"] = attempt { featureStatusConstants().toString() }
    out["const.ErrorCode"] = attempt { "${errorCodeNames.size} codes" }
    out["const.FinishReason"] =
      attempt { "${Candidate.FinishReason.STOP},${Candidate.FinishReason.MAX_TOKENS}" }
    out["const.ModelReleaseStage"] = attempt { ModelReleaseStage.STABLE.toString() }

    // Builders and property setters — the Kotlin-property-vs-setter shape is
    // exactly what @Metadata describes, so these are the calls that would break
    // if -Xskip-metadata-version-check were papering over a real difference.
    out["build.request"] = attempt {
      request(listOf("ping"), "be brief", 0.5, 8).contents.size.toString()
    }
    out["build.systemInstruction"] = attempt { SystemInstruction("x").textString }
    out["build.textPart"] = attempt { Content.Builder().text("x").build().parts.size.toString() }

    // Suspend calls. A `genai:` outcome here is a device answer, not a
    // dispatch failure, and the verdict logic treats it as such.
    out["call.checkStatus"] = attemptSuspend { statusName(client.checkStatus()) }
    out["call.getTokenLimit"] = attemptSuspend { client.getTokenLimit().toString() }
    out["call.getBaseModelName"] = attemptSuspend { client.getBaseModelName() }
    out["call.isSystemPromptAvailable"] =
      attemptSuspend { client.isSystemPromptAvailable().toString() }
    out["call.countTokens"] = attemptSuspend {
      client.countTokens(request(listOf("ping"), null, null, null)).totalTokens.toString()
    }
    out["call.warmup"] = attemptSuspend { client.warmup(); "unit" }

    return out
  }

  private inline fun attempt(block: () -> String): String = try {
    "ok:" + block().take(60)
  } catch (throwable: Throwable) {
    SpikeWatch.record("10", throwable)
    classify(throwable)
  }

  private suspend fun attemptSuspend(block: suspend () -> String): String = try {
    "ok:" + block().take(60)
  } catch (throwable: Throwable) {
    SpikeWatch.record("10", throwable)
    classify(throwable)
  }

  // MARK: - Teardown

  override fun close() {
    try {
      client.close()
    } catch (throwable: Throwable) {
      // Nothing to do about a failed close at the end of a run.
    }
  }
}
