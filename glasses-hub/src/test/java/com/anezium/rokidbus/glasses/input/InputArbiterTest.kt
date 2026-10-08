// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.input

import com.anezium.rokidbus.glasses.input.RoutedIntent.PassThrough
import com.anezium.rokidbus.glasses.input.RoutedIntent.ToLegacyLauncher
import com.anezium.rokidbus.glasses.input.RoutedIntent.ToNotice
import com.anezium.rokidbus.glasses.input.RoutedIntent.ToRing
import com.anezium.rokidbus.glasses.input.RoutedIntent.ToSession
import com.anezium.rokidbus.glasses.input.RoutedIntent.ToSurface
import com.anezium.rokidbus.glasses.session.SessionEffect
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionModel
import com.anezium.rokidbus.glasses.session.SessionReducer
import com.anezium.rokidbus.glasses.session.SessionState
import com.anezium.rokidbus.glasses.session.Underneath
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbiter with a scripted context and the real PR1 reducer behind the session, so the
 * session rules are checked on the transitions they cause, not only on the routed events.
 */
class InputArbiterTest {
    private class Rig : InputContext {
        override var backend = LauncherBackend.SESSION
        override var editableFocused = false
        override var noticeArmed = false
        override var legacyShown = false
        override var activeSurfaceId: String? = null
        override val surfaceOwnsKeys: Boolean get() = activeSurfaceId != null
        override val nativeInFront: Boolean get() = activeSurfaceId == null && !legacyShown

        var noticeTakes: (RawKeyEvent) -> Boolean = { false }
        var surfaceTakes: (RawKeyEvent) -> Boolean = { true }
        val noticeAsked = mutableListOf<RawKeyEvent>()
        val delivered = mutableListOf<RoutedIntent>()

        val reducer = SessionReducer()
        var model = SessionModel()
        val sessionEffects = mutableListOf<SessionEffect>()

        override val sessionGate: Boolean get() = model.state is SessionState.Opening
        override val sessionOpen: Boolean
            get() = model.state !is SessionState.Closed && model.state !is SessionState.Opening

        override fun noticeHandles(event: RawKeyEvent): Boolean {
            noticeAsked += event
            return noticeTakes(event)
        }

        override fun deliver(intent: RoutedIntent): Boolean {
            delivered += intent
            return when (intent) {
                is ToSession -> {
                    reduce(intent.event)
                    true
                }
                is ToLegacyLauncher -> {
                    if (intent.open) legacyShown = true
                    true
                }
                is ToSurface -> surfaceTakes(intent.key)
                is ToRing -> true
                is ToNotice, PassThrough -> false
            }
        }

        /** What the session runner does when its timer fires. */
        fun reduce(event: SessionEvent) {
            val transition = reducer.reduce(model, event)
            model = transition.model
            sessionEffects += transition.effects.filter { it != SessionEffect.None }
        }

        fun sessionEvents(): List<SessionEvent> = delivered.filterIsInstance<ToSession>().map { it.event }
    }

    private val rig = Rig()
    private val arbiter = InputArbiter(rig)

    @Test
    fun `A1 prog blue passes untouched before anything and leaves no press behind`() {
        openSessionRoot(at = 1_000)

        val down = arbiter.onKey(raw(InputKeys.PROG_BLUE, DOWN, at = 3_000))
        val up = arbiter.onKey(raw(InputKeys.PROG_BLUE, UP, at = 3_100, down = 3_000))

        assertEquals(InputDecision(false, listOf(PassThrough)), down)
        assertEquals(InputDecision(false, listOf(PassThrough)), up)
        assertTrue(rig.delivered.isEmpty())
        assertTrue(rig.noticeAsked.isEmpty())
    }

    @Test
    fun `A2 an orphan up is consumed and routed nowhere`() {
        rig.activeSurfaceId = "player:card"

        val decision = arbiter.onKey(raw(InputKeys.ENTER, UP, at = 2_000, down = 1_500))

        assertEquals(InputDecision(true), decision)
        assertTrue(rig.delivered.isEmpty())
        assertTrue(rig.noticeAsked.isEmpty())
    }

    @Test
    fun `A2 retargeting never happens across a press`() {
        rig.backend = LauncherBackend.LEGACY
        rig.activeSurfaceId = "player:card"
        val down = arbiter.onKey(raw(InputKeys.DPAD_RIGHT, DOWN, at = 1_000))
        assertTrue(down.consumed)
        assertEquals(listOf<RoutedIntent>(ToSurface(raw(InputKeys.DPAD_RIGHT, DOWN, at = 1_000))), down.intents)
        rig.noticeAsked.clear()

        // Ownership would now be the launcher, and the notice would take it: the press stays the surface's.
        rig.activeSurfaceId = null
        rig.legacyShown = true
        rig.noticeTakes = { true }
        val repeat = arbiter.onKey(raw(InputKeys.DPAD_RIGHT, DOWN, at = 1_400, down = 1_000, repeat = 1))
        val up = arbiter.onKey(raw(InputKeys.DPAD_RIGHT, UP, at = 1_500, down = 1_000))

        assertTrue(repeat.consumed)
        assertEquals(listOf<RoutedIntent>(ToSurface(raw(InputKeys.DPAD_RIGHT, DOWN, 1_400, 1_000, 1))), repeat.intents)
        // The UP of a consumed press is swallowed, never sent on, as the filter always did.
        assertEquals(InputDecision(true), up)
        assertTrue(rig.noticeAsked.isEmpty())
        assertTrue(rig.delivered.none { it is ToLegacyLauncher })
    }

    @Test
    fun `A2 a session press keeps the session through its up after the session closed`() {
        openSessionRoot(at = 1_000)
        arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 3_000))
        rig.reduce(SessionEvent.Back(3_050))
        assertTrue(rig.model.state is SessionState.Closed)
        rig.activeSurfaceId = "player:card"
        rig.delivered.clear()

        val up = arbiter.onKey(raw(InputKeys.ENTER, UP, at = 3_100, down = 3_000))

        assertTrue(up.consumed)
        assertEquals(listOf<RoutedIntent>(ToSession(SessionEvent.Enter(3_100))), rig.delivered)
    }

    @Test
    fun `A2 a missing up cannot swallow the up of a later press`() {
        rig.activeSurfaceId = "player:card"
        assertTrue(arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 1_000, deviceId = 7)).consumed)
        // That press never sends its UP. A later press of the same key passes through, UP included.
        rig.activeSurfaceId = null
        val down = arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 2_000, deviceId = 7))
        val up = arbiter.onKey(raw(InputKeys.ENTER, UP, at = 2_100, down = 2_000, deviceId = 7))

        assertFalse(down.consumed)
        assertFalse(up.consumed)
        assertEquals(listOf<RoutedIntent>(PassThrough), up.intents)
    }

    @Test
    fun `A3 the gate trace TripleTap 1000 Contact 1700 Tick 1800 Enter 2500 is absorbed`() {
        tripleTap(600, 800, 1_000)
        assertEquals(nativeTripleTap(1_000), rig.sessionEvents().single())
        assertTrue(rig.sessionGate)

        val contact = arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 1_700))
        assertTrue(contact.consumed)
        rig.reduce(SessionEvent.Tick(1_800))
        assertTrue(rig.model.state is SessionState.Root)
        rig.sessionEffects.clear()

        arbiter.onKey(raw(InputKeys.NOTIFICATION, UP, at = 1_760, down = 1_700))
        val down = arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 2_450))
        val up = arbiter.onKey(raw(InputKeys.ENTER, UP, at = 2_500, down = 2_450))

        assertTrue(down.consumed && up.consumed)
        assertEquals(
            listOf(nativeTripleTap(1_000), SessionEvent.Contact(1_700), SessionEvent.Enter(2_500)),
            rig.sessionEvents(),
        )
        assertTrue(rig.model.state is SessionState.Root)
        assertTrue(rig.sessionEffects.isEmpty())
    }

    @Test
    fun `A3 every touchpad classification during the gate goes to the reducer and is consumed`() {
        tripleTap(600, 800, 1_000)
        rig.activeSurfaceId = "player:card"
        rig.noticeTakes = { true }
        rig.noticeAsked.clear()
        val keys = listOf(InputKeys.ENTER, InputKeys.BACK, InputKeys.DPAD_RIGHT)
        keys.forEachIndexed { index, code ->
            val at = 1_100L + index * 100
            assertTrue(arbiter.onKey(raw(code, DOWN, at = at)).consumed)
            assertTrue(arbiter.onKey(raw(code, UP, at = at + 30, down = at)).consumed)
        }

        assertEquals(
            listOf(
                nativeTripleTap(1_000), SessionEvent.Enter(1_130), SessionEvent.Back(1_230),
                SessionEvent.Step(1, 1_300),
            ),
            rig.sessionEvents(),
        )
        assertTrue(rig.model.state is SessionState.Opening)
        assertTrue(rig.noticeAsked.isEmpty())
        assertTrue(rig.delivered.none { it is ToSurface })
    }

    @Test
    fun `A3 ring keys during the gate are consumed and dropped`() {
        tripleTap(600, 800, 1_000)
        rig.delivered.clear()

        listOf(InputKeys.RING_TAP, InputKeys.RING_FORWARD, InputKeys.RING_BACKWARD).forEachIndexed { index, code ->
            val at = 1_100L + index * 100
            assertTrue(arbiter.onKey(raw(code, DOWN, at = at, device = DeviceClass.R08)).consumed)
            assertTrue(arbiter.onKey(raw(code, UP, at = at + 30, down = at, device = DeviceClass.R08)).consumed)
        }
        assertTrue(arbiter.onTick(1_800).intents.isEmpty())

        assertTrue(rig.delivered.isEmpty())
        assertNull(arbiter.nextDeadlineMs())
    }

    @Test
    fun `A4 three contacts within 600 ms open the session and 601 ms apart do not`() {
        tripleTap(0, 300, 601)
        assertTrue(rig.sessionEvents().isEmpty())

        tripleTap(2_000, 2_300, 2_600)
        assertEquals(listOf(nativeTripleTap(2_600)), rig.sessionEvents())
        assertEquals(listOf(SessionEffect.ShowGate), rig.sessionEffects)
    }

    @Test
    fun `A4 three fast swipes never trigger`() {
        listOf(0L, 150L, 300L).forEach { at ->
            arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = at))
            arbiter.onKey(raw(InputKeys.DPAD_RIGHT, DOWN, at = at + 40))
            arbiter.onKey(raw(InputKeys.DPAD_RIGHT, UP, at = at + 60, down = at + 40))
        }
        assertTrue(rig.sessionEvents().isEmpty())
    }

    @Test
    fun `A4 an armed notice blocks recognition`() {
        rig.noticeArmed = true
        tripleTap(0, 200, 400)

        assertTrue(rig.sessionEvents().isEmpty())
        assertTrue(rig.model.state is SessionState.Closed)
        // Nothing was recognised, so nothing is left to replay either.
        assertNull(arbiter.nextDeadlineMs())

        rig.noticeArmed = false
        tripleTap(2_000, 2_200, 2_400)
        assertEquals(listOf(nativeTripleTap(2_400)), rig.sessionEvents())
    }

    @Test
    fun `A4 editable blocks recognition`() {
        rig.editableFocused = true
        rig.activeSurfaceId = "notes:card"
        rig.surfaceTakes = { false }
        tripleTap(0, 200, 400)

        assertTrue(rig.sessionEvents().isEmpty())
        assertNull(arbiter.nextDeadlineMs())
        assertEquals(3, rig.delivered.count { it is ToSurface && it.key.isDown })
    }

    @Test
    fun `A4 TripleTap at the root followed by ENTER at 300 ms and BACK at 600 ms produces no SendAction ShowFrame or close`() {
        openSessionRoot(at = 1_000)

        tripleTap(5_000, 5_150, 5_300)
        tap(InputKeys.ENTER, 5_600)
        tap(InputKeys.BACK, 5_900)

        assertEquals(
            listOf(SessionEvent.Contact(5_000), SessionEvent.Contact(5_150), nativeTripleTap(5_300)),
            rig.sessionEvents(),
        )
        assertTrue(rig.model.state is SessionState.Root)
        assertTrue(
            rig.sessionEffects.none {
                it is SessionEffect.SendAction || it == SessionEffect.ShowFrame ||
                    it is SessionEffect.SendClosed || it is SessionEffect.RestoreUnderneath ||
                    it is SessionEffect.RequestPage
            },
        )
        // Past the detector's 800 ms the classifications are the session's again.
        tap(InputKeys.BACK, 6_200)
        assertTrue(rig.model.state is SessionState.Closed)
    }

    @Test
    fun `A4 on the legacy backend the trigger opens the legacy launcher and its suppression swallows the taps`() {
        rig.backend = LauncherBackend.LEGACY
        tripleTap(0, 200, 400)

        val open = rig.delivered.filterIsInstance<ToLegacyLauncher>().single()
        assertTrue(open.open)
        assertTrue(rig.sessionEvents().isEmpty())
        rig.delivered.clear()

        assertTrue(arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 700)).consumed)
        assertTrue(arbiter.onKey(raw(InputKeys.ENTER, UP, at = 750, down = 700)).consumed)
        assertTrue(rig.delivered.isEmpty())

        // Past 800 ms the launcher, now shown, gets the next tap.
        tap(InputKeys.ENTER, 1_300)
        assertEquals(1, rig.delivered.count { it is ToLegacyLauncher && !it.open })
    }

    @Test
    fun `A5 an open session owns touchpad navigation and nothing else hears it`() {
        openSessionRoot(at = 1_000)
        rig.activeSurfaceId = "player:card"
        rig.noticeTakes = { true }

        // The firmware's duplicated swipe pair counts once.
        arbiter.onKey(raw(InputKeys.DPAD_DOWN, DOWN, at = 3_000))
        arbiter.onKey(raw(InputKeys.DPAD_DOWN, DOWN, at = 3_040))
        arbiter.onKey(raw(InputKeys.DPAD_DOWN, UP, at = 3_050, down = 3_000))
        arbiter.onKey(raw(InputKeys.DPAD_DOWN, UP, at = 3_060, down = 3_040))
        tap(InputKeys.DPAD_UP, 3_400)
        val other = arbiter.onKey(raw(KEYCODE_SPACE, DOWN, at = 3_700))
        tap(InputKeys.BACK, 4_000)

        assertTrue(other.consumed)
        assertEquals(
            listOf(SessionEvent.Step(1, 3_000), SessionEvent.Step(-1, 3_400), SessionEvent.Back(4_030)),
            rig.sessionEvents(),
        )
        assertTrue(rig.delivered.all { it is ToSession })
        assertTrue(rig.noticeAsked.isEmpty())
    }

    @Test
    fun `A5 an open session owns the ring and resolves its taps on the tick`() {
        openSessionRoot(at = 1_000)
        rig.noticeArmed = true

        ring(InputKeys.RING_FORWARD, 3_000)
        ring(InputKeys.RING_TAP, 3_200)
        assertEquals(3_200 + InputArbiter.RING_TAP_DEADLINE_MS, arbiter.nextDeadlineMs())
        arbiter.onTick(3_200 + InputArbiter.RING_TAP_DEADLINE_MS)
        // Two taps 340 ms apart are a double; 360 ms apart they would be two singles.
        ring(InputKeys.RING_TAP, 5_000)
        ring(InputKeys.RING_TAP, 5_340)
        arbiter.onTick(5_340 + InputArbiter.RING_TAP_DEADLINE_MS)

        assertEquals(
            listOf(
                SessionEvent.Step(1, 3_000),
                SessionEvent.Enter(3_200 + InputArbiter.RING_TAP_DEADLINE_MS),
                SessionEvent.Back(5_340 + InputArbiter.RING_TAP_DEADLINE_MS),
            ),
            rig.sessionEvents(),
        )
        assertTrue(rig.delivered.none { it is ToRing })
    }

    @Test
    fun `A6 a closed session hands keys to the notice then the legacy launcher then the surface`() {
        rig.backend = LauncherBackend.LEGACY
        rig.noticeTakes = { it.keyCode == InputKeys.BACK }
        rig.legacyShown = true
        rig.activeSurfaceId = "player:card"

        val back = tap(InputKeys.BACK, 1_000)
        val enter = tap(InputKeys.ENTER, 2_000)
        rig.legacyShown = false
        val right = tap(InputKeys.DPAD_RIGHT, 3_000)

        assertTrue(back.first.consumed && enter.first.consumed && right.first.consumed)
        assertEquals(listOf<RoutedIntent>(ToNotice(raw(InputKeys.BACK, DOWN, at = 1_000))), back.first.intents)
        assertEquals(listOf<RoutedIntent>(ToLegacyLauncher(raw(InputKeys.ENTER, DOWN, at = 2_000))), enter.first.intents)
        assertEquals(listOf<RoutedIntent>(ToSurface(raw(InputKeys.DPAD_RIGHT, DOWN, at = 3_000))), right.first.intents)
        // The notice keeps its own press: its UP goes back to it.
        assertEquals(listOf<RoutedIntent>(ToNotice(raw(InputKeys.BACK, UP, at = 1_030, down = 1_000))), back.second.intents)
    }

    @Test
    fun `A6 native pass-through when nothing owns`() {
        val (down, up) = tap(InputKeys.ENTER, 1_000)

        assertEquals(InputDecision(false, listOf(PassThrough)), down)
        assertEquals(InputDecision(false, listOf(PassThrough)), up)
        assertEquals(1, rig.noticeAsked.size)
    }

    @Test
    fun `A6 a key the surface does not take reaches the system with its up`() {
        rig.activeSurfaceId = "notes:card"
        rig.surfaceTakes = { false }

        val (down, up) = tap(KEYCODE_A, 1_000)

        assertFalse(down.consumed)
        assertFalse(up.consumed)
        assertEquals(listOf<RoutedIntent>(ToSurface(raw(KEYCODE_A, UP, at = 1_030, down = 1_000))), up.intents)
    }

    @Test
    fun `A7 the ring outside a session keeps its policy and passes when nothing owns it`() {
        val passed = ring(InputKeys.RING_TAP, 1_000)
        assertFalse(passed.first.consumed)
        assertFalse(passed.second.consumed)

        rig.activeSurfaceId = "player:card"
        val owned = ring(InputKeys.RING_FORWARD, 2_000)
        assertTrue(owned.first.consumed && owned.second.consumed)
        assertEquals(listOf<RoutedIntent>(ToRing(raw(InputKeys.RING_FORWARD, DOWN, 2_000, device = DeviceClass.R08))), owned.first.intents)
        assertTrue(owned.second.intents.isEmpty())

        rig.activeSurfaceId = null
        rig.noticeArmed = true
        assertTrue(ring(InputKeys.RING_TAP, 3_000).first.consumed)
        assertTrue(rig.sessionEvents().isEmpty())
    }

    @Test
    fun `A8 an open session never asks the notice dispatcher`() {
        openSessionRoot(at = 1_000)
        rig.noticeTakes = { true }

        tap(InputKeys.BACK, 3_000)

        assertTrue(rig.noticeAsked.isEmpty())
        assertTrue(rig.model.state is SessionState.Closed)
    }

    @Test
    fun `backend switch exclusivity only the active backend receives GLOBAL and a reset drops pending contacts`() {
        rig.backend = LauncherBackend.LEGACY
        tripleTap(0, 200, 400)
        assertEquals(1, rig.delivered.count { it is ToLegacyLauncher && it.open })
        assertTrue(rig.sessionEvents().isEmpty())

        rig.legacyShown = false
        rig.backend = LauncherBackend.SESSION
        arbiter.reset()
        tripleTap(2_000, 2_200, 2_400)
        assertEquals(listOf(nativeTripleTap(2_400)), rig.sessionEvents())
        assertEquals(1, rig.delivered.count { it is ToLegacyLauncher && it.open })

        // Two contacts, then a switch: the streak is gone and nothing is replayed.
        rig.reduce(SessionEvent.Tick(3_200))
        rig.reduce(SessionEvent.Back(3_300))
        rig.activeSurfaceId = "player:card"
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 4_000))
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 4_200))
        arbiter.reset()
        rig.backend = LauncherBackend.LEGACY
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 4_400))
        // Without the reset, 4 000, 4 200 and 4 400 would have been a triple tap.
        assertEquals(1, rig.delivered.count { it is ToLegacyLauncher && it.open })
        assertEquals(1, arbiter.onTick(5_100).intents.count { it is ToSurface && it.unclassifiedContact })
    }

    @Test
    fun `expired contacts replay at most two to the surface and never to a notice`() {
        rig.backend = LauncherBackend.LEGACY
        rig.activeSurfaceId = "player:card"
        rig.noticeTakes = { true }
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 1_000))
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 1_200))
        assertEquals(1_200 + InputArbiter.CONTACT_DEADLINE_MS, arbiter.nextDeadlineMs())

        assertTrue(arbiter.onTick(1_800).intents.isEmpty())
        val flushed = arbiter.onTick(1_200 + InputArbiter.CONTACT_DEADLINE_MS).intents

        assertEquals(2, flushed.size)
        assertTrue(flushed.all { it is ToSurface && it.unclassifiedContact })
        assertNull(arbiter.nextDeadlineMs())
    }

    @Test
    fun `a non contact down cancels the pending flush`() {
        rig.activeSurfaceId = "player:card"
        arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = 1_000))
        arbiter.onKey(raw(InputKeys.ENTER, DOWN, at = 1_300))

        assertNull(arbiter.nextDeadlineMs())
        assertTrue(arbiter.onTick(2_000).intents.isEmpty())
    }

    @Test
    fun `a window key of a press the filter routed is left to the window`() {
        rig.activeSurfaceId = "notes:card"
        rig.surfaceTakes = { false }
        val down = raw(KEYCODE_A, DOWN, at = 1_000)
        arbiter.onKey(down)
        rig.delivered.clear()

        assertEquals(InputDecision(false), arbiter.onWindowKey(down))
        arbiter.onKey(raw(KEYCODE_A, UP, at = 1_050, down = 1_000))
        assertEquals(InputDecision(false), arbiter.onWindowKey(raw(KEYCODE_A, UP, at = 1_050, down = 1_000)))
        assertEquals(1, rig.delivered.size)
    }

    @Test
    fun `a press only a window sees is routed without global recognition`() {
        rig.activeSurfaceId = "player:card"
        listOf(0L, 200L, 400L).forEach { at -> arbiter.onWindowKey(raw(InputKeys.NOTIFICATION, DOWN, at = at)) }
        assertTrue(rig.sessionEvents().isEmpty())
        assertNull(arbiter.nextDeadlineMs())

        val down = arbiter.onWindowKey(raw(InputKeys.ENTER, DOWN, at = 1_000))
        val up = arbiter.onWindowKey(raw(InputKeys.ENTER, UP, at = 1_050, down = 1_000))
        assertTrue(down.consumed)
        assertEquals(listOf<RoutedIntent>(ToSurface(raw(InputKeys.ENTER, DOWN, at = 1_000))), down.intents)
        assertEquals(InputDecision(true), up)
    }

    @Test
    fun `the classifier keeps every device but the ring on the touchpad pipeline`() {
        assertEquals(DeviceClass.R08, KeyEventAdapter.classify("Rokid R08 Ring"))
        assertEquals(DeviceClass.R08, KeyEventAdapter.classify("r08-ring"))
        assertEquals(DeviceClass.TOUCHPAD, KeyEventAdapter.classify("ROKID,PSOC-TP-R"))
        assertEquals(DeviceClass.TOUCHPAD, KeyEventAdapter.classify("Logitech K380"))
        assertEquals(DeviceClass.TOUCHPAD, KeyEventAdapter.classify(null))
    }

    @Test
    fun `A4 the triple tap records the surface it was made over`() {
        rig.activeSurfaceId = "player:card"
        tripleTap(0, 200, 400)

        assertEquals(
            listOf(SessionEvent.TripleTap(400, Underneath.NexusSurface("player:card"))),
            rig.sessionEvents(),
        )
        assertEquals(Underneath.NexusSurface("player:card"), rig.model.underneath)
    }

    private fun nativeTripleTap(at: Long) = SessionEvent.TripleTap(at, Underneath.NativeApp)

    private fun openSessionRoot(at: Long) {
        tripleTap(at - 400, at - 200, at)
        rig.reduce(SessionEvent.Tick(at + PageSurfaceContract.GATE_MS))
        assertTrue(rig.model.state is SessionState.Root)
        rig.delivered.clear()
        rig.noticeAsked.clear()
        rig.sessionEffects.clear()
    }

    private fun tripleTap(vararg at: Long) {
        at.forEach { time ->
            arbiter.onKey(raw(InputKeys.NOTIFICATION, DOWN, at = time))
            arbiter.onKey(raw(InputKeys.NOTIFICATION, UP, at = time + 40, down = time))
        }
    }

    private fun tap(code: Int, at: Long): Pair<InputDecision, InputDecision> =
        arbiter.onKey(raw(code, DOWN, at = at)) to arbiter.onKey(raw(code, UP, at = at + 30, down = at))

    private fun ring(code: Int, at: Long): Pair<InputDecision, InputDecision> =
        arbiter.onKey(raw(code, DOWN, at = at, device = DeviceClass.R08)) to
            arbiter.onKey(raw(code, UP, at = at + 30, down = at, device = DeviceClass.R08))

    private fun raw(
        code: Int,
        action: Int,
        at: Long,
        down: Long = at,
        repeat: Int = 0,
        device: DeviceClass = DeviceClass.TOUCHPAD,
        deviceId: Int = if (device == DeviceClass.R08) 9 else 1,
    ) = RawKeyEvent(code, action, repeat, at, down, deviceId, device)

    private companion object {
        const val DOWN = RawKeyEvent.ACTION_DOWN
        const val UP = RawKeyEvent.ACTION_UP
        const val KEYCODE_SPACE = 62
        const val KEYCODE_A = 29
    }
}
