package com.anezium.rokidbus.phone

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputPhoneContractTest {
    @Test
    fun `sequence is monotonic inside a field and restarts for another session`() {
        val sequence = RemoteInputSequence()

        sequence.reset("field-a")
        assertEquals(1L, sequence.next("field-a"))
        assertEquals(2L, sequence.next("field-a"))
        assertEquals(1L, sequence.next("field-b"))
        assertEquals(2L, sequence.next("field-b"))
    }

    @Test
    fun `paste chunks stay inside wire limit without splitting a surrogate pair`() {
        val emoji = "\uD83D\uDE80"
        val source = "a".repeat(RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16 - 1) + emoji + "tail"

        val chunks = RemoteTextChunks.split(source)

        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16 })
        assertFalse(chunks.first().last().isHighSurrogate())
        assertEquals(listOf(""), RemoteTextChunks.split(""))
    }

    @Test
    fun `cjk paste chunks also stay inside utf8 byte limit`() {
        val source = "界".repeat(400)

        val chunks = RemoteTextChunks.split(source)

        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF16 })
        assertTrue(chunks.all {
            it.toByteArray(StandardCharsets.UTF_8).size <= RemoteInputPhoneContract.MAX_TEXT_DELTA_UTF8
        })
    }

    @Test
    fun `disconnected and idle field states keep keyboard disabled but idle keeps remote enabled`() {
        val disconnected = RemoteInputViewState.from(
            RemoteInputTransportState(connected = false, fieldActive = false),
        )
        val idle = RemoteInputViewState.from(
            RemoteInputTransportState(connected = true, fieldActive = false),
        )

        assertEquals(RemoteInputViewState.Phase.DISCONNECTED, disconnected.phase)
        assertFalse(disconnected.editorEnabled)
        assertFalse(disconnected.controlsEnabled)
        assertEquals(RemoteInputViewState.Phase.WAITING_FOR_FIELD, idle.phase)
        assertFalse(idle.editorEnabled)
        assertTrue(idle.controlsEnabled)
    }

    @Test
    fun `password field enables secure UI without carrying remote contents`() {
        val ready = RemoteInputViewState.from(
            RemoteInputTransportState(
                connected = true,
                fieldActive = true,
                password = true,
                sessionId = "login-password",
                fieldLabel = "Password",
                imeAction = RemoteInputPhoneContract.IME_ACTION_NEXT,
            ),
        )

        assertTrue(ready.editorEnabled)
        assertTrue(ready.secureWindow)
        assertEquals(RemoteInputPhoneContract.EDITOR_NEXT, ready.primaryAction)
        assertEquals("Password", ready.fieldLabel)
        assertNull(RemoteInputTransportState(false, false).sessionId)
    }

    @Test
    fun `a self-opened screen closes once its requested field is gone`() {
        assertFalse(keyboardRequestEnded(true, false, null, null))
        assertFalse(keyboardRequestEnded(true, false, "relay-reply", "relay-reply"))
        assertTrue(keyboardRequestEnded(true, false, "relay-reply", null))
        assertTrue(keyboardRequestEnded(true, false, "relay-reply", "launcher-search"))
    }

    @Test
    fun `a screen opened by hand or taken over by the pointer never closes itself`() {
        assertFalse(keyboardRequestEnded(false, false, "relay-reply", null))
        assertFalse(keyboardRequestEnded(true, true, "relay-reply", null))
    }
}
