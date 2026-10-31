package com.anezium.rokidbus.media.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeckMonitorHubTest {
    private val fake = FakeHub()
    private val hub = fake.hub
    private val heard = mutableListOf<Pair<MediaDeckState, Boolean>>()
    private val record = MediaDeckStateListener { state, _, changed -> heard += state to changed }

    @Test
    fun `closing the surface leaves the monitor running for the activity`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.IMMERSION, record)
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        assertEquals(1, fake.monitor.starts)

        hub.unsubscribe(MediaDeckSubscriber.IMMERSION)
        assertTrue(fake.monitor.started)
        assertTrue(hub.isMonitoring)
        assertEquals(setOf(MediaDeckSubscriber.ACTIVITY), hub.subscribers)

        hub.unsubscribe(MediaDeckSubscriber.ACTIVITY)
        assertFalse(fake.monitor.started)
        assertEquals(1, fake.monitor.stops)
        assertTrue(hub.state is MediaDeckState.Unknown)
    }

    @Test
    fun `turning the activity off leaves an open surface watching`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        hub.subscribe(MediaDeckSubscriber.IMMERSION, record)

        hub.unsubscribe(MediaDeckSubscriber.ACTIVITY)
        assertTrue(fake.monitor.started)
        assertTrue(hub.state is MediaDeckState.Active)
    }

    @Test
    fun `a position tick reaches the surface without a new revision`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.IMMERSION, record)
        val revision = hub.state.revision
        heard.clear()

        fake.monitor.emit(observed(), positionMs = 42_000L)
        assertEquals(listOf(false), heard.map { it.second })
        assertEquals(revision, hub.state.revision)
        assertEquals(42_000L, hub.snapshot?.positionMs)
    }

    @Test
    fun `a late callback after the last subscriber left brings nothing back`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        hub.unsubscribe(MediaDeckSubscriber.ACTIVITY)
        heard.clear()

        // The real monitor stops reporting; a callback already queued must still be ignored.
        fake.monitor.start()
        assertTrue(heard.isEmpty())
        assertTrue(hub.state is MediaDeckState.Unknown)
    }

    @Test
    fun `a command reaches exactly the session shown at the revision shown`() {
        fake.monitor.live = observed(identity = SESSION_A)
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)

        assertEquals(MediaDeckControlOutcome.DONE, hub.control(shown, MediaDeckCommand.PAUSE))
        assertEquals(listOf(observed(identity = SESSION_A) to MediaDeckCommand.PAUSE), fake.monitor.sent)
    }

    @Test
    fun `a command decided on another session is stale and never sent`() {
        fake.monitor.live = observed(identity = SESSION_A)
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)

        fake.monitor.emit(observed(identity = SESSION_B))
        val outcome = hub.control(shown, MediaDeckCommand.NEXT)
        assertEquals(MediaDeckControlOutcome.STALE_SESSION, outcome)
        assertEquals(MediaDeckControlOutcome.Status.STALE, outcome.status)
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a command decided before the session moved on is stale and never sent`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)

        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PAUSED))
        assertEquals(MediaDeckControlOutcome.STALE_REVISION, hub.control(shown, MediaDeckCommand.PAUSE))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a change whose callback has not arrived yet makes the command stale`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)

        // The player already paused; its callback is still on the way.
        fake.monitor.live = observed(phase = MediaPlaybackPhase.PAUSED)
        assertEquals(MediaDeckControlOutcome.STALE_REVISION, hub.control(shown, MediaDeckCommand.PAUSE))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `without a session or access a command is rejected and nothing else is picked`() {
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        assertEquals(MediaDeckControlOutcome.NO_SESSION, hub.control(null, MediaDeckCommand.PLAY))

        fake.monitor.emit(MediaDeckObservation.AccessRequired)
        val revoked = hub.control(null, MediaDeckCommand.PLAY)
        assertEquals(MediaDeckControlOutcome.ACCESS_REQUIRED, revoked)
        assertEquals(MediaDeckControlOutcome.Status.REJECTED, revoked.status)

        // A session appearing later is never the target of a command decided on none.
        fake.monitor.live = observed()
        assertEquals(MediaDeckControlOutcome.ACCESS_REQUIRED, hub.control(null, MediaDeckCommand.PLAY))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `with nobody watching nothing is known and nothing is sent`() {
        fake.monitor.live = observed()
        assertEquals(MediaDeckControlOutcome.NO_SESSION, hub.control(null, MediaDeckCommand.NEXT))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `play and pause are the desired state the revision offered`() {
        fake.monitor.live = observed(phase = MediaPlaybackPhase.PLAYING)
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)

        assertEquals(MediaDeckControlOutcome.NOT_OFFERED, hub.control(shown, MediaDeckCommand.PLAY))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a refused command is reported once and never retried`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.ACTIVITY, record)
        val shown = MediaDeckTarget.of(hub.state)
        fake.monitor.sendResult = MediaDeckSendResult.FAILED

        assertEquals(MediaDeckControlOutcome.FAILED, hub.control(shown, MediaDeckCommand.NEXT))
        fake.monitor.sendResult = null
        fake.monitor.refresh()
        assertTrue(fake.monitor.sent.isEmpty())
    }
}
