package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloPluginStateTest {
    @Test
    fun `selection wraps once in either direction`() {
        val state = HelloPluginState()
        state.move(-1)
        assertEquals(5, state.selectedIndex)
        state.move(1)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `tap marks only the selected row`() {
        val state = HelloPluginState()
        state.move(1)
        state.activate()
        assertEquals(1, state.presentation().lines.count { "✓" in it })
        assertTrue("✓" in state.presentation().lines[1])
    }

    @Test
    fun `menu activation stays unchanged for the original three choices`() {
        val state = HelloPluginState()

        repeat(3) { index ->
            while (state.selectedIndex != index) state.move(1)
            assertEquals(HelloPluginAction.RENDER, state.activate())
            assertEquals(1, state.presentation().lines.count { "✓" in it })
            assertTrue("✓" in state.presentation().lines[index])
            assertEquals(HelloPluginMode.MENU, state.mode)
        }
    }

    @Test
    fun `speak row triggers the one-line TTS demo without leaving the menu`() {
        val state = HelloPluginState()
        repeat(3) { state.move(1) }

        assertEquals(HelloPluginAction.SPEAK_TTS, state.activate())
        assertEquals(HelloPluginMode.MENU, state.mode)
        assertTrue("✓" in state.presentation().lines[3])
    }

    @Test
    fun `background mic row starts raw audio without leaving the menu`() {
        val state = HelloPluginState()
        state.move(-1)

        assertEquals(HelloPluginAction.START_BACKGROUND_AUDIO, state.activate())
        assertEquals(HelloPluginMode.MENU, state.mode)
        assertTrue("✓" in state.presentation().lines[5])
    }

    @Test
    fun `partial is replaced by final and multiple finals accumulate`() {
        val state = liveDictationState()
        state.onSpeechStarted(realtime = true)

        state.onSpeechPartial("hello wor")
        assertEquals("hello wor", state.presentation().lines[2])

        state.onSpeechFinal("hello world")
        state.onSpeechPartial("again...")
        assertEquals("hello world again...", state.presentation().lines[2])

        state.onSpeechFinal("again")
        state.onSpeechFinal("and done")
        assertEquals("hello world again and done", state.presentation().lines[2])
    }

    @Test
    fun `transcript wraps on words and hard splits long words at the hud width`() {
        assertEquals(
            listOf("one two", "x".repeat(28), "${"x".repeat(11)} three"),
            wrapDictationTranscript("one two ${"x".repeat(39)} three"),
        )
        assertEquals(
            listOf("x".repeat(28), "x".repeat(17)),
            wrapDictationTranscript("x".repeat(45)),
        )
    }

    @Test
    fun `transcript keeps only the last six wrapped lines`() {
        val words = (1..8).joinToString(" ") { "${it}${"x".repeat(19)}" }
        val lines = wrapDictationTranscript(words)

        assertEquals(6, lines.size)
        assertTrue(lines.first().startsWith("3"))
        assertTrue(lines.last().startsWith("8"))
        assertFalse(lines.any { it.startsWith("1") })
    }

    @Test
    fun `empty transcript uses realtime and batch placeholders`() {
        val realtime = liveDictationState()
        assertEquals(listOf("Starting...", "", "Say something..."), realtime.presentation().lines)
        realtime.onSpeechStarted(realtime = true)
        assertEquals(listOf("Listening...", "", "Say something..."), realtime.presentation().lines)

        val batch = liveDictationState()
        batch.onSpeechStarted(realtime = false)
        assertEquals(
            listOf("Listening... (batch)", "", "Text arrives when you stop."),
            batch.presentation().lines,
        )
    }

    @Test
    fun `speech states use the requested live status copy`() {
        val state = liveDictationState()
        state.onSpeechStarted(realtime = true)

        val expected = mapOf(
            NexusSpeechState.LISTENING to "Listening...",
            NexusSpeechState.RECOGNIZING to "Recognizing...",
            NexusSpeechState.PROCESSING to "Transcribing...",
        )
        expected.forEach { (speechState, status) ->
            state.onSpeechState(speechState)
            assertEquals(status, state.presentation().lines[0])
        }
    }

    @Test
    fun `immediate start failures use permission and availability copy`() {
        val permission = liveDictationState()
        permission.onSpeechStartResult(NexusSdkResult.CAPABILITY_NOT_GRANTED)
        assertEquals(
            "Grant Speech to text in Nexus settings.",
            permission.presentation().lines[0],
        )

        listOf(null, NexusSdkResult.NOT_REGISTERED, NexusSdkResult.CAPABILITY_NOT_AVAILABLE)
            .forEach { result ->
                val unavailable = liveDictationState()
                unavailable.onSpeechStartResult(result)
                assertEquals(
                    "Speech isn't available right now.",
                    unavailable.presentation().lines[0],
                )
            }
    }

    @Test
    fun `every stop reason maps to its ended copy`() {
        val expected = mapOf(
            NexusSpeechStopReason.COMPLETED to "Nothing heard.",
            NexusSpeechStopReason.CANCELLED to "Stopped.",
            NexusSpeechStopReason.NO_SPEECH to "Didn't catch that.",
            NexusSpeechStopReason.ERROR to "Speech failed.",
            NexusSpeechStopReason.LINK_LOST to "Glasses link lost.",
            NexusSpeechStopReason.REVOKED to "Speech access was revoked.",
            NexusSpeechStopReason.DENIED_BUSY to "Speech is busy - try again in a moment.",
            NexusSpeechStopReason.DENIED_NO_LINK to "No glasses link.",
            NexusSpeechStopReason.DENIED_NOT_READY to "Add a speech API key in Nexus > Speech.",
            NexusSpeechStopReason.DENIED_START_FAILED to "Couldn't start the recorder.",
            NexusSpeechStopReason.DENIED_INVALID to "Speech request was rejected.",
        )

        assertEquals(NexusSpeechStopReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, line) ->
            val state = liveDictationState()
            state.onSpeechStopped(reason, null)
            assertEquals(line, state.presentation().lines[0])
        }

        val completed = liveDictationState()
        completed.onSpeechFinal("transcript")
        completed.onSpeechStopped(NexusSpeechStopReason.COMPLETED, null)
        assertEquals("Done.", completed.presentation().lines[0])
    }

    @Test
    fun `speech error renders kind and provider but never detail`() {
        val state = liveDictationState()
        state.onSpeechStopped(
            NexusSpeechStopReason.ERROR,
            NexusSpeechError(
                kind = "provider_error",
                provider = "example",
                detail = "private quoted speech",
            ),
        )

        assertEquals("provider_error · example", state.presentation().lines[1])
        assertFalse(state.presentation().lines.any { "private quoted speech" in it })
    }

    @Test
    fun `retry after ended session clears transcript`() {
        val state = liveDictationState()
        state.onSpeechFinal("old transcript")
        state.onSpeechStopped(NexusSpeechStopReason.COMPLETED, null)
        assertTrue(state.presentation().lines.any { it == "old transcript" })

        assertEquals(HelloPluginAction.START_SPEECH, state.activate())
        assertEquals(
            listOf("Starting...", "", "Say something..."),
            state.presentation().lines,
        )
    }

    private fun liveDictationState(): HelloPluginState = HelloPluginState().apply {
        repeat(4) { move(1) }
        assertEquals(HelloPluginAction.START_SPEECH, activate())
    }
}
