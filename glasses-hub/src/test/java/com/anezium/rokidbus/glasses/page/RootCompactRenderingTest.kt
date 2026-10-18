package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.session.ActivityStop
import com.anezium.rokidbus.glasses.session.CommandFacts
import com.anezium.rokidbus.glasses.session.Frame
import com.anezium.rokidbus.glasses.session.FrameMode
import com.anezium.rokidbus.glasses.session.FrameStatus
import com.anezium.rokidbus.glasses.session.HistoryState
import com.anezium.rokidbus.glasses.session.IconPlace
import com.anezium.rokidbus.glasses.session.IconRef
import com.anezium.rokidbus.glasses.session.LocalPages
import com.anezium.rokidbus.glasses.session.NoticeCard
import com.anezium.rokidbus.glasses.session.NoticeCardKind
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.NotificationHistory
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.Snapshot
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration

/**
 * The root with several activities at once, drawn by the real renderer at 480x640 from entries
 * within the activity contract: Navigation and Relay together, a todo as the Activities row,
 * the Activities page as `LocalPages` answers it, and the conversation preview whose message is
 * the one reachable line wide enough to scroll. Marks are the canonical ones the hub would draw,
 * read from the checked-in glyph sources. The moving line keeps its place through the renders
 * a live value causes every second, and starts over only when it should.
 *
 * Set the JVM property `nexus.renderFixtures` to a directory to also write the still scenes as
 * PNGs; the assertions never depend on it.
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
    private val view = SessionScreenView(context, CanonicalMarks.source())

    private val nav = ActivityStop(
        "nav:local", "nav:main", "Waiting GPS", pinned = true, ownerPluginId = "nav", ownerLabel = "Navigation",
        detail = "Gare de Goussainville…", measure = "1.8 km", updatedAtWallMs = 1, icon = IconRef.Plugin("nav", "map"),
    )
    private val relay = ActivityStop(
        "relay:local", "nexus:activity:relay", "Mika", ownerPluginId = "relay", ownerLabel = "Relay",
        detail = "Reply when you are ready", updatedAtWallMs = 2, icon = IconRef.Plugin("relay", "relay"),
    )
    private val todo = ActivityStop(
        "sample:todo", "nexus:activity:sample", "4 to do", ownerPluginId = "sample", ownerLabel = "Hello Nexus",
        detail = "Milk · Eggs · Bread · Coffee", updatedAtWallMs = 3, icon = IconRef.Plugin("sample", "star"),
    )

    /** A Relay conversation's newest message, as the notice carries it: the root's long line. */
    private val message = "Can you check the build when you have a minute? I added a second message to exercise thread extraction."
    private val preview = NoticePreview(
        pageId = "relay:thread", text = "Mika", sourceLabel = "Relay", atWallMs = 4,
        card = NoticeCard(NoticeCardKind.CONVERSATION, "e1", "relay", HistoryState.NEW, secondary = message),
        icon = IconRef.Plugin("relay", "relay"),
    )

    private fun root(activities: List<ActivityStop>, selected: Int, preview: NoticePreview? = this.preview): SessionScreen =
        screens.screen(SessionState.Root(RootStops.build(activities, preview, 2), selected = selected), null)

    private var attached = false

    /**
     * Draws [screen] in a real 480x640 window (the test's qualifiers) through a completed
     * traversal, checks that every block painted more than its outline, and, with the
     * `nexus.renderFixtures` property set, writes the frame as a PNG with its SHA-256 beside it.
     */
    private fun render(screen: SessionScreen, name: String) {
        if (!attached) attach()
        showAttached(screen)
        assertEquals(PageStyle.WIDTH_PX, view.width)
        assertEquals(PageStyle.HEIGHT_PX, view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        view.blockViews.forEach { (key, block) ->
            assertTrue("$key painted ${litPixels(bitmap, block)} pixels inside its outline", litPixels(bitmap, block) > 150)
        }
        fixtureDir()?.let { dir ->
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            File(dir, "$name.png").writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            File(dir, "$name.sha256").writeText("$digest  $name.png\n")
        }
    }

    /** Opaque pixels brighter than the outline inside [block], its 2 px stroke and a margin left out. */
    private fun litPixels(bitmap: Bitmap, block: View): Int {
        val rect = Rect(0, 0, block.width, block.height)
        view.offsetDescendantRectToMyCoords(block, rect)
        rect.inset(6, 6)
        var lit = 0
        for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(bitmap.height)) {
            for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(bitmap.width)) {
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) == 255 && (Color.red(p) > 60 || Color.green(p) > 60 || Color.blue(p) > 60)) lit++
            }
        }
        return lit
    }

    private fun fixtureDir(): File? = System.getProperty("nexus.renderFixtures")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    private fun block(key: String): View = view.blockViews.getValue(key)

    private fun <T : View> find(root: View, type: Class<T>): List<T> = when {
        type.isInstance(root) -> listOf(type.cast(root)!!)
        root is ViewGroup -> (0 until root.childCount).flatMap { find(root.getChildAt(it), type) }
        else -> emptyList()
    }

    private inline fun <reified T : View> find(root: View): List<T> = find(root, T::class.java)

    private fun texts(root: View): List<TextView> = find(root)

    private fun wholeInViewport(key: String): Boolean {
        val b = block(key)
        return b.top >= 0 && b.bottom <= view.viewport.height
    }

    @Test
    fun `the fixture stays within the activity contract`() {
        listOf(nav, relay, todo).forEach {
            assertTrue(it.text, it.text.length <= ActivitySurfaceContract.MAX_PRIMARY_CHARS)
            assertTrue(it.detail!!, it.detail!!.length <= ActivitySurfaceContract.MAX_SECONDARY_CHARS)
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `Navigation and Relay share the root with the preview and the hub rows, marks and sizes as the hub draws them`() {
        render(root(listOf(nav, relay), selected = 0), "root-nav-relay")
        val keys = view.blockViews.keys.toList()
        assertEquals(listOf("nav:local", "relay:local", RootStops.PREVIEW_STOP_ID, RootStops.NOTIFICATIONS_PAGE_ID, RootStops.APPLICATIONS_PAGE_ID), keys)
        keys.forEach { assertTrue(it, wholeInViewport(it)) }
        val navTexts = texts(block("nav:local"))
        assertEquals(PageStyle.CARD_FIGURE_PX, navTexts.first { it.text == "Waiting GPS" }.textSize, 0f)
        assertEquals(PageStyle.CARD_BODY_PX, navTexts.first { it.text == nav.detail }.textSize, 0f)
        // No command line is invented on a card: a live activity without a pending or answered
        // command has none. The screen's own "tap open" hint under the viewport is not a card line.
        listOf("nav:local", "relay:local").forEach { key ->
            texts(block(key)).forEach { assertFalse("$key: ${it.text}", it.text.contains("tap open")) }
        }
        listOf("nav:local", "relay:local", RootStops.PREVIEW_STOP_ID, RootStops.NOTIFICATIONS_PAGE_ID, RootStops.APPLICATIONS_PAGE_ID)
            .forEach { assertEquals("$it has its mark", 1, find<ImageView>(block(it)).count { v -> v.drawable != null }) }
        texts(view).filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
            .forEach { assertTrue("${it.text} at ${it.textSize}", it.textSize >= PageStyle.CAPTION_PX) }
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `a third activity joins the root as its Activities row, which carries no mark, and the whole root fits`() {
        render(root(listOf(nav, relay, todo), selected = 2), "root-nav-relay-todo")
        val keys = view.blockViews.keys.toList()
        assertEquals(listOf("nav:local", "relay:local", RootStops.PREVIEW_STOP_ID, RootStops.ACTIVITIES_PAGE_ID), keys.take(4))
        assertEquals(PageSurfaceContract.MAX_ROOT_STOPS, keys.size)
        assertTrue(texts(block(RootStops.ACTIVITIES_PAGE_ID)).any { it.text == "3" })
        assertTrue(find<ImageView>(block(RootStops.ACTIVITIES_PAGE_ID)).none { it.drawable != null })
        keys.forEach { assertTrue(it, wholeInViewport(it)) }
        assertEquals(0, view.viewport.offset)
        val last = block(keys.last())
        assertTrue("room under the last row: ${last.bottom} of ${view.viewport.height}", last.bottom < view.viewport.height)
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `the Activities page as the hub answers it draws the three cards whole, equal, without invented lines`() {
        val activities = listOf(nav, relay, todo)
        val local = LocalPages({ NotificationHistory() }, { activities }, { emptyList() }, { false }, { "12:24" })
        val request = PageSurfaceRequest(PageCorrelation("r1", 1, 1, RootStops.ACTIVITIES_PAGE_ID), SessionReducer.REASON_OPEN)
        val page = (PageSurfaceContract.validateResponse(local.answer(request)) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
        val frame = Frame(
            RootStops.ACTIVITIES_PAGE_ID, FrameStatus.Shown(Snapshot(page), page.revision, 1_000, "r1"),
            selected = 2, mode = FrameMode.ROWS, selectedKey = items.entries(page).getOrNull(2)?.key,
        )
        val state = SessionState.InPage(listOf(frame), SessionState.Root(RootStops.build(activities, null, 0)))
        render(screens.screen(state, null, facts = CommandFacts(activities = activities)), "activities-three")

        val cards = view.blockViews.filterKeys { it.startsWith("row:") }
        assertEquals(3, cards.size)
        cards.keys.forEach { assertTrue(it, wholeInViewport(it)) }
        val shown = cards.values.map { card -> texts(card).map { it.text.toString() } }
        assertTrue(shown.any { "4 to do" in it && todo.detail in it })
        assertTrue(shown.any { "Waiting GPS" in it && "1.8 km" in it })
        shown.flatten().forEach { assertFalse(it, it.contains("tap open")) }
        assertEquals("cards of the same lines share one height", 1, cards.values.map { it.height }.distinct().size)
        assertEquals(PageStyle.CARD_FIGURE_PX, texts(view).first { it.text == "4 to do" }.textSize, 0f)
    }

    /**
     * Puts the HUD view in a real window at its exact 480x640, whatever decor the test activity
     * adds: a frame host turns the child's fixed size into an exact measure even when the
     * window's content area is shorter (a scroll host would measure it unspecified and let it
     * wrap), so the view is laid out, pre-drawn and drawn whole by the framework's traversal, as
     * the glasses' window manager lays out the HUD.
     */
    private fun attach(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val host = FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX))
        }
        controller.get().setContentView(host)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        attached = true
        return controller.get()
    }

    private fun showAttached(screen: SessionScreen) {
        view.show(screen)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun previewLine(): OverflowLineView =
        find<OverflowLineView>(block(RootStops.PREVIEW_STOP_ID)).single { it.text == message }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `the selected preview's long message scrolls, the other cards' lines do not`() {
        attach()
        showAttached(root(listOf(nav, relay, todo), selected = 2))
        val line = previewLine()
        assertTrue(line.overflowPx() > 0)
        assertTrue(line.scrolling)
        assertEquals(message, line.text.toString())
        val navLine = find<OverflowLineView>(block("nav:local")).single { it.text == nav.detail }
        assertEquals(0, navLine.overflowPx())
        assertFalse(navLine.scrolling)
        idle(OverflowScroll.HOLD_MS + 1_000)
        assertTrue(line.scrollX > 0)
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `a value ticking every second on another card does not restart the selected line`() {
        attach()
        var screen = root(listOf(nav, relay, todo), selected = 2)
        showAttached(screen)
        val overflow = previewLine().overflowPx()
        assertTrue(overflow > 0)
        var last = 0
        var reachedEnd = false
        val ticks = (OverflowScroll.cycleMs(overflow) / 1_000L).toInt() + 1
        repeat(ticks) { i ->
            idle(1_000)
            // Navigation's figure changes; the whole screen is drawn again with new card views.
            screen = root(listOf(nav.copy(text = "${120 - 10 * i} m"), relay, todo), selected = 2)
            showAttached(screen)
            val line = previewLine()
            assertTrue("tick $i rebuilt the line", line.scrolling)
            assertTrue("tick $i: ${line.scrollX} after $last", line.scrollX >= last || last == overflow)
            if (line.scrollX == overflow) reachedEnd = true
            last = line.scrollX
        }
        assertTrue("the hidden end was reached while the other card kept changing", reachedEnd)
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    fun `losing the selection, removal, reselection and another screen each start the line over`() {
        attach()
        showAttached(root(listOf(nav, relay, todo), selected = 2))
        idle(OverflowScroll.HOLD_MS + 1_000)
        val moved = previewLine().scrollX
        assertTrue(moved > 0)

        // Deselected: the rebuilt line is at rest and its place is dropped.
        showAttached(root(listOf(nav, relay, todo), selected = 0))
        assertFalse(previewLine().scrolling)
        assertEquals(0, previewLine().scrollX)
        showAttached(root(listOf(nav, relay, todo), selected = 2))
        assertTrue(previewLine().scrolling)
        assertEquals("reselected: a fresh hold", 0, previewLine().scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, previewLine().scrollX)
        idle(1_000)
        assertTrue(previewLine().scrollX > 0)

        // Removed from the root, then back: a fresh hold again.
        showAttached(root(listOf(nav, relay, todo), selected = 2, preview = null))
        assertFalse(view.blockViews.containsKey(RootStops.PREVIEW_STOP_ID))
        showAttached(root(listOf(nav, relay, todo), selected = 2))
        assertEquals(0, previewLine().scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals(0, previewLine().scrollX)

        // Another screen: nothing carries over.
        idle(2_000)
        assertTrue(previewLine().scrollX > 0)
        showAttached(SessionScreen(com.anezium.rokidbus.glasses.session.ScreenHeader("ACTIVITIES", "3/3"), emptyList(), null))
        showAttached(root(listOf(nav, relay, todo), selected = 2))
        assertEquals(0, previewLine().scrollX)
    }
}

/**
 * The marks the hub draws for the fixture's entries, from their checked-in sources: built-in
 * keys from `bus-client`'s `ic_plugin_*` drawables (`map`, `star`, `bell`, `grid`), Relay's own
 * registered glyph from its `nexus_glyphs` array. The unit tests have no merged resources, so
 * the paths are read from the files `GlassesHub.launcherDrawable` would resolve them from.
 */
internal object CanonicalMarks {
    private val root: File = listOf(File(".."), File(".")).first { File(it, "bus-client/src/main/res/drawable").isDirectory }

    private fun builtIn(key: String): String? {
        val file = File(root, "bus-client/src/main/res/drawable/ic_plugin_$key.xml")
        if (!file.isFile) return null
        return Regex("""android:pathData="([^"]+)"""").find(file.readText())?.groupValues?.get(1)
    }

    private fun registered(pluginId: String, name: String): String? {
        val file = File(root, "plugins/$pluginId/src/main/res/values/nexus_glyphs.xml")
        if (!file.isFile) return null
        return Regex("""<item>$name\|([^<]+)</item>""").find(file.readText())?.groupValues?.get(1)
    }

    fun path(ref: IconRef): String? = when (ref) {
        is IconRef.Plugin -> ref.iconKey?.let { key -> builtIn(key) ?: registered(ref.pluginId, key) }
        is IconRef.Place -> builtIn(if (ref.place == IconPlace.BELL) "bell" else "grid")
        else -> null
    }

    fun source(): SessionIconSource = SessionIconSource { _, ref -> path(ref)?.let(::GlyphDrawable) }
}
