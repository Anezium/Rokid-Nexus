package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityActivation
import com.anezium.rokidbus.client.plugin.NexusActivityPersistence
import com.anezium.rokidbus.client.plugin.NexusActivityCommandStatus
import com.anezium.rokidbus.client.plugin.NexusPageDestination
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.FakeHub
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckObservation
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.MediaPlaybackPhase
import com.anezium.rokidbus.media.session.SESSION_B
import com.anezium.rokidbus.media.session.observed
import com.anezium.rokidbus.media.session.track
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeckActivityPublisherTest {
    private class FakePort : MediaDeckActivityPort {
        override var approved = false
        var held = 0
        var released = 0
        val calls = mutableListOf<String>()
        val activities = mutableListOf<NexusActivity>()
        var result = NexusSdkResult.SENT

        val holding: Boolean get() = held > released

        override fun start(activity: NexusActivity): NexusSdkResult {
            calls += "start"
            activities += activity
            return result
        }

        override fun update(activity: NexusActivity, significant: Boolean): NexusSdkResult {
            calls += if (significant) "update!" else "update"
            activities += activity
            return result
        }

        override fun end(): NexusSdkResult {
            calls += "end"
            return result
        }

        override fun hold(): Boolean {
            held++
            return true
        }

        override fun release() {
            released++
        }
    }

    private val fake = FakeHub()
    private val hub = fake.hub
    private val port = FakePort()
    private var clock = 10_000L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private val remembered = mutableListOf<Boolean>()

    private fun publisher(onHubAtStart: Boolean = false) = MediaDeckActivityPublisher(
        hub = hub,
        port = port,
        now = { clock },
        schedule = { delayMs, task ->
            val entry = (clock + delayMs) to task
            tasks += entry
            ({ tasks.remove(entry); Unit })
        },
        onHubAtStart = onHubAtStart,
        rememberOnHub = { remembered += it },
    )

    private fun advance(ms: Long) {
        clock += ms
        while (true) {
            val due = tasks.filter { it.first <= clock }.minByOrNull { it.first } ?: return
            tasks.remove(due)
            due.second()
        }
    }

    /** Enabled, approved and showing [SESSION_A] playing. */
    private fun live(): MediaDeckActivityPublisher {
        fake.monitor.live = observed()
        val publisher = publisher()
        publisher.setEnabled(true)
        port.approved = true
        publisher.onRegistration(approved = true, generation = 1)
        return publisher
    }

    private fun actionId(activity: NexusActivity, typed: NexusActionType): String =
        activity.actions.single { it.typed == typed }.id

    @Test
    fun `enabling watches the sessions and publishes explicit typed commands once approved`() {
        fake.monitor.live = observed(track = track(title = "Teardrop (Mad Professor remix)"))
        val publisher = publisher()

        publisher.setEnabled(true)
        assertTrue(publisher.isWatching)
        assertTrue(fake.monitor.started)
        assertTrue(port.holding)
        assertTrue(port.calls.isEmpty())

        port.approved = true
        publisher.onRegistration(approved = true, generation = 1)
        assertEquals(listOf("start"), port.calls)
        val activity = port.activities.single()
        assertEquals(
            listOf(NexusActionType.MediaPrevious, NexusActionType.MediaPause, NexusActionType.MediaNext),
            activity.actions.map { it.typed },
        )
        assertEquals(actionId(activity, NexusActionType.MediaPause), activity.defaultActionId)
        assertEquals(NexusActivityActivation.COMMANDS, activity.activation)
        assertEquals(NexusActivityPersistence.ONGOING, activity.persistence)
        assertEquals(NexusPageDestination(MediaDeckPages.MAIN), activity.destination)
        assertEquals("Teardrop (M…", activity.primary)
        assertTrue(activity.primary.length <= ActivitySurfaceContract.MAX_PRIMARY_CHARS)
        assertEquals("Massive Attack", activity.secondary)
        assertEquals(MediaDeckActivityStatus.PLAYING, publisher.status)
        assertEquals(listOf(true), remembered)
    }

    @Test
    fun `a paused session offers Play and falls back to the app name without an artist`() {
        fake.monitor.live = observed(track = track(artist = ""), phase = MediaPlaybackPhase.PAUSED)
        val publisher = publisher()
        port.approved = true
        publisher.setEnabled(true)

        val activity = port.activities.single()
        assertEquals(NexusActionType.MediaPlay, activity.actions[1].typed)
        assertEquals(activity.actions[1].id, activity.defaultActionId)
        assertEquals("Player", activity.secondary)
        assertEquals(MediaDeckActivityModel.GLYPH_PAUSED, activity.glyph)
    }

    @Test
    fun `a position tick publishes nothing and a burst collapses into one update`() {
        val publisher = live()
        port.calls.clear()

        fake.monitor.emit(observed(), positionMs = 90_000L)
        assertTrue(port.calls.isEmpty())

        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PAUSED))
        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PLAYING))
        fake.monitor.emit(observed(track = track(title = "Angel")))
        assertTrue(port.calls.isEmpty())

        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        assertEquals(listOf("update"), port.calls)
        assertEquals("Angel", port.activities.last().primary)
        assertTrue(publisher.isStarted)
    }

    @Test
    fun `a new track or another player is an ordinary update, never a significant one`() {
        live()
        port.calls.clear()

        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        fake.monitor.emit(observed(track = track(title = "Angel")))
        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        fake.monitor.emit(observed(track = track(title = "Teardrop")))
        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        fake.monitor.emit(observed(identity = SESSION_B, track = track(title = "Angel")))

        assertEquals(listOf("update", "update", "update"), port.calls)
        assertEquals("Angel", port.activities.last().primary)
    }

    @Test
    fun `turning it off ends the activity, stops watching and lets the service go`() {
        val publisher = live()

        publisher.setEnabled(false)
        assertEquals(listOf("start", "end"), port.calls)
        assertFalse(publisher.isWatching)
        assertFalse(fake.monitor.started)
        assertFalse(hub.isMonitoring)
        assertEquals(MediaDeckActivityStatus.DISABLED, publisher.status)

        assertTrue(port.holding)
        advance(MediaDeckActivityPublisher.RELEASE_AFTER_END_MS)
        assertFalse(port.holding)
        assertEquals(listOf(true, false), remembered)
    }

    @Test
    fun `closing the surface keeps an enabled activity watching and publishing`() {
        fake.monitor.live = observed()
        hub.subscribe(MediaDeckSubscriber.IMMERSION) { _, _, _ -> }
        val publisher = live()

        hub.unsubscribe(MediaDeckSubscriber.IMMERSION)
        assertTrue(fake.monitor.started)

        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        fake.monitor.emit(observed(track = track(title = "Angel")))
        assertEquals("update", port.calls.last())
        assertEquals("Angel", port.activities.last().primary)
        assertTrue(publisher.isStarted)
    }

    @Test
    fun `revoked access ends the activity and keeps the switch until access returns`() {
        val publisher = live()

        fake.monitor.emit(MediaDeckObservation.AccessRequired)
        assertEquals("end", port.calls.last())
        assertTrue(publisher.enabled)
        assertTrue(publisher.isWatching)
        assertEquals(MediaDeckActivityStatus.REVOKED, publisher.status)
        advance(MediaDeckActivityPublisher.RELEASE_AFTER_END_MS)
        assertFalse(port.holding)

        // Access is back: the listener connects and the monitor reports again.
        fake.monitor.emit(observed())
        assertEquals("start", port.calls.last())
        assertTrue(port.holding)
    }

    @Test
    fun `nothing playing ends the activity and lets the service go`() {
        val publisher = live()

        fake.monitor.emit(MediaDeckObservation.NoSession)
        assertEquals(listOf("start", "end"), port.calls)
        assertEquals(MediaDeckActivityStatus.NO_SESSION, publisher.status)
        advance(MediaDeckActivityPublisher.RELEASE_AFTER_END_MS)
        assertFalse(port.holding)
        assertTrue(publisher.isWatching)
    }

    @Test
    fun `a command runs once on the session and revision it was published for`() {
        val publisher = live()
        val pause = actionId(port.activities.last(), NexusActionType.MediaPause)

        val outcome = publisher.onCommand("inv-1", pause, NexusActionType.MediaPause)
        assertEquals(MediaDeckControlOutcome.DONE, outcome)
        assertEquals(NexusActivityCommandStatus.DONE, outcome!!.toActivityResult().status)
        assertEquals(listOf(observed() to MediaDeckCommand.PAUSE), fake.monitor.sent)

        // The same invocation again was already answered: nothing runs, nothing is answered.
        assertNull(publisher.onCommand("inv-1", pause, NexusActionType.MediaPause))
        assertEquals(1, fake.monitor.sent.size)
    }

    @Test
    fun `a command for an older revision is stale even before the update went out`() {
        val publisher = live()
        val pause = actionId(port.activities.last(), NexusActionType.MediaPause)

        // Paused on the phone; the new revision is still waiting for its update slot.
        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PAUSED))
        val outcome = publisher.onCommand("inv-1", pause, NexusActionType.MediaPause)
        assertEquals(MediaDeckControlOutcome.STALE_REVISION, outcome)
        assertEquals(NexusActivityCommandStatus.STALE, outcome!!.toActivityResult().status)
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a command after another session took over is stale and reaches neither session`() {
        val publisher = live()
        val next = actionId(port.activities.last(), NexusActionType.MediaNext)

        fake.monitor.emit(observed(identity = SESSION_B))
        advance(MediaDeckActivityPublisher.MIN_UPDATE_INTERVAL_MS)
        assertEquals(MediaDeckActivityStatus.SESSION_CHANGED, publisher.status)

        assertEquals(MediaDeckControlOutcome.STALE_SESSION, publisher.onCommand("inv-1", next, NexusActionType.MediaNext))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a command with no session or no access is rejected`() {
        val publisher = live()
        val next = actionId(port.activities.last(), NexusActionType.MediaNext)

        fake.monitor.emit(MediaDeckObservation.NoSession)
        val none = publisher.onCommand("inv-1", next, NexusActionType.MediaNext)
        assertEquals(MediaDeckControlOutcome.NO_SESSION, none)
        assertEquals(NexusActivityCommandStatus.REJECTED, none!!.toActivityResult().status)

        fake.monitor.emit(MediaDeckObservation.AccessRequired)
        assertEquals(MediaDeckControlOutcome.ACCESS_REQUIRED, publisher.onCommand("inv-2", next, NexusActionType.MediaNext))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `an id and a type that disagree, or an unknown id, are not offered`() {
        val publisher = live()
        val activity = port.activities.last()
        val next = actionId(activity, NexusActionType.MediaNext)

        assertEquals(MediaDeckControlOutcome.NOT_OFFERED, publisher.onCommand("inv-1", next, NexusActionType.MediaPrevious))
        assertEquals(MediaDeckControlOutcome.NOT_OFFERED, publisher.onCommand("inv-2", "next", NexusActionType.MediaNext))
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `a command whose result was lost is never sent again after a reconnect`() {
        val publisher = live()
        val next = actionId(port.activities.last(), NexusActionType.MediaNext)
        assertEquals(MediaDeckControlOutcome.DONE, publisher.onCommand("inv-1", next, NexusActionType.MediaNext))

        // The link drops before the result arrives; the service reconnects with a new registration.
        publisher.onLinkState(usable = false)
        publisher.onLinkState(usable = true)
        publisher.onRegistration(approved = true, generation = 2)
        assertEquals(listOf("start", "start"), port.calls)
        assertEquals(1, fake.monitor.sent.size)

        // The same invocation delivered again is not run again.
        assertNull(publisher.onCommand("inv-1", next, NexusActionType.MediaNext))
        assertEquals(1, fake.monitor.sent.size)
    }

    @Test
    fun `a registration that is not approved stops watching and keeps the switch`() {
        val publisher = live()

        port.approved = false
        publisher.onRegistration(approved = false, generation = 2)
        assertFalse(port.holding)
        assertFalse(publisher.isWatching)
        assertFalse(fake.monitor.started)
        assertTrue(publisher.enabled)
        assertEquals(MediaDeckActivityStatus.SUSPENDED, publisher.status)

        // The hub binds Media Deck again and approves it: the activity resumes.
        port.approved = true
        publisher.onRegistration(approved = true, generation = 3)
        assertTrue(publisher.isWatching)
        assertEquals("start", port.calls.last())
    }

    @Test
    fun `an activity the hub replaced is not restarted until a new registration`() {
        val publisher = live()

        publisher.onActivityClosed(ActivityCloseReason.REPLACED.wireValue)
        fake.monitor.emit(observed(track = track(title = "Angel")))
        assertEquals(listOf("start"), port.calls)
        assertFalse(publisher.isWatching)
        advance(MediaDeckActivityPublisher.RELEASE_AFTER_END_MS)
        assertFalse(port.holding)

        publisher.onRegistration(approved = true, generation = 1)
        assertEquals(listOf("start"), port.calls)

        publisher.onRegistration(approved = true, generation = 2)
        assertEquals(listOf("start", "start"), port.calls)
    }

    @Test
    fun `turning the activity off in Nexus stops it without touching the switch`() {
        val publisher = live()

        publisher.onError(ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED)
        assertTrue(publisher.enabled)
        assertFalse(publisher.isWatching)
        assertEquals(listOf("start"), port.calls)
        assertEquals(MediaDeckActivityStatus.SUSPENDED, publisher.status)
        assertEquals(listOf(true, false), remembered)
    }

    @Test
    fun `an entry an earlier process left is ended once a registration is approved`() {
        fake.monitor.live = MediaDeckObservation.NoSession
        val publisher = publisher(onHubAtStart = true)

        publisher.rearm()
        assertTrue(port.holding)
        assertTrue(port.calls.isEmpty())

        port.approved = true
        publisher.onRegistration(approved = true, generation = 1)
        assertEquals(listOf("end"), port.calls)
        assertFalse(publisher.mayBeOnHub)
        assertEquals(listOf(false), remembered)
        advance(MediaDeckActivityPublisher.RELEASE_AFTER_END_MS)
        assertFalse(port.holding)

        publisher.rearm()
        assertEquals(listOf("end"), port.calls)
        assertFalse(port.holding)
    }

    @Test
    fun `glasses without the launcher bundle reach only the shown session too`() {
        val publisher = live()
        val pause = actionId(port.activities.last(), NexusActionType.MediaPause)

        fake.monitor.emit(observed(identity = SESSION_B))
        assertEquals(MediaDeckControlOutcome.STALE_SESSION, publisher.onLegacyAction(pause))
        assertTrue(fake.monitor.sent.isEmpty())
        assertTrue(hub.state is MediaDeckState.Active)
    }

    @Test
    fun `a start refused under a skill lease is forgotten, not retried, and sent whole on the next change`() {
        val publisher = live()
        val pause = actionId(port.activities.last(), NexusActionType.MediaPause)

        publisher.onError(MediaDeckActivityPublisher.SKILL_LEASE_DENIED)
        assertFalse(publisher.isStarted)
        assertTrue(publisher.enabled)
        assertTrue(publisher.isWatching)
        assertNull(publisher.suspendedBy)
        assertTrue(publisher.status != MediaDeckActivityStatus.SUSPENDED)
        assertTrue(publisher.mayBeOnHub)
        // No retry now or later: a send during the call would be refused the same way.
        advance(60_000L)
        assertEquals(listOf("start"), port.calls)
        // The refused revision binds to nothing on the glasses.
        assertTrue(publisher.onCommand("inv-1", pause, NexusActionType.MediaPause) != MediaDeckControlOutcome.DONE)
        assertTrue(fake.monitor.sent.isEmpty())

        fake.monitor.emit(observed(phase = MediaPlaybackPhase.PAUSED))
        assertEquals(listOf("start", "start"), port.calls)
        assertTrue(publisher.isStarted)
    }

    @Test
    fun `an end refused under a skill lease keeps the entry counted on the hub until it is ended`() {
        val publisher = live()
        publisher.setEnabled(false)
        assertEquals(listOf("start", "end"), port.calls)
        assertFalse(publisher.mayBeOnHub)

        publisher.onError(MediaDeckActivityPublisher.SKILL_LEASE_DENIED)
        assertTrue(publisher.mayBeOnHub)
        assertEquals(listOf(true, false, true), remembered)
        assertEquals(listOf("start", "end"), port.calls)

        // The next event the plugin already receives ends it again.
        publisher.rearm()
        assertEquals(listOf("start", "end", "end"), port.calls)
        assertFalse(publisher.mayBeOnHub)
    }
}
