package com.anezium.rokidbus.phone

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBusJournalTest {
    @Test
    fun `oldest events are evicted past capacity`() {
        val journal = enabledJournal(capacity = 3)

        repeat(5) { index ->
            journal.record(
                pluginId = "plugin",
                category = PluginBusJournal.Category.TRANSPORT,
                direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
                path = "/event/$index",
            )
        }

        assertEquals(listOf("/event/2", "/event/3", "/event/4"), journal.snapshot().map { it.path })
    }

    @Test
    fun `snapshot is isolated from later records`() {
        val journal = enabledJournal(capacity = 3)
        journal.record(
            category = PluginBusJournal.Category.REGISTRATION,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
        )

        val snapshot = journal.snapshot()
        journal.record(
            category = PluginBusJournal.Category.LIFECYCLE,
            direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
        )

        assertEquals(1, snapshot.size)
        assertEquals(2, journal.snapshot().size)
    }

    @Test
    fun `disabled journal short circuits recording`() {
        val journal = PluginBusJournal()

        journal.record(
            category = PluginBusJournal.Category.SURFACE,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
            path = "/surface/show",
        )

        assertTrue(journal.snapshot().isEmpty())
        journal.enabled.set(true)
        journal.record(
            category = PluginBusJournal.Category.SURFACE,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
            path = "/surface/show",
        )
        assertEquals(1, journal.snapshot().size)
    }

    @Test
    fun `stored diagnostic strings are bounded`() {
        val journal = enabledJournal()

        journal.record(
            pluginId = "p".repeat(PluginBusJournal.MAX_PLUGIN_ID_CHARS + 10),
            category = PluginBusJournal.Category.TRANSPORT,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
            path = "/" + "x".repeat(PluginBusJournal.MAX_PATH_CHARS + 10),
            verdict = PluginBusJournal.Verdict.REJECTED,
            reason = "r".repeat(PluginBusJournal.MAX_REASON_CHARS + 10),
        )

        val event = journal.snapshot().single()
        assertEquals(PluginBusJournal.MAX_PLUGIN_ID_CHARS, event.pluginId?.length)
        assertEquals(PluginBusJournal.MAX_PATH_CHARS, event.path?.length)
        assertEquals(PluginBusJournal.MAX_REASON_CHARS, event.reason?.length)
    }

    @Test
    fun `concurrent records do not corrupt the journal`() {
        val workers = 8
        val recordsPerWorker = 250
        val journal = enabledJournal(capacity = workers * recordsPerWorker)
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)

        repeat(workers) { worker ->
            executor.execute {
                start.await()
                repeat(recordsPerWorker) { index ->
                    journal.record(
                        pluginId = "plugin-$worker",
                        category = PluginBusJournal.Category.INPUT,
                        direction = PluginBusJournal.Direction.GLASSES_TO_HUB,
                        path = "/input/$index",
                    )
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(workers * recordsPerWorker, journal.snapshot().size)
    }

    private fun enabledJournal(capacity: Int = PluginBusJournal.DEFAULT_CAPACITY): PluginBusJournal =
        PluginBusJournal(capacity).apply { enabled.set(true) }
}
