package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.session.ActivityStop
import com.anezium.rokidbus.glasses.session.IconRef
import com.anezium.rokidbus.glasses.session.LocalPages
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.ScreenHeader
import com.anezium.rokidbus.glasses.session.ScreenHint
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
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
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * The root with several activities at once, drawn by the real renderer at 480x640: Navigation
 * and Relay together, then with a todo as a third, with Notifications and Applications under
 * them. Every card keeps its fixed lines at the card sizes, the whole root fits the viewport
 * without a scroll, and a card line wider than the card is read whole on the selected card only.
 *
 * Set `nexus.renderFixtures` (a directory) to also write each scene as a PNG, for a review
 * that wants to see the layout the glasses would draw; the assertions never depend on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RootCompactRenderingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val items = TypedPageItems(PageTemplateContract::isLocalPageId, providerPageAllowed = { true })
    private val labels = mapOf("nav" to "Navigation", "relay" to "Relay", "sample" to "Hello Nexus")
    private val screens = SessionScreens(items, { labels[it] }, { "12:24" }, { it })
    private val icons = SessionIconSource { _, _ -> GlyphDrawable("M4,12 h16 M12,4 v16") }
    private val view = SessionScreenView(context, icons)

    private val nav = ActivityStop(
        "nav:local", "nav:main", "120 m", pinned = true, ownerPluginId = "nav", ownerLabel = "Navigation",
        detail = "Gare de Goussainville, Val-d'Oise", measure = "1.8 km", updatedAtWallMs = 1, icon = IconRef.Plugin("nav"),
    )
    private val relay = ActivityStop(
        "relay:local", "nexus:activity:relay", "Mika", ownerPluginId = "relay", ownerLabel = "Relay",
        detail = "Reply from the glasses when you are ready, I added a second message", updatedAtWallMs = 2, icon = IconRef.Plugin("relay"),
    )
    private val todo = ActivityStop(
        "sample:todo", "nexus:activity:sample", "4 to do", ownerPluginId = "sample", ownerLabel = "Hello Nexus",
        detail = "Milk · Eggs · Bread · Coffee", updatedAtWallMs = 3, icon = IconRef.Plugin("sample"),
    )

    private fun root(activities: List<ActivityStop>, selected: Int): SessionScreen {
        val stops = RootStops.build(activities, NoticePreview(RootStops.NOTICE_PAGE_ID, "Standup · 14:00", "Calendar", 1), 2)
        return screens.screen(SessionState.Root(stops, selected = selected), null)
    }

    private fun render(screen: SessionScreen, name: String) {
        view.show(screen)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
        fixtureDir()?.let { dir ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun fixtureDir(): File? = System.getProperty("nexus.renderFixtures")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    private fun block(key: String): View = view.blockViews.getValue(key)

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun lines(root: View): List<OverflowLineView> = texts(root).filterIsInstance<OverflowLineView>()

    private fun wholeInViewport(key: String): Boolean {
        val b = block(key)
        return b.top >= 0 && b.bottom <= view.viewport.height
    }

    @Test
    fun `Navigation and Relay share the root with the hub rows, every card at the card sizes`() {
        render(root(listOf(nav, relay), selected = 0), "root-nav-relay")
        val keys = view.blockViews.keys.toList()
        assertEquals(listOf("nav:local", "relay:local"), keys.take(2))
        assertTrue(keys.containsAll(listOf(RootStops.NOTIFICATIONS_PAGE_ID, RootStops.APPLICATIONS_PAGE_ID)))
        keys.forEach { assertTrue(it, wholeInViewport(it)) }
        val navTexts = texts(block("nav:local"))
        assertEquals(PageStyle.CARD_FIGURE_PX, navTexts.first { it.text == "120 m" }.textSize, 0f)
        assertEquals(PageStyle.CARD_BODY_PX, navTexts.first { it.text == nav.detail }.textSize, 0f)
        assertEquals(PageStyle.CARD_BODY_PX, texts(block("relay:local")).first { it.text == relay.detail }.textSize, 0f)
        texts(view).filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
            .forEach { assertTrue("${it.text} at ${it.textSize}", it.textSize >= PageStyle.CAPTION_PX) }
    }

    @Test
    fun `a third activity joins the root as its Activities row, and the whole root fits without a scroll`() {
        render(root(listOf(nav, relay, todo), selected = 2), "root-nav-relay-todo")
        val keys = view.blockViews.keys.toList()
        assertEquals(listOf("nav:local", "relay:local"), keys.take(2))
        assertTrue(keys.contains(RootStops.ACTIVITIES_PAGE_ID))
        assertTrue(texts(block(RootStops.ACTIVITIES_PAGE_ID)).any { it.text == "3" })
        assertEquals(PageSurfaceContract.MAX_ROOT_STOPS, keys.size)
        keys.forEach { assertTrue(it, wholeInViewport(it)) }
        assertEquals(0, view.viewport.offset)
        val last = block(keys.last())
        assertTrue("the root leaves room under its last row: ${last.bottom} of ${view.viewport.height}", last.bottom < view.viewport.height)
    }

    @Test
    fun `the Activities page draws the three cards whole at the card sizes`() {
        // Every live activity carries its command line as its third line, as the page builds it.
        val cards = listOf(nav, relay, todo).mapIndexed { index, stop ->
            ScreenBlock.Card(
                key = stop.id, label = stop.ownerLabel!!.uppercase(), time = "12:24", primary = stop.text, secondary = stop.detail,
                measure = stop.measure, figure = true, ended = false, selected = index == 2, icon = stop.icon,
                tertiary = listOf("tap open · 3 commands", "tap open · reply", "tap open · 4 commands")[index],
            )
        }
        render(SessionScreen(ScreenHeader(LocalPages.TITLE_ACTIVITIES.uppercase(), "3/3"), cards, ScreenHint("swipe next · tap open", "back root"), focusKey = "sample:todo"), "activities-three")
        assertEquals(3, view.blockViews.size)
        view.blockViews.keys.forEach { assertTrue(it, wholeInViewport(it)) }
        val todoTexts = texts(block("sample:todo"))
        assertEquals(PageStyle.CARD_FIGURE_PX, todoTexts.first { it.text == "4 to do" }.textSize, 0f)
        assertEquals(PageStyle.CAPTION_PX, todoTexts.first { it.text == "tap open · 4 commands" }.textSize, 0f)
        val heights = view.blockViews.values.map { it.height }
        assertEquals("cards with the same lines and no command strip share one height", 1, heights.distinct().size)
    }

    @Test
    fun `a long line is kept whole and scrolls on the selected card only`() {
        render(root(listOf(nav, relay, todo), selected = 1), "root-long-line-selected")
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        controller.get().setContentView(view)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()

        val relayLine = lines(block("relay:local")).single { it.text == relay.detail }
        assertTrue(relayLine.overflowPx() > 0)
        assertTrue("the selected card's long line scrolls", relayLine.scrolling)
        assertEquals(relay.detail, relayLine.text.toString())
        val navLine = lines(block("nav:local")).single { it.text == nav.detail }
        assertFalse("an unselected card stays still", navLine.scrolling)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OverflowScroll.HOLD_MS + 1_500))
        assertTrue(relayLine.scrollX > 0)
        assertEquals(0, navLine.scrollX)

        // The selection moves: the card that lost it stops and the new one may move.
        view.show(root(listOf(nav, relay, todo), selected = 0))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(relayLine.scrolling)
        assertFalse(relayLine.isAttachedToWindow)
        val navNow = lines(block("nav:local")).single { it.text == nav.detail }
        assertEquals(navNow.overflowPx() > 0, navNow.scrolling)
    }
}
