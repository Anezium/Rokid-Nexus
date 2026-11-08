package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.*
import org.junit.Test

class WhatsAppSettingsSessionTest {
    private class Clock : SettingsScheduler {
        var time = 0L
        private data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val item = Task(time + delayMs, task)
            tasks += item
            return { item.cancelled = true }
        }
        fun advance(by: Long) {
            val target = time + by
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                time = next.at
                next.run()
            }
            time = target
        }
    }
    private class Backend : WhatsAppSettingsBackend {
        var releases = 0
        var query: ((Result<WhatsAppConnection>) -> Unit)? = null
        var start: ((Result<WhatsAppPairing>) -> Unit)? = null
        var poll: ((Result<WhatsAppPairing>) -> Unit)? = null
        var requestedSession = ""
        var polls = 0
        var timeout = 0L
        override fun acquire() {}
        override fun release() { releases++ }
        override fun query(timeoutMs: Long, done: (Result<WhatsAppConnection>) -> Unit) { timeout = timeoutMs; query = done }
        override fun startLink(number: String, done: (Result<WhatsAppPairing>) -> Unit) { start = done }
        override fun link(session: String, done: (Result<WhatsAppPairing>) -> Unit) { requestedSession = session; polls++; poll = done }
        override fun logout(done: (Result<Unit>) -> Unit) { done(Result.success(Unit)) }
        fun state(authenticated: Boolean = false) = query!!.invoke(Result.success(
            WhatsAppConnection(if (authenticated) "linked" else "unlinked", authenticated, authenticated)))
    }
    private class Fixture(hold: Boolean = false) {
        val clock = Clock()
        val backend = Backend()
        var holds = 0
        var holdEnds = 0
        val session = WhatsAppSettingsSession(backend, clock, { clock.time }, { holds++; hold }, { holdEnds++ })
        init { session.start {}; backend.state() }
        fun pairing() {
            session.startLink("synthetic input")
            backend.start!!(Result.success(WhatsAppPairing("synthetic-session", "pairing")))
            clock.advance(0)
        }
        fun accepted() {
            pairing()
            backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "success")))
        }
    }

    @Test fun recreationDuringStartRetainsAttemptAndOwnership() {
        val f = Fixture()
        f.session.startLink("synthetic input")
        f.session.stop(true)
        f.backend.start!!(Result.success(WhatsAppPairing("synthetic-session", "pairing")))
        f.clock.advance(1_000)
        assertEquals(0, f.backend.polls)
        f.session.start {}
        f.clock.advance(0)
        assertEquals("synthetic-session", f.backend.requestedSession)
        assertEquals(0, f.backend.releases)
        f.clock.advance(5_000)
        assertEquals(0, f.backend.releases)
    }

    @Test fun backgroundReleasesImmediatelyAndRejectsStaleResults() {
        val f = Fixture()
        f.pairing()
        val stalePoll = f.backend.poll!!
        stalePoll(Result.success(WhatsAppPairing("synthetic-session", "pairing", "SYNTHETIC")))
        f.session.stop(false)
        assertEquals(1, f.backend.releases)
        assertEquals("", f.session.view.code)
        f.session.start {}
        stalePoll(Result.success(WhatsAppPairing("synthetic-session", "success")))
        assertEquals("Checking linked device…", f.session.view.text)
        f.clock.advance(180_000)
        assertEquals(1, f.backend.polls)
    }

    @Test fun abandonedConfigurationHandoffExpiresAfterFiveSeconds() {
        val f = Fixture()
        f.pairing()
        f.session.stop(true)
        f.clock.advance(4_999)
        assertEquals(0, f.backend.releases)
        f.clock.advance(1)
        assertEquals(1, f.backend.releases)
        assertEquals("", f.session.view.code)
    }

    @Test fun acceptanceShowsFinishingUntilAuthenticated() {
        val f = Fixture()
        f.accepted()
        assertEquals("Finishing link…", f.session.view.text)
        assertEquals(8_000L, f.backend.timeout)
        f.backend.state()
        assertEquals("Finishing link…", f.session.view.text)
        f.clock.advance(100)
        f.backend.state(true)
        assertEquals("linked · authenticated", f.session.view.text)
        assertFalse(f.session.view.busy)
        assertFalse(f.session.view.canLink)
    }

    @Test fun finishingAndOverallDeadlineAreBounded() {
        val f = Fixture()
        f.accepted()
        f.clock.advance(30_000)
        f.backend.state()
        assertEquals("Link not authenticated · check WhatsApp", f.session.view.text)
        assertFalse(f.session.view.busy)
        val other = Fixture()
        other.pairing()
        other.clock.advance(179_000)
        other.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "success")))
        assertEquals(1_000L, other.backend.timeout)
        other.clock.advance(1_000)
        other.backend.state()
        assertFalse(other.session.view.busy)
    }

    @Test fun existingSessionNeverEnablesLinkAndPasskeyIsExplicit() {
        val f = Fixture()
        f.session.refresh()
        f.backend.state(true)
        f.session.startLink("synthetic input")
        assertNull(f.backend.start)
        val other = Fixture()
        other.pairing()
        other.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "passkey_required")))
        assertTrue(other.session.view.text.startsWith("Passkey required"))
        assertEquals("", other.session.view.code)
    }

    @Test fun screenStaysAwakeOnlyForPairingAndFinalization() {
        val f = Fixture()
        assertFalse(f.session.view.keepScreenOn)
        f.session.startLink("synthetic input")
        assertTrue(f.session.view.keepScreenOn)
        f.backend.start!!(Result.success(WhatsAppPairing("synthetic-session", "pairing")))
        f.clock.advance(0)
        assertTrue(f.session.view.keepScreenOn)
        f.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "success")))
        assertTrue(f.session.view.keepScreenOn)
        f.backend.state(true)
        assertFalse(f.session.view.keepScreenOn)
        val background = Fixture()
        background.pairing()
        background.session.stop(false)
        assertFalse(background.session.view.keepScreenOn)
        val failed = Fixture()
        failed.pairing()
        failed.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "timeout")))
        assertFalse(failed.session.view.keepScreenOn)
    }

    @Test fun hiddenPairingKeepsPollingUntilAuthenticatedThenReleases() {
        val f = Fixture(hold = true)
        f.pairing()
        f.session.stop(false)
        assertEquals(0, f.backend.releases)
        assertEquals(1, f.holds)
        f.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "pairing", "SYNTHETIC")))
        f.clock.advance(1_500)
        assertEquals(2, f.backend.polls)
        f.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "success")))
        f.backend.state(authenticated = true)
        assertEquals(1, f.backend.releases)
        assertEquals(1, f.holdEnds)
    }

    @Test fun hiddenPairingEndsAtItsOwnDeadline() {
        val f = Fixture(hold = true)
        f.pairing()
        f.session.stop(false)
        repeat(200) {
            f.backend.poll?.invoke(Result.success(WhatsAppPairing("synthetic-session", "pairing", "SYNTHETIC")))
            f.backend.poll = null
            f.clock.advance(1_500)
        }
        assertEquals(1, f.backend.releases)
        assertEquals(1, f.holdEnds)
        val polls = f.backend.polls
        f.clock.advance(60_000)
        assertEquals(polls, f.backend.polls)
    }

    @Test fun returningDuringAHeldPairingResumesWithoutRelease() {
        val f = Fixture(hold = true)
        f.pairing()
        f.session.stop(false)
        f.session.start {}
        assertEquals(1, f.holdEnds)
        assertEquals(0, f.backend.releases)
        f.backend.poll!!(Result.success(WhatsAppPairing("synthetic-session", "pairing", "SYNTHETIC")))
        assertEquals("SYNTHETIC", f.session.view.code)
    }

    @Test fun idleSettingsAreNeverHeldInBackground() {
        val f = Fixture(hold = true)
        f.session.stop(false)
        assertEquals(0, f.holds)
        assertEquals(1, f.backend.releases)
    }
}
