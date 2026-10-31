package com.anezium.rokidbus.glasses

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.page.OverflowLineView
import com.anezium.rokidbus.glasses.page.OverflowScroll
import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfacePosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The activity island at the HUD's 480 pixels: folded, an activity without a measure is its glyph
 * beside the title over the secondary, the title whole and scrolling in place; one with a measure
 * keeps the medium chip; expanded, its commands are never a cut label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w320dp-h640dp-hdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityIslandRenderingTest {
    private val headline = "Teardrop (Mad Professor remix)"
    private lateinit var controller: ActivityController<Activity>
    private lateinit var island: ActivityOverlayRenderer.ActivityIsland
    private var oldMotion = true

    @Before
    fun attach() {
        oldMotion = HudMotion.enabled
        HudMotion.enabled = false
        controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()
        island = ActivityOverlayRenderer.ActivityIsland(
            activity,
            glyphOf = { _, _ -> GlyphDrawable(DISC) },
        )
        island.place(PinSurfacePosition.TOP_RIGHT, 0)
        activity.setContentView(island)
        controller.visible()
        idle()
    }

    @After
    fun detach() {
        controller.pause().stop().destroy()
        HudMotion.enabled = oldMotion
    }

    @Test
    fun `folded without a measure it is the glyph beside the title over the secondary and nothing under`() {
        show(content(headline = headline, secondary = "Paused · Massive Attack"), ActivityPresentation.CHIP)

        val compact = island.folded as ActivityOverlayRenderer.ActivityCompactView
        assertSame(compact, island.currentForm)
        assertEquals(listOf(headline, "Paused · Massive Attack"), shownTexts(compact))
        val glyph = rectOf(compact, compact.glyph)
        val title = rectOf(compact, compact.title)
        val secondary = rectOf(compact, compact.secondary)
        // Two rows beside the glyph, the secondary second, and the glyph as tall as both.
        assertTrue(title.left >= glyph.right)
        assertEquals(title.left, secondary.left)
        assertTrue(secondary.top >= title.bottom)
        assertEquals(secondary.bottom - title.top, glyph.height())
        assertEquals(glyph.top, title.top)
        // Nothing under them: the form ends with its padding below the two rows.
        assertEquals(compact.height - compact.paddingBottom, secondary.bottom)

        // The whole title, not the hub's cut, scrolling inside the width it already has.
        assertEquals(headline, compact.title.text.toString())
        assertTrue(compact.title.overflowPx() > 0)
        assertTrue(compact.title.scrolling)
        val width = compact.width
        val height = compact.height
        assertEquals(0, compact.title.scrollX)
        idle(OverflowScroll.HOLD_MS - 200)
        assertEquals("it waits before it moves", 0, compact.title.scrollX)
        idle(1_000)
        assertTrue(compact.title.scrollX > 0)
        assertEquals(width, compact.width)
        assertEquals(height, compact.height)

        // Not shown, hidden or dark, it does not run.
        island.visibility = View.GONE
        idle()
        assertFalse(compact.title.scrolling)
        island.visibility = View.VISIBLE
        idle()
        assertTrue(compact.title.scrolling)
        screenState(View.SCREEN_STATE_OFF)
        assertFalse(compact.title.scrolling)
        screenState(View.SCREEN_STATE_ON)
        assertTrue(compact.title.scrolling)
        show(content(headline = headline, secondary = "Massive Attack"), ActivityPresentation.PANEL)
        assertFalse(compact.title.scrolling)
    }

    @Test
    fun `without a headline the folded form draws the primary, and a short one does not move`() {
        show(content(headline = null, secondary = null), ActivityPresentation.CHIP)

        val compact = island.folded as ActivityOverlayRenderer.ActivityCompactView
        assertEquals(listOf("Teardrop (M…"), shownTexts(compact))
        assertFalse(compact.title.scrolling)
        // The missing second line keeps the form's height: the title sits centred by the glyph.
        val glyph = rectOf(compact, compact.glyph)
        val title = rectOf(compact, compact.title)
        assertTrue(title.top > glyph.top && title.bottom < glyph.bottom)
    }

    @Test
    fun `folded, a byline too long for its line scrolls from the playback state that opens it, at any font scale`() {
        try {
            for (fontScale in listOf(1f, 1.5f)) {
                RuntimeEnvironment.setFontScale(fontScale)
                val compact = ActivityOverlayRenderer.ActivityCompactView(controller.get())
                controller.get().setContentView(compact)
                // Media Deck's longest bylines, for an artist called "Someone With A Long Band Name".
                for ((state, byline) in listOf("Paused" to "Paused · Someone With A Lon…", "Stopped" to "Stopped · Someone With A Lo…")) {
                    compact.render(headline, byline, GlyphDrawable(DISC))
                    idle()
                    assertEquals(288, compact.secondary.width)
                    assertEquals(byline, compact.secondary.text.toString())
                    assertTrue("the byline scrolls at font scale $fontScale", compact.secondary.scrolling)
                    assertEquals("$state leads the line at font scale $fontScale", 0, compact.secondary.scrollX)
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun `folded with a measure it keeps the medium chip with the street on the line below`() {
        show(content(primary = "3 min", measure = "250 m", secondary = "Rue de Rivoli"), ActivityPresentation.CHIP)

        val chip = island.folded
        assertTrue(chip is PinOverlayRenderer.PinPanelView)
        assertSame(chip, island.currentForm)
        assertEquals(listOf("3 min", "250 m", "Rue de Rivoli"), shownTexts(chip))
        val texts = find<TextView>(chip).filter { it.isShown }
        val (title, measure, street) = texts.map { rectOf(chip, it) }
        val glyph = rectOf(chip, find<android.widget.ImageView>(chip).single { it.isShown })
        assertTrue(title.left >= glyph.right)
        assertEquals(title.left, measure.left)
        assertTrue(street.top >= glyph.bottom)
        assertTrue(street.left < glyph.right)
    }

    @Test
    fun `passive expanded islands show information without command buttons`() {
        val media = listOf(
            ActivityAction("prev@1", "prev", "Previous"),
            ActivityAction("pause@1", "pause", "Pause"),
            ActivityAction("next@1", "next", "Next"),
        )
        show(content(headline = headline, secondary = "Massive Attack", actions = media), ActivityPresentation.PANEL)

        assertTrue(find<HudActionRowView>(island).none { it.isShown })
        // The panel's title is the headline too, scrolling where it does not fit.
        val title = find<OverflowLineView>(island).single { it.isShown }
        assertEquals(headline, title.text.toString())
        assertTrue(title.scrolling)

        show(
            content(headline = headline, actions = listOf(ActivityAction("go", "next", "Go"), ActivityAction("end", "stop", "End"))),
            ActivityPresentation.PANEL,
        )
        assertTrue(find<HudActionRowView>(island).none { it.isShown })
    }

    @Test
    fun `the flare names the activity by its headline and scrolls it to its end, measure included`() {
        show(content(headline = headline), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        val flare = requireNotNull(island.currentForm)
        assertTrue(flare is NoticeOverlayRenderer.NoticeBandView)
        assertTrue(shownTexts(flare).toString(), headline in shownTexts(flare))
        assertDrawnWhole(find<TextView>(flare).single { it.isShown && it.text.toString() == headline }, headline, flare)

        show(content(headline = headline, measure = "250 m"), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        val measured = "$headline - 250 m"
        assertDrawnWhole(find<TextView>(flare).single { it.isShown && it.text.toString() == measured }, measured, flare)
    }

    @Test
    fun `the flare stays until its longest title has been read to its end`() {
        val longest = "Teardrop (Mad Professor remix) · Massive"
        assertEquals(40, longest.length)
        show(content(headline = longest, measure = "250 m"), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        val line = find<OverflowLineView>(requireNotNull(island.currentForm)).single { it.isShown }
        val overflow = line.overflowPx()
        assertTrue(overflow > 0)

        // Where the renderer's own collapse first asks, the end is not reached yet.
        idle(HudMotion.STANDARD_MS + HudMotion.HOLD_MS)
        val left = island.flareTitleLeftMs()
        assertTrue("$left ms", left > 0)
        assertTrue(line.scrollX < overflow)
        idle(left)
        assertEquals("the end is shown before the flare folds", overflow, line.scrollX)
        assertEquals(0, island.flareTitleLeftMs())

        // A title that fits keeps the usual lifetime.
        show(content(headline = "Teardrop"), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        assertEquals(0, island.flareTitleLeftMs())
    }

    @Test
    fun `a measure ticking every second keeps the flare's title moving to its end`() {
        val longest = "Teardrop (Mad Professor remix) · Massive"
        show(content(headline = longest, measure = "950 m"), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        val line = find<OverflowLineView>(requireNotNull(island.currentForm)).single { it.isShown }
        val overflow = line.overflowPx()
        // Measured once a second, the end shows within the tick after one pass.
        val bound = OverflowScroll.cycleMs(overflow) + 1_000
        var meters = 950
        var elapsed = 0L
        while (island.flareTitleLeftMs() > 0 && elapsed <= bound) {
            idle(1_000)
            elapsed += 1_000
            meters -= 10
            island.render(item(content(headline = longest, measure = "$meters m"), ActivityPresentation.CHIP), guidance = null)
            idle()
            assertEquals("$longest - $meters m", line.text.toString())
        }
        assertEquals("the title was read once within one pass", 0, island.flareTitleLeftMs())
        assertTrue("$elapsed ms past $bound ms", elapsed <= bound)
    }

    @Test
    fun `a measure that shrinks mid-travel keeps the flare's title where it stands`() {
        val longest = "Teardrop (Mad Professor remix) · Massive"
        show(content(headline = longest, measure = "12345678"), ActivityPresentation.CHIP)
        island.showFlare(urgent = false)
        idle()
        val line = find<OverflowLineView>(requireNotNull(island.currentForm)).single { it.isShown }
        idle(OverflowScroll.HOLD_MS + 3_000)
        val before = line.scrollX
        assertTrue(before > 0)

        island.render(item(content(headline = longest, measure = "9"), ActivityPresentation.CHIP), guidance = null)
        idle()
        assertTrue(line.scrolling)
        val shrunk = line.overflowPx()
        assertTrue("${line.scrollX} after $before, end $shrunk", line.scrollX >= minOf(before, shrunk) - 1)
        assertTrue(line.scrollX <= shrunk)
    }

    @Test
    fun `a flare never outlives its bound, whatever its title still needs`() {
        assertEquals(0, ActivityOverlayRenderer.flareHoldMs(titleLeftMs = 0, nowMs = 1_000, foldByMs = 15_000))
        assertEquals(2_000, ActivityOverlayRenderer.flareHoldMs(titleLeftMs = 2_000, nowMs = 1_000, foldByMs = 15_000))
        assertEquals(1_000, ActivityOverlayRenderer.flareHoldMs(titleLeftMs = 5_000, nowMs = 14_000, foldByMs = 15_000))
        assertEquals(0, ActivityOverlayRenderer.flareHoldMs(titleLeftMs = 5_000, nowMs = 15_000, foldByMs = 15_000))
    }

    @Test
    fun `after an older phone announces, every island names the activity by its primary`() {
        val bundled = content(headline = headline, measure = "250 m")
        val kept = ActivityOverlayRenderer.islandStateOf(ActivityRenderState(listOf(item(bundled, ActivityPresentation.CHIP))), bundle = true)
        assertEquals(headline, kept.items.single().activity.content.headline)
        val whole = bundled.copy(wholeSecondary = "A whole secondary", wholeDetail = listOf("A whole detail"))
        val stripped = ActivityOverlayRenderer.islandStateOf(ActivityRenderState(listOf(item(whole, ActivityPresentation.CHIP))), bundle = false)
            .items.single().activity.content
        assertEquals(null, stripped.wholeSecondary)
        assertTrue(stripped.wholeDetail.isEmpty())

        // The old phone's partial update replaces the primary; the headline from before stays stored.
        listOf(bundled, bundled.copy(primary = "Angel")).forEach { stored ->
            val plain = ActivityOverlayRenderer.islandStateOf(ActivityRenderState(listOf(item(stored, ActivityPresentation.CHIP))), bundle = false)
                .items.single().activity.content
            assertEquals(null, plain.headline)
            for (presentation in listOf(ActivityPresentation.CHIP, ActivityPresentation.PANEL)) {
                for (shown in listOf(plain, plain.copy(measure = null))) {
                    show(shown, presentation)
                    val texts = shownTexts(requireNotNull(island.currentForm))
                    assertTrue(texts.toString(), texts.any { it.startsWith(stored.primary) })
                    assertTrue(texts.toString(), texts.none { headline in it })
                }
            }
            island.showFlare(urgent = false)
            idle()
            val flare = shownTexts(requireNotNull(island.currentForm))
            assertTrue(flare.toString(), flare.any { it.startsWith(stored.primary) })
            assertTrue(flare.toString(), flare.none { headline in it })
        }
    }

    @Test
    fun `folded with a measure and a headline, the chip keeps its layout and scrolls the title in its own width`() {
        show(content(headline = headline, measure = "250 m", secondary = "Rue de Rivoli"), ActivityPresentation.CHIP)

        val chip = island.folded
        assertTrue(chip is PinOverlayRenderer.PinPanelView)
        assertSame(chip, island.currentForm)
        assertEquals(listOf(headline, "250 m", "Rue de Rivoli"), shownTexts(chip))
        val title = find<TextView>(chip).single { it.isShown && it.text.toString() == headline }
        val glyph = rectOf(chip, find<android.widget.ImageView>(chip).single { it.isShown })
        val (measure, street) = find<TextView>(chip).filter { it.isShown && it !== title }.map { rectOf(chip, it) }
        assertTrue(rectOf(chip, title).left >= glyph.right)
        assertEquals(rectOf(chip, title).left, measure.left)
        assertTrue(street.top >= glyph.bottom)
        assertDrawnWhole(title, headline, chip)
    }

    @Test
    fun `a pin's panel and a notice's band keep plain titles that never move`() {
        val activity = controller.get()
        assertTrue(find<OverflowLineView>(PinOverlayRenderer.PinPanelView(activity)).isEmpty())
        assertTrue(find<OverflowLineView>(NoticeOverlayRenderer.NoticeBandView(activity)).isEmpty())
    }

    /**
     * [line] carries the whole [text], too wide for it, and moves to its very end after the
     * hold without ever drawing an ellipsis or resizing the [form] it stands in.
     */
    private fun assertDrawnWhole(line: TextView, text: String, form: View) {
        assertEquals(text, line.text.toString())
        assertEquals("no drawn title characters are replaced by an ellipsis", 0, requireNotNull(line.layout).getEllipsisCount(0))
        line as OverflowLineView
        val overflow = line.overflowPx()
        assertTrue("$text fits its ${line.roomPx()} px", overflow > 0)
        assertTrue(line.scrolling)
        val width = form.width
        val height = form.height
        idle(OverflowScroll.HOLD_MS + OverflowScroll.travelMs(overflow) + OverflowScroll.FRAME_MS)
        assertEquals("the hidden end is reached", overflow, line.scrollX)
        // Measured beside the line's glyph, not through it: the last letter is inside the line.
        val textRoom = line.width - line.totalPaddingLeft - line.totalPaddingRight
        assertTrue(line.scrollX + textRoom >= kotlin.math.ceil(line.paint.measureText(text)).toInt())
        assertEquals(0, requireNotNull(line.layout).getEllipsisCount(0))
        assertEquals(width, form.width)
        assertEquals(height, form.height)
    }

    @Test
    fun `a compact cover island scrolls the full paused artist list through its last name`() {
        val whole = "Paused · MUNZi, yuri63k, kairo6k"
        show(content(headline = "Final life", secondary = "Paused · MUNZi, yuri63k, ka…").copy(
            wholeSecondary = whole,
            artwork = com.anezium.rokidbus.shared.LauncherArtwork(1, 1, "gA=="),
        ), ActivityPresentation.PANEL)
        val compact = island.folded as ActivityOverlayRenderer.ActivityCompactView
        assertSame(compact, island.currentForm)
        assertTrue(compact.width <= 240)
        assertTrue(compact.height <= 60)
        assertDrawnWhole(compact.secondary, whole, compact)
    }

    @Test
    fun `a retained cover stays compact offline within half the HUD`() {
        val artwork = com.anezium.rokidbus.shared.LauncherArtwork(1, 1, "gA==")
        show(content(headline = headline, secondary = "Massive Attack").copy(artwork = artwork), ActivityPresentation.PANEL)
        val compact = island.folded as ActivityOverlayRenderer.ActivityCompactView
        assertSame(compact, island.currentForm)
        assertTrue(compact.width <= 240)
        assertTrue(compact.height <= 60)
        assertEquals(36, compact.glyph.width)
        assertFalse(shownTexts(island).any { it == "Playing" })
        // Last-known entries have no executable actions; their cover still chooses this form.
        show(content(headline = headline, secondary = "Last known").copy(artwork = artwork), ActivityPresentation.PANEL)
        assertSame(compact, island.currentForm)
        assertTrue(compact.width <= 240)
    }

    @Test
    fun `coverless media keeps compact geometry after link loss hides commands`() {
        val source = content(headline = headline, secondary = "Artist", actions = listOf(
            ActivityAction("play", "play", "Play", com.anezium.rokidbus.shared.PageActionType.MediaPlay),
        ))
        val stale = item(source, ActivityPresentation.PANEL).copy(stale = true, knownAtWallMs = 1_000)
        island.render(stale, guidance = null)
        island.showSteady(ActivityPresentation.PANEL)
        idle()
        val compact = island.folded as ActivityOverlayRenderer.ActivityCompactView
        assertSame(compact, island.currentForm)
        assertTrue(compact.width <= 240)
        assertTrue(compact.height <= 60)
        assertEquals(17f, compact.title.textSize)
        assertTrue(GuidanceIslands.lastKnown(stale).actions.isEmpty())
    }

    private fun show(content: ActivitySurfaceContent, presentation: ActivityPresentation) {
        island.render(item(content, presentation), guidance = null)
        island.showSteady(presentation)
        idle()
    }

    private fun content(
        primary: String = "Teardrop (M…",
        headline: String? = null,
        secondary: String? = null,
        measure: String? = null,
        actions: List<ActivityAction> = emptyList(),
    ) = ActivitySurfaceContent(
        glyph = "disc",
        primary = primary,
        secondary = secondary,
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = actions,
        maxDurationMs = null,
        measure = measure,
        headline = headline,
    )

    private fun item(content: ActivitySurfaceContent, presentation: ActivityPresentation) = ActivityRenderItem(
        activity = NexusActivitySurface(
            surfaceId = "media:activity",
            ownerPluginId = "media",
            seq = 1,
            content = content,
            corner = PinSurfacePosition.TOP_RIGHT,
            startedOrder = 1,
            lastUpdatedOrder = 1,
            lastSignificantOrder = null,
            collapseAtMs = 0,
            maxDurationDeadlineMs = null,
            selectedActionIndex = 0,
            motionToken = 1,
        ),
        primary = true,
        presentation = presentation,
    )

    /** What the framework dispatches down a window when the display turns off or on; not public API. */
    private fun screenState(state: Int) {
        View::class.java.getDeclaredMethod("dispatchScreenStateChanged", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(island, state)
    }

    private fun shownTexts(root: View): List<String> = find<TextView>(root).filter { it.isShown }.map { it.text.toString() }

    private fun rectOf(root: View, child: View): Rect = Rect(0, 0, child.width, child.height)
        .also { (root as ViewGroup).offsetDescendantRectToMyCoords(child, it) }

    private inline fun <reified T : View> find(root: View): List<T> = find(root, T::class.java)

    private fun <T : View> find(root: View, type: Class<T>): List<T> = when {
        type.isInstance(root) -> listOf(type.cast(root)!!)
        root is ViewGroup -> (0 until root.childCount).flatMap { find(root.getChildAt(it), type) }
        else -> emptyList()
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val DISC = "M3,12 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0"
    }
}
