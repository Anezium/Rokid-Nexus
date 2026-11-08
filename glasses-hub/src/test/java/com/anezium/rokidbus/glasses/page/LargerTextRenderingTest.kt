package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.os.Looper
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.glasses.HudTextSize
import com.anezium.rokidbus.glasses.HudTopInset
import com.anezium.rokidbus.glasses.session.FrameIdentity
import com.anezium.rokidbus.glasses.session.InlineFooter
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.ScreenHeader
import com.anezium.rokidbus.glasses.session.ScreenHint
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageTextSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The Larger text setting drawn by real Views measured at the HUD's 480x640 logical pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargerTextRenderingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val view = SessionScreenView(context)
    private val paged = mutableListOf<SessionEvent.DocumentPaged>()
    private val scale = PageStyle.LARGE_TEXT_SCALE
    private val longText = List(80) { "A sentence long enough to wrap across the lines of the HUD." }.joinToString(" ")
    private var unsubscribe: (() -> Unit)? = null

    @Before
    fun clean() {
        context.getSharedPreferences(HudTopInset.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        view.onDocumentPaged = { paged += it }
    }

    @After
    fun stopObserving() {
        unsubscribe?.invoke()
    }

    private fun layout() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun screen(vararg blocks: ScreenBlock, footer: InlineFooter? = null) = SessionScreen(
        header = ScreenHeader("Relay", trail = "12:24"),
        blocks = blocks.toList(),
        hint = ScreenHint("Tap open", "Back"),
        footer = footer,
    )

    private fun document(key: String = "doc") = ScreenBlock.Document(
        key, FrameIdentity(1, 1), "page", 1, listOf(PageSegment("s", PageSegmentKind.PROSE, longText)), null, false,
    )

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun lineHeight(text: TextView): Int = text.paint.getFontMetricsInt(null)

    /** A shown line is as tall as its font asks, and the container it sits in holds it whole. */
    private fun assertNoClipping(container: View) {
        val shown = texts(container).filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
        assertTrue("nothing shown in $container", shown.isNotEmpty())
        shown.forEach {
            assertTrue("${it.text}: ${it.height} under its line of ${lineHeight(it)}", it.height >= lineHeight(it))
            var bottom = it.bottom
            var parent = it.parent as View
            while (parent !== container) {
                bottom += parent.top
                parent = parent.parent as View
            }
            assertTrue("${it.text} ends at $bottom past ${container.height}", bottom <= container.height)
        }
    }

    @Test
    fun `a row's text grows and stays whole`() {
        view.setTextSize(PageTextSize.LARGE)
        view.show(
            screen(
                ScreenBlock.Row("one", "Applications", selected = true),
                ScreenBlock.Row("two", "Mika", sub = "Can you check the build", trail = "3m"),
                ScreenBlock.Row("three", "A label long enough to wrap over several lines of a row, up to its cap of three", sub = "A sub long enough to wrap on two lines of the row, and then to be cut short"),
            ),
        )
        layout()

        listOf("one", "two", "three").forEach { assertNoClipping(view.blockViews.getValue(it)) }
        val label = texts(view.blockViews.getValue("one")).single()
        assertEquals(PageStyle.BODY_PX * scale, label.textSize, 0.01f)
        assertTrue(view.blockViews.getValue("one").height >= PageStyle.ROW_MIN_HEIGHT_PX)
    }

    @Test
    fun `a card and the page's fixed chrome hold their larger lines`() {
        view.setTextSize(PageTextSize.LARGE)
        view.show(
            screen(
                ScreenBlock.Card("card", "NAVIGATION", "12:24", "1.8 km", "Gare de Fontainebleau", "12 min", figure = true, ended = false, selected = true, tertiary = "Arrive 12:36"),
                footer = InlineFooter.StatusLine("Listening to the wearer", sub = "Tap to stop"),
            ),
        )
        layout()

        assertNoClipping(view.blockViews.getValue("card"))
        assertNoClipping(view.footer)
        val chrome = (0 until view.childCount).map(view::getChildAt).filter { it is ViewGroup && it !== view.viewport && it !== view.footer }
        assertEquals(2, chrome.size)
        chrome.forEach(::assertNoClipping)
    }

    @Test
    fun `a document page holds its larger lines and takes more pages`() {
        view.show(screen(document()))
        layout()
        val normalPages = paged.last().pageStarts.size

        view.setTextSize(PageTextSize.LARGE)
        layout()

        val page = view.blockViews.getValue("doc") as DocumentPageView
        assertEquals(PageStyle.BODY_PX * scale, page.sizeOf(PageSegmentKind.PROSE), 0.01f)
        assertTrue("${paged.last().pageStarts.size} pages after $normalPages", paged.last().pageStarts.size > normalPages)
        val paint = TextPaint().apply {
            textSize = PageStyle.BODY_PX * scale
            typeface = PageStyle.sans
        }
        val lines = page.visibleText().lines().size
        assertTrue("$lines lines in ${page.height}", lines * paint.getFontMetricsInt(null) * 1.3f <= page.height)
    }

    @Test
    fun `a size change redraws the screen on show`() {
        unsubscribe = HudTextSize.observe(context, view::setTextSize)
        shadowOf(Looper.getMainLooper()).idle()
        view.show(screen(ScreenBlock.Row("row", "Applications"), footer = InlineFooter.StatusLine("Listening")))
        layout()
        val before = view.blockViews.getValue("row")
        assertEquals(PageStyle.BODY_PX, texts(before).single().textSize, 0f)

        HudTextSize.set(context, PageTextSize.LARGE)
        layout()

        val row = view.blockViews.getValue("row")
        assertTrue("the row was not rebuilt", row !== before)
        assertEquals(PageStyle.BODY_PX * scale, texts(row).single().textSize, 0.01f)
        assertEquals(PageStyle.BODY_PX * scale, texts(view.footer).first().textSize, 0.01f)
        assertEquals(PageStyle.CAPTION_PX * scale, texts(view).first { it.text == "Tap open" }.textSize, 0.01f)

        HudTextSize.set(context, PageTextSize.NORMAL)
        layout()

        assertEquals(PageStyle.BODY_PX, texts(view.blockViews.getValue("row")).single().textSize, 0f)
        assertEquals(PageStyle.CAPTION_PX, texts(view).first { it.text == "Tap open" }.textSize, 0f)
    }

    @Test
    fun `a document shown when the size changes reports its new pages`() {
        view.show(screen(document()))
        layout()
        val reports = paged.size

        view.setTextSize(PageTextSize.LARGE)
        layout()

        assertEquals(reports + 1, paged.size)
    }

    @Test
    fun `views outside a session screen keep their sizes`() {
        view.setTextSize(PageTextSize.LARGE)

        assertEquals(PageStyle.BODY_PX, PageStyle.text(context, "island", PageStyle.BODY_PX, PageStyle.ink).textSize, 0f)
        assertEquals(PageStyle.BODY_PX, OverflowLineView(context).apply { style(PageStyle.BODY_PX, PageStyle.ink, PageStyle.sans) }.textSize, 0f)
    }
}
