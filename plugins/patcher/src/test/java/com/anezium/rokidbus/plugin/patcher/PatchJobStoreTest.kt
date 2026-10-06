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
}
