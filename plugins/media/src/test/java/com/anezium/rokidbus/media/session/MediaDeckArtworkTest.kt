package com.anezium.rokidbus.media.session

import com.anezium.rokidbus.shared.LauncherArtwork
import org.junit.Assert.*
import org.junit.Test

class MediaDeckArtworkTest {
    @Test fun `clearing a cover notifies subscribers and stale decoding cannot restore it`() {
        lateinit var observedListener: MediaDeckMonitorListener
        val hub = MediaDeckMonitorHub { listener ->
            observedListener = listener
            FakeMonitor(listener)
        }
        val decodes = mutableListOf<(LauncherArtwork?) -> Unit>()
        hub.loadArtwork = { _, done -> decodes += done }
        val changes = mutableListOf<Boolean>()
        hub.subscribe(MediaDeckSubscriber.PAGE, MediaDeckStateListener { _, _, changed -> changes += changed })
        val session = observed()
        val snapshot = snapshotOf(session).copy(artworkUri = "content://player/cover")
        observedListener.onObserved(session, snapshot)
        val cover = LauncherArtwork(1, 1, "gA==")
        decodes.last()(cover)
        assertEquals(cover, hub.artwork)
        changes.clear()
        observedListener.onObserved(session, snapshot.copy(artworkUri = ""))
        assertNull(hub.artwork)
        assertEquals(listOf(true), changes)
        decodes.first()(cover)
        assertNull(hub.artwork)
        hub.unsubscribe(MediaDeckSubscriber.PAGE)
        decodes.last()(cover)
        assertNull(hub.artwork)
    }
}
