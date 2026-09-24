//
//  Spike00ViabilityProbe.kt — "is Firebase Test Lab usable for this at all?"
//
//  RUN THIS ALONE FIRST. It is the only test in the suite whose answer decides
//  whether running the other twelve is worth anything:
//
//      --test-targets "class expo.modules.ondevicellmandroid.spike.Spike00ViabilityProbe"
//
//  AICore is a preinstalled system service on an allowlisted device (research
//  §6). A Test Lab physical Pixel is an allowlisted *model*, which is not the
//  same claim: the farm image may carry no Gemini Nano assets, the feature may
//  be region- or account-gated, and a device whose bootloader state Google does
//  not like is documented as unsupported. Nobody knows until a device answers.
//
//  Four outcomes, recorded in `data.outcome`:
//
//   * `farm-viable`                 — AVAILABLE on arrival, and a generation ran.
//   * `needs-download-and-downloaded` — DOWNLOADABLE, downloaded inside the
//                                     budget, and then a generation ran. Every
//                                     later run on this model pays the same cost
//                                     unless Test Lab caches device state, which
//                                     it does not.
//   * `download-timeout`            — the budget expired. §11.2 documents models
//                                     stuck downloading indefinitely with nothing
//                                     reported to the app; this is that, observed.
//   * `unavailable`                 — UNAVAILABLE, or `checkStatus()` itself
//                                     threw. The farm cannot answer the register.
//
//  Only the first two are `confirmed`. The other two are `blocked`: they say
//  nothing about the provider and everything about the device.
//

package expo.modules.ondevicellmandroid.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spike00ViabilityProbe {

  @Test
  fun viability() = runSpike("00", SpikeBudget.probeMs) { ctx ->
    val mlKit = ctx.requireMlKit() ?: return@runSpike
    ctx.put("featureStatusConstants", mlKit.featureStatusConstants())
    ctx.put("downloadBudgetMs", SpikeBudget.downloadMs)

    // ---- 1. checkStatus ---------------------------------------------------
    val raw = try {
      mlKit.checkStatusRaw()
    } catch (throwable: Throwable) {
      ctx.recordError("checkStatusError", mlKit, SpikeBridge.engine, throwable)
      ctx.put("outcome", "unavailable")
      SpikeState.viable = false
      ctx.verdict(
        Verdict.BLOCKED,
        "checkStatus() threw ${throwable.javaClass.simpleName} — AICore could not even be " +
          "asked on this device, so nothing in the register can be measured here",
      )
      return@runSpike
    }
    var status = mlKit.statusName(raw)
    ctx.put("statusRaw", raw)
    ctx.put("status", status)
    SpikeState.statusRaw = raw
    SpikeState.statusName = status
    SpikeReport.progress("00", "checkStatus() = $raw ($status)")

    // ---- 2. download, if the assets are merely absent ----------------------
    if (status == "DOWNLOADABLE" || status == "DOWNLOADING") {
      SpikeReport.progress("00", "status is $status; triggering download() with a ${SpikeBudget.downloadMs}ms budget")
      val download = mlKit.download(
        budgetMs = SpikeBudget.downloadMs,
        progressEveryMs = SpikeBudget.downloadProgressEveryMs,
      ) { line -> SpikeReport.progress("00", line) }
      ctx.put("download", download.summary())

      if (download.outcome != "completed") {
        ctx.put("outcome", if (download.outcome == "timeout") "download-timeout" else download.outcome)
        SpikeState.viable = false
        ctx.verdict(
          Verdict.BLOCKED,
          "download() ended as '${download.outcome}' after ${download.ms}ms with " +
            "${download.events} status events" +
            (download.failure?.let { " ($it)" } ?: "") +
            " — the farm cannot reach a usable model within the budget",
        )
        return@runSpike
      }

      status = try {
        val after = mlKit.checkStatusRaw()
        ctx.put("statusRawAfterDownload", after)
        mlKit.statusName(after)
      } catch (throwable: Throwable) {
        ctx.recordError("checkStatusAfterDownloadError", mlKit, SpikeBridge.engine, throwable)
        "UNKNOWN_AFTER_DOWNLOAD"
      }
      ctx.put("statusAfterDownload", status)
    }

    if (status == "UNAVAILABLE") {
      ctx.put("outcome", "unavailable")
      SpikeState.viable = false
      ctx.verdict(
        Verdict.BLOCKED,
        "checkStatus() reports UNAVAILABLE: this device is not on AICore's allowlist, or " +
          "its bootloader state disqualifies it (research §6). Nothing else in the " +
          "register can be measured on this model.",
      )
      return@runSpike
    }

    if (status != "AVAILABLE") {
      ctx.put("outcome", "unavailable")
      SpikeState.viable = false
      ctx.verdict(
        Verdict.INCONCLUSIVE,
        "status settled at '$status' rather than AVAILABLE — if that is a state name " +
          "this build does not know, register item 6's second half just fired",
      )
      return@runSpike
    }

    // ---- 3. one tiny generation, through the shipped code path -------------
    //
    // "Say OK" with a small output budget is the cheapest possible proof that
    // AVAILABLE means available — which is exactly what D9 was learned about on
    // Apple and what §11.1 documents happening here (`AVAILABLE` then
    // `Feature not available` on real Pixel hardware).
    val engine = SpikeBridge.engine
    ctx.put("bridgeEngine", engine?.javaClass?.name ?: "null")
    val started = System.currentTimeMillis()
    try {
      if (engine != null) {
        val result = engine.generate(
          SpikeBridge.request(
            messages = listOf(SpikeBridge.user("Say OK")),
            maxOutputTokens = 16,
          ),
        )
        ctx.put(
          "generation",
          linkedMapOf<String, Any?>(
            "path" to "GenAiBridge (the shipped path: MlKitPresence -> GenAiEngine -> PromptEncoding)",
            "text" to result.text.take(120),
            "chars" to result.text.length,
            "finishReason" to result.finishReason,
            "ms" to (System.currentTimeMillis() - started),
          ),
        )
      } else {
        // The firewall failed but the SDK is present: still worth knowing
        // whether the model itself works, because that separates "R8 ate our
        // engine" (item 11) from "this device cannot generate" (item 0).
        val result = mlKit.generate(listOf("Say OK"), null, null, 16)
        ctx.put("generation", result.summary() + mapOf("path" to "SpikeMlKit (bridge unavailable)"))
      }
    } catch (throwable: Throwable) {
      ctx.recordError("generateError", mlKit, engine, throwable)
      ctx.put("outcome", "unavailable")
      SpikeState.viable = false
      ctx.verdict(
        Verdict.BLOCKED,
        "status was AVAILABLE but generation threw ${throwable.javaClass.simpleName} — " +
          "this is §11.1's documented 'available then fails' shape, and it means the " +
          "register cannot be measured on this device even though it claims support",
      )
      return@runSpike
    }

    val downloaded = ctx.data.containsKey("download")
    ctx.put("outcome", if (downloaded) "needs-download-and-downloaded" else "farm-viable")
    SpikeState.viable = true
    ctx.verdict(
      Verdict.CONFIRMED,
      if (downloaded) {
        "Test Lab is usable, but this model arrived without assets: the download had to be " +
          "triggered and completed first. Budget every run accordingly."
      } else {
        "Test Lab is usable for the register: checkStatus() said AVAILABLE and a generation " +
          "returned through the shipped bridge path."
      },
    )
  }
}
