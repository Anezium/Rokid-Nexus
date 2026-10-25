package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.os.Looper
import android.view.Display
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
 * hidden end; it never moves at rest, unattached, hidden, or when the text fits. A line its
 * owner rebuilds continues where the one it replaces was; a line hidden and shown again, resized,
 * or given a new text starts over. The island case drives the real [NavIslandView.bind].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverflowLineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val long = "Gare de Fontainebleau, Essonne-Sud, Île-de-France"

    @Test
    fun `a line whose hidden part changes keeps its place, its end hold and its passes`() {
        val hold = OverflowScroll.HOLD_MS
        // Shrunk mid-travel past the new end: it stands at that end and holds it.
        val shrunk = OverflowScroll.rebase(10_000, from = 342, to = 248)
        assertEquals(309, OverflowScroll.offsetAt(10_000, 342))
        assertEquals(248, OverflowScroll.offsetAt(shrunk, 248))
        assertEquals(hold + OverflowScroll.travelMs(248), shrunk)
        // Grown or shrunk before that end: the same pixel, give or take the rounding.
        val at = hold + 3_000
        val before = OverflowScroll.offsetAt(at, 248)
        assertTrue(kotlin.math.abs(OverflowScroll.offsetAt(OverflowScroll.rebase(at, 248, 342), 342) - before) <= 1)
        assertTrue(kotlin.math.abs(OverflowScroll.offsetAt(OverflowScroll.rebase(at, 342, 248), 248) - before) <= 1)
        // Still in the first hold, or in the end hold: the same time into it.
        assertEquals(500, OverflowScroll.rebase(500, 342, 248))
        val endHold = hold + OverflowScroll.travelMs(342) + 300
        assertEquals(hold + OverflowScroll.travelMs(248) + 300, OverflowScroll.rebase(endHold, 342, 248))
        // A pass already made stays made.
        assertEquals(OverflowScroll.cycleMs(248) + 500, OverflowScroll.rebase(OverflowScroll.cycleMs(342) + 500, 342, 248))
        assertEquals(1_234, OverflowScroll.rebase(1_234, 342, 342))
    }

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

    private fun line(text: String, active: Boolean, memory: OverflowMemory? = null, key: String? = null): OverflowLineView =
        OverflowLineView(context).apply {
            this.text = text
            style(PageStyle.CARD_BODY_PX, PageStyle.ink, PageStyle.sans)
            this.memory = memory
            this.memoryKey = key
            this.active = active
        }

    private fun host(width: Int, vararg children: View): FrameLayout = FrameLayout(context).apply {
        children.forEach { addView(it, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)) }
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
        val view = line(long, active = false)
        attach(host(200, view))
        assertTrue(view.overflowPx() > 0)
        assertFalse(view.scrolling)
        assertEquals(long, view.text.toString())
        assertTrue(requireNotNull(view.layout).getEllipsisCount(0) > 0)
    }

    @Test
    fun `an active wide line scrolls to its end and back, and shows every character on the way`() {
        val view = line(long, active = true)
        attach(host(200, view))
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
        val view = line("Rue de Rivoli", active = true)
        attach(host(400, view))
        assertEquals(0, view.overflowPx())
        assertFalse(view.scrolling)
        idle(OverflowScroll.HOLD_MS + 2_000)
        assertEquals(0, view.scrollX)
    }

    @Test
    fun `losing the selection, being hidden or detached stops the line where it stands`() {
        val view = line(long, active = true)
        val host = host(200, view)
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

    @Test
    fun `glasses that go dark stop the line, which starts over once they are lit`() {
        val memory = OverflowMemory()
        val view = line(long, active = true, memory = memory, key = "media:activity:primary")
        val host = host(200, view)
        attach(host)
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(view.scrollX > 0)

        screenState(host, View.SCREEN_STATE_OFF)
        assertFalse(view.scrolling)
        assertEquals(0, view.scrollX)
        idle(OverflowScroll.cycleMs(view.overflowPx()))
        assertEquals("no frame while dark", 0, view.scrollX)

        screenState(host, View.SCREEN_STATE_ON)
        assertTrue(view.scrolling)
        assertEquals(0, view.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals("it holds at the start again", 0, view.scrollX)
        idle(1_000)
        assertTrue(view.scrollX > 0)

        // The display going off stops it too, and a line put on a display that is already off
        // does not start.
        shadowOf(view.display).setState(Display.STATE_OFF)
        idle(OverflowScroll.FRAME_MS * 2)
        assertFalse(view.scrolling)
        assertEquals(0, view.scrollX)
        val late = line(long, active = true)
        host.addView(late, FrameLayout.LayoutParams(200, FrameLayout.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(late.overflowPx() > 0)
        assertFalse(late.scrolling)
        shadowOf(view.display).setState(Display.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(late.scrolling)
    }

    @Test
    fun `a line taken off while dark and put back on lit glasses starts over, and stays still while they are dark`() {
        val memory = OverflowMemory()
        val view = line(long, active = true, memory = memory, key = "media:activity:primary")
        val host = host(200, view)
        attach(host)
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(view.scrollX > 0)

        // Dark, then taken off its window; the glasses wake while it is off, so it hears nothing.
        lit(host, false)
        assertFalse(view.scrolling)
        host.removeView(view)
        lit(host, true)
        host.addView(view, FrameLayout.LayoutParams(200, FrameLayout.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("put back on lit glasses, it moves again", view.scrolling)
        assertEquals(0, view.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals("a fresh hold, not the phase it had before the dark", 0, view.scrollX)
        idle(1_000)
        assertTrue(view.scrollX > 0)

        // Taken off and put back while the glasses are still dark, it waits for them.
        lit(host, false)
        host.removeView(view)
        host.addView(view, FrameLayout.LayoutParams(200, FrameLayout.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.scrolling)
        idle(OverflowScroll.cycleMs(view.overflowPx()))
        assertEquals(0, view.scrollX)
        lit(host, true)
        assertTrue(view.scrolling)
    }

    /** The display's state and the screen state the framework dispatches down an attached window with it. */
    private fun lit(host: View, on: Boolean) {
        shadowOf(host.display).setState(if (on) Display.STATE_ON else Display.STATE_OFF)
        screenState(host, if (on) View.SCREEN_STATE_ON else View.SCREEN_STATE_OFF)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What the framework dispatches down a window when the display turns off or on; not public API. */
    private fun screenState(root: View, state: Int) {
        View::class.java.getDeclaredMethod("dispatchScreenStateChanged", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(root, state)
    }

    @Test
    fun `a rebuilt line with the same text and room continues where its predecessor was`() {
        val memory = OverflowMemory()
        val first = line(long, active = true, memory = memory, key = "card:secondary")
        val host = host(200, first)
        attach(host)
        idle(OverflowScroll.HOLD_MS + 1_000)
        val moved = first.scrollX
        assertTrue(moved > 0)

        // The owner rebuilds the line: the new one is built at rest, the old one goes, the new one attaches.
        host.removeView(first)
        assertFalse(first.scrolling)
        val second = line(long, active = true, memory = memory, key = "card:secondary")
        host.addView(second, FrameLayout.LayoutParams(200, FrameLayout.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second.scrolling)
        assertTrue("continued at ${second.scrollX}, not restarted", second.scrollX >= moved)
        assertEquals(1, memory.size)

        // A different text under the same key starts over.
        host.removeView(second)
        val third = line("$long et Oise", active = true, memory = memory, key = "card:secondary")
        host.addView(third, FrameLayout.LayoutParams(200, FrameLayout.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, third.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, third.scrollX)

        // Deselected on screen: the place is dropped, and the next selection starts over.
        idle(1_000)
        assertTrue(third.scrollX > 0)
        third.active = false
        assertFalse(memory.remembers("card:secondary"))
        third.active = true
        assertEquals(0, third.scrollX)
    }

    @Test
    fun `a line hidden and shown again, or resized, starts over`() {
        val memory = OverflowMemory()
        val view = line(long, active = true, memory = memory, key = "street")
        val host = host(200, view)
        attach(host)
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(view.scrollX > 0)

        host.visibility = View.GONE
        shadowOf(Looper.getMainLooper()).idle()
        host.visibility = View.VISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.scrolling)
        assertEquals(0, view.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals("the hold runs again after a hide", 0, view.scrollX)
        idle(1_000)
        assertTrue(view.scrollX > 0)

        view.layoutParams = FrameLayout.LayoutParams(260, FrameLayout.LayoutParams.WRAP_CONTENT)
        view.requestLayout()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.overflowPx() > 0)
        assertEquals("a new room reads from the hold", 0, view.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, view.scrollX)
    }

    @Test
    fun `an island rebound every second with a new distance keeps its street moving to the end`() {
        val street = "W".repeat(28)
        val nav = NavIslandView(context)
        val content = IslandContent.Guidance("turn-left", "120 m", street = street)
        nav.bind(IslandForm.TEXT, content)
        attach(host(FrameLayout.LayoutParams.WRAP_CONTENT, nav))
        fun streetLine(): OverflowLineView = (0 until nav.childCount).map(nav::getChildAt)
            .flatMap { child -> if (child is android.view.ViewGroup) (0 until child.childCount).map(child::getChildAt) else listOf(child) }
            .flatMap { child -> if (child is android.view.ViewGroup) (0 until child.childCount).map(child::getChildAt) else listOf(child) }
            .filterIsInstance<OverflowLineView>().single()
        val overflow = streetLine().overflowPx()
        assertTrue("the 28-character street is wider than the text island", overflow > 0)
        assertTrue(streetLine().scrolling)

        var last = 0
        var reachedEnd = false
        repeat((OverflowScroll.cycleMs(overflow) / 1_000L).toInt() + 1) { i ->
            idle(1_000)
            nav.bind(IslandForm.TEXT, content.copy(figure = "${120 - 10 * i} m"))
            shadowOf(Looper.getMainLooper()).idle()
            val line = streetLine()
            assertTrue("tick $i rebound the street", line.scrolling)
            assertTrue("tick $i: ${line.scrollX} after $last", line.scrollX >= last || last == overflow)
            if (line.scrollX == overflow) reachedEnd = true
            last = line.scrollX
        }
        assertTrue(reachedEnd)

        // A new street starts over; guidance without one forgets it.
        nav.bind(IslandForm.TEXT, content.copy(street = "V".repeat(28)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, streetLine().scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, streetLine().scrollX)
        idle(1_000)
        assertTrue(streetLine().scrollX > 0)
        nav.bind(IslandForm.TEXT, content.copy(street = null))
        nav.bind(IslandForm.TEXT, content.copy(street = "V".repeat(28)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, streetLine().scrollX)
    }

    @Test
    fun `an island rebound while hidden, as a flare hides it, starts its street over when shown`() {
        val street = "W".repeat(28)
        val nav = NavIslandView(context)
        val content = IslandContent.Guidance("turn-left", "120 m", street = street)
        nav.bind(IslandForm.TEXT, content)
        attach(host(FrameLayout.LayoutParams.WRAP_CONTENT, nav))
        fun streetLine(): OverflowLineView = (0 until nav.childCount).map(nav::getChildAt)
            .flatMap { child -> if (child is android.view.ViewGroup) (0 until child.childCount).map(child::getChildAt) else listOf(child) }
            .flatMap { child -> if (child is android.view.ViewGroup) (0 until child.childCount).map(child::getChildAt) else listOf(child) }
            .filterIsInstance<OverflowLineView>().single()
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(streetLine().scrollX > 0)

        // The flare hides the form; a changed distance rebinds it meanwhile; the fold shows it again.
        nav.visibility = View.INVISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(streetLine().scrolling)
        nav.bind(IslandForm.TEXT, content.copy(figure = "110 m"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(streetLine().scrolling)
        nav.visibility = View.VISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(streetLine().scrolling)
        assertEquals("shown again after a hide: a fresh hold, not the old phase", 0, streetLine().scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, streetLine().scrollX)
        idle(1_000)
        assertTrue(streetLine().scrollX > 0)
    }
}
