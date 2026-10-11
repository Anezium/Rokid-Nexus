package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.FakeHub
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.observed
import com.anezium.rokidbus.plugin.media.MediaPauseOutcome
import com.anezium.rokidbus.plugin.media.MediaSkillAccess
import com.anezium.rokidbus.plugin.media.MediaSkillOutcome
import com.anezium.rokidbus.plugin.media.MediaSkillSession
import com.anezium.rokidbus.plugin.media.MediaSkills
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A "Which player?" answer authorizes one skill reference for one call and nothing else. These
 * tests run skill listings and selections beside a live HUD and check that the HUD's player, its
 * monitoring, its activity, and the commands the activity offered are exactly what they were.
 */
class MediaSkillsHudBoundaryTest {
    private class RecordingPort : MediaDeckActivityPort {
        override var approved = false
        var held = 0
        val calls = mutableListOf<String>()
        val activities = mutableListOf<NexusActivity>()

        override fun start(activity: NexusActivity): NexusSdkResult {
            calls += "start"
            activities += activity
            return NexusSdkResult.SENT
        }

        override fun update(activity: NexusActivity, significant: Boolean): NexusSdkResult {
            calls += "update"
            activities += activity
            return NexusSdkResult.SENT
        }

        override fun end(): NexusSdkResult {
            calls += "end"
            return NexusSdkResult.SENT
        }

        override fun hold(): Boolean {
            held++
            return true
        }

        override fun release() = Unit
    }

    /** The phone's players as a skill call sees them, through its own access. */
    private class Players : MediaSkillAccess {
        var available = listOf(
            MediaSkillSession("ref-hud", "Player A", "playing", "Teardrop"),
            MediaSkillSession("ref-other", "Player B", "playing", "Angel"),
        )

        /** The chosen session is destroyed between the read and the pause. */
        var destroyedBeforePause = false
        val paused = mutableListOf<String>()

        override fun hasAccess() = true

        override fun sessions(includeMetadata: Boolean): List<MediaSkillSession> = available

        override fun pause(reference: String, remainingMs: () -> Long, cancelled: () -> Boolean): MediaPauseOutcome {
            // Like the Android access: only the exact reference, still among the live players.
            if (destroyedBeforePause || available.none { it.reference == reference }) return MediaPauseOutcome.STALE
            paused += reference
            return MediaPauseOutcome.PAUSED
        }
    }

    private data class Hud(
        val state: MediaDeckState,
        val subscribers: Set<MediaDeckSubscriber>,
        val monitorStarts: Int,
        val monitorStops: Int,
        val calls: List<String>,
        val held: Int,
        val enabled: Boolean,
        val watching: Boolean,
        val started: Boolean,
        val mayBeOnHub: Boolean,
    )

    private val fake = FakeHub()
    private val hub = fake.hub
    private val port = RecordingPort()
    private val players = Players()
    private val skills = MediaSkills(players)

    private fun publisher() = MediaDeckActivityPublisher(
        hub = hub,
        port = port,
        now = { 0L },
        schedule = { _, _ -> {} },
    )

    /** Enabled, approved and publishing the HUD's session playing. */
    private fun liveHud(): MediaDeckActivityPublisher {
        fake.monitor.live = observed()
        val publisher = publisher()
        publisher.setEnabled(true)
        port.approved = true
        publisher.onRegistration(approved = true, generation = 1)
        return publisher
    }

    private fun hud(publisher: MediaDeckActivityPublisher) = Hud(
        state = hub.state,
        subscribers = hub.subscribers,
        monitorStarts = fake.monitor.starts,
        monitorStops = fake.monitor.stops,
        calls = port.calls.toList(),
        held = port.held,
        enabled = publisher.enabled,
        watching = publisher.isWatching,
        started = publisher.isStarted,
        mayBeOnHub = publisher.mayBeOnHub,
    )

    private fun run(operation: String, session: String? = null): MediaSkillOutcome = skills.run(
        operation,
        JSONObject().apply { session?.let { put("session", it) } },
        remainingMs = { 10_000L },
        cancelled = { false },
    )

    @Test
    fun `listing players and pausing the chosen one leave the HUD and its offered commands untouched`() {
        val publisher = liveHud()
        val pause = port.activities.last().actions.single { it.typed == NexusActionType.MediaPause }.id
        val before = hud(publisher)

        val choice = run(MediaSkills.PAUSE) as MediaSkillOutcome.Choice
        assertEquals(listOf("ref-hud", "ref-other"), choice.choices.map { it.reference.value })
        assertTrue(players.paused.isEmpty())
        assertEquals(before, hud(publisher))

        val paused = run(MediaSkills.PAUSE, session = "ref-other") as MediaSkillOutcome.Completed
        assertEquals("ref-other", paused.data.getString("session"))
        assertEquals(listOf("ref-other"), players.paused)
        assertEquals(before, hud(publisher))
        assertTrue(fake.monitor.sent.isEmpty())

        // The activity's command still reaches only the session and revision it was shown for.
        assertEquals(MediaDeckControlOutcome.DONE, publisher.onCommand("inv-1", pause, NexusActionType.MediaPause))
        assertEquals(listOf(observed() to MediaDeckCommand.PAUSE), fake.monitor.sent)
    }

    @Test
    fun `reads and a selection with the HUD off start no monitoring and turn on no activity`() {
        val publisher = publisher()
        val before = hud(publisher)

        assertTrue(run(MediaSkills.NOW_PLAYING) is MediaSkillOutcome.Choice)
        assertTrue(run(MediaSkills.NOW_PLAYING, session = "ref-other") is MediaSkillOutcome.Completed)
        assertTrue(run(MediaSkills.PAUSE, session = "ref-other") is MediaSkillOutcome.Completed)

        assertEquals(before, hud(publisher))
        assertFalse(hub.isMonitoring)
        assertEquals(0, fake.monitor.starts)
        assertFalse(publisher.enabled)
        assertTrue(port.calls.isEmpty())
        assertEquals(0, port.held)
    }

    @Test
    fun `a chosen player that went away fails stale and no other player is paused`() {
        val publisher = liveHud()
        val before = hud(publisher)
        val chosen = (run(MediaSkills.PAUSE) as MediaSkillOutcome.Choice).choices.last().reference.value

        // Destroyed before the call: its reference is gone from the read.
        players.available = players.available.filter { it.reference != chosen }
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE), run(MediaSkills.PAUSE, session = chosen))

        // Destroyed between the read and the pause: the access answers stale and sends nothing.
        players.available += MediaSkillSession(chosen, "Player B", "playing")
        players.destroyedBeforePause = true
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE), run(MediaSkills.PAUSE, session = chosen))

        assertTrue(players.paused.isEmpty())
        assertTrue(fake.monitor.sent.isEmpty())
        assertEquals(before, hud(publisher))
    }

    @Test
    fun `the skill path has no handle on the HUD monitor, publisher, preferences or identities`() {
        val sources = listOf(
            "src/main/java/com/anezium/rokidbus/plugin/media/MediaSkills.kt",
            "src/main/java/com/anezium/rokidbus/media/session/AndroidMediaSkillAccess.kt",
            "src/main/java/com/anezium/rokidbus/media/session/MediaSkillReferences.kt",
        ).associateWith { path ->
            // Code only: comments may name what the code must not reach.
            File(path).readText(Charsets.UTF_8).lines()
                .map(String::trimStart)
                .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                .joinToString("\n")
        }
        val hudOnly = listOf(
            "MediaDeckMonitorHub",
            "MediaSessionMonitor",
            "MediaSessionIdentities",
            "MediaDeckActivityControl",
            "MediaDeckActivityPublisher",
            "MediaDeckPreferences",
            "showInActivities",
            "MediaDeckTarget",
            "MediaDeckSubscriber",
            "MediaDeckPageProvider",
            "MediaDeckRuntime",
        )
        sources.forEach { (path, code) ->
            hudOnly.forEach { name -> assertFalse("$path reaches $name", code.contains(name)) }
        }
    }
}
