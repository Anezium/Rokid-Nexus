package com.anezium.rokidbus.glasses

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.page.IslandContent
import com.anezium.rokidbus.glasses.page.IslandForm
import com.anezium.rokidbus.glasses.page.NavIslandView
import com.anezium.rokidbus.glasses.page.OverflowLineView
import com.anezium.rokidbus.glasses.page.OverflowScroll
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Two things sharing a row of the HUD's 480 pixels, as the activity renderer sizes them: a folded
 * island gives way to a guidance island, an expanded panel or a pin beside it, two folded islands share the row,
 * and the edge margin stays between them; the title scrolls inside the narrower line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w320dp-h640dp-hdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityIslandRowTest {
    private val headline = "Relaxin' with Cherry (Kid Loco remix) II"
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var container: FrameLayout
    private var edgePx = 0
    private var oldMotion = true

    @Before
    fun attach() {
        oldMotion = HudMotion.enabled
        HudMotion.enabled = false
        controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        activity = controller.get()
        container = FrameLayout(activity)
        activity.setContentView(container)
        controller.visible()
        edgePx = BusTheme.dp(activity, 12)
        idle()
        assertEquals(480, container.width)
    }

    @After
    fun detach() {
        controller.pause().stop().destroy()
        HudMotion.enabled = oldMotion
    }

    @Test
    fun `a folded island gives way to a guidance island in its row and still scrolls its title to the end`() {
        assertEquals(40, headline.length)
        val media = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val nav = item("nav", PinSurfacePosition.TOP_RIGHT, content(primary = "120 m"))
        val guidance = GuidanceIsland(IslandForm.TEXT, IslandContent.Guidance("turn-left", "120 m", street = "Rue de Rivoli"))
        val (mediaIsland, navIsland) = draw(listOf(media to null, nav to guidance))

        val compact = mediaIsland.folded as ActivityOverlayRenderer.ActivityCompactView
        val navForm = navIsland.currentForm as NavIslandView
        assertEquals("the guidance form is never squashed", IslandForm.TEXT.widthPx, navForm.width)
        assertApart(rectOf(compact), rectOf(navForm))
        assertDrawnWhole(compact.title, headline, compact)
    }

    @Test
    fun `a folded chip with a measure gives way to a guidance island in its row`() {
        val transit = item("transit", PinSurfacePosition.TOP_LEFT, content(headline = headline, measure = "3 min", secondary = "Gare de Lyon"))
        val nav = item("nav", PinSurfacePosition.TOP_RIGHT, content(primary = "120 m"))
        val guidance = GuidanceIsland(IslandForm.TEXT, IslandContent.Guidance("turn-left", "120 m", street = "Rue de Rivoli"))
        val (transitIsland, navIsland) = draw(listOf(transit to null, nav to guidance))

        val chip = transitIsland.folded
        assertTrue(chip is PinOverlayRenderer.PinPanelView)
        assertApart(rectOf(chip), rectOf(navIsland.currentForm!!))
        val title = find<OverflowLineView>(chip).single()
        assertDrawnWhole(title, headline, chip)
    }

    @Test
    fun `two folded islands in a row share it and both scroll their titles`() {
        val other = "Teardrop (Mad Professor remix) Massive A"
        val left = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val right = item("podcast", PinSurfacePosition.TOP_RIGHT, content(headline = other, secondary = "Massive Attack"))
        val (leftIsland, rightIsland) = draw(listOf(left to null, right to null))

        val leftForm = leftIsland.folded as ActivityOverlayRenderer.ActivityCompactView
        val rightForm = rightIsland.folded as ActivityOverlayRenderer.ActivityCompactView
        assertApart(rectOf(leftForm), rectOf(rightForm))
        for ((form, text) in listOf(leftForm to headline, rightForm to other)) {
            assertTrue("$text scrolls", form.title.overflowPx() > 0 && form.title.scrolling)
            assertEquals(0, requireNotNull(form.title.layout).getEllipsisCount(0))
        }
        // Both started together: each reaches its own end, the shorter way first.
        var elapsed = 0L
        for (line in listOf(leftForm.title, rightForm.title).sortedBy { it.overflowPx() }) {
            val end = OverflowScroll.HOLD_MS + OverflowScroll.travelMs(line.overflowPx()) + OverflowScroll.FRAME_MS
            idle(end - elapsed)
            elapsed = end
            assertEquals("${line.text} reaches its hidden end", line.overflowPx(), line.scrollX)
        }
    }

    @Test
    fun `a folded island alone in its row keeps its own width`() {
        val media = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val other = item("timer", PinSurfacePosition.BOTTOM_RIGHT, content(headline = headline, secondary = "Timer"))
        val budgets = ActivityOverlayRenderer.foldedWidthBudgets(activity, listOf(media, other), emptyMap(), pin = null)
        assertTrue(budgets.isEmpty())
        val (mediaIsland) = draw(listOf(media to null, other to null))

        val compact = mediaIsland.folded as ActivityOverlayRenderer.ActivityCompactView
        assertEquals("the pins' medium text width", (480 * 0.60f).toInt(), compact.title.width)
        assertEquals((480 * 0.60f).toInt(), compact.secondary.maxWidth)
    }

    @Test
    fun `a folded island gives way to a drawn pin in its row`() {
        val pin = NexusPinSurface(
            surfaceId = "pin",
            seq = 1,
            content = PinSurfaceContent(
                title = "Groceries for tonight",
                lines = listOf(PinSurfaceLine("Eggs, flour, milk and two lemons")),
                position = PinSurfacePosition.TOP_RIGHT,
                ttlMs = null,
                size = PinSurfaceSize.MEDIUM,
            ),
            expiresAtMs = null,
        )
        val pinView = PinOverlayRenderer.PinPanelView(activity).apply { render(pin) }
        // Where the pin's own window puts it: its corner, the edge margin from the screen's edges.
        container.addView(
            pinView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(edgePx, edgePx, edgePx, edgePx) },
        )
        val media = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val (mediaIsland) = draw(listOf(media to null), pin)

        assertEquals(PinOverlayRenderer.drawnWidthPx(activity, pin), pinView.width)
        val compact = mediaIsland.folded as ActivityOverlayRenderer.ActivityCompactView
        assertApart(rectOf(compact), rectOf(pinView))
        assertTrue(compact.title.overflowPx() > 0 && compact.title.scrolling)
    }

    @Test
    fun `a folded island beside a guidance panel keeps only its glyph, and its text when the guidance shrinks or ends`() {
        val media = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val nav = item("nav", PinSurfacePosition.TOP_RIGHT, content(primary = "120 m"))
        val street = IslandContent.Guidance("turn-left", "120 m", street = "Rue de Rivoli")
        val islands = mutableMapOf<String, ActivityOverlayRenderer.ActivityIsland>()
        val (mediaIsland, navIsland) = draw(listOf(media to null, nav to GuidanceIsland(IslandForm.PANEL, street)), islands = islands)

        assertEquals("the route keeps its panel", IslandForm.PANEL.widthPx, navIsland.currentForm!!.width)
        assertGivesWay(mediaIsland, navIsland, budgetPx = 480 - 3 * edgePx - IslandForm.PANEL.widthPx)

        draw(listOf(media to null, nav to GuidanceIsland(IslandForm.TEXT, street)), islands = islands)
        val compact = mediaIsland.currentForm as ActivityOverlayRenderer.ActivityCompactView
        assertApart(rectOf(compact), rectOf(navIsland.currentForm!!))
        assertDrawnWhole(compact.title, headline, compact)

        draw(listOf(media to null, nav to GuidanceIsland(IslandForm.PANEL, street)), islands = islands)
        assertGivesWay(mediaIsland, navIsland, budgetPx = 480 - 3 * edgePx - IslandForm.PANEL.widthPx)
        // The route ends: the island is alone in its row again, at its own widths.
        container.removeView(navIsland)
        draw(listOf(media to null), islands = islands)
        assertEquals((480 * 0.60f).toInt(), (mediaIsland.currentForm as ActivityOverlayRenderer.ActivityCompactView).title.width)
    }

    @Test
    fun `a measured chip beside a guidance panel keeps only its glyph, and its text when the guidance shrinks`() {
        val transit = item("transit", PinSurfacePosition.TOP_LEFT, content(headline = headline, measure = "3 min", secondary = "Gare de Lyon"))
        val nav = item("nav", PinSurfacePosition.TOP_RIGHT, content(primary = "120 m"))
        val street = IslandContent.Guidance("turn-left", "120 m", street = "Rue de Rivoli")
        val islands = mutableMapOf<String, ActivityOverlayRenderer.ActivityIsland>()
        val (transitIsland, navIsland) = draw(listOf(transit to null, nav to GuidanceIsland(IslandForm.PANEL, street)), islands = islands)

        assertGivesWay(transitIsland, navIsland, budgetPx = 480 - 3 * edgePx - IslandForm.PANEL.widthPx)

        draw(listOf(transit to null, nav to GuidanceIsland(IslandForm.MINIMAP, street)), islands = islands)
        val chip = transitIsland.currentForm!!
        assertTrue(chip is PinOverlayRenderer.PinPanelView)
        assertApart(rectOf(chip), rectOf(navIsland.currentForm!!))
        assertDrawnWhole(find<OverflowLineView>(chip).single(), headline, chip)
    }

    @Test
    fun `a folded island beside an expanded panel keeps only its glyph, and its text when the panel folds`() {
        val panelPx = (480 * 0.78f).toInt()
        val other = "Teardrop (Mad Professor remix) Massive A"
        val podcast = item("podcast", PinSurfacePosition.TOP_RIGHT, content(headline = other, secondary = "Massive Attack"))
        val islands = mutableMapOf<String, ActivityOverlayRenderer.ActivityIsland>()
        val expanded = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"), ActivityPresentation.PANEL)
        val (mediaIsland, podcastIsland) = draw(listOf(expanded to null, podcast to null), islands = islands)

        assertEquals("the panel keeps its width", panelPx, mediaIsland.currentForm!!.width)
        assertTrue(mediaIsland.currentForm !== mediaIsland.folded)
        assertGivesWay(podcastIsland, mediaIsland, budgetPx = 480 - 3 * edgePx - panelPx)

        // The ten seconds are up: the panel folds into its text form, and the row is shared again.
        draw(listOf(item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco")) to null, podcast to null), islands = islands)
        val left = mediaIsland.currentForm as ActivityOverlayRenderer.ActivityCompactView
        val right = podcastIsland.currentForm as ActivityOverlayRenderer.ActivityCompactView
        assertApart(rectOf(left), rectOf(right))
        assertDrawnWhole(right.title, other, right)

        draw(listOf(expanded to null, podcast to null), islands = islands)
        assertGivesWay(podcastIsland, mediaIsland, budgetPx = 480 - 3 * edgePx - panelPx)
    }

    @Test
    fun `a measured chip beside an expanded panel in the bottom row keeps only its glyph, and its text when the panel folds`() {
        val panelPx = (480 * 0.78f).toInt()
        val transit = item("transit", PinSurfacePosition.BOTTOM_LEFT, content(headline = headline, measure = "3 min", secondary = "Gare de Lyon"))
        val timer = { presentation: ActivityPresentation ->
            item("timer", PinSurfacePosition.BOTTOM_RIGHT, content(headline = "Pasta", secondary = "Timer"), presentation)
        }
        val islands = mutableMapOf<String, ActivityOverlayRenderer.ActivityIsland>()
        val (transitIsland, timerIsland) = draw(listOf(transit to null, timer(ActivityPresentation.PANEL) to null), islands = islands)

        assertEquals(panelPx, timerIsland.currentForm!!.width)
        assertGivesWay(transitIsland, timerIsland, budgetPx = 480 - 3 * edgePx - panelPx)

        draw(listOf(transit to null, timer(ActivityPresentation.CHIP) to null), islands = islands)
        val chip = transitIsland.currentForm!!
        assertTrue(chip is PinOverlayRenderer.PinPanelView)
        assertApart(rectOf(chip), rectOf(timerIsland.currentForm!!))
        assertDrawnWhole(find<OverflowLineView>(chip).single(), headline, chip)
    }

    @Test
    fun `an island with no room for its glyph is not drawn, and comes back by itself`() {
        val media = item("media", PinSurfacePosition.TOP_LEFT, content(headline = headline, secondary = "Kid Loco"))
        val island = ActivityOverlayRenderer.ActivityIsland(activity, glyphOf = { _, _ -> GlyphDrawable(DISC) })
        container.addView(island, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        island.place(media.activity.corner, 0)
        val drawWithin = { budgetPx: Int? ->
            island.render(media, null, budgetPx)
            island.showSteady(media.presentation)
            idle()
        }
        drawWithin(null)
        val glyphPx = glyphSizePx(island)

        drawWithin(glyphPx - 1)
        assertNotDrawn(island)

        drawWithin(glyphPx)
        assertTrue(island.currentForm is ActivityOverlayRenderer.ActivityGlyphView)
        assertEquals(glyphPx, island.currentForm!!.width)
        assertEquals(1f, island.currentForm!!.alpha)

        drawWithin(glyphPx - 1)
        assertNotDrawn(island)
        drawWithin(null)
        val compact = island.currentForm as ActivityOverlayRenderer.ActivityCompactView
        assertEquals(1f, compact.alpha)
        assertEquals((480 * 0.60f).toInt(), compact.title.width)
    }

    @Test
    fun `fixed widths are kept and only folded islands are bounded`() {
        val room = 480 - 3 * 18
        assertEquals(18, edgePx)
        val budgets = { occupants: Map<PinSurfacePosition, Int?> -> ActivityOverlayRenderer.rowBudgets(480, edgePx, occupants) }
        assertEquals(
            mapOf(PinSurfacePosition.TOP_LEFT to room - 232),
            budgets(mapOf(PinSurfacePosition.TOP_LEFT to null, PinSurfacePosition.TOP_RIGHT to 232)),
        )
        assertEquals(
            mapOf(PinSurfacePosition.BOTTOM_LEFT to room / 2, PinSurfacePosition.BOTTOM_RIGHT to room - room / 2),
            budgets(mapOf(PinSurfacePosition.BOTTOM_LEFT to null, PinSurfacePosition.BOTTOM_RIGHT to null)),
        )
        assertEquals(emptyMap<PinSurfacePosition, Int>(), budgets(mapOf(PinSurfacePosition.TOP_LEFT to 232, PinSurfacePosition.TOP_RIGHT to 262)))
        assertEquals(emptyMap<PinSurfacePosition, Int>(), budgets(mapOf(PinSurfacePosition.TOP_LEFT to null, PinSurfacePosition.BOTTOM_RIGHT to null)))
        assertEquals(
            mapOf(PinSurfacePosition.TOP_RIGHT to 0),
            budgets(mapOf(PinSurfacePosition.TOP_LEFT to 480, PinSurfacePosition.TOP_RIGHT to null)),
        )
    }

    /**
     * Draws each item in its own full-screen island, sized as the renderer sizes them; an island
     * already in [islands] is drawn again, as the renderer redraws its nodes.
     */
    private fun draw(
        items: List<Pair<ActivityRenderItem, GuidanceIsland?>>,
        pin: NexusPinSurface? = null,
        islands: MutableMap<String, ActivityOverlayRenderer.ActivityIsland> = mutableMapOf(),
    ): List<ActivityOverlayRenderer.ActivityIsland> {
        val guidances = items.associate { (item, guidance) -> item.activity.surfaceId to guidance }
        val budgets = ActivityOverlayRenderer.foldedWidthBudgets(activity, items.map { it.first }, guidances, pin)
        return items.map { (item, guidance) ->
            islands.getOrPut(item.activity.surfaceId) {
                ActivityOverlayRenderer.ActivityIsland(
                    activity,
                    glyphOf = { _, _ -> GlyphDrawable(DISC) },
                ).also { container.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            }.also { island ->
                island.place(item.activity.corner, 0)
                island.render(item, guidance, budgets[item.activity.corner])
                island.showSteady(item.presentation)
            }
        }.also { idle() }
    }

    /**
     * [folded] had [budgetPx] beside [neighbour]: its glyph alone when that fits, apart from the
     * neighbour by the edge margin, or nothing at all.
     */
    private fun assertGivesWay(folded: ActivityOverlayRenderer.ActivityIsland, neighbour: ActivityOverlayRenderer.ActivityIsland, budgetPx: Int) {
        val glyphPx = glyphSizePx(folded)
        if (budgetPx < glyphPx) {
            assertNotDrawn(folded)
            return
        }
        val form = folded.currentForm
        assertTrue("$form is the glyph alone", form is ActivityOverlayRenderer.ActivityGlyphView)
        assertTrue(folded.folded === form && !folded.withheld)
        assertEquals(glyphPx, form!!.width)
        assertEquals(1f, form.alpha)
        assertTrue("$glyphPx px fits $budgetPx px", form.width <= budgetPx)
        val (left, right) = listOf(rectOf(form), rectOf(neighbour.currentForm!!)).sortedBy { it.left }
        assertApart(left, right)
    }

    /** Nothing of [island] is drawn: no form, its outline folded away. */
    private fun assertNotDrawn(island: ActivityOverlayRenderer.ActivityIsland) {
        assertTrue(island.withheld)
        assertNull(island.currentForm)
        for (index in 0 until island.childCount) {
            val form = island.getChildAt(index)
            assertTrue("$form is hidden", form.visibility != View.VISIBLE && form.alpha == 0f)
        }
    }

    /** The glyph-only form's width, which the island decides by, as measured. */
    private fun glyphSizePx(island: ActivityOverlayRenderer.ActivityIsland): Int {
        val glyph = find<ActivityOverlayRenderer.ActivityGlyphView>(island).single()
        assertEquals((glyph.layoutParams as FrameLayout.LayoutParams).width, glyph.sizePx)
        return glyph.sizePx
    }

    /** Side by side in one row, apart by at least the edge margin, each inside the screen's. */
    private fun assertApart(left: Rect, right: Rect) {
        assertFalse("$left meets $right", Rect.intersects(left, right))
        assertTrue("the gap ${right.left - left.right} px is at least the edge margin", right.left - left.right >= edgePx)
        assertTrue(left.left >= edgePx && right.right <= container.width - edgePx)
    }

    /** [line] scrolls to its very end without an ellipsis, inside a [form] that keeps its size. */
    private fun assertDrawnWhole(line: OverflowLineView, text: String, form: View) {
        assertEquals(text, line.text.toString())
        assertEquals(0, requireNotNull(line.layout).getEllipsisCount(0))
        val overflow = line.overflowPx()
        assertTrue("$text fits its ${line.roomPx()} px", overflow > 0)
        assertTrue(line.scrolling)
        val width = form.width
        idle(OverflowScroll.HOLD_MS + OverflowScroll.travelMs(overflow) + OverflowScroll.FRAME_MS)
        assertEquals("the hidden end is reached", overflow, line.scrollX)
        assertEquals(0, requireNotNull(line.layout).getEllipsisCount(0))
        assertEquals(width, form.width)
    }

    private fun content(
        primary: String = "Relaxin' w…",
        headline: String? = null,
        secondary: String? = null,
        measure: String? = null,
    ) = ActivitySurfaceContent(
        glyph = "disc",
        primary = primary,
        secondary = secondary,
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = emptyList(),
        maxDurationMs = null,
        measure = measure,
        headline = headline,
    )

    private fun item(
        owner: String,
        corner: PinSurfacePosition,
        content: ActivitySurfaceContent,
        presentation: ActivityPresentation = ActivityPresentation.CHIP,
    ) = ActivityRenderItem(
        activity = NexusActivitySurface(
            surfaceId = "$owner:activity",
            ownerPluginId = owner,
            seq = 1,
            content = content,
            corner = corner,
            startedOrder = 1,
            lastUpdatedOrder = 1,
            lastSignificantOrder = null,
            collapseAtMs = 0,
            maxDurationDeadlineMs = null,
            selectedActionIndex = 0,
            motionToken = 1,
        ),
        primary = owner == "media",
        presentation = presentation,
    )

    private fun rectOf(child: View): Rect = Rect(0, 0, child.width, child.height)
        .also { container.offsetDescendantRectToMyCoords(child, it) }

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
