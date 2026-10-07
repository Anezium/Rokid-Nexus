package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test

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
}
