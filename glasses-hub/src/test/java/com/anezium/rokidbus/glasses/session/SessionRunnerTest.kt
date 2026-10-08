// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runner with a fake clock, timer and sink: the host-level behaviour that needs no window.
 * Deadlines, queued follow-up events and the host's attach and detach are what the Android side
 * depends on.
 */
class SessionRunnerTest {
    private class FakeTimer : SessionTimer {
        var at: Long? = null
        var task: (() -> Unit)? = null
        var scheduled = 0

        override fun schedule(atUptimeMs: Long, task: () -> Unit) {
            at = atUptimeMs
            this.task = task
            scheduled++
        }

        override fun cancel() {
            at = null
            task = null
        }
    }

    private class Fixture(val onEffect: (SessionEffect, SessionRunner) -> Unit = { _, _ -> }) {
        var now = 0L
        val timer = FakeTimer()
        val effects = ArrayList<SessionEffect>()
        val settled = ArrayList<SessionState>()
        val errors = ArrayList<Throwable>()
        val runner: SessionRunner = SessionRunner(
            reducer = SessionReducer(
                PageItemResolver { page ->
                    page.actions.map { if (it.kind == "immersion") PageItem.Launch(it.id) else PageItem.Invoke(it.id) }
                },
            ),
            clock = { now },
            timer = timer,
            sink = object : SessionEffectSink {
                override fun execute(effect: SessionEffect) {
                    effects += effect
                    onEffect(effect, runner)
                }

                override fun settled(model: SessionModel) {
                    settled += model.state
                }
            },
            onError = { errors += it },
        )

        fun at(time: Long, event: SessionEvent) {
            now = time
            runner.dispatch(event)
        }

        fun fireTimer() {
            val task = timer.task ?: error("nothing scheduled")
            now = timer.at!!
            timer.cancel()
            task()
        }

        /** Opens the root, then a page whose only row is [pluginId]'s immersion. */
        fun showImmersion(pluginId: String = "maps") {
            at(1_000, SessionEvent.TripleTap(1_000))
            fireTimer()
            at(3_000, SessionEvent.Enter(3_000))
            val request = effects.filterIsInstance<SessionEffect.RequestPage>().last().request
            val page = PageSurfaceContract.correlationPayload(request.correlation)
                .put("revision", 1)
                .put("template", "summary")
                .put("title", "Page")
                .put("body", JSONObject())
                .put(
                    "actions",
                    JSONArray().put(
                        JSONObject().put("id", pluginId).put("label", pluginId).put("kind", "immersion")
                            .put("confirm", false),
                    ),
                )
                .put("live", false)
            at(3_100, SessionEvent.PageResponse(page, 3_100))
            assertTrue(runner.state is SessionState.InPage)
        }
    }

    @Test
    fun `a pending open expires through the timer and the page comes back with a status`() {
        val f = Fixture()
        f.showImmersion()
        f.at(4_000, SessionEvent.Enter(4_000))
        val launching = f.runner.state as SessionState.Launching
        assertEquals(4_000 + SessionReducer.OPEN_TIMEOUT_MS, f.timer.at)
        assertTrue(SessionEffect.SendLauncherOpen("maps", launching.token) in f.effects)
        f.effects.clear()

        f.fireTimer()

        assertTrue(f.runner.state is SessionState.InPage)
        assertEquals(listOf(SessionEffect.ShowStatus(SessionStatus.OpenFailed("maps", OpenFailure.TIMEOUT))), f.effects)
        // Back on the page, the timer follows the page's own lease again.
        assertEquals(f.runner.model.nextDeadlineMs(), f.timer.at)
    }

    @Test
    fun `the surface arriving closes the session and detaches the host`() {
        val f = Fixture()
        f.showImmersion()
        f.at(4_000, SessionEvent.Enter(4_000))
        f.effects.clear()

        f.at(4_500, SessionEvent.SurfaceShown("maps:surface", "maps", 4_500))

        assertSame(SessionState.Closed, f.runner.state)
        assertEquals(
            listOf(
                SessionEffect.SendClosed(RootStops.NOTIFICATIONS_PAGE_ID, SessionReducer.CLOSE_SESSION_CLOSED),
                SessionEffect.DetachHost,
            ),
            f.effects,
        )
        assertTrue(f.effects.none { it is SessionEffect.RestoreUnderneath })
        assertNull(f.timer.task)
    }

    @Test
    fun `re-entrant dispatch is queued, not interleaved`() {
        val order = ArrayList<String>()
        val f = Fixture(onEffect = { effect, runner ->
            order += effect::class.simpleName.orEmpty()
            // The bus refuses the send and the sink reports it from inside the effect.
            if (effect is SessionEffect.SendLauncherOpen) {
                order += "dispatch:OpenFailed"
                runner.dispatch(SessionEvent.OpenFailed(effect.token, OpenFailure.SEND_FAILED))
                order += "returned"
            }
        })
        f.showImmersion()
        order.clear()
        f.settled.clear()

        f.at(4_000, SessionEvent.Enter(4_000))

        // The open transition settles before the queued failure is handled.
        assertEquals(listOf("SendLauncherOpen", "dispatch:OpenFailed", "returned", "ShowStatus"), order)
        assertEquals(2, f.settled.size)
        assertTrue(f.settled[0] is SessionState.Launching)
        assertTrue(f.settled[1] is SessionState.InPage)
    }

    @Test
    fun `exactly one timer pending, at the earliest deadline, and none while closed`() {
        val f = Fixture()
        assertNull(f.timer.at)

        f.at(1_000, SessionEvent.TripleTap(1_000))
        assertEquals(1_000 + PageSurfaceContract.GATE_MS, f.timer.at)
        f.fireTimer()
        assertNull(f.timer.at)

        f.at(3_000, SessionEvent.Enter(3_000))
        assertEquals(3_000 + PageSurfaceContract.LOADING_HINT_MS, f.timer.at)
        val scheduled = f.timer.scheduled
        // Events that move no deadline do not re-arm the timer.
        f.at(3_100, SessionEvent.Step(1, 3_100))
        f.at(3_200, SessionEvent.Contact(3_200))
        assertEquals(scheduled, f.timer.scheduled)

        f.fireTimer()
        assertEquals(3_000 + PageSurfaceContract.PAGE_TIMEOUT_MS, f.timer.at)
        f.at(4_000, SessionEvent.Abort)
        assertSame(SessionState.Closed, f.runner.state)
        assertNull(f.timer.at)
        assertNull(f.timer.task)
    }

    @Test
    fun `the host is attached before the first effect and detached after the last, once per session`() {
        val f = Fixture()
        f.at(1_000, SessionEvent.TripleTap(1_000))
        f.fireTimer()
        f.at(3_000, SessionEvent.Enter(3_000))
        f.at(3_500, SessionEvent.Back(3_500))
        f.at(3_600, SessionEvent.Back(3_600))

        assertEquals(SessionEffect.AttachHost, f.effects.first())
        assertEquals(SessionEffect.ShowGate, f.effects[1])
        assertEquals(SessionEffect.DetachHost, f.effects.last())
        assertEquals(SessionEffect.RestoreUnderneath, f.effects[f.effects.size - 2])
        assertEquals(1, f.effects.count { it == SessionEffect.AttachHost })
        assertEquals(1, f.effects.count { it == SessionEffect.DetachHost })
        assertTrue(f.effects.none { it is SessionEffect.ScheduleDeadline || it == SessionEffect.CancelDeadline })
    }

    @Test
    fun `a window that cannot be added aborts through the queue and the host is detached`() {
        val f = Fixture(onEffect = { effect, runner ->
            if (effect == SessionEffect.AttachHost) runner.dispatch(SessionEvent.Abort)
        })

        f.at(1_000, SessionEvent.TripleTap(1_000, Underneath.NativeApp))

        assertSame(SessionState.Closed, f.runner.state)
        assertEquals(
            listOf(
                SessionEffect.AttachHost, SessionEffect.ShowGate,
                SessionEffect.RestoreUnderneath(Underneath.NativeApp), SessionEffect.DetachHost,
            ),
            f.effects,
        )
        assertNull(f.timer.task)
    }

    @Test
    fun `screens settle after every event including ones with no effects`() {
        val f = Fixture()
        f.at(1_000, SessionEvent.Enter(1_000))
        assertEquals(listOf<SessionState>(SessionState.Closed), f.settled)
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun `a throwing sink does not stop the drain`() {
        val f = Fixture(onEffect = { effect, _ -> if (effect == SessionEffect.AttachHost) error("window refused") })

        f.at(1_000, SessionEvent.TripleTap(1_000))

        assertEquals(1, f.errors.size)
        // The reducer already moved on; the gate's own effect still ran and later events are handled.
        assertTrue(SessionEffect.ShowGate in f.effects)
        assertTrue(f.runner.state is SessionState.Opening)
        f.fireTimer()
        f.at(2_000, SessionEvent.Back(2_000))
        assertSame(SessionState.Closed, f.runner.state)
        assertEquals(1, f.errors.size)
    }
}
