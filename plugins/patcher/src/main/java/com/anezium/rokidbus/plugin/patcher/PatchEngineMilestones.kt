package com.anezium.rokidbus.plugin.patcher

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class PatchEngineMilestones(private val report: (PatchProgress) -> Unit) {
    suspend fun <T> observe(block: suspend () -> T): T {
        val loggers = LOGGER_NAMES.map(Logger::getLogger)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { translate(record.loggerName, record.message)?.let(report) }
            override fun flush() {}
            override fun close() {}
        }
        loggers.forEach { it.addHandler(handler) }
        try { return block() } finally { loggers.forEach { it.removeHandler(handler) } }
    }

    companion object {
        private const val ROOT = "app.morphe.patcher."
        internal val LOGGER_NAMES = listOf(ROOT + "Patcher", ROOT + "patch.BytecodePatchContext",
            ROOT + "patch.ResourcePatchContext", ROOT + "resource.coder.ArsclibResourceCoder")
        fun translate(logger: String?, message: String?): PatchProgress? {
            if (logger !in LOGGER_NAMES || message == null) return null
            if (logger == ROOT + "patch.BytecodePatchContext") {
                val count = Regex("""(?:Writing ([0-9]+) new classes to new DEX files|Processing ([0-9]+) classes (?:\(single threaded mode\)|in parallel \([0-9]+ threads\)))""")
                    .matchEntire(message)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toLongOrNull()
                if (count != null) return PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.DEX, workTotal = count)
            }
            val (phase, step) = when {
                logger == ROOT + "Patcher" && message == "Executing patches" -> PatchPhase.APPLY_PATCHES to PatchSubstep.EXECUTE
                logger == ROOT + "patch.ResourcePatchContext" && message in setOf("Decoding all resources", "Decoding resources in raw mode") -> PatchPhase.APPLY_PATCHES to PatchSubstep.DECODE
                logger == ROOT + "patch.BytecodePatchContext" && message.matches(Regex("""Compiling patched dex files \(mode: (NONE|FULL|STRIP_FAST|STRIP_SAFE)\)""")) -> PatchPhase.COMPILE to PatchSubstep.DEX
                logger == ROOT + "patch.BytecodePatchContext" && message.matches(Regex("Stripping [0-9]+ modified classes from original DEX files")) -> PatchPhase.COMPILE to PatchSubstep.STRIP
                logger == ROOT + "patch.ResourcePatchContext" && message == "Compiling modified resources" -> PatchPhase.COMPILE to PatchSubstep.RESOURCES
                logger == ROOT + "resource.coder.ArsclibResourceCoder" && message == "Writing resource APK" -> PatchPhase.COMPILE to PatchSubstep.RESOURCE_APK
                else -> return null
            }
            return PatchProgress(phase, substep = step)
        }
    }
}
