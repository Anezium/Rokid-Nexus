package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOptionsMenuTest {
    @Test
    fun `opening with the grant asks the hub and a tap flips the answer`() {
        val menu = AssistantOptionsMenu()
        assertFalse(menu.isOpen)
        assertNull(menu.view())

        assertTrue(menu.open(assistantGranted = true))
        assertEquals(AssistantOptionsMenu.State.Loading, menu.state)
        assertEquals("Checking…", menu.view()?.sub)
        // Nothing to flip until the hub has answered.
        assertEquals(AssistantOptionsMenu.Action.None, menu.onConfirm())

        menu.onStatus(takeover = true)
        assertEquals(AssistantOptionsMenu.TEXT_NEXUS, menu.view()?.text)
        assertEquals(AssistantOptionsMenu.FOOTER_SWITCH, menu.view()?.footer)

        assertEquals(AssistantOptionsMenu.Action.Set(takeover = false), menu.onConfirm())
        assertEquals(AssistantOptionsMenu.State.Loading, menu.state)
        menu.onStatus(takeover = false)
        assertEquals(AssistantOptionsMenu.TEXT_ROKID, menu.view()?.text)
        assertEquals(AssistantOptionsMenu.Action.Set(takeover = true), menu.onConfirm())

        menu.close()
        assertFalse(menu.isOpen)
        // Late answers to a closed menu do not reopen it.
        menu.onStatus(takeover = true)
        menu.onError("INVALID_REQUEST")
        assertFalse(menu.isOpen)
    }

    @Test
    fun `without the grant the menu explains instead of asking`() {
        val menu = AssistantOptionsMenu()

        assertFalse(menu.open(assistantGranted = false))
        assertEquals(
            AssistantOptionsMenu.State.Unavailable(AssistantOptionsMenu.REASON_NOT_GRANTED),
            menu.state,
        )
        assertEquals(AssistantOptionsMenu.TEXT_UNKNOWN, menu.view()?.text)
        assertEquals(AssistantOptionsMenu.FOOTER_CLOSE, menu.view()?.footer)
        assertEquals(AssistantOptionsMenu.Action.None, menu.onConfirm())
    }

    @Test
    fun `hub errors map to something the wearer can act on`() {
        val menu = AssistantOptionsMenu()
        menu.open(assistantGranted = true)

        menu.onError("PLUGIN_NAMESPACE_DENIED")
        assertEquals(AssistantOptionsMenu.REASON_HUB_TOO_OLD, menu.view()?.sub)

        menu.onError("CAPABILITY_REQUIRED_ASSISTANT")
        assertEquals(AssistantOptionsMenu.REASON_NOT_GRANTED, menu.view()?.sub)

        menu.onError("INVALID_REQUEST")
        assertEquals("Nexus did not answer (INVALID_REQUEST).", menu.view()?.sub)
        assertEquals(AssistantOptionsMenu.Action.None, menu.onConfirm())
    }
}
