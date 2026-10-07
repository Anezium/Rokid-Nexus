package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchExecutionDiagnosticsTest {
    @Test fun schedulerValuesAreNumericOrAllowlistedAndMissingFilesStayUnknown() {
        assertTrue(PatchExecutionDiagnostics.isDispatcherThread("DefaultDispatch\n"))
        assertTrue(PatchExecutionDiagnostics.isDispatcherThread("DefaultDispatcher-worker-4"))
        assertFalse(PatchExecutionDiagnostics.isDispatcherThread("/private/account"))
        assertEquals("/moderate", PatchExecutionDiagnostics.cpuset("/moderate\n"))
        assertEquals("/foreground-boost", PatchExecutionDiagnostics.cpuset("/foreground-boost\n"))
        assertEquals("/high", PatchExecutionDiagnostics.cpuset("/high\n"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpuset("/user/private/account"))
        assertEquals("0-2", PatchExecutionDiagnostics.cpus("Name: private\nCpus_allowed_list:\t0-2\n"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpus("Cpus_allowed_list: private/path"))
        assertEquals("unknown", PatchExecutionDiagnostics.cpus(null))
        assertEquals("unknown", PatchExecutionDiagnostics.cpuset(null))
    }

    @Test fun unchangedSamplesAreSilentAndOnlyPhaseVisibilityOrSchedulerChangesLog() {
        val context = RuntimeEnvironment.getApplication()
        PatchVisibility.install(context)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(power).setIsInteractive(true)
        shadowOf(keyguard).setKeyguardLocked(false)
        var group = "/top-app"
        var cpus = "0-7"
        val lines = mutableListOf<String>()
        val diagnostics = PatchExecutionDiagnostics(context, read = { path -> when {
            path.endsWith("/cpuset") -> group
            path.endsWith("/status") -> "Cpus_allowed_list:\t$cpus\n"
            path.endsWith("/cgroup") -> "0::$group\n"
            else -> null
        } }, sink = lines::add)
        val tid = android.os.Process.myTid()
        fun sample(phase: PatchPhase = PatchPhase.COMPILE) { diagnostics.sample(phase, tid) }
        fun summaries() = lines.count { it.startsWith("diagnostic phase=") }
        sample()
        repeat(20) { sample() }
        assertEquals(1, summaries())
        sample(PatchPhase.WRITE)
        assertEquals(2, summaries())
        sample()
        assertEquals(3, summaries())
        val screen = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        sample()
        assertEquals(4, summaries())
        assertTrue(lines.last { it.startsWith("diagnostic phase=") }.contains("visibility=fullscreen"))
        screen.get().enterPictureInPictureMode(PatchPictureInPicture.params(true))
        screen.pause()
        sample()
        assertEquals(5, summaries())
        assertTrue(lines.last { it.startsWith("diagnostic phase=") }.contains("visibility=pip"))
        screen.stop()
        sample()
        assertEquals(6, summaries())
        assertTrue(lines.last { it.startsWith("diagnostic phase=") }.contains("visibility=hidden"))
        group = "/foreground-boost"
        sample()
        assertEquals(7, summaries())
        assertTrue(lines.any { it.contains("cpuset=/foreground-boost") })
        cpus = "0-2"
        sample()
        assertEquals(8, summaries())
        shadowOf(power).setIsInteractive(false)
        sample()
        assertEquals(9, summaries())
        shadowOf(keyguard).setKeyguardLocked(true)
        sample()
        assertEquals(10, summaries())
        repeat(20) { sample() }
        assertEquals(10, summaries())
        diagnostics.reset()
        sample()
        assertEquals(11, summaries())
        screen.destroy()
    }

    @Test fun changesBetweenUnallowlistedPathsStaySilentAndNeverExposeTheirValues() {
        val context = RuntimeEnvironment.getApplication()
        PatchVisibility.install(context)
        var path = "/private/account/one"
        val lines = mutableListOf<String>()
        val diagnostics = PatchExecutionDiagnostics(context, read = { path }, sink = lines::add)
        diagnostics.sample(PatchPhase.COMPILE, 17)
        val count = lines.size
        path = "/private/account/two"
        diagnostics.sample(PatchPhase.COMPILE, 17)
        assertEquals(count, lines.size)
        assertTrue(lines.none { it.contains("/private") || it.contains("account") })
    }
}
