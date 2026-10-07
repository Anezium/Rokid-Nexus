package com.anezium.rokidbus.plugin.patcher

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File

internal class PatchExecutionDiagnostics(private val context: Context,
    private val read: (String) -> String? = { runCatching { File(it).readText() }.getOrNull() },
    private val sink: (String) -> Unit = { Log.i(PatchTimings.TAG, it) }) {
    fun sample(phase: PatchPhase, workerTid: Int) {
        val power = context.getSystemService(PowerManager::class.java)
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val gc = runCatching { Debug.getRuntimeStats() }.getOrDefault(emptyMap())
        sink("diagnostic phase=${phase.name.lowercase()} importance=${info.importance} " +
            "heap_limit_bytes=${Runtime.getRuntime().maxMemory()} processors=${Runtime.getRuntime().availableProcessors()} " +
            "process_cpu_ms=${Process.getElapsedCpuTime()} thermal=${power.currentThermalStatus} " +
            "interactive=${power.isInteractive} power_save=${power.isPowerSaveMode} " +
            "gc_count=${numeric(gc["art.gc.gc-count"])} gc_time=${numeric(gc["art.gc.gc-time"])}")
        thread(workerTid, "worker")
        File("/proc/self/task").listFiles()?.forEach { task ->
            val tid = task.name.toIntOrNull() ?: return@forEach
            if (tid != workerTid && isDispatcherThread(read("${task.path}/comm")))
                thread(tid, "dex_dispatcher")
        }
    }
    private fun thread(tid: Int, role: String) {
        val base = "/proc/self/task/$tid"
        val nice = runCatching { Process.getThreadPriority(tid).toString() }.getOrDefault("unknown")
        val groups = read("$base/cgroup")?.lineSequence()?.mapNotNull { line ->
            line.split(':', limit = 3).takeIf { it.size == 3 }?.last()?.trim()?.takeIf { it in GROUPS }
        }?.distinct()?.joinToString(",")?.takeIf { it.isNotEmpty() } ?: "unknown"
        sink("diagnostic_thread role=$role tid=$tid nice=$nice cpuset=${cpuset(read("$base/cpuset"))} " +
            "cpus=${cpus(read("$base/status"))} groups=$groups")
    }
    companion object {
        private val GROUPS = setOf("/top-app", "/foreground", "/moderate", "/background", "/system-background", "/low", "/")
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
