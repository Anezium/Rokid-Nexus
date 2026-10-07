package com.anezium.rokidbus.plugin.patcher

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File

internal class PatchExecutionDiagnostics(private val context: Context,
    private val read: (String) -> String? = { runCatching { File(it).readText() }.getOrNull() },
    private val sink: (String) -> Unit = { Log.i(PatchTimings.TAG, it) }) {
    private data class Scheduler(val cpuset: String, val cpus: String, val groups: String)
    private data class Sample(val phase: PatchPhase, val visibility: String, val interactive: Boolean,
        val locked: Boolean, val processCpuset: String, val worker: Scheduler)
    private var previous: Sample? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized
    fun sample(phase: PatchPhase, workerTid: Int) {
        val power = context.getSystemService(PowerManager::class.java)
        val current = Sample(phase, PatchVisibility.mode, power.isInteractive,
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            cpuset(read("/proc/self/cpuset")), scheduler(workerTid))
        if (current == previous) return
        previous = current
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val gc = runCatching { Debug.getRuntimeStats() }.getOrDefault(emptyMap())
        sink("diagnostic phase=${phase.name.lowercase()} importance=${info.importance} " +
            "visibility=${current.visibility} locked=${current.locked} " +
            "background_restricted=${context.getSystemService(ActivityManager::class.java).isBackgroundRestricted} " +
            "fgs_type=${(context as? Service)?.foregroundServiceType ?: 0} " +
            "heap_limit_bytes=${Runtime.getRuntime().maxMemory()} processors=${Runtime.getRuntime().availableProcessors()} " +
            "process_cpu_ms=${Process.getElapsedCpuTime()} thermal=${power.currentThermalStatus} " +
            "interactive=${current.interactive} power_save=${power.isPowerSaveMode} " +
            "gc_count=${numeric(gc["art.gc.gc-count"])} gc_time=${numeric(gc["art.gc.gc-time"])}")
        thread(workerTid, "worker", current.worker)
        File("/proc/self/task").listFiles()?.forEach { task ->
            val tid = task.name.toIntOrNull() ?: return@forEach
            if (tid != workerTid && isDispatcherThread(read("${task.path}/comm")))
                thread(tid, "dex_dispatcher")
        }
    }
    private fun scheduler(tid: Int): Scheduler {
        val base = "/proc/self/task/$tid"
        val groups = read("$base/cgroup")?.lineSequence()?.mapNotNull { line ->
            line.split(':', limit = 3).takeIf { it.size == 3 }?.last()?.trim()?.takeIf { it in GROUPS }
        }?.distinct()?.joinToString(",")?.takeIf { it.isNotEmpty() } ?: "unknown"
        return Scheduler(cpuset(read("$base/cpuset")), cpus(read("$base/status")), groups)
    }
    private fun thread(tid: Int, role: String, scheduler: Scheduler = scheduler(tid)) {
        val nice = runCatching { Process.getThreadPriority(tid).toString() }.getOrDefault("unknown")
        sink("diagnostic_thread role=$role tid=$tid nice=$nice cpuset=${scheduler.cpuset} " +
            "cpus=${scheduler.cpus} groups=${scheduler.groups}")
    }
    companion object {
        private val GROUPS = setOf("/top-app", "/foreground", "/foreground-boost", "/moderate", "/background", "/system-background", "/low", "/high", "/")
        // Linux comm is truncated to 15 bytes, including Android coroutine worker names.
        fun isDispatcherThread(value: String?): Boolean = value?.trim()?.let {
            it == "DefaultDispatch" || it.matches(Regex("DefaultDispatcher-worker-[0-9]+"))
        } == true
        fun cpuset(value: String?): String = value?.trim()?.takeIf { it in GROUPS } ?: "unknown"
        fun cpus(status: String?): String = status?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.length <= 64 && it.matches(Regex("[0-9,-]+")) } ?: "unknown"
        private fun numeric(value: String?): String = value?.takeIf { it.matches(Regex("[0-9]+")) } ?: "unknown"
    }
}
