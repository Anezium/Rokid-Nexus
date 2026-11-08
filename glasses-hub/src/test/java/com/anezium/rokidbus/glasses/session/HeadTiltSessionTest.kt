package com.anezium.rokidbus.glasses.session

import org.junit.Assert.*
import org.junit.Test

class HeadTiltSessionTest {
    private val reducer = SessionReducer()
    private val event = SessionEvent.HeadTilt(1_000, null, null, Underneath.NativeApp)

    @Test fun `head tilt opens root without a touchpad contact gate`() {
        val opened = reducer.reduce(SessionModel(), event)
        assertTrue(opened.model.state is SessionState.Root)
        assertEquals(1L, opened.model.generation)
        assertEquals(Underneath.NativeApp, opened.model.underneath)
        assertTrue(opened.effects.contains(SessionEffect.ShowRoot))
        val closed = reducer.reduce(opened.model, SessionEvent.Back(2_000))
        assertEquals(SessionState.Closed, closed.model.state)
    }

    @Test fun `head tilt honors notice and editable gates and never changes an open session`() {
        for (blocked in listOf(SessionModel(noticeArmed = true), SessionModel(editableFocused = true))) {
            assertEquals(blocked.state, reducer.reduce(blocked, event).model.state)
        }
        val root = reducer.reduce(SessionModel(), event).model
        assertEquals(root, reducer.reduce(root, event).model)
    }

    @Test fun `plugin target uses existing correlated launch and backs to root`() {
        val opened = reducer.reduce(SessionModel(), event.copy(pluginId = "relay"))
        val launching = opened.model.state as SessionState.Launching
        assertEquals("relay", launching.pluginId)
        assertTrue(opened.effects.any { it is SessionEffect.SendLauncherOpen })
        assertTrue(reducer.reduce(opened.model, SessionEvent.Back(2_000)).model.state is SessionState.Root)
    }
}
