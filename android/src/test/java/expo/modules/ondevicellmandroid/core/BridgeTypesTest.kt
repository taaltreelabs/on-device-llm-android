//
//  BridgeTypesTest.kt
//  @taaltreelabs/on-device-llm-android
//
//  Request parsing and the wire shapes the TypeScript half decodes with
//  (`src/native/types.ts`). Field names are asserted literally: a rename here is
//  silent on both sides until a user sees an empty response.
//

package expo.modules.ondevicellmandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BridgeTypesTest {
  private fun message(role: String, content: String) = mapOf("role" to role, "content" to content)

  @Test
  fun `a well-formed request parses`() {
    val request = BridgeRequest.parse(
      messages = listOf(message("system", "s"), message("user", "u")),
      temperature = 0.4,
      maxOutputTokens = 128,
    )

    assertEquals(2, request.messages.size)
    assertEquals(BridgeRole.SYSTEM, request.messages[0].role)
    assertEquals("u", request.messages[1].content)
    assertEquals(0.4, request.options.temperature!!, 0.0)
    assertEquals(128, request.options.maxOutputTokens)
  }

  @Test
  fun `an unknown role is an invalidRequest, never coerced to user`() {
    try {
      BridgeRequest.parse(listOf(message("tool", "x")), null, null)
      fail("expected an invalidRequest")
    } catch (exception: BridgeException) {
      assertEquals("invalidRequest", exception.payload.code)
      assertTrue(exception.payload.message.contains("messages[0]"))
    }
  }

  @Test
  fun `a message missing a field is an invalidRequest naming the index`() {
    listOf(mapOf("content" to "x"), mapOf("role" to "user")).forEachIndexed { _, raw ->
      try {
        BridgeRequest.parse(listOf(raw), null, null)
        fail("expected an invalidRequest")
      } catch (exception: BridgeException) {
        assertEquals("invalidRequest", exception.payload.code)
        assertTrue(exception.payload.message.contains("messages[0]"))
      }
    }
  }

  // DECISIONS.md D6: `parse` no longer takes `schemaJson` or `tools`. The three
  // tests that used to pin those two refusals here are replaced by the three
  // below, which pin what the narrowed signature must still guarantee. The
  // refusals themselves moved to `src/wire.ts` (`rejectSchema`, `rejectTools`)
  // and are covered by the TypeScript suite; the surface that would have had to
  // carry them across the bridge does not exist any more, so there is nothing
  // left here to reject.

  @Test
  fun `parse takes exactly the three arguments the bridge sends`() {
    // Pinned deliberately, as an arity test. The single-package wave 1 had a
    // real defect of exactly this kind: the Kotlin `generate` declared five
    // positional arguments while the TypeScript half called it with four, so
    // Expo would have rejected every request on the first device that ran it.
    // Both sides are now the same three-argument shape, and this reads as the
    // one-line record of it.
    val request = BridgeRequest.parse(listOf(message("user", "u")), 0.2, 64)

    assertEquals(1, request.messages.size)
    assertEquals(0.2, request.options.temperature!!, 0.0)
    assertEquals(64, request.options.maxOutputTokens)
  }

  @Test
  fun `absent sampling options stay null rather than becoming our own defaults`() {
    // The SDK has its own defaults for temperature, topK, seed and
    // candidateCount, and inventing ours would silently override them for every
    // caller who did not ask. `null` means "the caller said nothing", and
    // `GenAiEngine` only sets a field it was actually given.
    val request = BridgeRequest.parse(listOf(message("user", "u")), null, null)

    assertNull(request.options.temperature)
    assertNull(request.options.maxOutputTokens)
  }

  @Test
  fun `consecutive same-role messages are preserved, never merged`() {
    // Turn merging is the context manager's business (D12, in the core package),
    // and the role encoding is PromptEncoding's. A bridge that quietly folded two
    // user messages into one would change the prompt the caller composed and make
    // `countTokens` disagree with what generation consumes.
    val request = BridgeRequest.parse(
      listOf(message("user", "one"), message("user", "two"), message("assistant", "a")),
      null,
      null,
    )

    assertEquals(3, request.messages.size)
    assertEquals(listOf("one", "two", "a"), request.messages.map { it.content })
    assertEquals(
      listOf(BridgeRole.USER, BridgeRole.USER, BridgeRole.ASSISTANT),
      request.messages.map { it.role },
    )
  }

  @Test
  fun `the result wire shape matches NativeResult`() {
    val bare = BridgeResult(text = "hi", finishReason = "stop").toMap()

    assertEquals(mapOf("text" to "hi", "finishReason" to "stop"), bare)
    // `usage` is omitted rather than sent as zeros: absent means "not
    // reported", and hardcoded zeros are indistinguishable from a measurement.
    assertFalse(bare.containsKey("usage"))
  }

  @Test
  fun `an error payload omits every field it does not carry`() {
    val payload = BridgeErrorPayload(code = "cancelled", message = "The request was cancelled")

    assertEquals(mapOf("code" to "cancelled", "message" to "The request was cancelled"),
      payload.toMap())
  }

  @Test
  fun `stream events carry their requestId and discriminant`() {
    // All streams share one event, so the bridge demultiplexes on requestId and
    // concurrent streams cannot interleave into the wrong consumer.
    val delta = BridgeStreamEvent.Delta("abc", reset = false).toMap("r1")
    assertEquals("r1", delta["requestId"])
    assertEquals("delta", delta["type"])
    assertEquals("abc", delta["delta"])
    assertEquals(false, delta["reset"])

    val finish = BridgeStreamEvent.Finish(BridgeResult("abc", "stop")).toMap("r1")
    assertEquals("finish", finish["type"])
    @Suppress("UNCHECKED_CAST")
    assertEquals("abc", (finish["result"] as Map<String, Any?>)["text"])

    val failure = BridgeStreamEvent.Failure(ErrorMapping.cancelled()).toMap("r1")
    assertEquals("error", failure["type"])
    @Suppress("UNCHECKED_CAST")
    assertEquals("cancelled", (failure["error"] as Map<String, Any?>)["code"])
  }

  @Test
  fun `usage omits absent fields and reports emptiness`() {
    assertTrue(BridgeUsage().isEmpty)
    assertEquals(emptyMap<String, Any?>(), BridgeUsage().toMap())

    val partial = BridgeUsage(inputTokens = 10)
    assertFalse(partial.isEmpty)
    assertEquals(mapOf("inputTokens" to 10), partial.toMap())
  }

  @Test
  fun `role wire values match src core MessageRole`() {
    assertEquals(BridgeRole.SYSTEM, BridgeRole.fromWire("system"))
    assertEquals(BridgeRole.USER, BridgeRole.fromWire("user"))
    assertEquals(BridgeRole.ASSISTANT, BridgeRole.fromWire("assistant"))
    assertNull(BridgeRole.fromWire("model"))
  }
}
