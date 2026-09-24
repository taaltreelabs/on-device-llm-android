//
//  PromptEncodingTest.kt
//  OnDeviceLlm — Android
//
//  The role encoding (DECISIONS.md D35), pinned exactly.
//
//  These tests cannot tell us whether the encoding *works* — that needs an
//  allowlisted device, because AICore runs on no emulator
//  (docs/research/android-genai.md §6). What they do is make the encoding a
//  decision rather than an accident: the exact bytes we intend to send are
//  written down here, so the wave-2 spike can change one constant and see
//  precisely what moved, and so nobody edits the frame by accident.
//

package expo.modules.ondevicellmandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptEncodingTest {
  private fun user(text: String) = BridgeMessage(BridgeRole.USER, text)
  private fun assistant(text: String) = BridgeMessage(BridgeRole.ASSISTANT, text)
  private fun system(text: String) = BridgeMessage(BridgeRole.SYSTEM, text)

  @Test
  fun `a single user message is sent unframed`() {
    // The shape of every documented Google sample: Builder(TextPart(prompt)).
    // It is the only shape with evidence behind it, so the invented frame stays
    // out of it.
    val encoded = PromptEncoding.encode(listOf(user("Hoe gaat het?")), systemPromptSupported = true)

    assertNull(encoded.systemInstruction)
    assertEquals(listOf("Hoe gaat het?"), encoded.contents)
    assertEquals(false, encoded.framed)
  }

  @Test
  fun `multi-turn history is framed one Content per message`() {
    val encoded = PromptEncoding.encode(
      listOf(user("one"), assistant("two"), user("three")),
      systemPromptSupported = true,
    )

    assertEquals(listOf("User: one", "Model: two", "User: three"), encoded.contents)
    assertEquals(true, encoded.framed)
    assertNull(encoded.systemInstruction)
  }

  @Test
  fun `system messages become a SystemInstruction when the model supports it`() {
    val encoded = PromptEncoding.encode(
      listOf(system("Be brief."), user("hi")),
      systemPromptSupported = true,
    )

    assertEquals("Be brief.", encoded.systemInstruction)
    // One user turn plus a system instruction is still the single-turn shape:
    // the instruction rides its own request field, so no second voice is in the
    // contents.
    assertEquals(listOf("hi"), encoded.contents)
    assertEquals(false, encoded.framed)
  }

  @Test
  fun `several system messages are joined in order`() {
    val encoded = PromptEncoding.encode(
      listOf(system("First."), user("hi"), system("[summary of earlier conversation] …")),
      systemPromptSupported = true,
    )

    // Order among them is preserved; their position between turns is not,
    // because the request has one system slot. A Phase 2 rolling summary (D13)
    // is exactly the message that arrives mid-list.
    assertEquals("First.\n\n[summary of earlier conversation] …", encoded.systemInstruction)
  }

  @Test
  fun `blank system messages are dropped`() {
    val encoded = PromptEncoding.encode(
      listOf(system("   "), system(""), user("hi")),
      systemPromptSupported = true,
    )

    assertNull(encoded.systemInstruction)
    assertEquals(listOf("hi"), encoded.contents)
  }

  @Test
  fun `system text folds into the first content when the model has no system slot`() {
    val encoded = PromptEncoding.encode(
      listOf(system("Be brief."), user("hi")),
      systemPromptSupported = false,
    )

    assertNull(encoded.systemInstruction)
    // Folded, not dropped: silently discarding the caller's instructions is the
    // one outcome that must never happen. The fold puts a second voice in the
    // contents, so the turn is framed too.
    assertEquals(listOf("System: Be brief.\n\nUser: hi"), encoded.contents)
    assertEquals(true, encoded.framed)
  }

  @Test
  fun `a folded system block lands on the first content only`() {
    val encoded = PromptEncoding.encode(
      listOf(system("Be brief."), user("one"), assistant("two"), user("three")),
      systemPromptSupported = false,
    )

    assertEquals(
      listOf("System: Be brief.\n\nUser: one", "Model: two", "User: three"),
      encoded.contents,
    )
  }

  @Test
  fun `a conversation that does not end with a user message is rejected`() {
    // D17's Android form: there is no "continue your own last message"
    // affordance, and a conversation ending on an assistant turn asks for a
    // second assistant turn with no question in front of it.
    try {
      PromptEncoding.encode(
        listOf(user("one"), assistant("two")),
        systemPromptSupported = true,
      )
      fail("expected an invalidRequest")
    } catch (exception: BridgeException) {
      assertEquals("invalidRequest", exception.payload.code)
      assertTrue(exception.payload.message.contains("end with a user message"))
    }
  }

  @Test
  fun `an empty conversation is rejected`() {
    try {
      PromptEncoding.encode(emptyList(), systemPromptSupported = true)
      fail("expected an invalidRequest")
    } catch (exception: BridgeException) {
      assertEquals("invalidRequest", exception.payload.code)
    }
  }

  @Test
  fun `requirePrompt false accepts a history ending on an assistant turn`() {
    // prewarm and countTokens: neither generates anything, and refusing to
    // count a history that ends on an assistant turn would make the Phase 2
    // context manager's job impossible.
    val encoded = PromptEncoding.encode(
      listOf(user("one"), assistant("two")),
      systemPromptSupported = true,
      requirePrompt = false,
    )

    assertEquals(listOf("User: one", "Model: two"), encoded.contents)
  }

  @Test
  fun `a system-only conversation still carries its text`() {
    val supported = PromptEncoding.encode(
      listOf(system("Be brief.")),
      systemPromptSupported = true,
      requirePrompt = false,
    )
    assertEquals("Be brief.", supported.systemInstruction)
    assertEquals(emptyList<String>(), supported.contents)

    val unsupported = PromptEncoding.encode(
      listOf(system("Be brief.")),
      systemPromptSupported = false,
      requirePrompt = false,
    )
    assertNull(unsupported.systemInstruction)
    assertEquals(listOf("System: Be brief."), unsupported.contents)
  }

  @Test
  fun `the frame is exactly one label, one colon, one space`() {
    // Pinned deliberately. The whole point of D35 is that the spike changes
    // this in one place; if it drifts, this test says so.
    assertEquals("User", RoleFrame.USER_LABEL)
    assertEquals("Model", RoleFrame.MODEL_LABEL)
    assertEquals("System", RoleFrame.SYSTEM_LABEL)
    assertEquals(": ", RoleFrame.SEPARATOR)
    assertEquals("User: x", RoleFrame.frame(RoleFrame.USER_LABEL, "x"))
  }
}
