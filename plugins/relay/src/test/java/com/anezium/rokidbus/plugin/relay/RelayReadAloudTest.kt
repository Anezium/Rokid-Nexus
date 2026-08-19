package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.TtsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReadAloudTest {
    @Test
    fun `disabled reading produces no speech`() {
        assertNull(
            RelayReadAloud.textFor(
                enabled = false,
                senderOnly = false,
                sender = "Alice",
                renderedThread = "Alice: This stays silent",
            ),
        )
    }

    @Test
    fun `only the newest thread message is spoken with its speaker`() {
        assertEquals(
            "Bob: The newest message in full",
            RelayReadAloud.textFor(
                enabled = true,
                senderOnly = false,
                sender = "Weekend plans",
                renderedThread = "Alice: The older message\nBob: The newest message in full",
            ),
        )
    }

    @Test
    fun `the notice sender labels a newest message without its own speaker`() {
        assertEquals(
            "Alice: Message without a label",
            RelayReadAloud.textFor(
                enabled = true,
                senderOnly = false,
                sender = "Alice",
                renderedThread = "Message without a label",
            ),
        )
    }

    @Test
    fun `speech cap keeps the newest 1024 characters`() {
        val newestWords = "n".repeat(TtsContract.MAX_TEXT_CHARS)
        val spoken = RelayReadAloud.textFor(
            enabled = true,
            senderOnly = false,
            sender = "Alice",
            renderedThread = "Alice: old\nAlice: prefix-$newestWords",
        )

        assertEquals(newestWords, spoken)
        assertTrue(spoken!!.length <= TtsContract.MAX_TEXT_CHARS)
    }

    @Test
    fun `sender-only mode names the sender and never the thread text`() {
        assertEquals(
            "Message from Alice",
            RelayReadAloud.textFor(
                enabled = true,
                senderOnly = true,
                sender = "Alice",
                renderedThread = "Alice: The secret text that must not be spoken",
            ),
        )
    }

    @Test
    fun `sender-only mode with a blank sender produces no speech`() {
        assertNull(
            RelayReadAloud.textFor(
                enabled = true,
                senderOnly = true,
                sender = "",
                renderedThread = "Alice: The secret text that must not be spoken",
            ),
        )
    }

    @Test
    fun `disabled reading in sender-only mode produces no speech`() {
        assertNull(
            RelayReadAloud.textFor(
                enabled = false,
                senderOnly = true,
                sender = "Alice",
                renderedThread = "Alice: The secret text that must not be spoken",
            ),
        )
    }
}
