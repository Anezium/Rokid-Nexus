package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.GlassesAssistSession.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesAssistSessionTest {
    private val session = GlassesAssistSession()

    @Test
    fun `a native prompter text report protects the scene until it ends`() {
        session.onScenesReport("wordTipsRunning")
        assertEquals(setOf("wordTipsRunning"), session.scenes)
        assertEquals(Press.NATIVE_SCENE, session.onPressStart(linkUp = true, takeoverEnabled = true))
        assertFalse(session.isHeld)
        assertTrue(session.nativeSceneRunning())

        session.onScenesReport("")
        assertFalse(session.nativeSceneRunning())
        assertEquals(Press.TAKE_OVER, session.onPressStart(linkUp = true, takeoverEnabled = true))
    }

    @Test
    fun `a press is held only once every gate passes`() {
        assertEquals(Press.TAKE_OVER, session.onPressStart(linkUp = true, takeoverEnabled = true))
        assertTrue(session.isHeld)
        assertEquals(Press.ALREADY_HELD, session.onPressStart(linkUp = true, takeoverEnabled = true))
        session.onPressStop()
        assertFalse(session.isHeld)
    }

    @Test
    fun `a press left to Rokid is never held, so the next press is not swallowed`() {
        assertEquals(Press.LINK_DOWN, session.onPressStart(linkUp = false, takeoverEnabled = true))
        assertFalse(session.isHeld)
        assertEquals(
            Press.TAKEOVER_PAUSED,
            session.onPressStart(linkUp = true, takeoverEnabled = false),
        )
        assertFalse(session.isHeld)
        session.onScenesReport("""["phoneCallRunning"]""")
        assertEquals(Press.NATIVE_SCENE, session.onPressStart(linkUp = true, takeoverEnabled = true))
        assertFalse(session.isHeld)

        session.onScenesReport("[]")
        assertEquals(Press.TAKE_OVER, session.onPressStart(linkUp = true, takeoverEnabled = true))
    }

    @Test
    fun `the link gate comes first and the pause before the scene gate`() {
        session.onScenesReport("""["phoneCallRunning"]""")
        assertEquals(Press.LINK_DOWN, session.onPressStart(linkUp = false, takeoverEnabled = false))
        assertEquals(
            Press.TAKEOVER_PAUSED,
            session.onPressStart(linkUp = true, takeoverEnabled = false),
        )
    }

    @Test
    fun `accessibility alone keeps the button with Nexus`() {
        session.onScenesReport("""{"accessibilityRunning":true}""")
        assertEquals(Press.TAKE_OVER, session.onPressStart(linkUp = true, takeoverEnabled = true))
        assertFalse(session.nativeSceneRunning())
    }

    @Test
    fun `a scene report says whether the running set or its shape changed`() {
        assertTrue(session.onScenesReport("""["otaRunning"]"""))
        assertFalse(session.onScenesReport("""["otaRunning"]"""))
        assertTrue(session.onScenesReport("""["otaRunning","launcher"]"""))
        assertEquals(setOf("otaRunning"), session.scenes)
        assertEquals("array[2]", session.scenesShape)
        assertTrue(session.nativeSceneRunning())
    }

    @Test
    fun `a press is held until released, and a later press is a different one`() {
        session.onPressStart(linkUp = true, takeoverEnabled = true)
        val first = session.press
        assertTrue(session.isHeld(first))

        session.onPressStop()
        assertFalse(session.isHeld(first))

        session.onPressStart(linkUp = true, takeoverEnabled = true)
        assertFalse(session.isHeld(first))
        assertTrue(session.isHeld(session.press))
    }

    @Test
    fun `a press left to Rokid does not become the current press`() {
        session.onPressStart(linkUp = true, takeoverEnabled = true)
        val taken = session.press
        session.onPressStop()
        session.onPressStart(linkUp = true, takeoverEnabled = false)
        assertEquals(taken, session.press)
    }

    @Test
    fun `work scheduled by a press stops with its session, not with the release of the press`() {
        val generation = session.generation
        session.onPressStart(linkUp = true, takeoverEnabled = true)
        session.onPressStop()
        session.onScenesReport("""["otaRunning"]""")
        assertTrue(session.isCurrent(generation))

        session.clear()

        assertFalse(session.isCurrent(generation))
        assertTrue(session.isCurrent(session.generation))
    }

    @Test
    fun `ending the session releases a held press and forgets the scenes`() {
        session.onScenesReport("""["navigationRunning"]""")
        session.onScenesReport("[]")
        session.onPressStart(linkUp = true, takeoverEnabled = true)
        session.onScenesReport("""["navigationRunning"]""")

        assertTrue(session.clear())

        assertFalse(session.isHeld)
        assertEquals(emptySet<String>(), session.scenes)
        assertEquals("", session.scenesShape)
        assertFalse(session.clear())
        assertEquals(Press.TAKE_OVER, session.onPressStart(linkUp = true, takeoverEnabled = true))
    }
}
