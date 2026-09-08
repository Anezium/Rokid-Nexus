package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.*
import org.junit.Test

class LitterConversationSelectionTest {
    @Test fun `phone navigation selects once then follows HUD without reopening the former session`() {
        val selection = LitterConversationSelection("thread-a")
        assertNull(selection.observe("thread-b", "Draft for A"))
        assertNull(selection.requestResume(visible = false, connected = true, sharedSessionId = "thread-b"))
        assertEquals("thread-a", selection.requestResume(visible = true, connected = true, sharedSessionId = "thread-b"))
        assertNull(selection.observe("thread-a", "Draft for A"))
        assertEquals("", selection.observe("thread-b", "Draft for A"))
        assertEquals("thread-b", selection.sessionId)
        assertNull(selection.requestResume(visible = true, connected = true, sharedSessionId = "thread-b"))
        assertNull(selection.requestResume(visible = true, connected = false, sharedSessionId = "thread-b"))
        assertNull(selection.requestResume(visible = true, connected = true, sharedSessionId = "thread-b"))
        assertEquals("thread-b", selection.requestResume(visible = true, connected = true, sharedSessionId = null))
        assertEquals("Draft for A", selection.observe("thread-a", "Draft for B"))
        assertEquals("Draft for B", selection.observe("thread-b", "Draft for A"))
    }

    @Test fun `acknowledged prompt clears only its own saved draft after selection changes`() {
        val selection = LitterConversationSelection("thread-a")
        selection.requestResume(visible = true, connected = true, sharedSessionId = null)
        selection.observe("thread-b", "Sent prompt A")
        selection.sent("thread-a", "Sent prompt A")
        assertEquals("", selection.observe("thread-a", "Unsent prompt B"))
        assertEquals("Unsent prompt B", selection.observe("thread-b", ""))
    }

    @Test fun `new session draft cannot follow an existing conversation before creation`() {
        val selection = LitterConversationSelection(null)
        assertNull(selection.observe("thread-a", "New project prompt"))
        assertNull(selection.requestResume(visible = true, connected = true, sharedSessionId = "thread-a"))
        assertNull(selection.sessionId)
        selection.created("thread-new")
        assertEquals("", selection.observe("thread-b", "New project prompt"))
        assertEquals("New project prompt", selection.observe("thread-new", "Another prompt"))
    }
}
