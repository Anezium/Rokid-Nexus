// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Random event sequences around the plugin open handoff; every invariant is checked after every step. */
class SessionOpenHandoffPropertyTest {
    private val plugins = listOf("maps", "lens", "player")
    private val surfaces = listOf("maps:surface", "lens:surface", "player:card", "other:surface")
    private val reducer = SessionReducer(
        PageItemResolver { page ->
            page.actions.map { if (it.kind == "immersion") PageItem.Launch(it.id) else PageItem.Invoke(it.id) }
        },
    )

    private fun randomEvent(r: Random, model: SessionModel, now: Long): SessionEvent {
        fun <T> pick(values: List<T>) = values[r.nextInt(values.size)]
        val tokens = listOfNotNull((model.state as? SessionState.Launching)?.token, 0L, 99L)
        return when (r.nextInt(16)) {
            0, 1 -> SessionEvent.TripleTap(
                now,
                pick(listOf(Underneath.Unknown, Underneath.NativeApp, Underneath.NexusSurface(pick(surfaces)))),
            )
            2, 3 -> SessionEvent.Enter(now)
            4 -> SessionEvent.Back(now)
            5 -> SessionEvent.Step(if (r.nextBoolean()) 1 else -1, now)
            6, 7 -> SessionEvent.Tick(model.nextDeadlineMs()?.takeIf { r.nextBoolean() } ?: now)
            8, 9 -> pendingResponse(model, now) ?: SessionEvent.Contact(now)
            10, 11 -> {
                val surface = pick(surfaces)
                SessionEvent.SurfaceShown(surface, surface.substringBefore(':'), now)
            }
            12 -> SessionEvent.OpenFailed(pick(tokens), pick(OpenFailure.entries.toList()))
            13 -> SessionEvent.Abort
            14 -> if (r.nextBoolean()) SessionEvent.LinkLost else SessionEvent.LinkRestored
            else -> SessionEvent.Contact(now)
        }
    }

    /** A page answering the request in flight, whose rows open every plugin. */
    private fun pendingResponse(model: SessionModel, now: Long): SessionEvent? {
        val top = (model.state as? SessionState.InPage)?.frames?.lastOrNull() ?: return null
        val loading = top.status as? FrameStatus.Loading ?: return null
        val correlation = PageCorrelation(
            loading.requestId, model.generation, (model.state as SessionState.InPage).frames.size, top.pageId,
        )
        val actions = JSONArray()
        plugins.forEach {
            actions.put(JSONObject().put("id", it).put("label", it).put("kind", "immersion").put("confirm", false))
        }
        val payload = PageSurfaceContract.correlationPayload(correlation)
            .put("revision", 1)
            .put("template", "summary")
            .put("title", "Page")
            .put("body", JSONObject())
            .put("actions", actions)
            .put("live", false)
        return SessionEvent.PageResponse(payload, now)
    }

    @Test
    fun `open handoff invariants hold for random sequences`() {
        var opensSeen = 0
        var closedUnseen = 0
        for (seed in 1L..300L) {
            val r = Random(seed)
            var model = SessionModel()
            var now = 0L
            repeat(120) { step ->
                now += r.nextInt(4_000)
                val event = randomEvent(r, model, now)
                if (event is SessionEvent.Tick) now = maxOf(now, event.nowMs)
                val before = model
                val transition = reducer.reduce(before, event)
                val after = transition.model
                val effects = transition.effects
                val ctx = "seed=$seed step=$step event=$event before=${before.state} after=${after.state} fx=$effects"

                // Pure and deterministic.
                assertEquals(ctx, transition, reducer.reduce(before, event))

                // An open is only ever sent by ENTER on a launch row, and it starts the wait for it.
                val opens = effects.filterIsInstance<SessionEffect.SendLauncherOpen>()
                if (opens.isNotEmpty()) {
                    opensSeen++
                    assertEquals(ctx, 1, opens.size)
                    assertTrue(ctx, event is SessionEvent.Enter && before.state is SessionState.InPage)
                    val launching = after.state as SessionState.Launching
                    assertEquals(ctx, launching.token, opens.single().token)
                    assertEquals(ctx, launching.pluginId, opens.single().pluginId)
                    assertTrue(ctx, launching.pluginId !in after.cancelledOpen)
                }
                (after.state as? SessionState.Launching)?.let {
                    assertTrue(ctx, it.previous is SessionState.InPage || it.previous is SessionState.Root)
                }

                // A surface is closed unseen only for a live cancelled open, never the session's own base.
                effects.filterIsInstance<SessionEffect.CloseSurface>().forEach { close ->
                    closedUnseen++
                    val shown = event as SessionEvent.SurfaceShown
                    assertEquals(ctx, shown.surfaceId, close.surfaceId)
                    val until = before.cancelledOpen[shown.ownerPluginId]
                    assertTrue(ctx, until != null && now < until)
                    assertTrue(ctx, (before.underneath as? Underneath.NexusSurface)?.surfaceId != close.surfaceId)
                }
                assertTrue(ctx, after.cancelledOpen.keys.all { it in plugins })

                // Abort always lands closed, restoring the base it was opened over.
                if (event == SessionEvent.Abort) {
                    assertEquals(ctx, SessionState.Closed, after.state)
                    if (before.state != SessionState.Closed) {
                        assertTrue(ctx, SessionEffect.RestoreUnderneath(before.underneath) in effects)
                    }
                }

                // Nothing is scheduled while closed, and a tick at the deadline always moves it on.
                if (after.state == SessionState.Closed) assertNull(ctx, after.nextDeadlineMs())
                val due = before.nextDeadlineMs()
                if (event is SessionEvent.Tick && due != null && event.nowMs == due) {
                    after.nextDeadlineMs()?.let { assertTrue(ctx, it > due) }
                }
                model = after
            }
        }
        // The generator must actually reach the handoff, or every invariant above holds vacuously.
        assertTrue("opens=$opensSeen", opensSeen > 50)
        assertTrue("closedUnseen=$closedUnseen", closedUnseen > 0)
    }
}
