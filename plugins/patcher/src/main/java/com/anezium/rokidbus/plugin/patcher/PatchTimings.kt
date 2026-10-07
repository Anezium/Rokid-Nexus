package com.anezium.rokidbus.plugin.patcher

import android.util.Log

class PatchTimings(
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit = { Log.i(TAG, it) },
) {
    fun start() = clock()
    fun end(step: String, started: Long, outcome: String = "ok", detail: String = "") {
        require(step.matches(Regex("[a-z_]+")) && outcome in setOf("ok", "failed", "skipped"))
        require(detail.matches(Regex("[a-z_0-9= ]*")))
        sink("step=$step duration_ms=${((clock() - started) / 1_000_000).coerceAtLeast(0)} outcome=$outcome" +
            if (detail.isEmpty()) "" else " $detail")
    }
    fun <T> measure(step: String, block: () -> T): T {
        val started = start()
        var outcome = "failed"
        try { return block().also { outcome = "ok" } }
        finally { end(step, started, outcome) }
    }
    suspend fun <T> measureSuspend(step: String, block: suspend () -> T): T {
        val started = start()
        var outcome = "failed"
        try { return block().also { outcome = "ok" } }
        finally { end(step, started, outcome) }
    }

    companion object { const val TAG = "Patcher" }
}
