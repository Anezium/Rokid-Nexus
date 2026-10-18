package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * A line wider than its room keeps its whole text and, while active and shown, scrolls to the
 * hidden end; it never moves at rest, unattached, hidden, or when the text fits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverflowLineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val long = "Gare de Goussainville, Val-d'Oise, Île-de-France"

    @Test
    fun `the timing holds, travels at a reading pace, holds at the end, then starts again`() {
        val overflow = 180
        assertEquals(0, OverflowScroll.offsetAt(0, overflow))
        assertEquals(0, OverflowScroll.offsetAt(OverflowScroll.HOLD_MS - 1, overflow))
        assertEquals(36, OverflowScroll.offsetAt(OverflowScroll.HOLD_MS + 1_000, overflow))
        val travel = OverflowScroll.travelMs(overflow)
        assertEquals(5_000, travel)
        assertEquals(overflow, OverflowScroll.offsetAt(OverflowScroll.HOLD_MS + travel, overflow))
        assertEquals(overflow, OverflowScroll.offsetAt(OverflowScroll.HOLD_MS + travel + OverflowScroll.END_HOLD_MS - 1, overflow))
        assertEquals(0, OverflowScroll.offsetAt(OverflowScroll.cycleMs(overflow), overflow))
        assertEquals(0, OverflowScroll.offsetAt(5_000, 0))
    }

    private fun line(width: Int, text: String, active: Boolean): Pair<OverflowLineView, FrameLayout> {
        val view = OverflowLineView(context).apply {
            this.text = text
            style(PageStyle.CARD_BODY_PX, PageStyle.ink, PageStyle.sans)
            this.active = active
        }
        val host = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)) }
        return view to host
    }

    private fun attach(host: View) {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        controller.get().setContentView(host)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `at rest a wide line is ellipsized and still holds its whole text`() {
        val (view, host) = line(200, long, active = false)
        attach(host)
        assertTrue(view.overflowPx() > 0)
        assertFalse(view.scrolling)
        assertEquals(long, view.text.toString())
        assertTrue(requireNotNull(view.layout).getEllipsisCount(0) > 0)
    }

    @Test
    fun `an active wide line scrolls to its end and back, and shows every character on the way`() {
        val (view, host) = line(200, long, active = true)
        attach(host)
        assertTrue(view.scrolling)
        assertEquals(0, view.scrollX)
        assertEquals(0, requireNotNull(view.layout).getEllipsisCount(0))
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue("moved after the hold: ${view.scrollX}", view.scrollX in 1 until view.overflowPx())
        idle(OverflowScroll.travelMs(view.overflowPx()))
        assertEquals(view.overflowPx(), view.scrollX)
        idle(OverflowScroll.END_HOLD_MS + OverflowScroll.FRAME_MS)
        assertEquals(0, view.scrollX)
        assertTrue(view.scrolling)
    }

    @Test
    fun `a line that fits never scrolls, active or not`() {
        val (view, host) = line(400, "Rue de Rivoli", active = true)
        attach(host)
        assertEquals(0, view.overflowPx())
        assertFalse(view.scrolling)
        idle(OverflowScroll.HOLD_MS + 2_000)
        assertEquals(0, view.scrollX)
    }

    @Test
    fun `losing the selection, being hidden or detached stops the line where it stands`() {
        val (view, host) = line(200, long, active = true)
        attach(host)
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(view.scrollX > 0)

        view.active = false
        assertFalse(view.scrolling)
        assertEquals(0, view.scrollX)
        idle(3_000)
        assertEquals(0, view.scrollX)

        view.active = true
        assertTrue(view.scrolling)
        host.visibility = View.GONE
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.scrolling)
        host.visibility = View.VISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.scrolling)

        host.removeView(view)
        assertFalse(view.scrolling)
        idle(3_000)
        assertEquals(0, view.scrollX)
    }
}
