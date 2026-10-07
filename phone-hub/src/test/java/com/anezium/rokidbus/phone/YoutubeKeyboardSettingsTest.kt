package com.anezium.rokidbus.phone

import android.content.Context
import android.Manifest
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteInputSessionOpen
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class YoutubeKeyboardSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `auto keyboard defaults off and remembers both choices`() {
        val settings = YoutubeKeyboardSettings(context)
        assertFalse(settings.autoOpen)
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE, false, 1))
        settings.autoOpen = true
        assertTrue(YoutubeKeyboardSettings(context).autoOpen)
        settings.autoOpen = false
        assertFalse(YoutubeKeyboardSettings(context).autoOpen)
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 1))
    }

    @Test
    fun `enabled preference opens only the two exact YouTube packages`() {
        val settings = YoutubeKeyboardSettings(context).apply { autoOpen = true }
        assertTrue(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE, false, 1))
        assertTrue(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 1))
        for (other in listOf(null, "", YoutubeSetupContract.MICROG, "com.example.editor",
            "app.morphe.android.youtube.other", "app.morphe.android.youtube.rokidtest.fake")) {
            assertFalse(settings.shouldRequestKeyboard(other, false, 1))
        }
    }

    @Test
    fun `changing the preference affects the existing bridge settings instance`() {
        val bridgeSettings = YoutubeKeyboardSettings(context)
        YoutubeKeyboardSettings(context).autoOpen = true
        assertTrue(bridgeSettings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 1))
        YoutubeKeyboardSettings(context).autoOpen = false
        assertFalse(bridgeSettings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 1))
    }

    @Test
    fun `turning YouTube auto keyboard off preserves native editable surface requests`() {
        val settings = YoutubeKeyboardSettings(context)
        assertTrue(settings.shouldRequestKeyboard("com.anezium.rokidbus.glasses", true, 1))
        settings.autoOpen = true
        assertTrue(settings.shouldRequestKeyboard("com.anezium.rokidbus.glasses", true, 1))
    }

    @Test
    fun `non editable YouTube focus does not open the keyboard`() {
        val settings = YoutubeKeyboardSettings(context).apply { autoOpen = true }
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 0))
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 0x80000))
        assertTrue(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false, 177))
    }

    @Test
    fun `transient sessions settle before opening and cancelled requests never open`() {
        ShadowSettings.setCanDrawOverlays(true)
        val settings = YoutubeKeyboardSettings(context).apply { autoOpen = true }
        val loop = shadowOf(Looper.getMainLooper())
        PhoneCoreRemoteBridge(context, { null }, { false }, { true }, { false }).use { bridge ->
            fun open(id: String) {
                bridge.handleRemote(BusEnvelope(path = RemoteInputContract.SESSION_PATH,
                    payload = RemoteInputContract.encodeSessionOpen(RemoteInputSessionOpen(
                        sessionId = id, packageName = YoutubeSetupContract.YOUTUBE_TEST,
                        inputType = 177, imeOptions = 3, sensitive = false,
                    ))))
            }
            open("youtube-session-0001")
            loop.idleFor(Duration.ofMillis(200))
            assertNull(shadowOf(context).nextStartedActivity)
            open("youtube-session-0002")
            loop.idleFor(Duration.ofMillis(300))
            assertNull(shadowOf(context).nextStartedActivity)
            loop.idleFor(Duration.ofMillis(200))
            assertEquals(RemoteInputActivity::class.java.name,
                shadowOf(context).nextStartedActivity.component?.className)
            open("youtube-session-0003")
            settings.autoOpen = false
            loop.idleFor(Duration.ofMillis(500))
            assertNull(shadowOf(context).nextStartedActivity)
            settings.autoOpen = true
            open("youtube-session-0004")
            bridge.onLinkStateChanged(false, false)
            loop.idleFor(Duration.ofMillis(500))
            assertNull(shadowOf(context).nextStartedActivity)
        }
    }

    @Test
    fun `bridge publishes the owner choice to the keyboard screen on each new session`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = YoutubeKeyboardSettings(context)
        PhoneCoreRemoteBridge(context, { null }, { false }, { true }, { false }).use { bridge ->
            fun open(packageName: String): RemoteInputTransportState {
                val payload = RemoteInputContract.encodeSessionOpen(RemoteInputSessionOpen(
                    sessionId = "youtube-session-0001", packageName = packageName,
                    inputType = 1, imeOptions = 3, sensitive = false,
                ))
                assertTrue(bridge.handleRemote(BusEnvelope(path = RemoteInputContract.SESSION_PATH, payload = payload)))
                return RemoteInputPhoneContract.parseState(shadowOf(context).broadcastIntents.last {
                    it.action == RemoteInputPhoneContract.ACTION_STATE
                })!!
            }
            assertFalse(open(YoutubeSetupContract.YOUTUBE_TEST).keyboardRequested)
            settings.autoOpen = true
            val enabled = open(YoutubeSetupContract.YOUTUBE_TEST)
            assertTrue(enabled.keyboardRequested)
            assertTrue(enabled.fieldActive)
            assertTrue(RemoteInputViewState.from(enabled).keyboardRequested)
            assertFalse(open("com.example.editor").keyboardRequested)
            settings.autoOpen = false
            assertFalse(open(YoutubeSetupContract.YOUTUBE_TEST).keyboardRequested)
        }
    }
}
