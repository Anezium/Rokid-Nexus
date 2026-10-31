package com.anezium.rokidbus.media

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusMedia
import com.anezium.rokidbus.client.plugin.NexusMediaAnchor
import com.anezium.rokidbus.media.session.FakeHub
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckObservation
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.MediaPlaybackPhase
import com.anezium.rokidbus.media.session.SESSION_B
import com.anezium.rokidbus.media.session.observed
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class MediaDeckRuntimeTest {
    private class FakeHost : MediaDeckRuntimeHost {
        val cards = mutableListOf<NexusCard>()
        val media = mutableListOf<NexusMedia>()
        val anchors = mutableListOf<NexusMediaAnchor>()
        var hidden = 0

        override fun supportsImage(): Boolean = false

        override fun sendCard(card: NexusCard, show: Boolean) {
            cards += card
        }

        override fun sendMedia(media: NexusMedia, imageBytes: ByteArray?, show: Boolean) {
            this.media += media
        }

        override fun updateMediaAnchor(contentKey: String, anchor: NexusMediaAnchor) {
            anchors += anchor
        }

        override fun hideSurface() {
            hidden++
        }
    }

    private val fake = FakeHub()
    private val host = FakeHost()
    private val runtime = MediaDeckRuntime(RuntimeEnvironment.getApplication(), host, fake.hub)

    private fun press(keyCode: Int) = runtime.input(NexusInputEvent("media", keyCode, KeyEvent.ACTION_DOWN))

    @Test
    fun `closing the surface leaves an enabled activity watching`() {
        fake.monitor.live = observed()
        runtime.open()
        fake.hub.subscribe(MediaDeckSubscriber.ACTIVITY) { _, _, _ -> }

        press(KeyEvent.KEYCODE_BACK)
        assertFalse(runtime.isOpen)
        assertEquals(1, host.hidden)
        assertTrue(fake.monitor.started)
        assertEquals(setOf(MediaDeckSubscriber.ACTIVITY), fake.hub.subscribers)
    }

    @Test
    fun `closing the surface alone stops watching`() {
        fake.monitor.live = observed()
        runtime.open()
        runtime.close()
        assertFalse(fake.monitor.started)
        assertFalse(fake.hub.isMonitoring)
    }

    @Test
    fun `the status card returns whenever there is no session to draw`() {
        fake.monitor.live = observed()
        runtime.open()
        assertEquals("Teardrop", host.media.last().mediaTitle)

        fake.monitor.emit(MediaDeckObservation.NoSession)
        assertEquals("status:idle", host.cards.last().contentKey)

        fake.monitor.emit(observed())
        fake.monitor.emit(MediaDeckObservation.AccessRequired)
        assertEquals("status:access", host.cards.last().contentKey)
        assertEquals("Media access was turned off.", host.cards.last().lines.first())

        fake.monitor.emit(MediaDeckObservation.Unavailable)
        assertEquals("status:unavailable", host.cards.last().contentKey)
    }

    @Test
    fun `a tap asks for the opposite of what is drawn, on the session drawn`() {
        fake.monitor.live = observed()
        runtime.open()
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(observed() to MediaDeckCommand.PAUSE, fake.monitor.sent.last())

        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PAUSED))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(observed(phase = MediaPlaybackPhase.PAUSED) to MediaDeckCommand.PLAY, fake.monitor.sent.last())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(MediaDeckCommand.NEXT, fake.monitor.sent.last().second)
        assertEquals(3, fake.monitor.sent.size)
    }

    @Test
    fun `a swipe never reaches a session the surface has not drawn`() {
        fake.monitor.live = observed()
        runtime.open()

        // Another player took over; its callback has not arrived yet.
        fake.monitor.live = observed(identity = SESSION_B)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(fake.monitor.sent.isEmpty())

        // With nothing playing, a tap has nothing to act on.
        fake.monitor.emit(MediaDeckObservation.NoSession)
        press(KeyEvent.KEYCODE_ENTER)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a buffering session shows as playing on a standing clock`() {
        fake.monitor.live = observed(phase = MediaPlaybackPhase.PLAYING)
        fake.monitor.liveAdvancing = false
        runtime.open()

        val anchor = host.media.last().anchor
        assertTrue(anchor.playing)
        assertEquals(0f, anchor.playbackSpeed)

        // Playback resumes: the same item, the clock starts.
        fake.monitor.liveAdvancing = true
        fake.monitor.refresh()
        assertEquals(1f, host.anchors.last().playbackSpeed)
    }
}
