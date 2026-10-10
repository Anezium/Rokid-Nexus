package com.anezium.rokidbus.glasses.page

import android.os.Looper
import android.view.View
import com.anezium.rokidbus.glasses.session.ActivityStop
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
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The renderer wired to the real runner: measured page starts reach the reducer for every revision. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentPagingRunnerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val items = TypedPageItems(PageTemplateContract::isLocalPageId, providerPageAllowed = { it.startsWith("relay:") })
    private val screens = SessionScreens(items, { if (it == "relay") "Relay" else null }, { "12:24" }, { it })
    private val view = SessionScreenView(context)
    private val requests = mutableListOf<PageSurfaceRequest>()
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
                if (effect is SessionEffect.RequestPage) requests += effect.request
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
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun top() = (runner.state as SessionState.InPage).frames.last()

    private fun answer(request: PageSurfaceRequest, revision: Long, template: String, body: PageBody) =
        PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", template).put("title", "Mika")
            .put("body", PageTemplateContract.encode(body))
            .put("actions", JSONArray()).put("live", true)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "e1")
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, "relay")

    private fun document(request: PageSurfaceRequest, revision: Long) =
        PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", "document").put("title", "Mika")
            .put(
                "body",
                PageTemplateContract.encode(
                    PageBody.Document(
                        listOf(
                            PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"),
                            PageSegment("p", PageSegmentKind.PROSE, (1..80).joinToString(" ") { "line $it of the thread, said plainly" }),
                        ),
                    ),
                ),
            )
            .put("actions", JSONArray()).put("live", true)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "e1")
            .put(PageSurfaceContract.FIELD_PLUGIN_ID, "relay")

    @Test
    fun `back from a child, an equal-revision refresh keeps the document paging`() {
        val thread = PageBody.Document(
            listOf(
                PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"),
                PageSegment("p", PageSegmentKind.PROSE, (1..80).joinToString(" ") { "line $it of the thread, said plainly" }),
            ),
            items = listOf(PageRow("child", "Open the details", target = PageTarget.Page("relay:child"))),
        )
        runner.dispatch(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:thread", "Mika: hi", pinned = true)))
        runner.dispatch(SessionEvent.TripleTap(now))
        now += PageSurfaceContract.GATE_MS
        runner.dispatch(SessionEvent.Tick(now))
        runner.dispatch(SessionEvent.Enter(now))
        val first = requests.last()
        runner.dispatch(SessionEvent.PageResponse(answer(first, 1, "document", thread), now))
        idle()
        val starts = top().pageStarts
        assertTrue("pages=${starts.size}", starts.size > 1)

        // Into the Commands rows, then a child page, then back.
        runner.dispatch(SessionEvent.Enter(now + 10))
        assertEquals(FrameMode.ROWS, top().mode)
        runner.dispatch(SessionEvent.Enter(now + 20))
        runner.dispatch(SessionEvent.PageResponse(answer(requests.last(), 1, "selectableList", PageBody.SelectableList(listOf(PageRow("a", "A")))), now + 30))
        idle()
        assertEquals("relay:child", top().pageId)
        runner.dispatch(SessionEvent.Back(now + 40))
        // The uncovered thread revalidates and the phone answers the same revision.
        val refresh = requests.last()
        assertEquals(SessionReducer.REASON_REFRESH, refresh.reason)
        runner.dispatch(SessionEvent.PageResponse(answer(refresh, 1, "document", thread), now + 50))
        idle()
        runner.dispatch(SessionEvent.Back(now + 60))
        idle()
        assertEquals(FrameMode.READING, top().mode)

        runner.dispatch(SessionEvent.Step(1, now + 70))
        assertEquals(1, top().page)
        assertEquals(1, (view.blockViews.getValue("document") as DocumentPageView).page)
    }

    @Test
    fun `an equal-revision answer to Retry keeps the document paging`() {
        val thread = PageBody.Document(
            listOf(
                PageSegment("h", PageSegmentKind.HEADER, "Mika · captured 14:11"),
                PageSegment("p", PageSegmentKind.PROSE, (1..80).joinToString(" ") { "line $it of the thread, said plainly" }),
            ),
            items = listOf(PageRow("child", "Open the details", target = PageTarget.Page("relay:child"))),
        )
        runner.dispatch(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:thread", "Mika: hi", pinned = true)))
        runner.dispatch(SessionEvent.TripleTap(now))
        now += PageSurfaceContract.GATE_MS
        runner.dispatch(SessionEvent.Tick(now))
        runner.dispatch(SessionEvent.Enter(now))
        runner.dispatch(SessionEvent.PageResponse(answer(requests.last(), 1, "document", thread), now))
        idle()
        assertTrue("pages=${top().pageStarts.size}", top().pageStarts.size > 1)

        // Commands, a child page, back; the thread's revalidation then times out.
        runner.dispatch(SessionEvent.Enter(now + 10))
        runner.dispatch(SessionEvent.Enter(now + 20))
        runner.dispatch(SessionEvent.PageResponse(answer(requests.last(), 1, "selectableList", PageBody.SelectableList(listOf(PageRow("a", "A")))), now + 30))
        idle()
        runner.dispatch(SessionEvent.Back(now + 40))
        now += 40 + PageSurfaceContract.PAGE_TIMEOUT_MS
        runner.dispatch(SessionEvent.Tick(now))
        idle()
        assertTrue("${top().status}", top().status is FrameStatus.Unavailable)

        // Retry, answered with the same revision.
        runner.dispatch(SessionEvent.Enter(now + 10))
        val retry = requests.last()
        assertEquals(SessionReducer.REASON_RETRY, retry.reason)
        runner.dispatch(SessionEvent.PageResponse(answer(retry, 1, "document", thread), now + 20))
        idle()
        assertEquals(FrameMode.READING, top().mode)

        runner.dispatch(SessionEvent.Step(1, now + 30))
        assertEquals(1, top().page)
        assertEquals(1, (view.blockViews.getValue("document") as DocumentPageView).page)
    }

    @Test
    fun `a revision with the same page starts still pages, through the real renderer`() {
        runner.dispatch(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:thread", "Mika: hi", pinned = true)))
        runner.dispatch(SessionEvent.TripleTap(now))
        now += PageSurfaceContract.GATE_MS
        runner.dispatch(SessionEvent.Tick(now))
        runner.dispatch(SessionEvent.Enter(now))
        val request = requests.single()
        runner.dispatch(SessionEvent.PageResponse(document(request, revision = 1), now))
        idle()
        assertEquals(FrameMode.READING, top().mode)
        val starts = top().pageStarts
        assertTrue("pages=${starts.size}", starts.size > 1)

        // The same text again as revision 2: the layout, and so its page starts, do not change.
        runner.dispatch(SessionEvent.PageResponse(document(request, revision = 2), now + 100))
        idle()
        assertEquals(starts, top().pageStarts)

        runner.dispatch(SessionEvent.Step(1, now + 200))
        assertEquals(1, top().page)
        assertEquals(1, (view.blockViews.getValue("document") as DocumentPageView).page)
    }
}
