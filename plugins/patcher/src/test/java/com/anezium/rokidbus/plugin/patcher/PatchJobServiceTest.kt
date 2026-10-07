package com.anezium.rokidbus.plugin.patcher

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Looper
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowService
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatchJobServiceTest {
    @Implements(Service::class)
    class RefusingForegroundService : ShadowService() {
        var refusePromotion = false
        @Implementation(minSdk = 29)
        override fun startForeground(id: Int, notification: Notification, type: Int) {
            if (!refusePromotion) super.startForeground(id, notification, type)
        }
    }
    private fun store(): PatchJobStore {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "patch-job.json").delete()
        return PatchJobStore(context.filesDir).also {
            PatchJobStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, it)
        }
    }

    @Test fun foregroundLeaseAndNotificationOutliveTheScreenAndEndOnFailure() {
        val store = store()
        val job = store.prepare()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = Uri.parse("content://test/input")
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(source, object : InputStream() {
            override fun read(): Int { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return -1 }
        })
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, PatchJobService::class.java).setData(source).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val worker = PatchJobService::class.java.getDeclaredField("task").apply { isAccessible = true }.get(service) as Future<*>
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        try {
            assertTrue(wakeLock.isHeld)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            val notice = shadowOf(service).lastForegroundNotification
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(job.targetId, shadowOf(notice.contentIntent).savedIntent.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
            assertEquals(PatchJobService.CANCEL, shadowOf(notice.actions.single().actionIntent).savedIntent.action)
            service.onTaskRemoved(Intent())
            assertTrue(wakeLock.isHeld)
            assertTrue(store.state.value.active)
        } finally {
            release.countDown()
            worker.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(wakeLock.isHeld)
            assertEquals(PatchJobStatus.FAILURE, store.state.value.status)
            assertTrue(shadowOf(service).isForegroundStopped)
            controller.destroy()
        }
    }

    @Test fun completionNotificationReopensTheSameTargetAndCannotCancelACompletedJob() {
        val store = store()
        val job = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, message = "Ready to install") }
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        val method = PatchJobService::class.java.getDeclaredMethod("notification", PatchJobState::class.java).apply { isAccessible = true }
        val notice = method.invoke(service, store.state.value) as Notification
        assertEquals("${PatchTargets.default.displayName} is ready to install", notice.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, notice.flags and Notification.FLAG_ONGOING_EVENT)
        assertNull(notice.actions)
        assertEquals(job.targetId, shadowOf(notice.contentIntent).savedIntent.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        service.onStartCommand(Intent(service, PatchJobService::class.java).setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertEquals(PatchJobStatus.SUCCESS, store.state.value.status)
        controller.destroy()
    }
    @Test fun cancellationReleasesForegroundLeaseAndKeepsTheVisibleScreen() {
        val store = store()
        val job = store.prepare()
        val entered = CountDownLatch(1)
        val source = Uri.parse("content://test/cancel")
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(source, object : InputStream() {
            override fun read(): Int { entered.countDown(); CountDownLatch(1).await(); return -1 }
        })
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, PatchJobService::class.java).setData(source).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val wake = ShadowPowerManager.getLatestWakeLock()
        service.onStartCommand(Intent(service, PatchJobService::class.java).setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, job.id), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PatchJobStatus.CANCELLED, store.state.value.status)
        assertEquals(PatchJobStatus.CANCELLED, PatchJobStore(RuntimeEnvironment.getApplication().filesDir).state.value.status)
        assertFalse(wake.isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertFalse(screen.get().isFinishing)
        screen.pause().stop().destroy()
        controller.destroy()
    }

    @Test fun androidTimeoutPersistsInterruptedAndReleasesTheLease() {
        val store = store()
        val job = store.prepare()
        val entered = CountDownLatch(1)
        val source = Uri.parse("content://test/timeout")
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(source, object : InputStream() {
            override fun read(): Int { entered.countDown(); CountDownLatch(1).await(); return -1 }
        })
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, PatchJobService::class.java).setData(source).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PatchJobStatus.INTERRUPTED, store.state.value.status)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    @Test @Config(sdk = [34]) fun prepareFailureAlertsOnlyWhenHiddenAndNotificationsAreAllowed() {
        val store = store()
        val job = store.prepare()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        val notify = PatchJobService::class.java.getDeclaredMethod("notifyState", PatchJobState::class.java).apply { isAccessible = true }
        val manager = service.getSystemService(android.app.NotificationManager::class.java)
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        store.change(job.id) { it.copy(status = PatchJobStatus.FAILURE, message = "Choose a supported stock APK.") }
        notify.invoke(service, store.state.value)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        screen.pause().stop()
        notify.invoke(service, store.state.value)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Choose a supported stock APK.", shadowOf(manager).allNotifications.single().extras.getString(Notification.EXTRA_TEXT))
        manager.cancelAll()
        shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notify.invoke(service, store.state.value)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        screen.destroy(); controller.destroy()
    }

    private fun runningJob(store: PatchJobStore): PatchJobState {
        val prepared = store.prepare()
        File(store.work(prepared.id), "stock.apk").writeBytes(byteArrayOf(1))
        store.change(prepared.id) { it.copy(status = PatchJobStatus.READY, stock = "stock.apk") }
        return store.patch("hash", listOf("patch"))
    }

    @Test @Config(sdk = [34]) fun dismissingPipAndRemovingTheTaskKeepRunningWorkInTheForeground() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ ->
            entered.countDown()
            release.await()
            store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS) }
        }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val worker = PatchJobService::class.java.getDeclaredField("task").apply { isAccessible = true }.get(service) as Future<*>
        val wake = ShadowPowerManager.getLatestWakeLock()
        org.robolectric.shadows.ShadowProcess.clearKilledProcesses()
        try {
            screen.get().onPictureInPictureModeChanged(true, android.content.res.Configuration())
            screen.pause().stop().destroy()
            service.onTaskRemoved(Intent(service, PatchActivity::class.java))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
            assertEquals(PatchJobStatus.RUNNING, PatchJobStore.decode(File(service.filesDir, "patch-job.json").readText()).status)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            assertFalse(shadowOf(service).isForegroundStopped)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(wake.isHeld)
            assertFalse(worker.isCancelled)
            assertFalse(org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
            val manager = service.getSystemService(android.app.NotificationManager::class.java)
            assertTrue(shadowOf(manager).allNotifications.single().flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            release.countDown(); worker.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
        assertEquals(PatchJobStatus.SUCCESS, store.state.value.status)
    }

    @Test @Config(sdk = [34]) fun aDemotedForegroundLeaseIsRestoredBeforeContinuingTheHeartbeat() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ -> entered.countDown(); CountDownLatch(1).await() }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            service.stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
            // Robolectric 4.13 does not clear the type when the framework demotes a service.
            org.robolectric.shadows.ShadowService::class.java.getDeclaredField("foregroundServiceType")
                .apply { isAccessible = true }.setInt(shadowOf(service), 0)
            assertEquals(0, service.foregroundServiceType)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            assertFalse(shadowOf(service).isForegroundStopped)
            assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally { controller.destroy() }
    }

    @Test @Config(sdk = [34]) fun unexpectedServiceDestructionNeverSchedulesASelfKill() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ ->
            entered.countDown()
            try { while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
            finally { exited.countDown() }
        }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        org.robolectric.shadows.ShadowProcess.clearKilledProcesses()
        try {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            assertEquals(PatchJobStatus.INTERRUPTED, store.state.value.status)
            assertEquals(PatchJobStatus.INTERRUPTED, PatchJobStore.decode(File(service.filesDir, "patch-job.json").readText()).status)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertFalse(org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
        } finally { release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS)) }
    }

    @Test @Config(sdk = [34]) fun destroyingAnIdleServiceDoesNotInterruptANewlyRequestedJob() {
        val store = store()
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        val requested = runningJob(store)
        controller.destroy()
        assertEquals(requested, store.state.value)
        assertEquals(PatchJobStatus.RUNNING, PatchJobStore.decode(File(controller.get().filesDir, "patch-job.json").readText()).status)
        screen.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], shadows = [RefusingForegroundService::class])
    fun silentForegroundRefusalNeverStartsUnprotectedWork() {
        val store = store()
        val job = runningJob(store)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        Shadow.extract<RefusingForegroundService>(service).refusePromotion = true
        service.runJob = { _, _ -> fail("No worker may run without a foreground lease") }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertEquals(PatchJobStatus.FAILURE, store.state.value.status)
        assertEquals(0, service.foregroundServiceType)
        controller.destroy()
    }

    @Test @Config(sdk = [34], shadows = [RefusingForegroundService::class])
    fun refusedLeaseRecoveryPersistsInterruptionWithoutASelfKill() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ ->
            entered.countDown()
            try { while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
            finally { exited.countDown() }
        }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        org.robolectric.shadows.ShadowProcess.clearKilledProcesses()
        try {
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
            ShadowService::class.java.getDeclaredField("foregroundServiceType")
                .apply { isAccessible = true }.setInt(shadowOf(service), 0)
            Shadow.extract<RefusingForegroundService>(service).refusePromotion = true
            service.onTaskRemoved(Intent())
            assertEquals(PatchJobStatus.INTERRUPTED, store.state.value.status)
            assertEquals(PatchJobStatus.INTERRUPTED, PatchJobStore.decode(File(service.filesDir, "patch-job.json").readText()).status)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            assertFalse(org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
        } finally {
            release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS)); controller.destroy()
        }
    }

    @Test @Config(sdk = [34]) fun runningCancelWaitsForUncooperativeWorkAndNeverKillsAResumedScreen() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ ->
            entered.countDown()
            try {
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
            } finally { exited.countDown() }
        }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        org.robolectric.shadows.ShadowProcess.clearKilledProcesses()
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            service.onStartCommand(Intent(service, PatchJobService::class.java).setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, job.id), 0, 2)
            assertEquals(PatchJobStatus.CANCELLED, store.state.value.status)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertTrue(shadowOf(service).isForegroundStopped)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertFalse(org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
            assertFalse(screen.get().isFinishing)
            val retry = store.patch("hash", listOf("patch"))
            service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, retry.id), 0, 3)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            service.onStartCommand(Intent(service, PatchJobService::class.java).setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, job.id), 0, 4)
            assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            service.onStartCommand(Intent(service, PatchJobService::class.java).setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, retry.id), 0, 5)
            assertEquals(PatchJobStatus.CANCELLED, store.state.value.status)
            screen.pause().stop()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertTrue(org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
            assertEquals(PatchJobStatus.CANCELLED, PatchJobStore.decode(File(service.filesDir, "patch-job.json").readText()).status)
        } finally {
            release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS))
            screen.destroy(); controller.destroy()
        }
    }

    @Test @Config(sdk = [34]) fun runningTimeoutPersistsInterruptedBeforeAnyFallbackKill() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ -> entered.countDown(); CountDownLatch(1).await() }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PatchJobStatus.INTERRUPTED, store.state.value.status)
        assertEquals(PatchJobStatus.INTERRUPTED, PatchJobStore.decode(File(service.filesDir, "patch-job.json").readText()).status)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    @Test @Config(sdk = [34]) fun oneHourDeadlineAlsoUsesInterruptedAndUnknownProgressOmitsTheProgressExtra() {
        val store = store()
        val job = runningJob(store)
        val entered = CountDownLatch(1)
        val controller = Robolectric.buildService(PatchJobService::class.java).create()
        val service = controller.get()
        service.runJob = { _, _ -> entered.countDown(); CountDownLatch(1).await() }
        service.onStartCommand(Intent(service, PatchJobService::class.java).putExtra(PatchJobService.JOB_ID, job.id), 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val notice = shadowOf(service).lastForegroundNotification
        assertEquals(0, notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notice.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("0%"))
        assertEquals("Loading the patch bundle · 0:00", notice.extras.getString(Notification.EXTRA_TEXT))
        val notification = PatchJobService::class.java.getDeclaredMethod("notification", PatchJobState::class.java).apply { isAccessible = true }
        // A measured zero is still nothing to show: the bar appears with the first real movement.
        val zero = notification.invoke(service, store.state.value.copy(progress = PatchProgress(PatchPhase.APPLY_PATCHES, 0.0, patchTotal = 23),
            elapsedMs = 61_000)) as Notification
        assertEquals(0, zero.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals("Applying 23 patches · 1:01", zero.extras.getString(Notification.EXTRA_TEXT))
        val moving = notification.invoke(service, store.state.value.copy(progress = PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23),
            elapsedMs = 134_000)) as Notification
        assertEquals(100, moving.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(30, moving.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals("Applying patches · 7 of 23 · 2:14", moving.extras.getString(Notification.EXTRA_TEXT))
        val deadline = PatchJobService::class.java.getDeclaredField("deadline").apply { isAccessible = true }.get(service) as Runnable
        deadline.run()
        assertEquals(PatchJobStatus.INTERRUPTED, store.state.value.status)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        controller.destroy()
    }

}
