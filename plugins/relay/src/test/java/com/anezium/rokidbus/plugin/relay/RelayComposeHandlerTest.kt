package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayComposeHandlerTest {
    private val page = RelayPages.threadPageId("t1")
    private var authority = true
    private var outcome: ReplySendResult = ReplySendResult.Sent
    private val sends = mutableListOf<Pair<String, String>>()
    private var changes = 0

    private val handler = RelayComposeHandler(
        threadIdOfPage = { if (it == page) "t1" else null },
        hasAuthority = { authority },
        send = { thread, text ->
            sends += thread to text
            outcome
        },
        changed = { changes += 1 },
    )

    private fun send(
        invocation: String = "i1",
        text: String? = "On my way",
        pageId: String = page,
        actionId: String = RelayPages.SEND_ACTION_ID,
        compose: Boolean = true,
        typed: Boolean = true,
    ): NexusPageResult = handler.handle(invocation, pageId, actionId, compose, typed, text)

    @Test
    fun `a send with live authority is handed to the app once per invocation`() {
        val result = send()
        assertEquals(NexusPageResultStatus.DONE, result.status)
        assertEquals(RelayComposeHandler.HANDED_OFF_MESSAGE, result.message)
        assertNull(result.code)
        assertEquals(listOf("t1" to "On my way"), sends)
        // A repeat of the same invocation gets the first answer and sends nothing.
        assertSame(result, send())
        assertEquals(1, sends.size)
    }

    @Test
    fun `authority is checked just before the send, and its loss sends nothing`() {
        authority = false
        val result = send()
        assertEquals(NexusPageResultStatus.REJECTED, result.status)
        assertEquals(NexusPageResult.CODE_AUTHORITY_LOST, result.code)
        assertTrue(sends.isEmpty())
        // The page that still offered Send is told to follow.
        assertEquals(1, changes)
        // A thread Relay no longer knows has no authority either.
        val unknown = send(invocation = "i2", pageId = RelayPages.threadPageId("gone"))
        assertEquals(NexusPageResult.CODE_AUTHORITY_LOST, unknown.code)
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `an app that can no longer take the reply is authority_lost, tried once and never again`() {
        val gone = listOf(
            ReplySendResult.Missing,
            ReplySendResult.NoFreeFormInput,
            ReplySendResult.Failed("CanceledException", authorityLost = true),
        )
        gone.forEachIndexed { index, result ->
            outcome = result
            val answer = send(invocation = "i$index")
            assertEquals(NexusPageResultStatus.REJECTED, answer.status)
            assertEquals(NexusPageResult.CODE_AUTHORITY_LOST, answer.code)
            assertSame(answer, send(invocation = "i$index"))
        }
        assertEquals(gone.size, sends.size)
    }

    @Test
    fun `a send that failed without saying whether the app has it is unconfirmed, not lost`() {
        outcome = ReplySendResult.Failed("SendIntentException")
        val result = send()
        assertEquals(NexusPageResultStatus.REJECTED, result.status)
        assertNull(result.code)
        assertEquals(RelayComposeHandler.UNCONFIRMED_MESSAGE, result.message)
        assertEquals(1, sends.size)
    }

    @Test
    fun `only the composer's send with text reaches the app`() {
        val refused = listOf(
            send(invocation = "a", text = "   "),
            send(invocation = "b", compose = false),
            send(invocation = "c", typed = false),
            send(invocation = "d", actionId = "other"),
            send(invocation = "e", pageId = RelayPages.MAIN),
        )
        refused.forEach {
            assertEquals(NexusPageResultStatus.REJECTED, it.status)
            assertNull(it.code)
        }
        assertTrue(sends.isEmpty())
        outcome = ReplySendResult.Blank
        val blank = send(invocation = "f")
        assertEquals(NexusPageResultStatus.REJECTED, blank.status)
        assertNull(blank.code)
    }
}
