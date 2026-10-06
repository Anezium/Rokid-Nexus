package com.anezium.rokidbus.plugin.youtubepatcher

import android.util.Log
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

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

    // Patcher 1.7.0 aligns inside applyTo; these fixed log boundaries expose that
    // duration without a second realignment pass or changing the upstream writer.
    fun <T> withAlignmentTiming(onAlign: () -> Unit = {}, block: () -> T): T {
        val logger = Logger.getLogger("app.morphe.patcher.apk.ApkUtils")
        val previous = logger.level
        var started: Long? = null
        val observer = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.message == "Aligning APK") { onAlign(); started = start() }
                if (record.message == "Writing changes") started?.let { end("align", it); started = null }
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(observer); logger.level = Level.FINE
        try { return block() }
        finally {
            started?.let { end("align", it, "failed") }
            logger.removeHandler(observer); logger.level = previous
        }
    }
    companion object { const val TAG = "Patcher" }
}
