//
//  SpikeMlKit.kt — the spike's SDK access, in pure types
//
//  The library confines every `com.google.mlkit.genai.*` reference to
//  `GenAiEngine.kt` and reaches it by name, because a class that references an
//  absent type fails when it is LOADED, not when it is called (D2). The spike
//  needs the same discipline for the same reason — in the T1 release run it is
//  the likeliest thing to go wrong — so this file declares the surface in
//  primitives, strings and lambdas, `SpikeMlKitImpl.kt` is the only file in the
//  suite that imports ML Kit, and no test class ever names an ML Kit type.
//
//  If that rule is broken, the failure mode is nasty and specific: the TEST
//  CLASS fails to load, JUnit reports `initializationError`, and no verdict is
//  emitted at all — the one outcome the reporting contract forbids. Keep the
//  imports where they are.
//
//  Why the spike talks to the SDK directly at all, rather than only through
//  `GenAiBridge`: three of the register's questions are about surface the bridge
//  deliberately does not expose. `download()` is not called by the provider at
//  all (D3 — `availability()` is a question, not a command), the raw
//  `checkStatus()` int is mapped away before the bridge returns it (item 6 asks
//  what the raw value is), and per-chunk arrival timing is not on the wire. The
//  rule the suite follows is: measure through the bridge wherever the bridge can
//  answer, so verdicts transfer to shipped behaviour, and reach past it only for
//  what it does not carry.
//

package expo.modules.ondevicellmandroid.spike

/** One generation, described without an ML Kit type. */
data class SpikeGeneration(
  val text: String,
  val finishReason: String,
  val ms: Long,
  val firstChunkMs: Long?,
  val chunks: Int,
) {
  fun summary(textChars: Int = 160): Map<String, Any?> = linkedMapOf(
    "chars" to text.length,
    "finishReason" to finishReason,
    "ms" to ms,
    "firstChunkMs" to firstChunkMs,
    "chunks" to chunks,
    "text" to text.take(textChars),
  )
}

/** The outcome of `download()`, described without an ML Kit type. */
data class SpikeDownload(
  /** `completed` · `failed` · `timeout` · `no-terminal-event` · `threw` */
  val outcome: String,
  val events: Int,
  val bytesToDownload: Long?,
  val bytesDownloaded: Long?,
  val ms: Long,
  val failure: String?,
) {
  fun summary(): Map<String, Any?> = linkedMapOf(
    "outcome" to outcome,
    "events" to events,
    "bytesToDownload" to bytesToDownload,
    "bytesDownloaded" to bytesDownloaded,
    "ms" to ms,
    "failure" to (failure ?: ""),
  )
}

/**
 * A throwable, as the SDK describes it.
 *
 * [rawCode] and [codeName] are the raw `GenAiException.getErrorCode()` and its
 * symbolic name. Register item 9 is precisely a question about these two
 * numbers, so they are reported as the SDK gives them, never as the taxonomy
 * sees them — the taxonomy's view comes separately, from the real
 * `ErrorMapping` through `GenAiBridge.mapThrowable`, and comparing the two is
 * the measurement.
 */
data class SpikeErrorInfo(
  val isGenAi: Boolean,
  val rawCode: Int?,
  val codeName: String?,
  val retryDelayMs: Long?,
  val throwableClass: String,
  val message: String?,
  val linkage: Boolean,
) {
  fun summary(): Map<String, Any?> = linkedMapOf(
    "isGenAi" to isGenAi,
    "rawCode" to rawCode,
    "codeName" to (codeName ?: ""),
    "retryDelayMs" to retryDelayMs,
    "throwable" to throwableClass,
    "message" to (message ?: "").take(300),
    "linkage" to linkage,
  )
}

/**
 * The SDK surface the spike drives, in types that cannot fail to load.
 *
 * Every method either returns a plain value or throws; nothing here swallows a
 * failure, because deciding what a failure MEANS is the individual test's job
 * and a swallowed exception is a lost measurement.
 */
interface SpikeMlKit {
  suspend fun checkStatusRaw(): Int

  /** `FeatureStatus`'s four constants, so a renumbering in a future beta is visible. */
  fun featureStatusConstants(): Map<String, Int>

  fun statusName(raw: Int): String

  /** `isSystemPromptAvailable` / caching / structured output / thinking mode. */
  suspend fun featureFlags(): Map<String, Any?>

  suspend fun download(
    budgetMs: Long,
    progressEveryMs: Long,
    onProgress: (String) -> Unit,
  ): SpikeDownload

  suspend fun tokenLimit(): Int

  suspend fun baseModelName(): String

  suspend fun isSystemPromptAvailable(): Boolean

  suspend fun warmup()

  suspend fun countTokens(contents: List<String>, systemInstruction: String?): Int

  suspend fun generate(
    contents: List<String>,
    systemInstruction: String?,
    temperature: Double?,
    maxOutputTokens: Int?,
  ): SpikeGeneration

  suspend fun stream(
    contents: List<String>,
    systemInstruction: String?,
    maxOutputTokens: Int?,
    onChunk: (String) -> Unit,
  ): SpikeGeneration

  fun errorInfo(throwable: Throwable): SpikeErrorInfo

  /**
   * Register item 10. Touch every kind of SDK surface `GenAiEngine` uses —
   * suspend calls, builder property setters, int constants, enum constants —
   * and report per-surface outcomes, separating LINKAGE failures (which are what
   * `-Xskip-metadata-version-check` failing at runtime actually looks like) from
   * `GenAiException`s (which are merely a device saying no).
   */
  suspend fun dispatchProbe(): Map<String, String>

  fun close()
}

/**
 * Builds [SpikeMlKit] the way `MlKitPresence` builds the engine: probe by name,
 * then instantiate by name.
 *
 * On a build where the consumer's `implementation` line is missing — or where
 * R8 removed the class — this returns null and every test reports a clean
 * `blocked`. That is the same code path the shipped provider takes, so a null
 * here is itself a finding about the firewall.
 */
object SpikeMlKitFactory {
  private const val ENTRY_CLASS = "com.google.mlkit.genai.prompt.Generation"
  private const val IMPL_CLASS = "expo.modules.ondevicellmandroid.spike.SpikeMlKitImpl"

  @Volatile
  var lastError: String? = null
    private set

  val classesPresent: Boolean by lazy {
    try {
      Class.forName(ENTRY_CLASS, false, SpikeMlKitFactory::class.java.classLoader)
      true
    } catch (throwable: Throwable) {
      lastError = "${throwable.javaClass.name}: ${throwable.message}"
      false
    }
  }

  /**
   * The process-wide instance. One `GenerativeModel` for the whole run, which is
   * what the provider does too (`GenerativeModel` is a connection to a system
   * service, not a conversation), so per-test clients would measure client
   * construction rather than the model.
   */
  val shared: SpikeMlKit? by lazy { fresh() }

  /** A brand-new client — only item 7 wants one, to get a colder first token. */
  fun fresh(): SpikeMlKit? {
    if (!classesPresent) return null
    return forceFresh()
  }

  /**
   * Build the adapter WITHOUT consulting the name probe first.
   *
   * Used by one test, item 11, to separate two states that look identical from
   * the outside and mean opposite things on a minified build:
   *
   *   * `Class.forName("…prompt.Generation")` fails because the consumer never
   *     added the dependency — the firewall working exactly as designed.
   *   * `Class.forName` fails because R8 RENAMED the class, while the spike's own
   *     direct bytecode references were rewritten to match and therefore still
   *     work. The classes are right there; only the name the probe asks for is
   *     gone. That is tripwire T1 firing, and without this discriminator it would
   *     be reported as a missing dependency and nobody would ever look.
   */
  fun forceFresh(): SpikeMlKit? = try {
    Class.forName(IMPL_CLASS).getDeclaredConstructor().newInstance() as SpikeMlKit
  } catch (throwable: Throwable) {
    lastError = "${throwable.javaClass.name}: ${throwable.message}"
    null
  }
}
