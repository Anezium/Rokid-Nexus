package com.anezium.rokidbus.plugin.patcher

import com.anezium.rokidbus.plugin.patcher.PatchPresentation.StageState
import org.junit.Assert.*
import org.junit.Test

class PatchPresentationTest {
    @Test fun everyPatchPhaseBelongsToExactlyOneStageAndPreparePhasesToNone() {
        val covered = PatchPresentation.patchStages.flatMap { it.phases }
        assertEquals(covered.size, covered.toSet().size)
        assertEquals(PatchPhase.entries.filterNot(PatchPresentation::isPreparePhase), covered)
        assertTrue(PatchPresentation.isPreparePhase(PatchPhase.SPLIT_MERGE))
        assertFalse(PatchPresentation.isPreparePhase(PatchPhase.BUNDLE_LOAD))
    }

    @Test fun stagesMarkDoneCurrentAndUpcomingInOrder() {
        val rows = PatchPresentation.stages(PatchPhase.WRITE)
        assertEquals(listOf("Load", "Patch", "Build", "Sign", "Save"), rows.map { it.label })
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.index })
        assertEquals(listOf(StageState.DONE, StageState.DONE, StageState.CURRENT, StageState.UPCOMING, StageState.UPCOMING), rows.map { it.state })
        assertEquals(StageState.CURRENT, PatchPresentation.stages(PatchPhase.BUNDLE_LOAD).first().state)
        assertTrue(PatchPresentation.stages(PatchPhase.READ_INPUT).all { it.state == StageState.UPCOMING })
        assertTrue(PatchPresentation.stages(PatchPhase.HAND_OFF, finished = true).all { it.state == StageState.DONE })
    }

    @Test fun phaseLineShowsNumbersOnlyWhenMeasured() {
        assertEquals("Reading your file · 42%", PatchPresentation.phaseLine(PatchProgress(PatchPhase.READ_INPUT, .42)))
        assertEquals("Reading your file", PatchPresentation.phaseLine(PatchProgress(PatchPhase.READ_INPUT)))
        assertEquals("Applying patches", PatchPresentation.phaseLine(PatchProgress(PatchPhase.APPLY_PATCHES)))
        assertEquals("Applying 23 patches", PatchPresentation.phaseLine(PatchProgress(PatchPhase.APPLY_PATCHES, 0.0, patchTotal = 23)))
        assertEquals("Applying patches · 7 of 23", PatchPresentation.phaseLine(PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23)))
        assertEquals("Compiling the patched code", PatchPresentation.phaseLine(PatchProgress(PatchPhase.COMPILE)))
        assertEquals("Ready to install", PatchPresentation.phaseLine(PatchProgress(PatchPhase.HAND_OFF, 1.0)))
    }

    @Test fun elapsedReadsAsAClock() {
        assertEquals("0:00", PatchPresentation.elapsed(0))
        assertEquals("0:00", PatchPresentation.elapsed(-5000))
        assertEquals("0:09", PatchPresentation.elapsed(9999))
        assertEquals("2:14", PatchPresentation.elapsed(134_000))
        assertEquals("1:02:05", PatchPresentation.elapsed(3_725_000))
    }

    @Test fun headlinesFollowTheJob() {
        assertEquals("Patch", PatchPresentation.headline(PatchJobStatus.IDLE, "YouTube"))
        assertEquals("Patch", PatchPresentation.headline(PatchJobStatus.READY, "YouTube"))
        assertEquals("Patching YouTube", PatchPresentation.headline(PatchJobStatus.RUNNING, "YouTube"))
        assertEquals("Patched", PatchPresentation.headline(PatchJobStatus.SUCCESS, "YouTube"))
        assertEquals("Patch failed", PatchPresentation.headline(PatchJobStatus.FAILURE, "YouTube"))
        assertEquals("Patch cancelled", PatchPresentation.headline(PatchJobStatus.CANCELLED, "YouTube"))
        assertEquals("Patch interrupted", PatchPresentation.headline(PatchJobStatus.INTERRUPTED, "YouTube"))
    }

    @Test fun noticesNameTheTargetAndCarryTheReason() {
        val target = PatchTargets.default
        val running = PatchJobState(status = PatchJobStatus.RUNNING,
            progress = PatchProgress(PatchPhase.APPLY_PATCHES, .5, "Hide ads", 7, 23), elapsedMs = 134_000)
        assertEquals(PatchPresentation.Notice("Patching ${target.displayName}", "Applying patches · 7 of 23"), PatchPresentation.notice(running, target))
        assertEquals("Checking your ${target.displayName} file", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.PREPARING), target).title)
        assertEquals("${target.displayName} is ready to install", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.SUCCESS), target).title)
        val failed = PatchPresentation.notice(PatchJobState(status = PatchJobStatus.FAILURE, message = "Not enough memory."), target)
        assertEquals("Patching ${target.displayName} failed", failed.title)
        assertEquals("Not enough memory.", failed.text)
        assertEquals("Patching cancelled", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.CANCELLED), target).title)
        assertEquals("Patching was interrupted", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.INTERRUPTED), target).title)
    }
}
