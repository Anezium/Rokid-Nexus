package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputContractTest {
    private val sessionId = "session_0123456789abcdef"

    @Test
    fun `core paths follow bus routing conventions`() {
        assertEquals("/core/remote-input/session", RemoteInputContract.SESSION_PATH)
        assertEquals("/core/remote-input/command", RemoteInputContract.COMMAND_PATH)
        assertEquals("/core/remote-input/status", RemoteInputContract.STATUS_PATH)
    }

    @Test
    fun `session open carries only editor metadata and round trips`() {
        val open = RemoteInputSessionOpen(
            sessionId = sessionId,
            packageName = "app.morphe.android.youtube",
            inputType = 0x81,
            imeOptions = 0x06,
            sensitive = true,
        )

        val payload = RemoteInputContract.encodeSessionOpen(open).put("futureField", "ignored")

        assertEquals(0L, payload.getLong("sequence"))
        assertFalse(payload.has("text"))
        assertEquals(open, RemoteInputContract.decodeSessionOpen(payload))
    }

    @Test
    fun `session metadata allows absent package and raw signed android flags`() {
        val valid = RemoteInputContract.encodeSessionOpen(
            RemoteInputSessionOpen(sessionId, null, 1, Int.MIN_VALUE, false),
        )

        assertEquals(null, RemoteInputContract.decodeSessionOpen(valid)?.packageName)
        assertNull(RemoteInputContract.decodeSessionOpen(JSONObject(valid.toString()).put("packageName", "bad/pkg")))
        assertNull(RemoteInputContract.decodeSessionOpen(JSONObject(valid.toString()).put("inputType", 1.5)))
        assertNull(RemoteInputContract.decodeSessionOpen(JSONObject(valid.toString()).put("imeOptions", Long.MAX_VALUE)))
        assertNull(RemoteInputContract.decodeSessionOpen(JSONObject(valid.toString()).put("sensitive", "true")))
    }

    @Test
    fun `trusted editor may request one phone keyboard launch`() {
        val open = RemoteInputSessionOpen(
            sessionId = sessionId,
            packageName = "com.anezium.rokidbus.glasses",
            inputType = 1,
            imeOptions = 6,
            sensitive = false,
            autoOpenPhoneKeyboard = true,
        )

        val payload = RemoteInputContract.encodeSessionOpen(open)

        assertTrue(payload.getBoolean("autoOpenPhoneKeyboard"))
        assertEquals(open, RemoteInputContract.decodeSessionOpen(payload))
        assertNull(
            RemoteInputContract.decodeSessionOpen(
                JSONObject(payload.toString()).put("autoOpenPhoneKeyboard", "true"),
            ),
        )
    }

    @Test
    fun `session closure round trips the final applied sequence`() {
        val closed = RemoteInputSessionClosed(
            sessionId,
            RemoteInputCloseReason.FOCUS_LOST,
            42L,
        )

        assertEquals(
            closed,
            RemoteInputContract.decodeSessionClosed(RemoteInputContract.encodeSessionClosed(closed)),
        )
    }

    @Test
    fun `every input command round trips`() {
        val commands = listOf(
            RemoteInputCommand.CommitText(sessionId, 1, "é"),
            RemoteInputCommand.SetComposingText(sessionId, 2, "候補", -1),
            RemoteInputCommand.SetComposingText(sessionId, 3, ""),
            RemoteInputCommand.FinishComposingText(sessionId, 4),
            RemoteInputCommand.DeleteSurroundingText(sessionId, 5, 2, 1),
            RemoteInputCommand.PerformEditorAction(sessionId, 6, RemoteEditorAction.NEXT),
            RemoteInputCommand.Close(sessionId, 7, RemoteInputCloseReason.USER_DISMISSED),
        )

        commands.forEach { command ->
            val wireCopy = JSONObject(RemoteInputContract.encodeCommand(command).toString())
            assertEquals(command, RemoteInputContract.decodeCommand(wireCopy))
        }
    }

    @Test
    fun `text commands redact their string representation`() {
        val secret = "dont-print-this"
        val commit = RemoteInputCommand.CommitText(sessionId, 1, secret)
        val composing = RemoteInputCommand.SetComposingText(sessionId, 2, secret)

        assertFalse(commit.toString().contains(secret))
        assertFalse(composing.toString().contains(secret))
        assertTrue(commit.toString().contains("<redacted:"))
    }

    @Test
    fun `valid unicode including surrogate pairs is accepted`() {
        val command = RemoteInputCommand.CommitText(sessionId, 1, "été 👓")

        assertEquals(
            command,
            RemoteInputContract.decodeCommand(RemoteInputContract.encodeCommand(command)),
        )
    }

    @Test
    fun `empty oversized null and malformed unicode commits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputContract.encodeCommand(RemoteInputCommand.CommitText(sessionId, 1, ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputContract.encodeCommand(
                RemoteInputCommand.CommitText(
                    sessionId,
                    1,
                    "a".repeat(RemoteInputContract.MAX_TEXT_UTF16_LENGTH + 1),
                ),
            )
        }
        listOf("x\u0000y", "\uD83D", "\uDC53").forEach { text ->
            val payload = RemoteInputContract.encodeCommand(
                RemoteInputCommand.CommitText(sessionId, 1, "valid"),
            ).put("text", text)
            assertNull(RemoteInputContract.decodeCommand(payload))
        }
    }

    @Test
    fun `utf8 byte limit is enforced independently of utf16 count`() {
        val multibyteCharacters = "界".repeat(200)

        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputContract.encodeCommand(
                RemoteInputCommand.CommitText(sessionId, 1, multibyteCharacters),
            )
        }
    }

    @Test
    fun `cursor delete and sequence boundaries are strict`() {
        val validCommit = RemoteInputContract.encodeCommand(
            RemoteInputCommand.CommitText(sessionId, 1, "a"),
        )
        assertNull(
            RemoteInputContract.decodeCommand(
                JSONObject(validCommit.toString()).put(
                    "newCursorPosition",
                    RemoteInputContract.MAX_CURSOR_OFFSET + 1,
                ),
            ),
        )
        val validDelete = RemoteInputContract.encodeCommand(
            RemoteInputCommand.DeleteSurroundingText(sessionId, 1, 1, 0),
        )
        assertNull(
            RemoteInputContract.decodeCommand(
                JSONObject(validDelete.toString()).put("beforeLength", 0).put("afterLength", 0),
            ),
        )
        assertNull(RemoteInputContract.decodeCommand(JSONObject(validCommit.toString()).put("sequence", 0)))
        assertNull(RemoteInputContract.decodeCommand(JSONObject(validCommit.toString()).put("sequence", 1.0)))
    }

    @Test
    fun `unsupported versions types actions and short session ids are rejected`() {
        val valid = RemoteInputContract.encodeCommand(
            RemoteInputCommand.PerformEditorAction(sessionId, 1, RemoteEditorAction.NEXT),
        )

        assertNull(RemoteInputContract.decodeCommand(JSONObject(valid.toString()).put("version", 2)))
        assertNull(RemoteInputContract.decodeCommand(JSONObject(valid.toString()).put("type", "paste_document")))
        assertNull(RemoteInputContract.decodeCommand(JSONObject(valid.toString()).put("action", "shell")))
        assertNull(RemoteInputContract.decodeCommand(JSONObject(valid.toString()).put("sessionId", "short")))
    }

    @Test
    fun `unknown fields are tolerated but total message size is bounded`() {
        val valid = RemoteInputContract.encodeCommand(
            RemoteInputCommand.CommitText(sessionId, 1, "a"),
        )

        assertEquals(
            RemoteInputCommand.CommitText(sessionId, 1, "a"),
            RemoteInputContract.decodeCommand(JSONObject(valid.toString()).put("future", true)),
        )
        assertNull(
            RemoteInputContract.decodeCommand(
                JSONObject(valid.toString()).put(
                    "future",
                    "x".repeat(RemoteInputContract.MAX_MESSAGE_CHARS),
                ),
            ),
        )
        assertNull(
            RemoteInputContract.decodeCommand(
                JSONObject(valid.toString()).put("future", "界".repeat(1_100)),
            ),
        )
    }

    @Test
    fun `ready applied rejected and closed statuses round trip`() {
        val statuses = listOf(
            RemoteInputStatus(sessionId, RemoteInputStatusCode.READY, 0),
            RemoteInputStatus(sessionId, RemoteInputStatusCode.APPLIED, 4),
            RemoteInputStatus(
                sessionId,
                RemoteInputStatusCode.REJECTED,
                4,
                expectedSequence = 5,
                errorCode = RemoteInputErrorCode.OUT_OF_ORDER_SEQUENCE,
            ),
            RemoteInputStatus(sessionId, RemoteInputStatusCode.CLOSED, 9),
        )

        statuses.forEach { status ->
            assertEquals(
                status,
                RemoteInputContract.decodeStatus(RemoteInputContract.encodeStatus(status)),
            )
        }
    }

    @Test
    fun `status error consistency is enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputContract.encodeStatus(
                RemoteInputStatus(sessionId, RemoteInputStatusCode.REJECTED, 0),
            )
        }
        val applied = RemoteInputContract.encodeStatus(
            RemoteInputStatus(sessionId, RemoteInputStatusCode.APPLIED, 1),
        )
        assertNull(
            RemoteInputContract.decodeStatus(
                JSONObject(applied.toString()).put("errorCode", "internal"),
            ),
        )
        assertNull(
            RemoteInputContract.decodeStatus(
                JSONObject(applied.toString()).put("expectedSequence", 0),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputContract.encodeStatus(
                RemoteInputStatus(
                    sessionId,
                    RemoteInputStatusCode.APPLIED,
                    1,
                    expectedSequence = 2,
                ),
            )
        }
    }

    @Test
    fun `sequence guard refuses stale wrong-session skipped and replayed commands`() {
        val next = RemoteInputCommand.CommitText(sessionId, 8, "x")

        assertTrue(RemoteInputContract.accepts(sessionId, 7, next))
        assertFalse(RemoteInputContract.accepts("session_fedcba9876543210", 7, next))
        assertFalse(RemoteInputContract.accepts(sessionId, 6, next))
        assertFalse(RemoteInputContract.accepts(sessionId, 8, next))
    }
}
