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
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * `/core/notice/purge` (plan 028a §6.2-§6.3): once an owner is purged, nothing it showed stays
 * on a root kept open or beneath a page, as drawn by the session window. A withdrawal is not a
 * purge and keeps its readable card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NoticePurgeRootTest {
    private var wallNow = 50_000_000L
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
    )
    private val screens = SessionScreens(items, { if (it == "relay") "Relay" else null }, { "14:02" }, { it }, { wallNow })
    private val view = SessionScreenView(RuntimeEnvironment.getApplication(), SessionIconSource { _, _ -> GlyphDrawable("M4,12 h16") })
    private var model = SessionModel()

    @Test
    fun `a purged conversation card keeps its place and none of its words`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        assertTrue(drawn().any { "Mika" in it })
        val order = stopOrder()

        send(SessionEvent.NoticePurged("relay"))
        assertGone("Mika", "le code est 4417")
        assertEquals(order, stopOrder())
        assertNull(noticeStop().paramsJson)
    }

    @Test
    fun `a purged aggregate keeps its place and none of its names`() {
        arrive(conversation("t1", "Mika", "on est devant"))
        arrive(conversation("t2", "Alan", "moi aussi"))
        openRoot()
        assertEquals(NoticeCardKind.AGGREGATE, noticeStop().notice?.kind)
        assertTrue(drawn().any { "Alan" in it || "Mika" in it })

        send(SessionEvent.NoticePurged("relay"))
        assertGone("Mika", "Alan", "on est devant", "moi aussi")
    }

    @Test
    fun `a purged generic preview keeps its place and none of its text`() {
        arrive(NoticeEntry("todo", "Todo", "Buy the surprise gift", null, atWallMs = wallNow))
        openRoot()
        assertTrue(drawn().any { "surprise gift" in it })

        send(SessionEvent.NoticePurged("todo"))
        assertGone("surprise gift", "Todo")
    }

    @Test
    fun `a root beneath a page is purged too, and Back shows it clean`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        val applications = (model.state as SessionState.Root).stops.indexOfFirst { it.kind == RootStopKind.APPLICATIONS }
        repeat(applications) { send(SessionEvent.Step(1, 2_000L + it)) }
        send(SessionEvent.Enter(3_000))
        assertTrue(model.state is SessionState.InPage)

        send(SessionEvent.NoticePurged("relay"))
        send(SessionEvent.Back(4_000))
        assertTrue(model.state is SessionState.Root)
        assertGone("Mika", "le code est 4417")
    }

    @Test
    fun `an owner's forget clears a frozen card whose entry the history already evicted`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        repeat(NoticeSurfaceContract.MAX_HISTORY_ENTRIES) { arrive(NoticeEntry("todo", "Todo", "Task $it", null, atWallMs = wallNow)) }
        assertTrue(model.history.entries.none { it.notice.ownerPluginId == "relay" })
        assertTrue(drawn().any { "Mika" in it })

        send(SessionEvent.NoticePurged("relay"))
        assertGone("Mika", "le code est 4417")
    }

    @Test
    fun `a withdrawal keeps the card readable, said as removed`() {
        arrive(conversation("t1", "Mika", "le code est 4417"))
        openRoot()
        send(SessionEvent.NoticeWithdrawn("relay", "t1"))
        assertTrue(drawn().any { "Mika" in it })
        assertEquals(HistoryState.WITHDRAWN, noticeStop().notice?.state)
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
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, entry.text), entry))
    }

    private fun openRoot() {
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        assertTrue(model.state is SessionState.Root)
    }

    private fun root(): SessionState.Root = when (val state = model.state) {
        is SessionState.Root -> state
        is SessionState.InPage -> state.root
        else -> error("no root: $state")
    }

    private fun noticeStop(): RootStop = root().stops.single { it.kind == RootStopKind.NOTICE_PREVIEW }

    private fun stopOrder() = root().stops.map { it.id to it.kind }

    /** Every text the session window draws for the root as it is now. */
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
