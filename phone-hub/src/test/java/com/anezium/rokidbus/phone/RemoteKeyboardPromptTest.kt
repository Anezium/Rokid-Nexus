package com.anezium.rokidbus.phone

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification fallback for a keyboard session needs a runtime grant only from Android 13.
 * The prompt itself reads string resources, which phone-hub unit tests do not load, so the gate
 * is checked on its own.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteKeyboardPromptTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the prompt needs no notification grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(RemoteKeyboardPrompt.notificationGrantMissing(app))
    }

    @Test
    @Config(sdk = [32])
    fun `on Android 12L the prompt needs no notification grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(RemoteKeyboardPrompt.notificationGrantMissing(app))
    }

    @Test
    @Config(sdk = [33])
    fun `on Android 13 the prompt needs its runtime grant`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(RemoteKeyboardPrompt.notificationGrantMissing(app))

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(RemoteKeyboardPrompt.notificationGrantMissing(app))
    }
}
