package com.anezium.rokidbus.media.session

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class MediaSessionMonitorTest {
    @Test
    fun `a loading session is observed until its first metadata arrives without polling`() {
        val app = RuntimeEnvironment.getApplication()
        val setting = "enabled_notification_listeners"
        val previous = Settings.Secure.getString(app.contentResolver, setting)
        val component = ComponentName(app, MediaDeckNotificationListenerService::class.java)
        Settings.Secure.putString(app.contentResolver, setting, component.flattenToString())
        val session = MediaSession(app, "loading-player")
        val controller = session.controller
        val controllerShadow = shadowOf(controller)
        controllerShadow.setPackageName("com.example.player")
        val manager = shadowOf(app.getSystemService(MediaSessionManager::class.java))
        manager.addController(controller)
        val observed = mutableListOf<MediaDeckObservation>()
        val monitor = MediaSessionMonitor(app) { state, _ -> observed += state }
        try {
            monitor.start()
            assertEquals(MediaDeckObservation.NoSession, observed.last())
            assertFalse(controllerShadow.callbacks.isEmpty())
            assertEquals(MediaDeckSendResult.MOVED, monitor.resumeIdle())

            val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Ready now").build()
            controllerShadow.setMetadata(metadata)
            controllerShadow.executeOnMetadataChanged(metadata)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Ready now", (observed.last() as MediaDeckObservation.Session).track.title)
        } finally {
            monitor.stop()
            assertTrue(controllerShadow.callbacks.isEmpty())
            manager.clearControllers()
            session.release()
            Settings.Secure.putString(app.contentResolver, setting, previous)
        }
    }
}
