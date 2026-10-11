package com.anezium.rokidbus.glasses.page

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.ActivityStop
import com.anezium.rokidbus.glasses.session.DocumentAnchor
import com.anezium.rokidbus.glasses.session.Frame
import com.anezium.rokidbus.glasses.session.FrameIdentity
import com.anezium.rokidbus.glasses.session.FrameMode
import com.anezium.rokidbus.glasses.session.FrameStatus
import com.anezium.rokidbus.glasses.session.NoticePreview
import com.anezium.rokidbus.glasses.session.RootStops
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.Snapshot
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Every template drawn by real Views measured at the HUD's 480x640 logical pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
// Real text measurement: wrapping and pagination are what is under test.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionScreenViewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val items = TypedPageItems(PageTemplateContract::isLocalPageId, providerPageAllowed = { true })
    private val screens = SessionScreens(items, { if (it == "nav") "Navigation" else if (it == "relay") "Relay" else null }, { "12:24" }, { it })
    private val view = SessionScreenView(context)
    private val paged = mutableListOf<SessionEvent.DocumentPaged>()

    init {
        view.onDocumentPaged = { paged += it }
    }

    private fun render(screen: SessionScreen, topInset: Int = 0) {
        view.setTopInsetPx(topInset)
        view.show(screen)
        layout()
    }

    private fun layout() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun page(pageId: String, body: PageBody, title: String = "Relay", actions: JSONArray = JSONArray(), revision: Long = 1): PageSurfaceResponse.Page {
        val payload = PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, pageId))
            .put("revision", revision).put("template", body.template).put("title", title)
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", false)
        return (PageSurfaceContract.validateResponse(payload) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
    }

    private fun shown(page: PageSurfaceResponse.Page, selected: Int = 0, mode: FrameMode = FrameMode.ROWS, anchor: DocumentAnchor? = null, tombstone: String? = null) =
        SessionState.InPage(
            listOf(
                Frame(
                    page.correlation.pageId, FrameStatus.Shown(Snapshot(page), page.revision, 1_000, "r1"),
                    selected = selected, mode = mode, anchor = anchor, tombstone = tombstone,
                    selectedKey = items.entries(page).getOrNull(selected)?.key,
                ),
            ),
            SessionState.Root(RootStops.build(emptyList(), null, 0)),
        )

    private fun visibleInViewport(key: String): Boolean {
        val block = view.blockViews.getValue(key)
        return block.top >= 0 && block.bottom <= view.viewport.height
    }

    private fun texts(root: View = view): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertMinima() {
        texts().filter { it.text.isNotBlank() && it.visibility == View.VISIBLE }.forEach {
            assertTrue("${it.text} at ${it.textSize}", it.textSize >= PageStyle.CAPTION_PX)
        }
    }

    @Test
    fun `the root draws every stop, figures at thirty pixels, and keeps the selection whole`() {
        val stops = RootStops.build(
            listOf(
                ActivityStop("nav:local", "nav:main", "120 m", pinned = true, ownerLabel = "Navigation", detail = "Rue de Rivoli", measure = "1.2 km", updatedAtWallMs = 1),
                ActivityStop("transit:local", "nexus:activity:transit", "3 stops", ownerLabel = "Transit", detail = "to Luxembourg"),
                ActivityStop("timer:local", "nexus:activity:timer", "4:00", ownerLabel = "Timer"),
            ),
            NoticePreview(RootStops.NOTICE_PAGE_ID, "Standup · 14:00", "Calendar", 1),
            1,
        )
        render(screens.screen(SessionState.Root(stops, selected = 0), null))
        assertEquals(6, view.blockViews.size)
        val figure = texts(view.blockViews.getValue("nav:local")).first { it.text == "120 m" }
        assertTrue(figure.textSize >= PageStyle.FIGURE_PX)
        val street = texts(view.blockViews.getValue("nav:local")).first { it.text == "Rue de Rivoli" }
        assertTrue(street.textSize >= PageStyle.BODY_PX)
        assertMinima()
        assertEquals(PageStyle.HEADER_HEIGHT_PX, view.getChildAt(0).height)
        assertTrue(visibleInViewport("nav:local"))

        // Applications, the sixth stop, is brought into view when it is selected.
        render(screens.screen(SessionState.Root(stops, selected = 5), null))
        assertTrue(visibleInViewport(RootStops.APPLICATIONS_PAGE_ID))
        assertTrue(visibleInViewport(RootStops.APPLICATIONS_PAGE_ID))
    }

    @Test
    fun `a card keeps its size and shortens a long street on one line`() {
        fun stops(street: String) = RootStops.build(
            listOf(ActivityStop("nav:local", "nav:main", "300 m", pinned = true, ownerLabel = "Navigation", detail = street)),
            null, 0,
        )
        render(screens.screen(SessionState.Root(stops("Rue A"), 0), null))
        val height = view.blockViews.getValue("nav:local").height
        val long = "Avenue des Champs-Élysées et rond-point Charles de Gaulle, sortie 3 vers la Grande Armée"
        render(screens.screen(SessionState.Root(stops(long), 0), null))
        assertEquals(height, view.blockViews.getValue("nav:local").height)
        val street = texts(view.blockViews.getValue("nav:local")).first { it.text.toString() == long }
        assertEquals(1, street.maxLines)
        // An ended card keeps the same size too.
        render(screens.screen(SessionState.Root(RootStops.withEnded(stops("Rue A"), "nav:local"), 0), null))
        assertEquals(height, view.blockViews.getValue("nav:local").height)
    }

    @Test
    fun `a body row and an appended action sharing a wire id are focused as themselves`() {
        val rows = listOf(PageRow("action:go", "Body go", target = PageTarget.Page("relay:thread:x"))) +
            (1..20).map { PageRow("f$it", "Thread $it", sub = "2 messages") }
        val actions = JSONArray().put(JSONObject().put("id", "go").put("label", "Go").put("kind", "plugin").put("confirm", true))
        val list = page("relay:main", PageBody.SelectableList(rows), actions = actions)
        // The body row on top is selected; the appended action is far below it.
        render(screens.screen(shown(list, selected = 0), null, 1))
        val first = view.viewport.getChildAt(0)
        assertEquals("Body go", texts(first).first().text.toString())
        assertTrue(first.top >= 0 && first.bottom <= view.viewport.height)
        // The appended action, selected, is the one scrolled into view.
        render(screens.screen(shown(list, selected = rows.size), null, 1))
        val last = view.viewport.getChildAt(view.viewport.childCount - 1)
        assertEquals("Go", texts(last).first().text.toString())
        assertTrue(last.top >= 0 && last.bottom <= view.viewport.height)
    }

    @Test
    fun `a long list follows the selection to its first hidden row and its last row`() {
        val rows = (1..20).map { PageRow("r$it", "Thread $it", sub = "2 messages") } +
            PageRow("wrap", "A very long name that wraps over more than one line of the row on the glasses display, still selected whole")
        val list = page("relay:main", PageBody.SelectableList(rows))
        render(screens.screen(shown(list, selected = 0), null, 1))
        val firstHidden = (0 until rows.size).first { !visibleInViewport("row:${rows[it].id}") }
        render(screens.screen(shown(list, selected = firstHidden), null, 1))
        assertTrue(visibleInViewport("row:${rows[firstHidden].id}"))
        render(screens.screen(shown(list, selected = rows.lastIndex), null, 1))
        val last = view.blockViews.getValue("row:wrap")
        assertTrue(visibleInViewport("row:wrap"))
        assertTrue(texts(last).first().lineCount > 1)
        assertMinima()

        // A nonzero inset moves the chrome down and the selection stays whole.
        render(screens.screen(shown(list, selected = rows.lastIndex), null, 1), topInset = 60)
        assertEquals(60, view.getChildAt(0).top)
        assertTrue(visibleInViewport("row:wrap"))
        // Back on the first row after returning from a child, the top is visible again.
        render(screens.screen(shown(list, selected = 0), null, 1), topInset = 60)
        assertTrue(visibleInViewport("row:r1"))
    }

    @Test
    fun `a removed selected row is drawn in its place as an inert tombstone`() {
        val list = page("relay:main", PageBody.SelectableList(listOf(PageRow("a", "Ana"), PageRow("c", "Cy"))))
        val screen = screens.screen(shown(list, selected = 1, tombstone = "Bo"), null, 1)
        render(screen)
        val keys = screen.blocks.map { it.key }
        assertEquals(listOf("row:a", "tombstone", "row:c"), keys)
        val tombstone = screen.blocks[1] as ScreenBlock.Row
        assertTrue(tombstone.selected && tombstone.tombstone && !tombstone.enabled)
        assertFalse((screen.blocks[2] as ScreenBlock.Row).selected)
        assertTrue(visibleInViewport("tombstone"))
    }

    @Test
    fun `summary and commands keep the hierarchy minima`() {
        val summary = page(
            "nav:main",
            PageBody.Summary(
                primary = "Turn right in 120 m", secondary = "Rue de Rivoli",
                facts = listOf(PageFact("eta", "ETA", "12:41"), PageFact("src", "Source", "Citymapper · 12:24")),
                items = listOf(PageRow("commands", "Commands", target = PageTarget.Page("nav:commands"))),
            ),
            title = "Navigation",
        )
        val screen = screens.screen(shown(summary), null, 1)
        render(screen)
        assertEquals("NAVIGATION", screen.header?.title)
        val primary = texts(view.blockViews.getValue("primary")).single()
        assertTrue(primary.textSize >= PageStyle.FIGURE_PX)
        assertMinima()

        val commands = page(
            "nav:commands",
            PageBody.Commands(listOf(PageRow("open", "Open the full app", sub = "exclusive surface", target = PageTarget.Action("open")))),
            title = "Commands",
            actions = JSONArray().put(org.json.JSONObject().put("id", "open").put("label", "Open").put("kind", "immersion").put("confirm", false)),
        )
        render(screens.screen(shown(commands), null, 1))
        assertTrue(visibleInViewport("row:open"))
        assertMinima()
    }

    @Test
    fun `a rebind before the queued layout report runs drops that stale report`() {
        fun prose(word: String) = (1..60).joinToString(" ") { "$word $it of the thread" }
        val first = page("relay:thread", PageBody.Document(listOf(PageSegment("a", PageSegmentKind.PROSE, prose("old")))))
        val second = page("relay:thread", PageBody.Document(listOf(PageSegment("b", PageSegmentKind.PROSE, prose("new")))), revision = 2)
        // Both bindings lay out before the main looper runs either report.
        listOf(first, second).forEach { document ->
            view.show(screens.screen(shown(document, mode = FrameMode.READING), null, 1))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val pager = view.blockViews.getValue("document") as DocumentPageView
        assertEquals(listOf(2L), paged.map { it.revision })
        assertEquals(pager.pageStarts, paged.single().pageStarts)
        assertTrue(paged.single().pageStarts.all { it.segmentId == "b" })
    }

    @Test
    fun `a document's footer is drawn after its text, and a document without one is unchanged`() {
        val segments = listOf(
            PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"),
            PageSegment("p", PageSegmentKind.PROSE, (1..60).joinToString(" ") { "line $it of the thread, said plainly" }),
        )
        fun read(body: PageBody.Document): Pair<List<DocumentAnchor>, List<String>> {
            val document = page("relay:thread", body)
            render(screens.screen(shown(document, mode = FrameMode.READING), null, 1))
            val pager = view.blockViews.getValue("document") as DocumentPageView
            val starts = pager.pageStarts
            val texts = starts.map { start ->
                render(screens.screen(shown(document, mode = FrameMode.READING, anchor = start), null, 1))
                pager.visibleText()
            }
            return starts to texts
        }
        val (plainStarts, plainPages) = read(PageBody.Document(segments))
        assertTrue(plainPages.none { "Captured on the phone" in it })

        val (starts, pages) = read(PageBody.Document(segments, footer = "Captured on the phone · 2 messages"))
        // The footer follows the last of the text, inside the measured pages.
        assertTrue(pages.last(), pages.last().trimEnd().endsWith("Captured on the phone · 2 messages"))
        assertEquals(1, pages.count { "Captured on the phone" in it })
        // The text's own pages start where they did without a footer.
        assertEquals(plainStarts, starts.take(plainStarts.size))
        assertEquals(plainPages.dropLast(1), pages.take(plainPages.size - 1))
    }

    @Test
    fun `a long document pages through measured text without losing a character`() {
        val prose = (1..60).joinToString(" ") { "line $it of the thread, said plainly" } + "\n" + "x".repeat(700) + " fin ünïcødé 🙂"
        val document = page(
            "relay:thread",
            PageBody.Document(listOf(PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"), PageSegment("p", PageSegmentKind.PROSE, prose))),
        )
        render(screens.screen(shown(document, mode = FrameMode.READING), null, 1))
        val pager = view.blockViews.getValue("document") as DocumentPageView
        assertTrue("pages=${pager.pageStarts.size}", pager.pageStarts.size > 1)
        val report = paged.last()
        assertEquals(FrameIdentity(1, 1), report.frame)
        assertEquals(pager.pageStarts, report.pageStarts)
        assertEquals(0, report.anchorPage)

        // Every page, in order, holds the whole text: nothing clipped, the long word included.
        val seen = StringBuilder()
        pager.pageStarts.forEach { start ->
            render(screens.screen(shown(document, mode = FrameMode.READING, anchor = start), null, 1))
            assertEquals(start, pager.pageStarts[pager.page])
            seen.append(pager.visibleText()).append('\n')
        }
        val flat = seen.toString().replace("\n", "").replace(" ", "")
        assertEquals(("Mika · captured 14:11" + prose).replace("\n", "").replace(" ", ""), flat)

        // A smaller viewport (a top inset) relayouts and keeps the anchor's text on screen.
        val anchor = pager.pageStarts[2]
        render(screens.screen(shown(document, mode = FrameMode.READING, anchor = anchor), null, 1), topInset = 80)
        val page = pager.page
        assertTrue(pager.pageStarts[page].let { it.segmentId == anchor.segmentId && it.offset <= anchor.offset })
    }

    @Test
    fun `unavailable lays out its title, reason and controls separately`() {
        val frame = Frame("nav:main", FrameStatus.Unavailable(null, PageSurfaceContract.ERROR_PAGE_TIMEOUT), selected = 0)
        val state = SessionState.InPage(listOf(frame), SessionState.Root(RootStops.build(emptyList(), null, 0)))
        val screen = screens.screen(state, null, 1)
        render(screen)
        val title = view.blockViews.getValue("unavailable")
        val reason = view.blockViews.getValue("reason")
        val retry = view.blockViews.getValue("retry")
        val back = view.blockViews.getValue("back")
        assertTrue(title.bottom <= reason.top && reason.bottom <= retry.top && retry.bottom <= back.top)
        assertTrue(visibleInViewport("retry") && visibleInViewport("back"))
        assertTrue(texts().none { "PAGE_TIMEOUT" in it.text })
        assertEquals("NAVIGATION", screen.header?.title)
        assertMinima()
    }
}
