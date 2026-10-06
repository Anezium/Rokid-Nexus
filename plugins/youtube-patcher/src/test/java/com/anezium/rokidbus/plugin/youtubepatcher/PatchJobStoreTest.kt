package com.anezium.rokidbus.plugin.youtubepatcher

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
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = "youtube-result.apk") }
        assertEquals(store.state.value, PatchJobStore(directory).state.value)
    }
    @Test fun cancelledJobRejectsLateWorkerSuccess() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.CANCELLED, result = null) }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = "youtube-partial.apk") }
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
}
