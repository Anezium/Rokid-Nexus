package com.anezium.rokidbus.plugin.youtubepatcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchProgressTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun phasesAdvanceAndElapsedCannotGoBackwards() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        store.progress(job.id, PatchProgress(PatchPhase.SIGNATURE_CHECK, .5), 1200)
        store.tick(job.id, 100)
        assertEquals(1200, store.state.value.elapsedMs)
        assertThrows(IllegalArgumentException::class.java) { store.progress(job.id, PatchProgress(PatchPhase.READ_INPUT), 1400) }
        store.progress(job.id, PatchProgress(PatchPhase.SPLIT_MERGE), 1400)
        assertEquals(3, store.state.value.progress.phaseIndex)
        assertEquals(13, store.state.value.progress.phaseTotal)
        assertNull(store.state.value.progress.estimatedRemainingMs)
    }
    @Test fun newObserverAndSerializedSnapshotKeepPatchCountsAndUnknownFractions() {
        val store = PatchJobStore(temp.newFolder())
        val job = store.prepare()
        val report = PatchProgress(PatchPhase.APPLY_PATCHES, .5, "Sample patch", 2, 4)
        store.progress(job.id, report, 5000)
        val recreatedUi = store.state
        assertEquals(report, recreatedUi.value.progress)
        assertEquals(store.state.value, PatchJobStore.decode(PatchJobStore.encode(store.state.value)))
        store.progress(job.id, PatchProgress(PatchPhase.COMPILE), 6000)
        assertNull(recreatedUi.value.progress.fraction)
        assertNull(recreatedUi.value.progress.patchName)
    }
    @Test fun everyTerminalStateFreezesProgressAndElapsed() {
        for (terminal in listOf(PatchJobStatus.SUCCESS, PatchJobStatus.FAILURE, PatchJobStatus.CANCELLED, PatchJobStatus.INTERRUPTED)) {
            val directory = temp.newFolder()
            val store = PatchJobStore(directory)
            val job = store.prepare()
            store.progress(job.id, PatchProgress(PatchPhase.SIGNATURE_CHECK), 1000)
            store.change(job.id) { it.copy(status = terminal, message = "Terminal reason") }
            val final = store.state.value
            store.progress(job.id, PatchProgress(PatchPhase.PUBLISH), 9999)
            store.tick(job.id, 9999)
            assertEquals(final, store.state.value)
            assertEquals(final, PatchJobStore(directory).state.value)
        }
    }
    @Test fun rejectsFalseCountsAndPercentages() {
        assertThrows(IllegalArgumentException::class.java) { PatchProgress(fraction = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PatchProgress(fraction = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { PatchProgress(patchIndex = 2, patchTotal = 1) }
        assertThrows(IllegalArgumentException::class.java) { PatchProgress(patchName = "Patch outside apply") }
    }
}
