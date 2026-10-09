package com.anezium.rokidbus.glasses.page

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.glasses.session.ActivityStop
import com.anezium.rokidbus.glasses.session.DocumentAnchor
import com.anezium.rokidbus.glasses.session.FrameMode
import com.anezium.rokidbus.glasses.session.FrameStatus
import com.anezium.rokidbus.glasses.session.SessionEffect
import com.anezium.rokidbus.glasses.session.SessionEffectSink
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionRunner
import com.anezium.rokidbus.glasses.session.SessionScreens
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.SessionTimer
import com.anezium.rokidbus.glasses.session.TypedPageItems
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
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

/**
 * The renderer wired to the real runner at the largest HUD inset the phone can set: text too tall
 * for the viewport is reached by STEP, and an unavailable page keeps its last content readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingRunnerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val items = TypedPageItems(PageTemplateContract::isLocalPageId) { true }
    private val screens = SessionScreens(items, { mapOf("nav" to "Navigation", "relay" to "Relay")[it] }, { "12:24" }, { it })
    private val view = SessionScreenView(context)
    private val effects = mutableListOf<SessionEffect>()
    private var now = 1_000L

    private val runner = SessionRunner(
        reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId),
        clock = { now },
        timer = object : SessionTimer {
            override fun schedule(atUptimeMs: Long, task: () -> Unit) = Unit
            override fun cancel() = Unit
        },
        sink = object : SessionEffectSink {
            override fun execute(effect: SessionEffect) {
                effects += effect
            }

            // What the host does: draw the state, measured at the HUD's size.
            override fun settled(model: SessionModel) {
                if (model.state !is SessionState.InPage) return
                view.show(screens.screen(model.state, null, model.generation))
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
            }
        },
    )

    init {
        view.onDocumentPaged = { runner.dispatch(it) }
        view.onContentOverflowed = { runner.dispatch(it) }
        // The phone's largest inset, in the glasses' pixels at their 240 dpi.
        view.setTopInsetPx(PhoneHubCapabilitiesContract.MAX_HUD_TOP_INSET_DP * 240 / 160)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dispatch(event: SessionEvent) {
        runner.dispatch(event)
        idle()
    }

    private fun top() = (runner.state as SessionState.InPage).frames.last()

    private fun requests() = effects.filterIsInstance<SessionEffect.RequestPage>()

    private fun answer(request: PageSurfaceRequest, revision: Long, body: PageBody, actions: JSONArray = JSONArray()): JSONObject =
        PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", body.template).put("title", "Page")
            .put("body", PageTemplateContract.encode(body))
            .put("actions", actions).put("live", true)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "e1")
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, PageTemplateContract.namespaceOf(request.correlation.pageId))

    /** Opens [pageId] from its root card and answers it with [body] at [revision]. */
    private fun open(pageId: String, body: PageBody, revision: Long = 1, actions: JSONArray = JSONArray()) {
        dispatch(SessionEvent.ActivityStarted(ActivityStop("${PageTemplateContract.namespaceOf(pageId)}:local", pageId, "now", pinned = true)))
        dispatch(SessionEvent.TripleTap(now))
        now += PageSurfaceContract.GATE_MS
        dispatch(SessionEvent.Tick(now))
        dispatch(SessionEvent.Enter(now))
        dispatch(SessionEvent.PageResponse(answer(requests().last().request, revision, body, actions), now))
    }

    private fun pager() = view.blockViews.getValue("document") as DocumentPageView

    /** Every page read in turn with STEP, from the one shown; their text joined, line breaks removed. */
    private fun readOn(): String = pagesOn().joinToString("").replace("\n", "")

    private fun pagesOn(): List<String> {
        val pages = mutableListOf(pager().visibleText())
        while (true) {
            val before = top().page
            dispatch(SessionEvent.Step(1, now))
            if (top().page == before) return pages
            assertEquals(top().page, pager().page)
            pages += pager().visibleText()
        }
    }

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun inViewport(key: String): Boolean {
        val block = view.blockViews.getValue(key)
        return block.top >= 0 && block.bottom <= view.viewport.height
    }

    private val lines = (1..60).map { "Step $it: keep straight" }

    private val commandsRow = PageRow("commands", "Commands", target = PageTarget.Page("nav:commands"))

    private val longSummary = PageBody.Summary(
        primary = "Turn right in 120 m",
        text = lines.joinToString("\n"),
        facts = listOf(PageFact("eta", "ETA", "12:41")),
        items = listOf(commandsRow),
    )

    private val thread = PageBody.Document(
        listOf(
            PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"),
            PageSegment("p", PageSegmentKind.PROSE, (1..80).joinToString(" ") { "line $it of the thread, said plainly" }),
        ),
        items = listOf(PageRow("child", "Open the details", target = PageTarget.Page("relay:child"))),
    )

    /** A plugin action the wearer could confirm while the page is shown. */
    private val acknowledge = JSONArray().put(JSONObject().put("id", "ack").put("label", "Acknowledge").put("kind", "plugin").put("confirm", true))

    @Test
    fun `a summary taller than the viewport is read by STEP, then SELECT reaches its rows`() {
        open("nav:main", longSummary)
        assertEquals(FrameMode.READING, top().mode)
        assertTrue("pages=${top().pageStarts.size}", top().pageStarts.size > 1)

        val read = readOn()
        (listOf("Turn right in 120 m", "ETA · 12:41") + lines).forEach { assertTrue(it, it in read) }

        // SELECT leaves the text for the rows; the Commands row is whole on screen and opens.
        dispatch(SessionEvent.Enter(now))
        assertEquals(FrameMode.ROWS, top().mode)
        assertTrue(inViewport("row:commands"))
        dispatch(SessionEvent.Enter(now))
        assertEquals("nav:commands", requests().last().request.correlation.pageId)
    }

    @Test
    fun `BACK from the rows of long text returns to the text, then pops`() {
        open("nav:main", longSummary)
        dispatch(SessionEvent.Enter(now))
        assertEquals(FrameMode.ROWS, top().mode)
        dispatch(SessionEvent.Back(now))
        assertEquals(FrameMode.READING, top().mode)
        assertEquals(1, (runner.state as SessionState.InPage).frames.size)
        dispatch(SessionEvent.Back(now))
        assertTrue(runner.state is SessionState.Root)
    }

    @Test
    fun `long commands text is read by STEP before its rows`() {
        val text = (1..50).map { "Say \"next\" to hear instruction $it" }
        val commands = PageBody.Commands(
            listOf(PageRow("open", "Open the full app", target = PageTarget.Page("nav:main"))),
            text = text.joinToString("\n"),
        )
        open("nav:commands", commands)
        assertEquals(FrameMode.READING, top().mode)
        val read = readOn()
        text.forEach { assertTrue(it, it in read) }
        dispatch(SessionEvent.Enter(now))
        assertTrue(inViewport("row:open"))
    }

    @Test
    fun `a summary that fits stays as it is drawn today`() {
        val summary = PageBody.Summary(
            primary = "Turn right in 120 m", secondary = "Rue de Rivoli",
            facts = listOf(PageFact("eta", "ETA", "12:41")),
            items = listOf(commandsRow),
        )
        open("nav:main", summary)
        assertEquals(FrameMode.ROWS, top().mode)
        assertFalse(top().overflowing)
        assertTrue(inViewport("primary") && inViewport("row:commands"))
        assertFalse("document" in view.blockViews)
    }

    @Test
    fun `an unavailable summary keeps what fits readable, dimmed, with Retry and Back`() {
        val summary = PageBody.Summary(primary = "Turn right in 120 m", secondary = "Rue de Rivoli", facts = listOf(PageFact("eta", "ETA", "12:41")), items = listOf(commandsRow))
        open("nav:main", summary, actions = acknowledge)
        dispatch(SessionEvent.LinkLost)
        assertTrue(top().status is FrameStatus.Unavailable)
        assertEquals(FrameMode.ROWS, top().mode)
        val primary = texts(view.blockViews.getValue("last:primary")).single()
        assertEquals("Turn right in 120 m", primary.text.toString())
        assertEquals(PageStyle.muted, primary.currentTextColor)
        assertTrue(inViewport("last:primary") && inViewport("retry"))
        // The page's own rows are not drawn: nothing on it can be chosen.
        assertFalse(view.blockViews.keys.any { it.startsWith("row:") })
        assertNoActionAfterPresses()
    }

    @Test
    fun `a long summary stays readable and pageable after link loss`() {
        open("nav:main", longSummary, actions = acknowledge)
        dispatch(SessionEvent.Step(1, now))
        val anchor = top().anchor
        dispatch(SessionEvent.LinkLost)
        assertUnavailableReading(anchor, listOf("Turn right in 120 m", "ETA · 12:41") + lines)
    }

    @Test
    fun `a long summary stays readable and pageable after its refresh times out`() {
        open("nav:main", longSummary, actions = acknowledge)
        timeOutRefresh()
        assertUnavailableReading(null, listOf("Turn right in 120 m", "ETA · 12:41") + lines)
    }

    @Test
    fun `a multipage document stays readable and pageable after link loss`() {
        open("relay:thread", thread, actions = acknowledge)
        dispatch(SessionEvent.Step(1, now))
        dispatch(SessionEvent.Step(1, now))
        val anchor = top().anchor
        dispatch(SessionEvent.LinkLost)
        assertUnavailableReading(anchor, listOf("Mika · captured 14:11", "line 80 of the thread"))
    }

    @Test
    fun `a multipage document stays readable and pageable after its refresh times out`() {
        open("relay:thread", thread, actions = acknowledge)
        timeOutRefresh()
        assertUnavailableReading(null, listOf("Mika · captured 14:11", "line 80 of the thread"))
    }

    /** Rows, a child page, back: the uncovered frame's revalidation then times out. */
    private fun timeOutRefresh() {
        dispatch(SessionEvent.Enter(now))
        assertEquals(FrameMode.ROWS, top().mode)
        dispatch(SessionEvent.Enter(now))
        val child = requests().last().request
        dispatch(SessionEvent.PageResponse(answer(child, 1, PageBody.SelectableList(listOf(PageRow("a", "A")))), now))
        dispatch(SessionEvent.Back(now))
        assertEquals(SessionReducer.REASON_REFRESH, requests().last().request.reason)
        now += PageSurfaceContract.PAGE_TIMEOUT_MS
        dispatch(SessionEvent.Tick(now))
        val status = top().status
        assertTrue("$status", status is FrameStatus.Unavailable && status.reason == PageSurfaceContract.ERROR_PAGE_TIMEOUT)
        // The wearer had gone to the rows: Retry and Back are on screen, and BACK returns to the text.
        assertTrue(inViewport("retry"))
        dispatch(SessionEvent.Back(now))
    }

    /**
     * The retained text is drawn dimmed under the failure, from [anchor]'s page when there is
     * one, and every line is reachable by STEP; SELECT reaches only Retry and Back.
     */
    private fun assertUnavailableReading(anchor: DocumentAnchor?, expected: List<String>) {
        assertTrue("${top().status}", top().status is FrameStatus.Unavailable)
        assertEquals(FrameMode.READING, top().mode)
        assertTrue(inViewport("unavailable"))
        val shown = pager()
        assertTrue("pages=${shown.pageStarts.size}", shown.pageStarts.size > 1)
        if (anchor != null) {
            // The page on screen holds the reading position the wearer had reached.
            val start = shown.pageStarts[shown.page]
            assertTrue("$start vs $anchor", start.segmentId == anchor.segmentId && start.offset <= anchor.offset)
            assertTrue(shown.page > 0)
            // STEP pages back to the start as well.
            while (top().page > 0) dispatch(SessionEvent.Step(-1, now))
            assertEquals(0, shown.page)
        }
        val read = readOn()
        expected.forEach { assertTrue(it, it in read) }
        assertNoActionAfterPresses()
    }

    /** Taps and swipes over an unavailable page reach Retry and Back, and never a plugin action. */
    private fun assertNoActionAfterPresses() {
        if (top().mode == FrameMode.READING) {
            dispatch(SessionEvent.Enter(now))
            assertEquals(FrameMode.ROWS, top().mode)
        }
        assertTrue(inViewport("retry"))
        dispatch(SessionEvent.Step(1, now))
        assertEquals(1, top().selected)
        dispatch(SessionEvent.Step(1, now))
        assertEquals(1, top().selected)
        dispatch(SessionEvent.Step(-1, now))
        dispatch(SessionEvent.Enter(now))
        assertTrue(effects.none { it is SessionEffect.SendAction })
    }
}
