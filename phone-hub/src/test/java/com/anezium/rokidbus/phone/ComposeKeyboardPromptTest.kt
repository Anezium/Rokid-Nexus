package com.anezium.rokidbus.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import com.anezium.rokidbus.shared.ComposeTypeStart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * The phone keyboard for a glasses Type capture on every Android the phone hub supports (API 30
 * and up): opened by itself when it can be, else a notification, which needs a runtime grant only
 * from Android 13.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeKeyboardPromptTest {
    private val app = RuntimeEnvironment.getApplication()
    private val request = ComposeTypeStart("cap-1", "", 500)

    @After
    fun tearDown() {
        ComposeKeyboardPrompt.close(app, request.captureId)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prompts() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    @Config(sdk = [30])
    fun `on Android 11 without the overlay permission the keyboard is offered by notification`() {
        ShadowSettings.setCanDrawOverlays(false)
        ComposeKeyboardPrompt.open(app, request)
        assertEquals(1, prompts().size)
    }

    @Test
    @Config(sdk = [32])
    fun `on Android 12L with the phone locked the keyboard is offered by notification`() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsKeyguardLocked(true)
        ComposeKeyboardPrompt.open(app, request)
        assertEquals(1, prompts().size)
    }

    @Test
    @Config(sdk = [33])
    fun `on Android 13 the notification needs its runtime grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ComposeKeyboardPrompt.open(app, request)
        assertTrue(prompts().isEmpty())
        ComposeKeyboardPrompt.close(app, request.captureId)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ComposeKeyboardPrompt.open(app, request)
        assertEquals(1, prompts().size)
    }
}
