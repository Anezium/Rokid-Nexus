package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchJobStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun processDeathBecomesInterruptedAndCannotReturnPartialOutput() {
        val directory = temp.newFolder()
        val store = PatchJobStore(directory)
        store.prepare()
        val recovered = PatchJobStore(directory).state.value
        assertEquals(PatchJobStatus.INTERRUPTED, recovered.status)
        assertNull(recovered.result)
        assertTrue(recovered.message.contains("interrupted"))
    }
    @Test fun completedResultSurvivesRecreation() {
        val directory = temp.newFolder()
        val store = PatchJobStore(directory)
        val job = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = "patched-result.apk") }
        assertEquals(store.state.value, PatchJobStore(directory).state.value)
    }
    @Test fun cancelledJobRejectsLateWorkerSuccess() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.CANCELLED, result = null) }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = "patched-partial.apk") }
        assertEquals(PatchJobStatus.CANCELLED, store.state.value.status)
        assertNull(store.state.value.result)
    }
    @Test fun onlyOneJobCanRunAndOldCallbacksCannotChangeNewJobs() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        assertThrows(IllegalStateException::class.java) { store.prepare() }
        store.change(job.id) { it.copy(status = PatchJobStatus.FAILURE) }
        val retry = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS) }
        assertEquals(retry, store.state.value)
    }

    @Test fun retryRetainsPreparedInputButRejectsCallbacksFromThePreviousExecution() {
        val store = PatchJobStore(temp.newFolder())
        val prepare = store.prepare()
        val stock = java.io.File(store.work(prepare.id), "stock.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.change(prepare.id) { it.copy(status = PatchJobStatus.READY, stock = stock.name) }
        val first = store.patch("a".repeat(64), listOf("Patch"))
        store.change(first.id) { it.copy(status = PatchJobStatus.FAILURE) }
        val retry = store.patch("a".repeat(64), listOf("Patch"))
        assertNotEquals(first.id, retry.id)
        assertEquals(first.workId, retry.workId)
        assertEquals(stock, store.stock(retry))
        store.change(first.id) { it.copy(status = PatchJobStatus.SUCCESS, result = "patched-old.apk") }
        assertEquals(retry, store.state.value)
    }

    @Test fun expiredOrMissingResultsHaveAnExplicitRetryState() {
        val directory = temp.newFolder()
        val store = PatchJobStore(directory)
        val job = store.prepare()
        val result = java.io.File(directory, "results/patched-result.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = result.name) }
        store.reconcileResult(result.lastModified() + PatchPolicy.RESULT_MAX_AGE_MS)
        assertEquals(PatchJobStatus.FAILURE, store.state.value.status)
        assertNull(store.state.value.result)
        assertFalse(result.exists())
        assertTrue(store.state.value.message.contains("expired"))
    }

    @Test fun runningJobRefusesKeyMaintenanceUntilItEnds() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        assertThrows(IllegalStateException::class.java) { store.beginKeyMaintenance() }
        assertFalse(store.keyMaintenance.value)
        store.change(job.id) { it.copy(status = PatchJobStatus.FAILURE) }
        val lease = store.beginKeyMaintenance()
        assertTrue(store.keyMaintenance.value)
        store.endKeyMaintenance(lease)
        assertFalse(store.keyMaintenance.value)
    }

    @Test fun keyMaintenanceRefusesPrepareAndPatchUntilReleased() {
        val directory = temp.newFolder()
        val store = PatchJobStore(directory)
        val prepared = store.prepare()
        java.io.File(store.work(prepared.workId), "prepare/stock.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(prepared.id) { it.copy(status = PatchJobStatus.READY, stock = "prepare/stock.apk") }
        val ready = store.state.value
        val lease = store.beginKeyMaintenance()
        assertThrows(IllegalStateException::class.java) { store.beginKeyMaintenance() }
        assertThrows(IllegalStateException::class.java) { store.prepare() }
        assertThrows(IllegalStateException::class.java) { store.patch("hash", listOf("patch")) }
        assertEquals("refused starts leave the held source untouched", ready, store.state.value)
        store.endKeyMaintenance(Any())
        assertTrue("a stale lease cannot release the current one", store.keyMaintenance.value)
        assertThrows(IllegalStateException::class.java) { store.patch("hash", listOf("patch")) }
        store.endKeyMaintenance(lease)
        assertEquals(PatchJobStatus.RUNNING, store.patch("hash", listOf("patch")).status)
    }

    @Test fun keyMaintenanceLeaseDoesNotSurviveProcessDeath() {
        val directory = temp.newFolder()
        PatchJobStore(directory).beginKeyMaintenance()
        val restarted = PatchJobStore(directory)
        assertFalse(restarted.keyMaintenance.value)
        assertEquals(PatchJobStatus.PREPARING, restarted.prepare().status)
    }

    @Test fun keyMaintenanceAndJobStartsNeverOverlapUnderContention() {
        repeat(200) {
            val store = PatchJobStore(temp.newFolder())
            val start = java.util.concurrent.CountDownLatch(1)
            val job = java.util.concurrent.atomic.AtomicBoolean()
            val key = java.util.concurrent.atomic.AtomicBoolean()
            val threads = listOf(
                Thread { start.await(); job.set(runCatching { store.prepare() }.isSuccess) },
                Thread { start.await(); key.set(runCatching { store.beginKeyMaintenance() }.isSuccess) },
            ).onEach { it.start() }
            start.countDown(); threads.forEach { it.join() }
            assertTrue("exactly one of the two may win", job.get() != key.get())
        }
    }
}
