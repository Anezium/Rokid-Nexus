package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityActivation
import com.anezium.rokidbus.client.plugin.NexusActivityPersistence
import com.anezium.rokidbus.client.plugin.NexusActivityProgress
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.ActivityCloseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoActivityTest {
    private class MemoryStorage : TodoStorage {
        private var state: TodoState? = null

        override fun read(): TodoState? = state

        override fun write(state: TodoState) {
            this.state = state
        }
    }

    private class Prefs : TodoActivityPrefs {
        override var showInActivities = false
        override var endPending = false
        override var publishedRevision: Long? = null
    }

    private class Sink : TodoActivitySink {
        override var registered = true
        override var approved = true
        override var activitiesAvailable = true
        override var launcherInteractive = true
        override var registrationGeneration = 1
        var result = NexusSdkResult.SENT
        val sent = mutableListOf<Pair<String, NexusActivity?>>()

        override fun start(activity: NexusActivity): NexusSdkResult = result.also { sent += "start" to activity }

        override fun update(activity: NexusActivity): NexusSdkResult = result.also { sent += "update" to activity }

        override fun end(): NexusSdkResult = result.also { sent += "end" to null }
    }

    private val store = TodoStore(MemoryStorage()) { 1_000L }
    private val prefs = Prefs()
    private var sink: Sink? = Sink()
    private var clock = 10_000L
    private val scheduled = mutableListOf<() -> Unit>()
    private val publisher = TodoActivityPublisher(
        prefs = prefs,
        list = store::list,
        sink = { sink },
        now = { clock },
        schedule = { _, task -> scheduled += task },
    )

    private fun on() {
        prefs.showInActivities = true
        prefs.endPending = false
    }

    private fun off() {
        prefs.showInActivities = false
        prefs.endPending = true
    }

    private fun later() {
        clock += TodoActivityPublisher.MIN_INTERVAL_MS
    }

    private val sent get() = sink!!.sent

    @Test
    fun theSummarySaysWhatIsLeftNamesTheListAndOpensIt() {
        val activity = TodoActivitySummary.build(store.list())

        assertEquals("4 left", activity.primary)
        assertEquals("Groceries", activity.secondary)
        assertEquals(NexusActivityProgress.Percent(20), activity.progress)
        assertEquals(listOf("Milk", "Eggs"), activity.detail)
        assertEquals(TodoPages.TODO, activity.destination!!.pageId)
        assertNull(activity.destination!!.params)
        assertEquals(NexusActivityActivation.DESTINATION, activity.activation)
        assertEquals(NexusActivityPersistence.ONGOING, activity.persistence)
        assertTrue(activity.actions.isEmpty())
    }

    @Test
    fun theSummaryFollowsTheListAndNeverClaimsMore() {
        store.setChecked("t1", true, store.list().revision)
        assertEquals("3 left", TodoActivitySummary.build(store.list()).primary)

        listOf("t2", "t3", "t4").forEach { store.setChecked(it, true, store.list().revision) }
        val allDone = TodoActivitySummary.build(store.list())
        assertEquals("All done", allDone.primary)
        assertEquals(NexusActivityProgress.Percent(100), allDone.progress)
        assertTrue(allDone.detail.isEmpty())

        store.list().tasks.forEach { store.remove(it.id) }
        val empty = TodoActivitySummary.build(store.list())
        assertEquals("No tasks", empty.primary)
        assertNull(empty.progress)
    }

    @Test
    fun longTextIsFittedToTheCardRatherThanRefused() {
        val long = TodoTask("t9", "A very long task title that goes on and on", checked = false)
        val activity = TodoActivitySummary.build(
            TodoList(1L, "Weekly groceries for the whole family", listOf(long) + store.list().tasks),
        )

        assertEquals("5 left", activity.primary)
        assertTrue(activity.secondary!!.length <= 28)
        assertTrue(activity.secondary!!.endsWith("…"))
        assertTrue(activity.detail[0].length <= 32)
        assertTrue(activity.detail[0].startsWith("A very long task"))
    }

    @Test
    fun offPublishesNothing() {
        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertTrue(sent.isEmpty())
        assertEquals(TodoActivityStatus.OFF, publisher.status)
    }

    @Test
    fun onStartsOnceAndSendsOnlyARealChange() {
        on()

        assertEquals(TodoFlush.SENT, publisher.flush())
        later()
        assertEquals(TodoFlush.IDLE, publisher.flush())
        store.setChecked("t1", true, store.list().revision)
        later()
        assertEquals(TodoFlush.SENT, publisher.flush())

        assertEquals(listOf("start", "update"), sent.map { it.first })
        assertEquals("3 left", sent.last().second!!.primary)
        assertEquals(store.list().revision, prefs.publishedRevision)
        assertEquals(TodoActivityStatus.SHOWN, publisher.status)
    }

    @Test
    fun aBurstOfChangesIsSentAfterTheInterval() {
        on()
        publisher.flush()
        store.setChecked("t1", true, store.list().revision)

        assertEquals(TodoFlush.DEFERRED, publisher.flush())
        assertEquals(TodoFlush.DEFERRED, publisher.flush())
        assertEquals(1, scheduled.size)

        later()
        scheduled.removeAt(0).invoke()
        assertEquals(listOf("start", "update"), sent.map { it.first })
    }

    @Test
    fun aNewRegistrationStartsTheEntryAgain() {
        on()
        publisher.flush()

        sink!!.registrationGeneration = 2
        later()

        assertEquals(TodoFlush.SENT, publisher.flush())
        assertEquals(listOf("start", "start"), sent.map { it.first })
    }

    @Test
    fun aNewClientCountsItsRegistrationsAfresh() {
        on()
        publisher.flush()
        val replacement = Sink()
        sink = replacement
        publisher.newClient()
        later()

        assertEquals(TodoFlush.SENT, publisher.flush())
        assertEquals("start", replacement.sent.single().first)
    }

    @Test
    fun turningItOffEndsItOnce() {
        on()
        publisher.flush()
        off()
        later()

        assertEquals(TodoFlush.SENT, publisher.flush())
        later()
        assertEquals(TodoFlush.IDLE, publisher.flush())

        assertEquals(listOf("start", "end"), sent.map { it.first })
        assertFalse(prefs.endPending)
        assertNull(prefs.publishedRevision)
        assertEquals(TodoActivityStatus.OFF, publisher.status)
    }

    @Test
    fun anEndThatCouldNotBeSentIsStillOwed() {
        off()
        sink = null

        assertEquals(TodoFlush.WAITING, publisher.flush())
        assertTrue(prefs.endPending)

        sink = Sink()
        assertEquals(TodoFlush.SENT, publisher.flush())
        assertFalse(prefs.endPending)
    }

    @Test
    fun withoutTheLauncherBundleNothingIsPublished() {
        on()
        sink!!.launcherInteractive = false

        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertTrue(sent.isEmpty())
        assertEquals(TodoActivityStatus.NEEDS_NEWER_NEXUS, publisher.status)
    }

    @Test
    fun asleepItReportsWhatNexusWasLastHanded() {
        on()
        publisher.flush()
        sink = null

        assertEquals(TodoFlush.WAITING, publisher.flush())
        assertEquals(TodoActivityStatus.SHOWN, publisher.status)

        store.setChecked("t1", true, store.list().revision)
        publisher.flush()
        assertEquals(TodoActivityStatus.WAITING, publisher.status)
    }

    @Test
    fun aClientThatHasNotRegisteredYetIsWaitingNotRefused() {
        on()
        sink!!.registered = false
        sink!!.approved = false

        assertEquals(TodoFlush.WAITING, publisher.flush())
        assertEquals(TodoActivityStatus.WAITING, publisher.status)

        sink!!.registered = true
        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertEquals(TodoActivityStatus.NOT_APPROVED, publisher.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aRefusalHoldsForItsRegistrationUntilTheUserAsksAgain() {
        on()
        publisher.flush()
        publisher.refused()
        store.setChecked("t1", true, store.list().revision)
        later()

        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertEquals(TodoActivityStatus.DISABLED_IN_NEXUS, publisher.status)

        publisher.enabledAgain()
        assertEquals(TodoFlush.SENT, publisher.flush())
        assertEquals(listOf("start", "start"), sent.map { it.first })
    }

    @Test
    fun anEvictionWaitsForTheNextChange() {
        on()
        publisher.flush()
        publisher.closed(ActivityCloseReason.REPLACED.wireValue)
        later()

        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertEquals(TodoActivityStatus.REPLACED, publisher.status)

        store.setChecked("t1", true, store.list().revision)
        publisher.listChanged()
        assertEquals(TodoFlush.SENT, publisher.flush())
        assertEquals(listOf("start", "start"), sent.map { it.first })
    }

    @Test
    fun theDemoRouteHoldsTheActivityUntilItEnds() {
        on()
        publisher.pausedForDemo = true

        assertEquals(TodoFlush.IDLE, publisher.flush())
        assertEquals(TodoActivityStatus.PAUSED_FOR_DEMO, publisher.status)

        publisher.pausedForDemo = false
        assertEquals(TodoFlush.SENT, publisher.flush())
        assertEquals("start", sent.single().first)
    }
}
