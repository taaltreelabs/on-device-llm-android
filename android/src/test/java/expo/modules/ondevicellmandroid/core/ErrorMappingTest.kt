//
//  ErrorMappingTest.kt
//  OnDeviceLlm — Android
//
//  The 21-code table (DECISIONS.md D36), asserted row by row.
//
//  The Phase 4 router branches on these codes, so a mismapping is not cosmetic:
//  it is a request retried that should not be, or abandoned that should have
//  been failed over. The table is also the one part of the provider that a beta
//  SDK can invalidate without any visible symptom, which is why the exhaustive
//  check at the bottom exists.
//

package expo.modules.ondevicellmandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingTest {
  private fun map(
    code: GenAiErrorCode?,
    rawCode: Int = 0,
    message: String? = "native message",
    retryDelayMs: Long? = null,
    nowMs: Long = 1_000_000L,
  ) = ErrorMapping.mapGenAiError(code, rawCode, message, retryDelayMs, nowMs)

  @Test
  fun `cancelled maps to cancelled`() {
    assertEquals("cancelled", map(GenAiErrorCode.CANCELLED).code)
  }

  @Test
  fun `the two overflow codes map to contextOverflow`() {
    assertEquals("contextOverflow", map(GenAiErrorCode.REQUEST_TOO_LARGE).code)
    assertEquals(
      "contextOverflow",
      map(GenAiErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR).code,
    )
  }

  @Test
  fun `the four unavailable codes carry the right reason`() {
    assertEquals("modelNotReady", map(GenAiErrorCode.NOT_AVAILABLE).reason)
    assertEquals("modelNotReady", map(GenAiErrorCode.NOT_ENOUGH_DISK_SPACE).reason)
    assertEquals("deviceNotEligible", map(GenAiErrorCode.AICORE_INCOMPATIBLE).reason)
    assertEquals("unsupportedPlatform", map(GenAiErrorCode.NEEDS_SYSTEM_UPDATE).reason)

    listOf(
      GenAiErrorCode.NOT_AVAILABLE,
      GenAiErrorCode.NOT_ENOUGH_DISK_SPACE,
      GenAiErrorCode.AICORE_INCOMPATIBLE,
      GenAiErrorCode.NEEDS_SYSTEM_UPDATE,
    ).forEach { assertEquals("unavailable", map(it).code) }
  }

  @Test
  fun `getRetryDelay becomes an absolute resetDate in epoch milliseconds`() {
    // The one field Apple's `RateLimited.resetDate` has a direct counterpart
    // for, and D30 makes `rateLimited` a fallback trigger — so the number is
    // load-bearing, not decoration. The SDK reports a *relative* duration; the
    // wire wants an absolute time JS can hand to `new Date(n)`.
    val payload = map(GenAiErrorCode.BUSY, retryDelayMs = 30_000L, nowMs = 1_000_000L)

    assertEquals("rateLimited", payload.code)
    assertEquals(1_030_000.0, payload.resetDate!!, 0.0)
  }

  @Test
  fun `a rate limit with no reported delay still maps, without a resetDate`() {
    val payload = map(GenAiErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED, retryDelayMs = null)

    assertEquals("rateLimited", payload.code)
    assertNull(payload.resetDate)
  }

  @Test
  fun `the four caller-error codes map to invalidRequest and are never transient`() {
    listOf(
      GenAiErrorCode.REQUEST_TOO_SMALL,
      GenAiErrorCode.NOT_SUPPORTED,
      GenAiErrorCode.INVALID_INPUT_IMAGE,
      GenAiErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR,
    ).forEach {
      assertEquals("invalidRequest", map(it).code)
      assertNull(map(it).transient)
    }
  }

  @Test
  fun `the D9 lane is transient unknown`() {
    // The documented shape of "inference failed on healthy, eligible hardware".
    // D30 has `unknownTransient` on by default, so these are the failures the
    // router is allowed to fail over.
    listOf(
      GenAiErrorCode.REQUEST_PROCESSING_ERROR,
      GenAiErrorCode.RESPONSE_PROCESSING_ERROR,
      GenAiErrorCode.RESPONSE_GENERATION_ERROR,
      GenAiErrorCode.CACHE_PROCESSING_ERROR,
      GenAiErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR,
      GenAiErrorCode.BACKGROUND_USE_BLOCKED,
      GenAiErrorCode.AUDIO_BUFFER_OVERFLOW,
    ).forEach {
      assertEquals("unknown", map(it).code)
      assertEquals(true, map(it).transient)
    }
  }

  @Test
  fun `the SDK's own UNKNOWN leaves transient unset`() {
    // D30: "don't know" shares the non-retryable switch. Treating every mystery
    // as retryable makes each one cost two generations and two bills.
    val payload = map(GenAiErrorCode.UNKNOWN)

    assertEquals("unknown", payload.code)
    assertNull(payload.transient)
  }

  @Test
  fun `an unrecognised error code degrades to transient unknown with its raw value`() {
    // Expected to fire: the API is beta with an explicit no-compatibility
    // promise and has shipped four betas in eight months.
    val payload = map(code = null, rawCode = 4242, message = "something new")

    assertEquals("unknown", payload.code)
    assertEquals(true, payload.transient)
    assertEquals(4242, payload.nativeCode)
    assertEquals(ErrorMapping.GEN_AI_DOMAIN, payload.nativeDomain)
  }

  @Test
  fun `every payload carries the native code and domain for reporting`() {
    GenAiErrorCode.entries.forEach { code ->
      val payload = map(code, rawCode = 99, message = "detail text")
      assertEquals("$code lost its native domain", ErrorMapping.GEN_AI_DOMAIN, payload.nativeDomain)
      assertEquals("$code lost its native code", 99, payload.nativeCode)
      assertEquals("$code lost its detail", "detail text", payload.nativeDetail)
    }
  }

  @Test
  fun `a blank native message is not attached as a detail`() {
    assertNull(map(GenAiErrorCode.BUSY, message = "   ").nativeDetail)
    assertNull(map(GenAiErrorCode.BUSY, message = null).nativeDetail)
  }

  @Test
  fun `an unclassified throwable is transient unknown with its class attached`() {
    // D9's Android form. A NoClassDefFoundError from a half-present SDK, an
    // IllegalStateException from ML Kit's internals: unclassifiable, but never
    // unreportable and never fatal.
    val payload = ErrorMapping.mapUnclassified("java.lang.IllegalStateException", "boom")

    assertEquals("unknown", payload.code)
    assertEquals(true, payload.transient)
    assertEquals("boom", payload.message)
    assertEquals("java.lang.IllegalStateException", payload.nativeDomain)
    assertEquals("boom", payload.nativeDetail)
  }

  @Test
  fun `an unclassified throwable with no message still says something useful`() {
    val payload = ErrorMapping.mapUnclassified("java.lang.NoClassDefFoundError", null)

    assertTrue(payload.message.contains("java.lang.NoClassDefFoundError"))
    assertEquals(true, payload.transient)
  }

  @Test
  fun `the table has exactly the artifact's 21 codes and maps every one`() {
    // Read from the shipping AAR by javap (docs/research/android-genai.md §5).
    // If a beta adds a code, this fails and the table gets a considered row
    // rather than silently taking the null branch.
    assertEquals(21, GenAiErrorCode.entries.size)

    val known = setOf(
      "cancelled", "contextOverflow", "unavailable", "rateLimited",
      "invalidRequest", "unknown",
    )
    GenAiErrorCode.entries.forEach { code ->
      val payload = map(code)
      assertNotNull("$code produced no payload", payload)
      assertTrue("$code mapped to an unknown LLMErrorCode: ${payload.code}", payload.code in known)
      assertTrue("$code produced an empty message", payload.message.isNotBlank())
    }
  }

  @Test
  fun `no code ever maps to guardrail`() {
    // D36, the safety asymmetry: there is no guardrail code on Android and
    // there cannot be one. Safety is implemented as injected prompt text, so a
    // refusal most likely arrives as ordinary generated text. D30's policy that
    // `guardrail` does not fall through by default is therefore unenforceable
    // here. This test exists so the absence stays deliberate.
    GenAiErrorCode.entries.forEach { code ->
      assertTrue("$code must not map to guardrail", map(code).code != "guardrail")
    }
  }

  @Test
  fun `no code ever maps to unsupportedLocale or network`() {
    // Neither is reachable: there is no locale error (§4) and nothing here
    // talks to a network.
    GenAiErrorCode.entries.forEach { code ->
      val mapped = map(code).code
      assertTrue("$code must not map to unsupportedLocale", mapped != "unsupportedLocale")
      assertTrue("$code must not map to network", mapped != "network")
    }
  }
}
