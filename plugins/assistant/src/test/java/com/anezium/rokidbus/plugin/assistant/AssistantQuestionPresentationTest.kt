package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantQuestionPresentationTest {
    @Test
    fun `repeated HUD entries retire every replaced id so back closes the plugin`() {
        val hub = FakeHub()
        hub.show("launcher")
        var previous = "launcher"
        listOf("question-one", "question-two", "note").forEach { next ->
            assertEquals(
                NexusSdkResult.SENT,
                replaceAssistantSurface(
                    showReplacement = { hub.show(next) },
                    retirePrevious = { hub.hide(previous) },
                ),
            )
            assertEquals(setOf(next), hub.registeredSurfaces)
            assertEquals(0, hub.selfCloses)
            previous = next
        }
        hub.hide(previous)
        assertTrue(hub.registeredSurfaces.isEmpty())
        assertEquals(1, hub.selfCloses)
    }

    @Test
    fun `failed replacement leaves the current surface usable until back`() {
        val hub = FakeHub()
        hub.show("launcher")
        assertEquals(
            NexusSdkResult.SURFACE_BUSY,
            replaceAssistantSurface(
                showReplacement = { NexusSdkResult.SURFACE_BUSY },
                retirePrevious = { hub.hide("launcher") },
            ),
        )
        assertEquals(setOf("launcher"), hub.registeredSurfaces)
        assertEquals(0, hub.selfCloses)
        hub.hide("launcher")
        assertEquals(1, hub.selfCloses)
    }

    @Test
    fun `new content keys prevent editor and completed card metadata leaking into thinking`() {
        val thinking = assistantPlainCard(listOf("Thinking…"), false, true)
        val streaming = assistantPlainCard(listOf("Partial"), false, true)
        val answer = assistantPlainCard(listOf("Answer"), true, true)
        assertNotEquals("assistant-editor", thinking.contentKey)
        assertEquals(thinking.contentKey, streaming.contentKey)
        assertNotEquals(thinking.contentKey, answer.contentKey)
        assertNull(thinking.editable)
        assertNull(thinking.subtitle)
        assertTrue(thinking.footer.isNullOrEmpty())
        assertNull(answer.editable)
        assertNull(answer.subtitle)
        assertEquals("Tap to write a question · Back to close", answer.footer)
        assertEquals(
            "Write in phone settings · Back to close",
            assistantPlainCard(listOf("Answer"), true, false).footer,
        )
    }

    @Test
    fun `blank common pipeline input never reaches the provider`() {
        var called = false
        assertFalse(dispatchAssistantQuestion(" \t\n") { called = true })
        assertFalse(called)
    }

    private class FakeHub {
        val registeredSurfaces = mutableSetOf<String>()
        var selfCloses = 0
            private set

        fun show(id: String): NexusSdkResult {
            registeredSurfaces += id
            return NexusSdkResult.SENT
        }

        fun hide(id: String) {
            registeredSurfaces -= id
            if (registeredSurfaces.isEmpty()) selfCloses += 1
        }
    }
}
