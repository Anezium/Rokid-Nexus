package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSurfaceContractTest {
    @Test
    fun `v4 carries the grown-band text budgets`() {
        assertEquals(4, NoticeSurfaceContract.VERSION)
        assertEquals(8192, NoticeSurfaceContract.MAX_BODY_CHARS)
        assertEquals(64, NoticeSurfaceContract.MAX_LINES)
    }

    @Test
    fun `trims text collapses newlines and derives ttl`() {
        val result = NoticeSurfaceContract.validateShow(
            JSONObject()
                .put("kind", "notice")
                .put("title", "  Marie  ")
                .put("body", "On my way,\nten minutes out.")
                .put("footer", " tap to reply "),
        )

        val content = (result as NoticeSurfaceValidationResult.Valid).content
        assertEquals("Marie", content.title)
        assertEquals("On my way, ten minutes out.", content.body)
        assertEquals("tap to reply", content.footer)
        assertFalse(content.interactive)
        assertFalse(content.wakeDisplay)
        assertFalse(content.backdrop)
        assertEquals(
            NoticeSurfaceContract.derivedTtlMs(
                "Marie".length + "On my way, ten minutes out.".length + "tap to reply".length,
            ),
            content.ttlMs,
        )
    }

    @Test
    fun `clamps ttl to the notice window`() {
        val floor = NoticeSurfaceContract.validateShow(showPayload().put("ttlMs", 10L))
        val ceiling = NoticeSurfaceContract.validateShow(showPayload().put("ttlMs", 600_000L))

        assertEquals(
            NoticeSurfaceContract.MIN_TTL_MS,
            (floor as NoticeSurfaceValidationResult.Valid).content.ttlMs,
        )
        assertEquals(
            NoticeSurfaceContract.MAX_TTL_MS,
            (ceiling as NoticeSurfaceValidationResult.Valid).content.ttlMs,
        )
    }

    @Test
    fun `rejects a notice with no text`() {
        val result = NoticeSurfaceContract.validateShow(
            JSONObject().put("kind", "notice").put("footer", "tap to reply"),
        )

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects text past its cap rather than truncating`() {
        val result = NoticeSurfaceContract.validateShow(
            showPayload().put("title", "x".repeat(NoticeSurfaceContract.MAX_TITLE_CHARS + 1)),
        )

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `accepts the body character budget and rejects one more`() {
        val accepted = NoticeSurfaceContract.validateShow(
            showPayload().put("body", "x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS)),
        )
        val rejected = NoticeSurfaceContract.validateShow(
            showPayload().put("body", "x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS + 1)),
        )

        assertTrue(accepted is NoticeSurfaceValidationResult.Valid)
        assertTrue(rejected is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `accepts the line budget and rejects one more before dropping empties`() {
        val accepted = NoticeSurfaceContract.validateShow(
            linesPayload(List(NoticeSurfaceContract.MAX_LINES) { "line $it" }),
        )
        val rejected = NoticeSurfaceContract.validateShow(
            linesPayload(List(NoticeSurfaceContract.MAX_LINES) { "line $it" } + "   "),
        )

        assertTrue(accepted is NoticeSurfaceValidationResult.Valid)
        assertTrue(rejected is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `lines share the body budget including one separator each`() {
        val halfBudget = NoticeSurfaceContract.MAX_BODY_CHARS / 2
        val accepted = NoticeSurfaceContract.validateShow(
            linesPayload(listOf("x".repeat(halfBudget - 1), "y".repeat(halfBudget - 1))),
        )
        val rejected = NoticeSurfaceContract.validateShow(
            linesPayload(listOf("x".repeat(halfBudget), "y".repeat(halfBudget - 1))),
        )

        assertTrue(accepted is NoticeSurfaceValidationResult.Valid)
        assertTrue(rejected is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `lines trim collapse their own newlines and drop empty entries`() {
        val result = NoticeSurfaceContract.validateShow(
            linesPayload(
                listOf(
                    "  first\r\ncontinued  ",
                    " \n ",
                    "third\nline",
                ),
            ),
        )

        val content = (result as NoticeSurfaceValidationResult.Valid).content
        assertEquals(listOf("first continued", "third line"), content.lines)
        assertNull(content.body)
        assertEquals(
            NoticeSurfaceContract.derivedTtlMs(
                "first continued".length + 1 + "third line".length + 1,
            ),
            content.ttlMs,
        )
    }

    @Test
    fun `body and lines together are invalid on show and update`() {
        val show = NoticeSurfaceContract.validateShow(
            linesPayload(listOf("first")).put("body", "paragraph"),
        )
        val update = NoticeSurfaceContract.validateUpdate(
            JSONObject()
                .put("body", "paragraph")
                .put("lines", JSONArray().put("first")),
        )

        assertTrue(show is NoticeSurfaceValidationResult.Invalid)
        assertTrue(update is NoticeSurfacePatchResult.Invalid)
    }

    @Test
    fun `lines must be an array of strings`() {
        assertTrue(
            NoticeSurfaceContract.validateShow(
                JSONObject().put("kind", "notice").put("lines", "first"),
            ) is NoticeSurfaceValidationResult.Invalid,
        )
        assertTrue(
            NoticeSurfaceContract.validateShow(
                JSONObject()
                    .put("kind", "notice")
                    .put("lines", JSONArray().put(JSONObject().put("text", "first"))),
            ) is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `length derived ttl follows the reading rate and clamps`() {
        assertEquals(4_000L, NoticeSurfaceContract.derivedTtlMs(0))
        assertEquals(12_800L, NoticeSurfaceContract.derivedTtlMs(240))
        assertEquals(
            45_000L,
            NoticeSurfaceContract.derivedTtlMs(NoticeSurfaceContract.MAX_BODY_CHARS),
        )
    }

    @Test
    fun `show validates an image frame with the shipped image contract`() {
        val bytes = jpeg(width = 480, height = 160)
        val payload = showPayload()
            .put("imageVersion", ImageSurfaceContract.VERSION)
            .put("contentKey", "message-photo")
            .put("mimeType", ImageSurfaceContract.MIME_JPEG)
            .put("pixelWidth", 480)
            .put("pixelHeight", 160)
            .put("sha256", ImageSurfaceContract.sha256(bytes))

        val accepted = NoticeSurfaceContract.validateShow(payload, bytes)
        val missingFrame = NoticeSurfaceContract.validateShow(payload)

        assertTrue(accepted is NoticeSurfaceValidationResult.Valid)
        assertEquals(
            "message-photo",
            (accepted as NoticeSurfaceValidationResult.Valid).content.image?.contentKey,
        )
        assertTrue(missingFrame is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects a wrong kind and a non-boolean interactive`() {
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("kind", "pin"))
                is NoticeSurfaceValidationResult.Invalid,
        )
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("interactive", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `wake display is optional boolean on show and forbidden on update`() {
        val requested = NoticeSurfaceContract.validateShow(showPayload().put("wakeDisplay", true))
            as NoticeSurfaceValidationResult.Valid
        assertTrue(requested.content.wakeDisplay)
        assertTrue(
            NoticeSurfaceContract.toPayload("relay:notice", requested.content)
                .getBoolean("wakeDisplay"),
        )

        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("wakeDisplay", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
        val rejected = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("wakeDisplay", false),
        )
        assertTrue(rejected is NoticeSurfacePatchResult.Invalid)
        assertEquals(
            "wakeDisplay is show-only",
            (rejected as NoticeSurfacePatchResult.Invalid).reason,
        )
    }

    @Test
    fun `backdrop is optional boolean on show and forbidden on update`() {
        val requested = NoticeSurfaceContract.validateShow(showPayload().put("backdrop", true))
            as NoticeSurfaceValidationResult.Valid
        assertTrue(requested.content.backdrop)
        assertTrue(
            NoticeSurfaceContract.toPayload("relay:notice", requested.content)
                .getBoolean("backdrop"),
        )

        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("backdrop", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
        val rejected = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("backdrop", false),
        )
        assertTrue(rejected is NoticeSurfacePatchResult.Invalid)
        assertEquals(
            "backdrop is show-only",
            (rejected as NoticeSurfacePatchResult.Invalid).reason,
        )
    }

    @Test
    fun `an update leaves absent fields alone`() {
        val current = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = "tap to reply",
            interactive = true,
            ttlMs = 8_000L,
        )

        val patch = NoticeSurfaceContract.validateUpdate(JSONObject().put("footer", "Listening…"))
        val updated = (patch as NoticeSurfacePatchResult.Valid).patch.applyTo(current)

        assertEquals("Marie", updated.title)
        assertEquals("On my way", updated.body)
        assertEquals("Listening…", updated.footer)
        assertTrue(updated.interactive)
        assertEquals(8_000L, updated.ttlMs)
    }

    @Test
    fun `an update can clear a field it sends empty`() {
        val current = NoticeSurfaceContent("Marie", "On my way", "tap to reply")

        val patch = NoticeSurfaceContract.validateUpdate(JSONObject().put("footer", "   "))
        val updated = (patch as NoticeSurfacePatchResult.Valid).patch.applyTo(current)

        assertNull(updated.footer)
        assertEquals("Marie", updated.title)
    }

    @Test
    fun `an update still enforces the caps`() {
        val patch = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("body", "x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS + 1)),
        )

        assertTrue(patch is NoticeSurfacePatchResult.Invalid)
    }

    @Test
    fun `payload omits interactive when false and round-trips`() {
        val content = NoticeSurfaceContent("Marie", "On my way", null, interactive = false)
        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)

        assertFalse(payload.has("interactive"))
        assertFalse(payload.has("wakeDisplay"))
        assertFalse(payload.has("backdrop"))
        assertFalse(payload.has("footer"))
        assertEquals("relay:notice", payload.optString("surfaceId"))

        val reparsed = NoticeSurfaceContract.validateShow(payload)
        assertEquals(content.copy(ttlMs = content.ttlMs), (reparsed as NoticeSurfaceValidationResult.Valid).content)
    }

    @Test
    fun `structured lines serialize in order and round-trip`() {
        val content = NoticeSurfaceContent(
            title = "Thread",
            body = null,
            footer = null,
            lines = listOf("first", "second"),
        )

        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)

        assertFalse(payload.has("body"))
        assertEquals(listOf("first", "second"), payload.getJSONArray("lines").toStringList())
        val reparsed = NoticeSurfaceContract.validateShow(payload)
            as NoticeSurfaceValidationResult.Valid
        assertEquals(content, reparsed.content)
    }

    /**
     * The compatibility pin for the whole feature. Asserted key by key rather
     * than against a serialised string: `JSONObject` is backed by a HashMap
     * here, so a string comparison would fail on key order for reasons that
     * have nothing to do with what is on the wire, and the receiver reads by
     * key anyway.
     */
    @Test
    fun `a notice with no actions puts nothing new on the wire`() {
        val content = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = "tap to reply",
            interactive = true,
        )

        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)

        assertEquals(
            setOf("surfaceId", "kind", "ttlMs", "title", "body", "footer", "interactive"),
            payload.keys().asSequence().toSet(),
        )
        assertFalse(payload.has("actions"))
        assertEquals("relay:notice", payload.getString("surfaceId"))
        assertEquals("notice", payload.getString("kind"))
        assertEquals(NoticeSurfaceContract.DEFAULT_TTL_MS, payload.getLong("ttlMs"))
        assertEquals("Marie", payload.getString("title"))
        assertEquals("On my way", payload.getString("body"))
        assertEquals("tap to reply", payload.getString("footer"))
        assertTrue(payload.getBoolean("interactive"))
    }

    @Test
    fun `actions round-trip trimmed, in order, with their glyphs`() {
        val payload = showPayload().put(
            "actions",
            JSONArray()
                .put(action("  yes  ", "  play  ", "  Accept  "))
                .put(action("no", "stop", "Decline")),
        )

        val content = (NoticeSurfaceContract.validateShow(payload)
            as NoticeSurfaceValidationResult.Valid).content
        assertEquals(
            listOf(
                NoticeAction("yes", "play", "Accept"),
                NoticeAction("no", "stop", "Decline"),
            ),
            content.actions,
        )

        val reserialized = NoticeSurfaceContract
            .toPayload("relay:notice", content)
            .getJSONArray("actions")
        assertEquals(2, reserialized.length())
        assertEquals("yes", reserialized.getJSONObject(0).getString("id"))
        assertEquals("play", reserialized.getJSONObject(0).getString("glyph"))
        assertEquals("Accept", reserialized.getJSONObject(0).getString("label"))
        assertEquals("no", reserialized.getJSONObject(1).getString("id"))
    }

    @Test
    fun `rejects a fourth action rather than dropping it`() {
        val actions = JSONArray()
        repeat(NoticeSurfaceContract.MAX_ACTIONS + 1) { index ->
            actions.put(action("id-$index", "play", "Label"))
        }

        val result = NoticeSurfaceContract.validateShow(showPayload().put("actions", actions))

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects a label too long to read rather than letting the row truncate it`() {
        val label = "x".repeat(NoticeSurfaceContract.MAX_ACTION_LABEL_CHARS + 1)

        val result = NoticeSurfaceContract.validateShow(
            showPayload().put("actions", JSONArray().put(action("yes", "play", label))),
        )

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `keeps a label sitting exactly on the ceiling`() {
        val label = "x".repeat(NoticeSurfaceContract.MAX_ACTION_LABEL_CHARS)

        val content = (
            NoticeSurfaceContract.validateShow(
                showPayload().put("actions", JSONArray().put(action("yes", "play", label))),
            ) as NoticeSurfaceValidationResult.Valid
            ).content

        assertEquals(label, content.actions.single().label)
    }

    @Test
    fun `rejects malformed actions field by field`() {
        val malformed = listOf(
            JSONArray().put("yes"),
            JSONArray().put(action("", "play", "Accept")),
            JSONArray().put(action("yes", "not a glyph", "Accept")),
            JSONArray().put(action("yes", "play", "   ")),
        )

        malformed.forEach { actions ->
            assertTrue(
                "expected $actions to be refused",
                NoticeSurfaceContract.validateShow(showPayload().put("actions", actions))
                    is NoticeSurfaceValidationResult.Invalid,
            )
        }
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("actions", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `an update replaces the row only when it carries one`() {
        val current = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = null,
            actions = listOf(NoticeAction("yes", "play", "Accept")),
        )

        val untouched = NoticeSurfaceContract.validateUpdate(JSONObject().put("body", "Five out"))
        assertEquals(
            current.actions,
            (untouched as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions,
        )

        val replaced = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("actions", JSONArray().put(action("no", "stop", "Decline"))),
        )
        assertEquals(
            listOf(NoticeAction("no", "stop", "Decline")),
            (replaced as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions,
        )

        val cleared = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("actions", JSONArray()),
        )
        assertTrue(
            (cleared as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions.isEmpty(),
        )
    }

    @Test
    fun `offering actions is asking for input, with or without the flag`() {
        val plain = NoticeSurfaceContent("Marie", "On my way", null)
        val flagged = plain.copy(interactive = true)
        val chosen = plain.copy(actions = listOf(NoticeAction("yes", "play", "Accept")))

        assertFalse(plain.expectsInput)
        assertTrue(flagged.expectsInput)
        assertTrue(chosen.expectsInput)
    }

    /**
     * The hub relays this rather than re-serialising its state, so it has to
     * carry a clear as a clear. Anything it flattens to an absent key is a
     * field the wearer never sees change.
     */
    @Test
    fun `an update payload round-trips every field, including the cleared ones`() {
        val patch = NoticeSurfacePatch(
            title = NoticeField("Marie"),
            footer = NoticeField(null),
            interactive = NoticeField(false),
            actions = NoticeField(emptyList()),
            ttlMs = NoticeField(12_000L),
            lines = NoticeField(emptyList()),
        )

        val wire = NoticeSurfaceContract.toUpdatePayload("relay:notice", patch)

        assertEquals(
            setOf("surfaceId", "title", "footer", "interactive", "actions", "ttlMs", "lines"),
            wire.keys().asSequence().toSet(),
        )
        // Present and empty, which is how the patch spells a clear. Absent would
        // mean "leave it alone".
        assertEquals("", wire.getString("footer"))
        assertFalse(wire.getBoolean("interactive"))
        assertEquals(0, wire.getJSONArray("actions").length())
        assertEquals(0, wire.getJSONArray("lines").length())

        val reread = (NoticeSurfaceContract.validateUpdate(wire) as NoticeSurfacePatchResult.Valid).patch
        assertEquals(patch, reread)
    }

    @Test
    fun `an update payload carries only what the owner sent`() {
        val wire = NoticeSurfaceContract.toUpdatePayload(
            "relay:notice",
            NoticeSurfacePatch(body = NoticeField("Five out")),
        )

        assertEquals(setOf("surfaceId", "body"), wire.keys().asSequence().toSet())
        assertEquals("Five out", wire.getString("body"))
    }

    @Test
    fun `a lines patch replaces body and a body patch replaces lines`() {
        val paragraph = NoticeSurfaceContent(
            title = "Marie",
            body = "One paragraph",
            footer = null,
        )
        val linesPatch = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("lines", JSONArray().put("First").put("Second")),
        ) as NoticeSurfacePatchResult.Valid

        val structured = linesPatch.patch.applyTo(paragraph)
        assertNull(structured.body)
        assertEquals(listOf("First", "Second"), structured.lines)
        val wire = NoticeSurfaceContract.toUpdatePayload("relay:notice", linesPatch.patch)
        assertEquals(setOf("surfaceId", "lines"), wire.keys().asSequence().toSet())

        val bodyPatch = NoticeSurfaceContract.validateUpdate(JSONObject().put("body", "Replacement"))
            as NoticeSurfacePatchResult.Valid
        val replaced = bodyPatch.patch.applyTo(structured)
        assertEquals("Replacement", replaced.body)
        assertTrue(replaced.lines.isEmpty())
    }

    @Test
    fun `a relayed clear empties the field it names`() {
        val current = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = "tap to reply",
            interactive = true,
            actions = listOf(NoticeAction("reply", "phone", "Reply")),
        )

        val wire = NoticeSurfaceContract.toUpdatePayload(
            "relay:notice",
            NoticeSurfacePatch(
                footer = NoticeField(null),
                interactive = NoticeField(false),
                actions = NoticeField(emptyList()),
            ),
        )
        val applied = (NoticeSurfaceContract.validateUpdate(wire) as NoticeSurfacePatchResult.Valid)
            .patch
            .applyTo(current)

        assertNull(applied.footer)
        assertFalse(applied.interactive)
        assertTrue(applied.actions.isEmpty())
        assertFalse(applied.expectsInput)
        // Untouched fields are untouched.
        assertEquals("Marie", applied.title)
        assertEquals("On my way", applied.body)
    }

    @Test
    fun `action payload names the notice and the action`() {
        val payload = NoticeSurfaceContract.actionPayload("relay:notice", "yes")

        assertEquals("relay:notice", payload.getString("noticeId"))
        assertEquals("yes", payload.getString("id"))
    }

    @Test
    fun `text input round trips and cannot compete with gesture replies`() {
        val input = NoticeTextInput("assistant-question", "Ask Assistant")
        val content = NoticeSurfaceContent(
            title = "Assistant",
            body = "Type on your phone",
            footer = null,
            textInput = input,
        )

        val wire = NoticeSurfaceContract.toPayload("assistant:notice", content)
        val parsed = NoticeSurfaceContract.validateShow(wire) as NoticeSurfaceValidationResult.Valid

        assertEquals(input, parsed.content.textInput)
        assertTrue(NoticeSurfaceContract.hasValidInteraction(parsed.content))
        assertTrue(
            NoticeSurfaceContract.validateShow(wire.put("interactive", true))
                is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `text input patch can install and clear the editor exactly`() {
        val current = NoticeSurfaceContent("Assistant", "Listening", null)
        val inputWire = JSONObject()
            .put("id", "assistant-question")
            .put("hint", "Ask Assistant")
        val installedPatch = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("textInput", inputWire),
        ) as NoticeSurfacePatchResult.Valid
        val installed = installedPatch.patch.applyTo(current)
        assertEquals("assistant-question", installed.textInput?.id)

        val clearWire = NoticeSurfaceContract.toUpdatePayload(
            "assistant:notice",
            NoticeSurfacePatch(textInput = NoticeField(null)),
        )
        val clearedPatch = NoticeSurfaceContract.validateUpdate(clearWire)
            as NoticeSurfacePatchResult.Valid
        assertNull(clearedPatch.patch.applyTo(installed).textInput)
    }

    @Test
    fun `submitted text is bounded trimmed and redacted`() {
        val payload = NoticeSurfaceContract.textSubmissionPayload(
            "assistant:notice",
            "assistant-question",
            "  private question  ",
        )
        val parsed = NoticeSurfaceContract.parseTextSubmission(payload)!!

        assertEquals("private question", parsed.text)
        assertFalse(parsed.toString().contains("private question"))
        assertEquals(
            NoticeSurfaceContract.MAX_TEXT_INPUT_CHARS,
            NoticeSurfaceContract.parseTextSubmission(
                JSONObject(payload.toString()).put(
                    "text",
                    "界".repeat(NoticeSurfaceContract.MAX_TEXT_INPUT_CHARS),
                ),
            )?.text?.length,
        )
        assertNull(
            NoticeSurfaceContract.parseTextSubmission(
                JSONObject(payload.toString()).put(
                    "text",
                    "x".repeat(NoticeSurfaceContract.MAX_TEXT_INPUT_CHARS + 1),
                ),
            ),
        )
    }

    @Test
    fun `closed payload carries the reason`() {
        val payload = NoticeSurfaceContract.closedPayload("relay:notice", NoticeCloseReason.TIMEOUT)

        assertEquals("relay:notice", payload.optString("noticeId"))
        assertEquals("timeout", payload.optString("reason"))
        assertEquals(NoticeCloseReason.TIMEOUT, NoticeCloseReason.fromWireValue("timeout"))
    }

    private fun showPayload() = JSONObject()
        .put("kind", "notice")
        .put("title", "Marie")
        .put("body", "On my way")

    private fun linesPayload(lines: List<String>) = JSONObject()
        .put("kind", "notice")
        .put("lines", JSONArray(lines))

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }

    private fun action(id: String, glyph: String, label: String) = JSONObject()
        .put("id", id)
        .put("glyph", glyph)
        .put("label", label)

    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteArray(128).also { bytes ->
            byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
                0x00, 0x11, 0x08,
                (height ushr 8).toByte(), height.toByte(),
                (width ushr 8).toByte(), width.toByte(),
                0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                0xff.toByte(), 0xd9.toByte(),
            ).copyInto(bytes)
        }
}
