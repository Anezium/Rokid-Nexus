package com.anezium.rokidbus.phone

import android.content.Context
import android.Manifest
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteInputSessionOpen
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE, false))
        settings.autoOpen = true
        assertTrue(YoutubeKeyboardSettings(context).autoOpen)
        settings.autoOpen = false
        assertFalse(YoutubeKeyboardSettings(context).autoOpen)
        assertFalse(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false))
    }

    @Test
    fun `enabled preference opens only the two exact YouTube packages`() {
        val settings = YoutubeKeyboardSettings(context).apply { autoOpen = true }
        assertTrue(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE, false))
        assertTrue(settings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false))
        for (other in listOf(null, "", YoutubeSetupContract.MICROG, "com.example.editor",
            "app.morphe.android.youtube.other", "app.morphe.android.youtube.rokidtest.fake")) {
            assertFalse(settings.shouldRequestKeyboard(other, false))
        }
    }

    @Test
    fun `changing the preference affects the existing bridge settings instance`() {
        val bridgeSettings = YoutubeKeyboardSettings(context)
        YoutubeKeyboardSettings(context).autoOpen = true
        assertTrue(bridgeSettings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false))
        YoutubeKeyboardSettings(context).autoOpen = false
        assertFalse(bridgeSettings.shouldRequestKeyboard(YoutubeSetupContract.YOUTUBE_TEST, false))
    }

    @Test
    fun `turning YouTube auto keyboard off preserves native editable surface requests`() {
        val settings = YoutubeKeyboardSettings(context)
        assertTrue(settings.shouldRequestKeyboard("com.anezium.rokidbus.glasses", true))
        settings.autoOpen = true
        assertTrue(settings.shouldRequestKeyboard("com.anezium.rokidbus.glasses", true))
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
