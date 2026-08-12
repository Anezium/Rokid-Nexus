package com.anezium.rokidbus.glasses

import android.view.WindowManager
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeOverlayRendererTest {
    @Test
    fun `a new notice reverses an exit at every fade point`() {
        listOf(1f, 0.75f, 0.01f, 0f).forEach { fadeAlpha ->
            assertEquals(
                NoticeRenderMotion.REENTER,
                noticeRenderMotion(fadeAlpha, exitRunning = true),
            )
        }
    }

    @Test
    fun `a notice enters from detached alpha zero`() {
        assertEquals(
            NoticeRenderMotion.ENTER,
            noticeRenderMotion(fadeAlpha = 0f, exitRunning = false),
        )
    }

    @Test
    fun `an ordinary visible update does not restart entry`() {
        assertEquals(
            NoticeRenderMotion.UPDATE,
            noticeRenderMotion(fadeAlpha = 0.4f, exitRunning = false),
        )
        assertEquals(
            NoticeRenderMotion.UPDATE,
            noticeRenderMotion(fadeAlpha = 1f, exitRunning = false),
        )
    }

    @Test
    fun `only an engaged assistant notice starts an episode`() {
        assertTrue(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:notice",
                engaged = true,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:notice",
                engaged = false,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "relay:notice",
                engaged = true,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:activity",
                engaged = true,
            ),
        )
    }

    @Test
    fun `a notice window keeps the screen on for as long as it is up`() {
        val flags = noticeWindowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun `a notice editor is focusable but remains untouchable`() {
        val flags = noticeWindowFlags(textInputActive = true)

        assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertFalse(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun `Ink morph dismissal fades in place while ordinary dismissal still slides`() {
        assertEquals(
            NoticeDismissMotion.INK_FADE_IN_PLACE,
            noticeDismissMotion(inkMorphActive = true),
        )
        assertEquals(
            NoticeDismissMotion.SLIDE_AND_FADE,
            noticeDismissMotion(inkMorphActive = false),
        )
    }

    @Test
    fun `a newer notice cannot inherit or tear down an outgoing notice morph`() {
        val token = NoticeInkMorphToken(
            surfaceId = "assistant:notice",
            seq = 7L,
            ownerPluginId = "assistant",
            bandHeightPx = 84,
            initialAlpha = 1f,
        )
        val outgoing = NexusNoticeSurface(
            surfaceId = "assistant:notice",
            seq = 7L,
            content = NoticeSurfaceContent("Thinking", null, null),
            expiresAtMs = 1_000L,
            hardExpiresAtMs = 2_000L,
            ownerPluginId = "assistant",
        )

        assertTrue(token.matches(outgoing))
        assertFalse(token.matches(outgoing.copy(seq = 8L)))
        assertFalse(token.matches(outgoing.copy(surfaceId = "relay:notice")))
        assertFalse(token.matches(outgoing.copy(ownerPluginId = "relay")))
    }
}
