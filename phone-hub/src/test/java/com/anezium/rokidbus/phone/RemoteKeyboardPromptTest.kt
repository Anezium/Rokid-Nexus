package com.anezium.rokidbus.phone

import android.Manifest
import android.app.NotificationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** The notification fallback for a keyboard session, which needs a runtime grant only from Android 13. */
@RunWith(RobolectricTestRunner::class)
class RemoteKeyboardPromptTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @After
    fun tearDown() {
        RemoteKeyboardPrompt.dismiss(app)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prompts() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    @Config(sdk = [32])
    fun `on Android 12L the prompt needs no notification grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RemoteKeyboardPrompt.bringForward(app)
        assertEquals(1, prompts().size)
    }

    @Test
    @Config(sdk = [33])
    fun `on Android 13 the prompt needs its runtime grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RemoteKeyboardPrompt.bringForward(app)
        assertTrue(prompts().isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RemoteKeyboardPrompt.bringForward(app)
        assertEquals(1, prompts().size)
    }
}
