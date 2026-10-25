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
 * island gives way to a guidance island or a pin beside it, two folded islands share the row,
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

    /** Draws each item in its own full-screen island, sized as the renderer sizes them. */
    private fun draw(
        items: List<Pair<ActivityRenderItem, GuidanceIsland?>>,
        pin: NexusPinSurface? = null,
    ): List<ActivityOverlayRenderer.ActivityIsland> {
        val guidances = items.associate { (item, guidance) -> item.activity.surfaceId to guidance }
        val budgets = ActivityOverlayRenderer.foldedWidthBudgets(activity, items.map { it.first }, guidances, pin)
        return items.map { (item, guidance) ->
            ActivityOverlayRenderer.ActivityIsland(
                activity,
                glyphOf = { _, _ -> GlyphDrawable(DISC) },
                actionGlyphOf = { GlyphDrawable(DISC) },
            ).also { island ->
                container.addView(island, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                island.place(item.activity.corner, 0)
                island.render(item, guidance, budgets[item.activity.corner])
                island.showSteady(item.presentation)
            }
        }.also { idle() }
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

    private fun item(owner: String, corner: PinSurfacePosition, content: ActivitySurfaceContent) = ActivityRenderItem(
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
        presentation = ActivityPresentation.CHIP,
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
