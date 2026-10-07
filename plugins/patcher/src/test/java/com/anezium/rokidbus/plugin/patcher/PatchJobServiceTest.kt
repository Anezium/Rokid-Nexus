package com.anezium.rokidbus.plugin.patcher

import android.app.Notification
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
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatchJobServiceTest {
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

}
