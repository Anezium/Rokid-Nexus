package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class AmbientForegroundWindowAdapterTest {
    class WindowService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private lateinit var service: WindowService

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            ResolveInfo().apply {
                isDefault = true
                activityInfo = ActivityInfo().apply {
                    packageName = HOME
                    name = "$HOME.HomeActivity"
                    applicationInfo = ApplicationInfo().apply { packageName = HOME }
                }
            },
        )
        service = Robolectric.setupService(WindowService::class.java)
        AmbientForeground.onServiceConnected(app)
        AmbientPreferences.onLinkLost()
        AmbientPreferences.onPolicy(
            AmbientPreferencesContract.policyPayload(AmbientPolicy(1, mapOf(READER to ForegroundAmbientMode.NOTHING))),
        )
    }

    @After
    fun tearDown() {
        AmbientForeground.onServiceDestroyed()
        AmbientPreferences.onLinkLost()
        AmbientPreferences.onPolicy(AmbientPreferencesContract.policyPayload(AmbientPolicy(1, emptyMap())))
        AmbientPreferences.onLinkLost()
    }

    @Test
    fun `partial snapshots retain the saved native policy when the winning root or package is unreadable`() {
        for (priority in 0..2) {
            for (missingRoot in listOf(true, false)) {
                sample(window(READER))
                assertEquals(Foreground.App(READER), AmbientForeground.current)
                assertEquals(ForegroundAmbientMode.NOTHING, AmbientForeground.mode())

                sample(
                    window(HOME, layer = 3),
                    window(null, missingRoot, focused = priority == 0, active = priority == 1, layer = if (priority == 2) 4 else 1),
                )
                assertEquals(Foreground.App(READER), AmbientForeground.current)
                assertEquals(ForegroundAmbientMode.NOTHING, AmbientForeground.mode())
                assertEquals(ActivityPresentation.HIDDEN, applyForegroundMode(ActivityPresentation.PANEL, AmbientForeground.mode()))

                sample(
                    window(HOME, layer = 3),
                    window(SETTINGS, focused = priority == 0, active = priority == 1, layer = if (priority == 2) 4 else 1),
                )
                assertEquals(Foreground.App(SETTINGS), AmbientForeground.current)
                assertEquals(ForegroundAmbientMode.SIGNALS, AmbientForeground.mode())
            }
        }
    }

    @Test
    fun `unreadable rear applications and accessibility overlays still allow home to win`() {
        sample(window(READER))
        sample(
            window(HOME, focused = true),
            window(null, missingRoot = true, active = true, layer = 9),
            window(null, missingRoot = true, focused = true, active = true, layer = 10, type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY),
        )
        assertEquals(Foreground.Home, AmbientForeground.current)
        assertEquals(ForegroundAmbientMode.RESIDENTS, AmbientForeground.mode())
    }

    private fun sample(vararg windows: AccessibilityWindowInfo) {
        shadowOf(service).setWindows(windows.toList())
        try {
            AmbientForeground.onWindowsChanged(service)
        } finally {
            shadowOf(service).setWindows(emptyList())
            windows.forEach { it.recycle() }
        }
    }

    private fun window(
        packageName: String?,
        missingRoot: Boolean = false,
        focused: Boolean = false,
        active: Boolean = false,
        layer: Int = 0,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    ): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).apply {
            setType(type)
            setFocused(focused)
            setActive(active)
            setLayer(layer)
            if (!missingRoot) setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
        }
    }

    private companion object {
        const val HOME = "com.rokid.os.launcher"
        const val READER = "com.example.reader"
        const val SETTINGS = "com.android.settings"
    }
}
