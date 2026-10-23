package com.anezium.rokidbus.glasses.session

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.page.PageStyle
import com.anezium.rokidbus.glasses.page.SessionIconSource
import com.anezium.rokidbus.glasses.page.SessionScreenView
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The 24-hour display bound (plan 028a §6.3) holds for every copy of a notification, not only for
 * a freshly built root or list: a frozen root card, one beneath a page, and a named notice page all
 * stop saying it once it passes the bound, and an open session wakes for that moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NoticeExpiryRootTest {
    private var wallNow = 50_000_000L
    private var sessionNow = 0L
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { true },
    )
    private val reducer = SessionReducer(
        items,
        isLocalPage = PageTemplateContract::isLocalPageId,
        launcherActive = { true },
        wallClock = { wallNow },
        sessionClock = { sessionNow },
    )
    private val screens = SessionScreens(items, { if (it == "relay") "Relay" else null }, { "14:02" }, { it }, { wallNow })
    private val view = SessionScreenView(RuntimeEnvironment.getApplication(), SessionIconSource { _, _ -> GlyphDrawable("M4,12 h16") })
    private val local = LocalPages({ model.history }, { emptyList() }, { emptyList() }, { true }, { "14:02" }, { wallNow })
    private var model = SessionModel()

    @Test
    fun `a generic card left open past the bound keeps its place and none of its text`() {
        arrive(NoticeEntry("todo", "Todo", "Buy the surprise gift", null, atWallMs = wallNow))
        openRoot()
        assertTrue(drawn().any { "surprise gift" in it })
        val order = stopOrder()

        expireAndTick()
        assertGone("surprise gift", "Todo")
        assertEquals(order, stopOrder())
        assertEquals(RootStops.EXPIRED_TEXT, noticeStop().text)
    }

    @Test
    fun `a conversation card left open past the bound says nothing of it`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        assertTrue(drawn().any { "Mika" in it })

        expireAndTick()
        assertGone("Mika", "le code est 4417")
    }

    @Test
    fun `an aggregate left open past the bound says none of its names`() {
        arrive(conversation("t1", "Mika", "on est devant"))
        arrive(conversation("t2", "Alan", "moi aussi"))
        openRoot()
        assertEquals(NoticeCardKind.AGGREGATE, noticeStop().notice?.kind)

        expireAndTick()
        assertGone("Mika", "Alan", "on est devant", "moi aussi")
    }

    @Test
    fun `a root covered by a page during expiry comes back clean`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        val applications = root().stops.indexOfFirst { it.kind == RootStopKind.APPLICATIONS }
        repeat(applications) { send(SessionEvent.Step(1, 2_000L + it)) }
        send(SessionEvent.Enter(3_000))
        assertTrue(model.state is SessionState.InPage)

        expireAndTick()
        send(SessionEvent.Back(sessionNow + 100))
        assertTrue(model.state is SessionState.Root)
        assertGone("Mika", "le code est 4417")
    }

    @Test
    fun `a stop drawn after a wake without its tick still says nothing of it`() {
        arrive(NoticeEntry("todo", "Todo", "Buy the surprise gift", null, atWallMs = wallNow))
        openRoot()
        wallNow += NoticeSurfaceContract.MAX_HISTORY_AGE_MS
        // No tick ran (a deep sleep stops the session clock); the copy is still in the model.
        assertTrue(noticeStop().toString().contains("surprise gift"))
        assertFalse(drawn().any { "surprise gift" in it })
    }

    @Test
    fun `a named notice page past the bound shows no notification`() {
        arrive(NoticeEntry("todo", "Todo", "Buy the surprise gift", null, atWallMs = wallNow))
        openRoot()
        send(SessionEvent.Enter(2_000))
        val request = requestOf()
        assertEquals(RootStops.NOTICE_PAGE_ID, request.correlation.pageId)
        assertNotNull(NotificationHistory.entryOf(request.paramsJson))
        assertTrue(local.answer(request).toString().contains("surprise gift"))

        wallNow += NoticeSurfaceContract.MAX_HISTORY_AGE_MS
        val answer = local.answer(request).toString()
        assertFalse(answer, answer.contains("surprise gift"))
        assertTrue(answer, answer.contains("No notification to show."))
    }

    @Test
    fun `an open session asks to wake when its oldest shown notification expires`() {
        arrive(NoticeEntry("todo", "Todo", "Milk", null, atWallMs = wallNow))
        val firstAt = wallNow
        arrive(NoticeEntry("todo", "Todo", "Eggs", null, atWallMs = wallNow))
        openRoot()
        val expected = sessionNow + (firstAt + NoticeSurfaceContract.MAX_HISTORY_AGE_MS - wallNow) + 1
        assertEquals(expected, model.historyExpiryAtMs)
        assertEquals(expected, model.nextDeadlineMs())
    }

    private fun expireAndTick() {
        wallNow += NoticeSurfaceContract.MAX_HISTORY_AGE_MS
        sessionNow = model.historyExpiryAtMs ?: error("no expiry scheduled")
        send(SessionEvent.Tick(sessionNow))
    }

    private fun requestOf(): PageSurfaceRequest {
        val top = (model.state as SessionState.InPage).frames.last()
        val requestId = when (val status = top.status) {
            is FrameStatus.Loading -> status.requestId
            is FrameStatus.Shown -> status.requestId
            is FrameStatus.Unavailable -> error("no request")
        }
        val frameIndex = (model.state as SessionState.InPage).frames.size
        return PageSurfaceRequest(PageCorrelation(requestId, model.generation, frameIndex, top.pageId), SessionReducer.REASON_OPEN, top.paramsJson)
    }

    private fun assertGone(vararg words: String) {
        val stop = noticeStop()
        val texts = drawn()
        words.forEach { word ->
            assertFalse("$word drawn in $texts", texts.any { word in it })
            assertFalse("$word kept in $stop", stop.toString().contains(word))
        }
    }

    private fun conversation(thread: String, sender: String, text: String) = NoticeEntry(
        ownerPluginId = "relay",
        sourceLabel = "Relay",
        title = sender,
        body = text,
        atWallMs = wallNow,
        destination = PageDestination("relay:thread", JSONObject().put("thread", thread).toString()),
        eventKey = thread,
        conversation = true,
    )

    private fun arrive(entry: NoticeEntry) {
        wallNow += 1_000
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, entry.text), entry.copy(atWallMs = wallNow)))
    }

    private fun openRoot() {
        sessionNow = 1_000
        send(SessionEvent.TripleTap(1_000))
        sessionNow = 1_000 + PageSurfaceContract.GATE_MS
        send(SessionEvent.Tick(sessionNow))
        assertTrue(model.state is SessionState.Root)
    }

    private fun root(): SessionState.Root = when (val state = model.state) {
        is SessionState.Root -> state
        is SessionState.InPage -> state.root
        else -> error("no root: $state")
    }

    private fun noticeStop(): RootStop = root().stops.single { it.kind == RootStopKind.NOTICE_PREVIEW }

    private fun stopOrder() = root().stops.map { it.id to it.kind }

    private fun drawn(): List<String> {
        val screen = screens.screen(root(), null, model.generation, CommandFacts.of(model))
        view.show(screen)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
        return texts(view)
    }

    private fun texts(root: View): List<String> = when (root) {
        is TextView -> listOf(root.text.toString())
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun send(event: SessionEvent) {
        model = reducer.reduce(model, event).model
    }
}
