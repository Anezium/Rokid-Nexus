package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionMode
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageResultStatus
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPagesTest {
    private class MemoryStorage : TodoStorage {
        private var state: TodoState? = null

        override fun read(): TodoState? = state

        override fun write(state: TodoState) {
            this.state = state
        }
    }

    /** Stands in for the SDK's request, whose constructor only the SDK can call. */
    private data class Request(val pageId: String, val generation: Long = 1L, val index: Int = 0, val floor: Long? = null)

    private val store = TodoStore(MemoryStorage()) { 1_000L }
    private var extended = true
    private var clock = 0L
    private var changes = 0
    private val published = mutableListOf<Pair<Request, NexusPage>>()
    private val scheduled = mutableListOf<() -> Unit>()

    private val provider = TodoPageProvider<Request>(
        store = { store },
        extended = { extended },
        pageIdOf = { it.pageId },
        frameOf = { TodoFrame(it.generation, it.index) },
        floorOf = { it.floor },
        publish = { request, page ->
            published += request to page
            NexusSdkResult.SENT
        },
        changed = { changes++ },
        now = { clock },
        schedule = { _, task -> scheduled += task },
        cancelScheduled = { scheduled.clear() },
    )

    private fun content(page: NexusPage): NexusPage.Content = page as NexusPage.Content

    private fun rows(page: NexusPage) = (content(page).body as PageBody.SelectableList).items

    private fun act(
        actionId: String,
        revision: Long,
        invocationId: String = "inv-${actionId.hashCode()}-$revision",
        mode: NexusPageActionMode = NexusPageActionMode.QUICK,
        typed: NexusActionType? = TodoPages.parseAction(actionId)?.let { NexusActionType.SetChecked(it.checked) },
        pageId: String = TodoPages.TODO,
    ): NexusPageResult = provider.onAction(TodoFrame(1L, 0), pageId, revision, actionId, invocationId, mode, typed)

    @Test
    fun theTodoPageHasOneCheckableRowAndOneExplicitActionPerTask() {
        val list = store.list()
        val page = TodoPages.todo(list, extended = true)

        assertEquals(list.revision, page.revision)
        assertEquals("Groceries", page.title)
        assertTrue(page.live)
        val rows = rows(page)
        assertEquals(list.tasks.map(TodoTask::id), rows.map { it.id })
        list.tasks.zip(rows).forEach { (task, row) ->
            assertEquals(task.title, row.label)
            assertEquals(task.checked, row.checked)
            assertNull(row.sub)
            assertEquals(PageTarget.Action(TodoPages.actionId(task.id, !task.checked)), row.target)
        }
        assertEquals(rows.map { (it.target as PageTarget.Action).actionId }, page.actions.map { it.id })
        list.tasks.zip(page.actions).forEach { (task, action) ->
            assertEquals(NexusActionType.SetChecked(!task.checked), action.typed)
            assertEquals(NexusPageActionKind.PLUGIN, action.kind)
            assertTrue(action.label.length <= 24)
        }
    }

    @Test
    fun withoutTheLauncherBundleThePageFallsBackToPlainConfirmedRows() {
        val list = store.list()
        val page = TodoPages.todo(list, extended = false)

        rows(page).zip(list.tasks).forEach { (row, task) ->
            assertNull(row.checked)
            assertEquals(if (task.checked) "done · tap to uncheck" else "to do · tap to check", row.sub)
            // The id still names the explicit state, so a confirmed action cannot toggle blindly.
            assertEquals(PageTarget.Action(TodoPages.actionId(task.id, !task.checked)), row.target)
        }
        assertTrue(page.actions.all { it.typed == null })
    }

    @Test
    fun theMainPageSummarisesTheListAndLeadsToItAndToTheDemos() {
        val page = content(TodoPages.page(TodoPages.MAIN, store.list(), extended = true))

        val rows = rows(page)
        assertEquals(PageTarget.Page(TodoPages.TODO), rows[0].target)
        assertEquals("4 of 5 left", rows[0].sub)
        assertEquals(PageTarget.Action(TodoPages.OPEN_DEMOS), rows[1].target)
        assertEquals(NexusPageActionKind.IMMERSION, page.actions.single().kind)
        assertSame(NexusPage.Unavailable, TodoPages.page("hello:other", store.list(), extended = true))
    }

    @Test
    fun actionIdsRoundTripAndAnythingElseIsRefused() {
        assertEquals(SetCheckedRequest("t12", true), TodoPages.parseAction(TodoPages.actionId("t12", true)))
        assertEquals(SetCheckedRequest("t3", false), TodoPages.parseAction(TodoPages.actionId("t3", false)))
        assertNull(TodoPages.parseAction("set:t3:2"))
        assertNull(TodoPages.parseAction("set::1"))
        assertNull(TodoPages.parseAction("open-demos"))
    }

    @Test
    fun aRequestBelowTheRetainedFloorIsAnsweredAboveIt() {
        val floor = store.list().revision + 10

        val page = content(provider.onRequest(Request(TodoPages.TODO, floor = floor)))

        assertTrue(page.revision > floor)
        assertSame(NexusPage.Unavailable, provider.onRequest(Request("hello:nope")))
    }

    @Test
    fun anActionAppliesItsExplicitStateAndAnswersWithTheNewRevision() {
        val shown = content(provider.onRequest(Request(TodoPages.TODO)))

        val result = act(TodoPages.actionId("t1", true), shown.revision)

        assertEquals(NexusPageResultStatus.DONE, result.status)
        assertNotNull(result.replacement)
        val replacement = result.replacement!!
        assertTrue(replacement.revision > shown.revision)
        assertEquals(store.list().revision, replacement.revision)
        assertEquals(true, rows(replacement).first { it.id == "t1" }.checked)
        assertEquals(PageTarget.Action(TodoPages.actionId("t1", false)), rows(replacement).first { it.id == "t1" }.target)
        assertEquals(1, changes)
    }

    @Test
    fun anActionOnARevisionTheListMovedPastIsStale() {
        val shown = content(provider.onRequest(Request(TodoPages.TODO)))
        store.setChecked("t2", true, shown.revision)
        val moved = store.list()

        val result = act(TodoPages.actionId("t1", true), shown.revision)

        assertEquals(NexusPageResultStatus.STALE, result.status)
        assertNull(result.replacement)
        assertEquals(moved, store.list())
        assertEquals(0, changes)
    }

    @Test
    fun anActionOnADeletedTaskIsStale() {
        val shown = content(provider.onRequest(Request(TodoPages.TODO)))
        store.remove("t1")
        val after = store.list()

        val result = act(TodoPages.actionId("t1", true), shown.revision)

        assertEquals(NexusPageResultStatus.STALE, result.status)
        assertEquals(after, store.list())
    }

    @Test
    fun aRepeatedInvocationIsAnsweredOnceAndNeverAppliedAgain() {
        val shown = content(provider.onRequest(Request(TodoPages.TODO)))
        val first = act(TodoPages.actionId("t1", true), shown.revision, invocationId = "inv-1")
        val afterFirst = store.list()

        val again = act(TodoPages.actionId("t1", true), shown.revision, invocationId = "inv-1")

        assertEquals(NexusPageResultStatus.DONE, first.status)
        assertEquals(NexusPageResultStatus.REJECTED, again.status)
        assertNull(again.replacement)
        assertEquals(afterFirst, store.list())
        assertEquals(1, changes)
    }

    @Test
    fun aTypeThatContradictsTheActionOrAModeItDoesNotFitIsRejected() {
        val revision = content(provider.onRequest(Request(TodoPages.TODO))).revision
        val check = TodoPages.actionId("t1", true)

        assertEquals(NexusPageResultStatus.REJECTED, act(check, revision, "a", typed = NexusActionType.SetChecked(false)).status)
        assertEquals(NexusPageResultStatus.REJECTED, act(check, revision, "b", typed = NexusActionType.MediaPlay).status)
        assertEquals(NexusPageResultStatus.REJECTED, act(check, revision, "c", typed = null).status)
        assertEquals(NexusPageResultStatus.REJECTED, act(check, revision, "d", mode = NexusPageActionMode.COMPOSE).status)
        assertEquals(NexusPageResultStatus.REJECTED, act(check, revision, "e", pageId = TodoPages.MAIN).status)
        assertEquals(NexusPageResultStatus.REJECTED, act("open-demos", revision, "f", typed = null).status)
        assertEquals(revision, store.list().revision)
        assertEquals(0, changes)
    }

    @Test
    fun withoutTheBundleAConfirmedUntypedActionStillApplies() {
        extended = false
        val shown = content(provider.onRequest(Request(TodoPages.TODO)))

        val result = act(TodoPages.actionId("t5", false), shown.revision, mode = NexusPageActionMode.CONFIRMED, typed = null)

        assertEquals(NexusPageResultStatus.DONE, result.status)
        assertEquals(false, store.list().task("t5")!!.checked)
        assertTrue(result.replacement!!.actions.all { it.typed == null })
    }

    @Test
    fun aVisiblePageFollowsChangesMadeElsewhereAtMostTwiceASecond() {
        val request = Request(TodoPages.TODO)
        provider.onRequest(request)
        provider.onVisibility(TodoFrame(1L, 0), TodoPages.TODO, visible = true)
        assertTrue(published.isEmpty())

        store.add("Tea")
        provider.listChanged()
        assertEquals(1, published.size)
        assertEquals(store.list().revision, content(published.last().second).revision)

        clock += 100
        store.add("Jam")
        provider.listChanged()
        assertEquals(1, published.size)
        assertEquals(1, scheduled.size)

        clock += TodoPageProvider.MIN_INTERVAL_MS
        scheduled.removeAt(0).invoke()
        assertEquals(2, published.size)
        assertEquals(store.list().revision, content(published.last().second).revision)
    }

    @Test
    fun aCoveredPageCatchesUpWhenShownAgain() {
        provider.onRequest(Request(TodoPages.TODO))
        store.add("Tea")
        provider.listChanged()
        assertTrue(published.isEmpty())

        provider.onVisibility(TodoFrame(1L, 0), TodoPages.TODO, visible = true)

        assertEquals(store.list().revision, content(published.single().second).revision)
    }
}
