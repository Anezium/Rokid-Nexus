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

    /** The text drawn in the viewport, blocks and document page alike. */
    private fun drawn(): String = view.blockViews.values.joinToString("\n") { block ->
        if (block is DocumentPageView) block.visibleText() else texts(block).joinToString("\n") { it.text }
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

    /**
     * After a link loss, Retry answered at [revision]; what the glasses then draw. The providers
     * answer a rebuilt page above the floor; an equal revision keeps the retained body.
     */
    private fun retryAnswered(after: PageBody, revision: Long): String {
        dispatch(SessionEvent.LinkLost)
        dispatch(SessionEvent.LinkRestored)
        assertTrue(top().status is FrameStatus.Unavailable)
        dispatch(SessionEvent.Enter(now))
        val retry = requests().last()
        assertEquals(SessionReducer.REASON_RETRY, retry.request.reason)
        assertEquals(top().revisionFloor, retry.minimumRevision)
        dispatch(SessionEvent.PageResponse(answer(retry.request, revision, after), now))
        assertTrue("${top().status}", top().status is FrameStatus.Shown)
        return drawn()
    }

    private fun inbox(sub: String) =
        PageBody.SelectableList(listOf(PageRow("mika", "Mika", sub = sub, target = PageTarget.Page("relay:thread"))), emptyText = "No conversations yet")

    private fun guidance(primary: String, street: String) = PageBody.Summary(primary = primary, secondary = street, items = listOf(commandsRow))

    @Test
    fun `Retry draws newly hidden Relay previews above the floor, and keeps them at an equal revision`() {
        open("relay:main", inbox("14:11 · See you at eight"))
        val hidden = retryAnswered(inbox("14:11"), revision = 2)
        assertTrue(hidden, "Mika" in hidden && "See you at eight" !in hidden)
        val equal = retryAnswered(inbox("14:11 · See you at eight"), revision = 2)
        assertTrue(equal, "Mika" in equal && "See you at eight" !in equal)
    }

    @Test
    fun `Retry draws an emptied Relay inbox above the floor, and keeps it at an equal revision`() {
        open("relay:main", inbox("14:11 · See you at eight"))
        val empty = retryAnswered(PageBody.SelectableList(emptyList(), emptyText = "No conversations yet"), revision = 2)
        assertTrue(empty, "No conversations yet" in empty && "Mika" !in empty)
        val equal = retryAnswered(inbox("14:11 · See you at eight"), revision = 2)
        assertTrue(equal, "No conversations yet" in equal && "Mika" !in equal)
    }

    @Test
    fun `Retry draws changed Navigation guidance above the floor, and keeps it at an equal revision`() {
        open("nav:main", guidance("Turn right in 120 m", "Rue de Rivoli"))
        val changed = retryAnswered(guidance("Turn left in 300 m", "Avenue Foch"), revision = 2)
        assertTrue(changed, "Turn left in 300 m" in changed && "Turn right" !in changed)
        val equal = retryAnswered(guidance("Turn right in 120 m", "Rue de Rivoli"), revision = 2)
        assertTrue(equal, "Avenue Foch" in equal && "Rue de Rivoli" !in equal)
    }

    /**
     * The wearer goes from the long text straight to its rows, without paging, and the page is
     * lost there; Retry is then answered at the floor with the same content.
     */
    private fun retryEqualAfter(loss: () -> Unit) {
        open("nav:main", longSummary)
        assertTrue("pages=${top().pageStarts.size}", top().pageStarts.size > 1)
        dispatch(SessionEvent.Enter(now))
        assertEquals(FrameMode.ROWS, top().mode)
        loss()
        assertTrue("${top().status}", top().status is FrameStatus.Unavailable)
        assertEquals(FrameMode.ROWS, top().mode)
        dispatch(SessionEvent.Enter(now))
        val retry = requests().last()
        assertEquals(SessionReducer.REASON_RETRY, retry.request.reason)
        dispatch(SessionEvent.PageResponse(answer(retry.request, retry.minimumRevision!!, longSummary), now))
        assertTrue("${top().status}", top().status is FrameStatus.Shown)
        assertEquals(FrameMode.READING, top().mode)
        assertTrue("pages=${top().pageStarts.size}", top().pageStarts.size > 1)
    }

    @Test
    fun `Retry at an equal revision after a link loss in the rows keeps long text pageable`() {
        retryEqualAfter {
            dispatch(SessionEvent.LinkLost)
            dispatch(SessionEvent.LinkRestored)
        }
        val read = readOn()
        (listOf("Turn right in 120 m", "ETA · 12:41") + lines).forEach { assertTrue(it, it in read) }
    }

    @Test
    fun `Retry at an equal revision after a refresh timeout in the rows keeps long text pageable`() {
        retryEqualAfter {
            dispatch(SessionEvent.Enter(now))
            val child = requests().last().request
            dispatch(SessionEvent.PageResponse(answer(child, 1, PageBody.SelectableList(listOf(PageRow("a", "A")))), now))
            dispatch(SessionEvent.Back(now))
            assertEquals(SessionReducer.REASON_REFRESH, requests().last().request.reason)
            now += PageSurfaceContract.PAGE_TIMEOUT_MS
            dispatch(SessionEvent.Tick(now))
        }
        val read = readOn()
        (listOf("Turn right in 120 m", "ETA · 12:41") + lines).forEach { assertTrue(it, it in read) }
    }

    private val threeRows = listOf(
        PageRow("a", "Alpha", target = PageTarget.Page("nav:a")),
        PageRow("b", "Bravo", target = PageTarget.Page("nav:b")),
        PageRow("c", "Charlie", target = PageTarget.Page("nav:c")),
    )

    private fun guidanceRows(lineCount: Int) =
        PageBody.Summary(primary = "Turn right in 120 m", text = lines.take(lineCount).joinToString("\n"), items = threeRows)

    /** A fitting summary with three rows, the wearer on the third one. */
    private fun onThirdRow(lineCount: Int = 2) {
        open("nav:main", guidanceRows(lineCount))
        assertEquals(FrameMode.ROWS, top().mode)
        assertFalse(top().overflowing)
        dispatch(SessionEvent.Step(1, now))
        dispatch(SessionEvent.Step(1, now))
        assertEquals("c", top().selectedKey)
    }

    /**
     * Text found too tall on a later layout keeps the wearer on the row they chose, and no layout
     * acts; the text is reached by BACK, and from there STEP reads every line.
     */
    private fun assertRowKeptAndTextBehindBack(sent: Int, expected: List<String>) {
        assertTrue(top().overflowing)
        assertEquals(FrameMode.ROWS, top().mode)
        assertEquals("c", top().selectedKey)
        assertEquals(2, top().selected)
        assertTrue(inViewport("row:c"))
        assertEquals(sent, requests().size)
        assertTrue(effects.none { it is SessionEffect.SendAction })
        dispatch(SessionEvent.Back(now))
        assertEquals(FrameMode.READING, top().mode)
        assertEquals(1, (runner.state as SessionState.InPage).frames.size)
        val read = readOn()
        expected.forEach { assertTrue(it, it in read) }
    }

    @Test
    fun `a live revision too tall to fit keeps the selected row`() {
        onThirdRow()
        val opened = requests().last().request
        dispatch(SessionEvent.PageResponse(answer(opened, 2, guidanceRows(lines.size)), now))
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
        assertRowKeptAndTextBehindBack(requests().size, lines)
    }

    @Test
    fun `a refreshed revision too tall to fit keeps the selected row`() {
        onThirdRow()
        dispatch(SessionEvent.Enter(now))
        assertEquals("nav:c", requests().last().request.correlation.pageId)
        dispatch(SessionEvent.PageResponse(answer(requests().last().request, 1, PageBody.SelectableList(listOf(PageRow("x", "X")))), now))
        dispatch(SessionEvent.Back(now))
        val refresh = requests().last()
        assertEquals(SessionReducer.REASON_REFRESH, refresh.request.reason)
        dispatch(SessionEvent.PageResponse(answer(refresh.request, 2, guidanceRows(lines.size)), now))
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
        assertRowKeptAndTextBehindBack(requests().size, lines)
    }

    @Test
    fun `a larger HUD inset that makes the text too tall keeps the selected row`() {
        view.setTopInsetPx(0)
        onThirdRow(lineCount = 16)
        view.setTopInsetPx(PhoneHubCapabilitiesContract.MAX_HUD_TOP_INSET_DP * 240 / 160)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        idle()
        assertRowKeptAndTextBehindBack(requests().size, lines.take(16))
    }

    @Test
    fun `an unavailable page keeps its failure, Retry and Back in view together, whatever its length`() {
        var rows = 0
        var reading = 0
        for (count in 1..24) {
            val body = PageBody.Summary(primary = "Turn right in 120 m", text = lines.take(count).joinToString("\n"), items = listOf(commandsRow))
            if (count == 1) {
                open("nav:main", body)
            } else {
                dispatch(SessionEvent.LinkRestored)
                if (top().mode == FrameMode.READING) dispatch(SessionEvent.Enter(now))
                assertEquals(0, top().selected)
                dispatch(SessionEvent.Enter(now))
                dispatch(SessionEvent.PageResponse(answer(requests().last().request, count.toLong(), body), now))
                assertTrue("${top().status}", top().status is FrameStatus.Shown)
            }
            dispatch(SessionEvent.LinkLost)
            if (top().mode == FrameMode.READING) {
                reading++
                assertTrue(inViewport("unavailable"))
            } else {
                rows++
                listOf("unavailable", "reason", "retry", "back").forEach { assertTrue("$count lines: $it", inViewport(it)) }
            }
        }
        // The sweep crossed from what fits beside the failure to what is read page by page.
        assertTrue("rows=$rows reading=$reading", rows > 0 && reading > 0)
    }

    /** What the wearer can reach from here: every page by STEP, or the text drawn, as ellipsized. */
    private fun reachable(): String =
        if (top().mode == FrameMode.READING) readOn() else texts(view.viewport).joinToString("\n", transform = ::shownPart)

    private fun shownPart(text: TextView): String {
        val layout = text.layout ?: return ""
        val last = minOf(layout.lineCount, text.maxLines) - 1
        return (0..last).joinToString("") { line ->
            val start = layout.getLineStart(line)
            val end = if (layout.getEllipsisCount(line) > 0) start + layout.getEllipsisStart(line) else layout.getLineEnd(line)
            text.text.subSequence(start, end).toString()
        }.replace("\n", "")
    }

    @Test
    fun `a fact longer than its lines is read whole by STEP, before and after link loss`() {
        val label = "Ångström".repeat(7) + "\n" + "Øresund".repeat(8)
        val value = "Straße".repeat(40)
        assertTrue(label.length <= PageTemplateContract.MAX_LABEL_CHARS && value.length <= PageTemplateContract.MAX_DETAIL_CHARS)
        val summary = PageBody.Summary(primary = "Turn right in 120 m", facts = listOf(PageFact("far", label, value)), items = listOf(commandsRow))
        open("nav:main", summary, actions = acknowledge)
        val read = reachable()
        assertTrue(read, label.replace("\n", "") in read)
        assertTrue(read, value in read)

        dispatch(SessionEvent.LinkLost)
        if (top().mode == FrameMode.ROWS && top().overflowing) dispatch(SessionEvent.Back(now))
        val retained = reachable()
        assertTrue(retained, label.replace("\n", "") in retained && value in retained)
        assertNoActionAfterPresses()
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
