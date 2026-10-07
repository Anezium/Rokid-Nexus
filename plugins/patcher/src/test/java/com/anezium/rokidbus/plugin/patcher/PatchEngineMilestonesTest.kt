package com.anezium.rokidbus.plugin.patcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.logging.Logger

class PatchEngineMilestonesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun outOfOrderJulMilestonesAreIgnoredOncePerJobAndCompilationCanFinish() = runBlocking {
        val logs = mutableListOf<String>()
        val store = PatchJobStore(temp.newFolder(), logs::add)
        val logger = Logger.getLogger("app.morphe.patcher.patch.ResourcePatchContext")
        val before = logger.handlers.toList()
        repeat(2) {
            val job = store.prepare()
            PatchEngineMilestones { store.progress(job.id, it, 1000) }.observe {
                logger.info("Compiling modified resources")
                val compiling = store.state.value
                repeat(3) { logger.info("Decoding all resources") }
                assertEquals(compiling, store.state.value)
                assertEquals(PatchPhase.COMPILE, compiling.progress.phase)
                assertTrue(store.progress(job.id, PatchProgress(PatchPhase.WRITE), 2000))
                store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS) }
                logger.info("Decoding all resources")
            }
            assertEquals(PatchJobStatus.SUCCESS, store.state.value.status)
            assertEquals(PatchPhase.WRITE, store.state.value.progress.phase)
            assertEquals(before, logger.handlers.toList())
        }
        assertEquals(List(2) { "ignored_backwards_milestone_class=${PatchProgress::class.java.name}" }, logs)
    }

    @Test fun genuineMilestonesMoveWithoutInventedPercentages() {
        val decode = PatchEngineMilestones.translate("app.morphe.patcher.patch.ResourcePatchContext", "Decoding all resources")!!
        val dex = PatchEngineMilestones.translate("app.morphe.patcher.patch.BytecodePatchContext", "Compiling patched dex files (mode: STRIP_FAST)")!!
        val resources = PatchEngineMilestones.translate("app.morphe.patcher.patch.ResourcePatchContext", "Compiling modified resources")!!
        assertEquals(PatchSubstep.DECODE, decode.substep)
        assertEquals(PatchSubstep.DEX, dex.substep)
        assertEquals(PatchSubstep.RESOURCES, resources.substep)
        val counted = PatchEngineMilestones.translate("app.morphe.patcher.patch.BytecodePatchContext", "Processing 4200 classes in parallel (4 threads)")!!
        assertEquals(4200L, counted.workTotal)
        assertNull(counted.fraction)
        assertNull(dex.fraction)
        assertFalse(PatchPresentation.phaseLine(dex).contains("0%"))
        assertNull(PatchEngineMilestones.translate("app.morphe.patcher.patch.BytecodePatchContext", "/private/account"))
        assertNull(PatchEngineMilestones.translate("untrusted", "Compiling modified resources"))
        val restored = PatchJobStore.decode(PatchJobStore.encode(PatchJobState(progress = resources)))
        assertEquals(resources, restored.progress)
    }

    @Test fun loggerObserversAreRemovedWhenCompilationFails() = runBlocking {
        val logger = Logger.getLogger("app.morphe.patcher.patch.ResourcePatchContext")
        val before = logger.handlers.toList()
        val reports = mutableListOf<PatchProgress>()
        try {
            PatchEngineMilestones(reports::add).observe {
                logger.info("Compiling modified resources")
                error("upstream failure")
            }
        } catch (_: IllegalStateException) {}
        assertEquals(1, reports.size)
        assertEquals(before, logger.handlers.toList())
    }
}
