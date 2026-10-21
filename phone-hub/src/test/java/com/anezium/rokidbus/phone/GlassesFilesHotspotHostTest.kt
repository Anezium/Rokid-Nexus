package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesHotspotPolicy
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GlassesFilesHotspotHostTest {
    @Test fun `camera takeover just after the final transfer releases the idle batch immediately`() {
        val timers = ManualTimers()
        val requests = mutableListOf<GlassesFilesRequest>()
        val host = GlassesFilesHotspotHost(RuntimeEnvironment.getApplication(), timers, { requests.add(it); null })
        seed(host, "batch", "last_transfer")
        host.finish("last_transfer")
        timers.advanceTo(1_000)
        assertEquals(emptyList<GlassesFilesRequest>(), requests)
        host.onPeerFailed("last_transfer")
        assertEquals(listOf(GlassesFilesRequest.HotspotRelease("batch")), requests)
        assertNull(field(host, "batchId").get(host))
        timers.advanceTo(GlassesFilesHotspotPolicy.IDLE_MS + 10_000)
        assertEquals(1, requests.size)
        timers.shutdownNow()
    }

    @Test fun `ordinary idle retains the batch for 35 seconds and ignores stale peer failures`() {
        val timers = ManualTimers()
        val requests = mutableListOf<GlassesFilesRequest>()
        val host = GlassesFilesHotspotHost(RuntimeEnvironment.getApplication(), timers, { requests.add(it); null })
        seed(host, "batch", "current_transfer")
        host.finish("current_transfer")
        host.onPeerFailed("old_transfer")
        timers.advanceTo(GlassesFilesHotspotPolicy.IDLE_MS - 1)
        assertEquals(emptyList<GlassesFilesRequest>(), requests)
        timers.advanceTo(GlassesFilesHotspotPolicy.IDLE_MS)
        assertEquals(listOf(GlassesFilesRequest.HotspotRelease("batch")), requests)
        timers.shutdownNow()
    }

    @Test fun `controller releases an idle reservation even with no active transfer`() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<GlassesFilesRequest>()
        val controller = GlassesFilesController(RuntimeEnvironment.getApplication(), {
            GlassesFilesContract.parseRequest(it.payload)?.let(requests::add)
            null
        })
        try {
            val host = field(controller, "hotspot").get(controller) as GlassesFilesHotspotHost
            seed(host, "files_batch", "files_last")
            host.finish("files_last")
            controller.handleRemote(BusEnvelope(path = GlassesFilesContract.RESULT_PATH,
                payload = GlassesFilesContract.encode(GlassesFilesResult.HotspotFailed("files_last"))))
            (field(controller, "inbox").get(controller) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(GlassesFilesRequest.HotspotRelease("files_batch")), requests.toList())
            assertNull(field(host, "batchId").get(host))
        } finally {
            controller.close()
        }
    }

    private fun seed(host: GlassesFilesHotspotHost, batch: String, transfer: String) {
        field(host, "batchId").set(host, batch)
        field(host, "activeId").set(host, transfer)
        field(host, "lastTransferId").set(host, transfer)
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private class ManualTimers : ScheduledThreadPoolExecutor(1) {
        private var now = 0L
        private val tasks = mutableListOf<Task>()
        private inner class Task(val at: Long, command: Runnable) : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit): Long = unit.convert(at - now, TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(now + unit.toMillis(delay), command).also { tasks.add(it) }

        fun advanceTo(target: Long) {
            while (true) {
                val task = tasks.filter { !it.isDone && it.at <= target }.minByOrNull { it.at } ?: break
                now = task.at
                task.run()
                if (!task.isCancelled) task.get()
            }
            now = target
        }
    }
}
