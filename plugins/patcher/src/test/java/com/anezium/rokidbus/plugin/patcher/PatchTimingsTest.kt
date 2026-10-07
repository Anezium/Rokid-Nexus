package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.util.logging.Logger

class PatchTimingsTest {
    @Test fun recordsMonotonicDurationAndFailureWithoutExceptionText() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timings = PatchTimings({ now }, logs::add)
        assertThrows(IllegalStateException::class.java) {
            timings.measure("read_copy_input") { now = 42_000_000; error("/private/user/file") }
        }
        assertEquals(listOf("step=read_copy_input duration_ms=42 outcome=failed"), logs)
        assertThrows(IllegalArgumentException::class.java) { timings.end("user/path", now) }
    }
    @Test fun observesActualAlignmentBoundaryAndRestoresLogger() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timings = PatchTimings({ now }, logs::add)
        val logger = Logger.getLogger("app.morphe.patcher.apk.ApkUtils")
        val originalLevel = logger.level
        val handlers = logger.handlers.toList()
        timings.withAlignmentTiming {
            logger.info("Aligning APK"); now = 7_000_000; logger.fine("Writing changes")
        }
        assertEquals(listOf("step=align duration_ms=7 outcome=ok"), logs)
        assertEquals(originalLevel, logger.level)
        assertEquals(handlers, logger.handlers.toList())
    }
    @Test fun alignmentAndWritingMarkersFollowTheRealUpstreamOrder() {
        val phases = mutableListOf<PatchPhase>()
        val logger = Logger.getLogger("app.morphe.patcher.apk.ApkUtils")
        PatchTimings(sink = {}).withAlignmentTiming(
            onAlign = { phases += PatchPhase.ALIGN }, onWrite = { phases += PatchPhase.WRITE }) {
            logger.info("Aligning APK")
            logger.fine("Writing changes")
        }
        assertEquals(listOf(PatchPhase.ALIGN, PatchPhase.WRITE), phases)
        assertTrue(phases.zipWithNext().all { (before, after) -> before.ordinal < after.ordinal })
    }

}
