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

    @Test fun substepsReadAsPlainWordsWithMeasuredCountersOnly() {
        assertEquals("Compiling code · 12,345 classes",
            PatchPresentation.phaseLine(PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.DEX, workTotal = 12_345)))
        assertEquals("Writing the APK · 120 MB written",
            PatchPresentation.phaseLine(PatchProgress(PatchPhase.WRITE, substep = PatchSubstep.WRITE_ENTRIES, completedBytes = 120_400_000)))
        assertEquals("Unpacking resources and code", PatchPresentation.phaseLine(PatchProgress(PatchPhase.APPLY_PATCHES, substep = PatchSubstep.DECODE)))
        assertEquals("Finishing the APK · 0 MB written",
            PatchPresentation.phaseLine(PatchProgress(PatchPhase.WRITE, substep = PatchSubstep.WRITE_DIRECTORY, completedBytes = 0)))
        PatchSubstep.entries.forEach { step ->
            assertFalse(step.label, step.label.contains("DEX") || step.label.contains("entries") || step.label.contains("?"))
            assertFalse(PatchPresentation.phaseLine(PatchProgress(PatchPhase.WRITE, substep = step, completedBytes = 1)).contains("?"))
        }
        assertFalse(PatchProgress(PatchPhase.WRITE, substep = PatchSubstep.WRITE_ENTRIES, completedBytes = 5_000_000).display().contains("?"))
    }

    @Test fun elapsedReadsAsAClock() {
        assertEquals("0:00", PatchPresentation.elapsed(0))
        assertEquals("0:00", PatchPresentation.elapsed(-5000))
        assertEquals("0:09", PatchPresentation.elapsed(9999))
        assertEquals("2:14", PatchPresentation.elapsed(134_000))
        assertEquals("1:02:05", PatchPresentation.elapsed(3_725_000))
    }

    @Test fun resultAgeUsesCoarseHumanUnits() {
        assertEquals("just now", PatchPresentation.age(0))
        assertEquals("just now", PatchPresentation.age(59_000))
        assertEquals("1 min ago", PatchPresentation.age(60_000))
        assertEquals("54 min ago", PatchPresentation.age(54 * 60_000L))
        assertEquals("1 h ago", PatchPresentation.age(60 * 60_000L))
        assertEquals("2 h 5 min ago", PatchPresentation.age(125 * 60_000L))
        assertEquals("just now", PatchPresentation.age(-5))
    }

    @Test fun notificationsShowABarOnlyForRealMovement() {
        assertNull(PatchPresentation.notificationPercent(PatchProgress(PatchPhase.COMPILE)))
        assertNull(PatchPresentation.notificationPercent(PatchProgress(PatchPhase.APPLY_PATCHES, 0.0, patchTotal = 23)))
        assertEquals(1, PatchPresentation.notificationPercent(PatchProgress(PatchPhase.READ_INPUT, 0.004)))
        assertEquals(30, PatchPresentation.notificationPercent(PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23)))
        assertEquals(100, PatchPresentation.notificationPercent(PatchProgress(PatchPhase.HAND_OFF, 1.0)))
        val target = PatchTargets.default
        val unknown = PatchJobState(status = PatchJobStatus.RUNNING,
            progress = PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.DEX, workTotal = 900), elapsedMs = 150_000)
        assertEquals("Compiling code · 900 classes · 2:30", PatchPresentation.notice(unknown, target).text)
        val preparing = PatchJobState(status = PatchJobStatus.PREPARING, progress = PatchProgress(PatchPhase.READ_INPUT, .42), elapsedMs = 5_000)
        assertEquals("Reading your file · 42% · 0:05", PatchPresentation.notice(preparing, target).text)
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

    @Test fun compactWindowFollowsTheJobAndClosesInItsAccent() {
        val target = PatchTargets.default
        val running = PatchJobState(status = PatchJobStatus.RUNNING,
            progress = PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23), elapsedMs = 134_000)
        assertEquals(PatchPresentation.Compact("Patcher · ${target.displayName}", "Applying patches · 7 of 23", "2:14", 7.0 / 23, PatchPresentation.Accent.LIVE),
            PatchPresentation.compact(running, target))
        val unknown = PatchPresentation.compact(running.copy(progress = PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.DEX)), target)
        assertNull(unknown.fraction)
        assertEquals("Compiling code", unknown.line)
        assertEquals(PatchPresentation.Compact("Patcher · ${target.displayName}", "Ready to install", "6:40", 1.0, PatchPresentation.Accent.OK),
            PatchPresentation.compact(running.copy(status = PatchJobStatus.SUCCESS, elapsedMs = 400_000), target))
        val failed = PatchPresentation.compact(running.copy(status = PatchJobStatus.FAILURE), target)
        assertEquals(PatchPresentation.Accent.DANGER, failed.accent)
        assertEquals("Patch failed", failed.line)
        assertEquals(PatchPresentation.Accent.DANGER, PatchPresentation.compact(running.copy(status = PatchJobStatus.INTERRUPTED), target).accent)
        val cancelled = PatchPresentation.compact(running.copy(status = PatchJobStatus.CANCELLED), target)
        assertEquals(PatchPresentation.Accent.WARN, cancelled.accent)
        assertEquals("Patch cancelled", cancelled.line)
        assertEquals(1.0, cancelled.fraction)
        PatchJobStatus.entries.forEach { status ->
            assertEquals(PatchPresentation.accent(status), PatchPresentation.compact(running.copy(status = status), target).accent)
        }
    }

    @Test fun noticesNameTheTargetAndCarryTheReason() {
        val target = PatchTargets.default
        val running = PatchJobState(status = PatchJobStatus.RUNNING,
            progress = PatchProgress(PatchPhase.APPLY_PATCHES, .5, "Hide ads", 7, 23), elapsedMs = 134_000)
        assertEquals(PatchPresentation.Notice("Patching ${target.displayName}", "Applying patches · 7 of 23 · 2:14"), PatchPresentation.notice(running, target))
        assertEquals("Checking your ${target.displayName} file", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.PREPARING), target).title)
        val ready = PatchPresentation.notice(PatchJobState(status = PatchJobStatus.SUCCESS), target)
        assertEquals("${target.displayName} is ready to install", ready.title)
        assertEquals("Tap to come back; keep the glasses connected to install.", ready.text)
        val failed = PatchPresentation.notice(PatchJobState(status = PatchJobStatus.FAILURE, message = "Not enough memory."), target)
        assertEquals("Patching ${target.displayName} failed", failed.title)
        assertEquals("Not enough memory.", failed.text)
        assertEquals("Patching cancelled", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.CANCELLED), target).title)
        assertEquals("Patching was interrupted", PatchPresentation.notice(PatchJobState(status = PatchJobStatus.INTERRUPTED), target).title)
    }

    @Test fun adviceIsHonestAboutSpeedAndNeverPromisesScreenOffPatching() {
        val everything = listOf(PatchPresentation.idleAdvice(true), PatchPresentation.idleAdvice(false),
            PatchPresentation.runningAdvice(true, true), PatchPresentation.runningAdvice(true, false),
            PatchPresentation.runningAdvice(false, true), PatchPresentation.runningAdvice(false, false))
        everything.forEach { text ->
            assertTrue(text, text.contains("6–7 minutes"))
            assertFalse(text, text.contains("few minutes") || text.contains("display off") || text.contains("turn the display"))
        }
        assertTrue(PatchPresentation.runningAdvice(true, true).contains("small window"))
        assertTrue(PatchPresentation.runningAdvice(true, true).contains("slows it down"))
        assertTrue(PatchPresentation.runningAdvice(true, true).endsWith("brings you back when it is ready."))
        assertTrue(PatchPresentation.runningAdvice(true, false).endsWith("come back here to check on it."))
        assertFalse(PatchPresentation.runningAdvice(false, true).contains("small window"))
    }
}
