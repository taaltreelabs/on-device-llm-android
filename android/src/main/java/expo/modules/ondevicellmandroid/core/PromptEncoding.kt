//
//  PromptEncoding.kt
//  OnDeviceLlm — Android
//
//  ############################################################################
//  # PROVISIONAL — PENDING HARDWARE (DECISIONS.md D35)                        #
//  #                                                                          #
//  # Nothing in this file has been observed working against a real model. It  #
//  # cannot be: AICore is a preinstalled system service and runs on no        #
//  # emulator (docs/research/android-genai.md §6), so the *only* way to learn #
//  # whether this encoding is respected is to run it on an allowlisted device.#
//  # The wave-2 spike walks the list in DECISIONS.md "PROVISIONAL register".  #
//  #                                                                          #
//  # It is deliberately arranged so that the answer can be changed in one     #
//  # place: `RoleFrame`. Everything else is mechanical.                       #
//  ############################################################################
//
//  The problem, in one line: **`Content` has no role field.**
//
//  ```java
//  public final class Content { public final List<Part> getParts(); }
//  ```
//
//  `javap -p` finds exactly one private field on `Content` (a `List`), and a
//  grep of the constant pool of all 1,473 classes in `genai-prompt` finds zero
//  occurrences of the strings `role`, `user`, `model` or `assistant`
//  (docs/research/android-genai.md §1). The concept does not exist in the
//  artifact. `GenerateContentRequest.contents` is a positional
//  `List<Content>` and the runtime's turn-attribution contract is undocumented
//  — every published Google sample is single-turn.
//
//  So, unlike Apple's typed `Transcript` entries (D17), a multi-turn
//  conversation can only be expressed by **inventing a textual frame**. This
//  file invents it, and does so as pure string transformation: the engine
//  turns the strings this produces into `Content`/`SystemInstruction` objects
//  and does nothing else, which is what lets the encoding be unit-tested on a
//  machine with no Android SDK at all.
//

package expo.modules.ondevicellmandroid.core

/**
 * The invented role frame — the single constant the hardware spike is expected
 * to revise.
 *
 * **Why `User:` / `Model:`.** Three candidates were considered: ChatML-style
 * sentinels (`<|im_start|>user`), XML-ish tags (`<user>…</user>`), and plain
 * labelled lines. Plain labelled lines win for a base model we cannot probe:
 *
 * - Special sentinels only help if they match the tokenizer's *actual* control
 *   tokens. Guess wrong and they are ordinary text that wastes tokens and looks
 *   like noise. We cannot check which ones Gemini Nano uses, and the artifact
 *   carries no hint.
 * - Labelled lines are the single most common conversational shape in ordinary
 *   pretraining text, so a model that ignores the frame entirely still reads
 *   something coherent. That is the failure mode to optimise for when the frame
 *   is unverifiable.
 * - `Model` rather than `Assistant` because the surrounding stack is Gemini:
 *   the Gemini API's own two roles are `user` and `model`. If any of Nano's
 *   instruction tuning leaked a convention, that is the likelier one.
 *
 * Cost is two or three tokens per turn, which `countTokens` measures honestly
 * because it counts the request we actually build (§3).
 */
object RoleFrame {
  /** Label for a `user` message. */
  const val USER_LABEL: String = "User"

  /** Label for an `assistant` message. Gemini's own word for that role is "model". */
  const val MODEL_LABEL: String = "Model"

  /**
   * Label used when system text has to be folded into ordinary content because
   * `isSystemPromptAvailable()` said no.
   */
  const val SYSTEM_LABEL: String = "System"

  /** Separator between a label and its text. */
  const val SEPARATOR: String = ": "

  /** Separator between the folded system block and the first turn. */
  const val FOLD_SEPARATOR: String = "\n\n"

  fun frame(label: String, content: String): String = label + SEPARATOR + content

  fun labelFor(role: BridgeRole): String = when (role) {
    BridgeRole.USER -> USER_LABEL
    BridgeRole.ASSISTANT -> MODEL_LABEL
    BridgeRole.SYSTEM -> SYSTEM_LABEL
  }
}

/**
 * The strings one request becomes, before any ML Kit type exists.
 *
 * @property systemInstruction text for `SystemInstruction`, or `null` when
 *   there is no system text *or* when it had to be folded into [contents].
 * @property contents one entry per `Content`, in order. Never empty for a
 *   request that passed validation.
 * @property framed whether the role frame was applied. `false` is the
 *   single-turn shape every documented Google sample uses.
 */
data class EncodedPrompt(
  val systemInstruction: String?,
  val contents: List<String>,
  val framed: Boolean,
)

object PromptEncoding {
  /**
   * Encode a conversation.
   *
   * **System messages.** `SystemInstruction` is a first-class request field and
   * `isSystemPromptAvailable()` reports whether the resident model honours it
   * (§1). All system messages are concatenated in their original order — a JS
   * conversation can carry them anywhere, and a Phase 2 rolling summary (D13)
   * is a non-pinned `system` message sitting in front of the retained turns.
   * Blank ones are dropped. Their position *between* turns is not preserved,
   * exactly as on Apple, because the request has one system slot.
   *
   * When [systemPromptSupported] is `false` the system text is **folded into
   * the first content** as `System: …` followed by a blank line. Dropping it
   * would silently discard the caller's instructions; putting it in its own
   * `Content` would spend a turn slot on something that is not a turn.
   *
   * **The trailing user message (D17's Android form).** Apple splits the
   * conversation because `respond(to:)` and the seeded transcript are different
   * things. ML Kit has no such split — `contents` is the whole request — so
   * there is nothing to hold back. What *does* transfer is the rule D17
   * introduced and `src/apple/…/buildNativeRequest` already enforces in
   * TypeScript: **a request must end with a user message.** There is no
   * "continue your own last message" affordance here either, and a conversation
   * ending on an assistant turn asks the model to produce a second assistant
   * turn with no question in front of it. Rejected as `invalidRequest`; this is
   * the backstop, TypeScript checks first.
   *
   * **The single-turn exception.** When the request is one user message with no
   * system text, it is emitted **unframed** — a bare `TextPart`, byte for byte
   * the shape of every documented Google sample and therefore the only shape
   * with any evidence behind it. The invented frame is confined to the case
   * that genuinely needs it. (`framed` reports which happened, so the wave-2
   * spike can A/B the two on one device.)
   *
   * @param requirePrompt `false` relaxes the trailing-user-message rule, for
   *   `prewarm` and `countTokens` — neither generates anything, and refusing to
   *   count a history that ends on an assistant turn would make the Phase 2
   *   context manager's job impossible. Mirrors `TranscriptBuilder.prepare`.
   */
  fun encode(
    messages: List<BridgeMessage>,
    systemPromptSupported: Boolean,
    requirePrompt: Boolean = true,
  ): EncodedPrompt {
    val systemText = messages
      .filter { it.role == BridgeRole.SYSTEM }
      .map { it.content }
      .filter { it.isNotBlank() }
      .joinToString(RoleFrame.FOLD_SEPARATOR)
      .ifEmpty { null }

    val turns = messages.filter { it.role != BridgeRole.SYSTEM }

    if (requirePrompt) {
      val last = turns.lastOrNull()
        ?: throw BridgeException.invalidRequest(
          "The request contains no user message to respond to."
        )
      if (last.role != BridgeRole.USER) {
        throw BridgeException.invalidRequest(
          "The Android provider requires the conversation to end with a user message; " +
            "this one ends with an assistant message."
        )
      }
    }

    // A system-only conversation still has something to say. It can only reach
    // here with `requirePrompt = false` (prewarm / countTokens).
    if (turns.isEmpty()) {
      return when {
        systemPromptSupported && systemText != null ->
          EncodedPrompt(systemInstruction = systemText, contents = emptyList(), framed = false)
        systemText != null ->
          EncodedPrompt(
            systemInstruction = null,
            contents = listOf(RoleFrame.frame(RoleFrame.SYSTEM_LABEL, systemText)),
            framed = true,
          )
        else -> EncodedPrompt(systemInstruction = null, contents = emptyList(), framed = false)
      }
    }

    val instruction = if (systemPromptSupported) systemText else null
    val foldedSystem = if (systemPromptSupported) null else systemText

    // Frame whenever more than one voice is present. One user message and
    // nothing else is the documented single-turn shape and stays bare.
    val framed = turns.size > 1 || foldedSystem != null

    val contents = turns.mapIndexed { index, message ->
      val body = if (framed) {
        RoleFrame.frame(RoleFrame.labelFor(message.role), message.content)
      } else {
        message.content
      }
      if (index == 0 && foldedSystem != null) {
        RoleFrame.frame(RoleFrame.SYSTEM_LABEL, foldedSystem) + RoleFrame.FOLD_SEPARATOR + body
      } else {
        body
      }
    }

    return EncodedPrompt(systemInstruction = instruction, contents = contents, framed = framed)
  }
}
