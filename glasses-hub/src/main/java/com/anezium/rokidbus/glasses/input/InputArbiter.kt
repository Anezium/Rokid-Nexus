// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.input

import com.anezium.rokidbus.glasses.DpadPairDedupe
import com.anezium.rokidbus.glasses.RingTapPolicy
import com.anezium.rokidbus.glasses.TripleTapDetector
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.Underneath

/** Where the arbiter sent an event, in the order it was sent. */
internal sealed interface RoutedIntent {
    data class ToSession(val event: SessionEvent) : RoutedIntent

    /** A key for the legacy launcher, or with [open] the global gesture that opens it. */
    data class ToLegacyLauncher(val key: RawKeyEvent, val open: Boolean = false) : RoutedIntent

    data class ToNotice(val key: RawKeyEvent) : RoutedIntent

    /** With [unclassifiedContact], a touchpad contact the firmware never classified, replayed late. */
    data class ToSurface(val key: RawKeyEvent, val unclassifiedContact: Boolean = false) : RoutedIntent

    /** An R08 key for the existing ring policy (notice-owned ring, launcher, surface). */
    data class ToRing(val key: RawKeyEvent) : RoutedIntent

    data object PassThrough : RoutedIntent
}

/** [consumed] is the whole answer to "does this key reach the system". */
internal data class InputDecision(val consumed: Boolean, val intents: List<RoutedIntent> = emptyList())

/**
 * What the arbiter reads at each event. Everything is owned elsewhere; the arbiter only orders
 * the owners. The two delivery hooks run the owner synchronously because only the owner knows
 * whether it took the key: the notice dispatcher acts itself, as it always has, and every other
 * intent is handed over through [deliver].
 */
internal interface InputContext {
    val backend: LauncherBackend
    val editableFocused: Boolean

    /** The session is in its gate (`Opening`). */
    val sessionGate: Boolean

    /** The session is past its gate: root, page or a pending plugin open. */
    val sessionOpen: Boolean

    /** The generation of the latest session; every session that opens has a new one. */
    val sessionGeneration: Long

    /**
     * A visible notice can claim input (interactive, action-bearing, paged or backdrop). False
     * while the notice is suppressed by the camera overlay or an open session.
     */
    val noticeArmed: Boolean

    val legacyShown: Boolean
    val surfaceOwnsKeys: Boolean
    val activeSurfaceId: String?

    /** No Nexus window is in front, so a passed key reaches a native app or the system. */
    val nativeInFront: Boolean

    /** Asks the notice dispatcher; true when the notice took [event]. */
    fun noticeHandles(event: RawKeyEvent): Boolean

    /** Hands [intent] to its owner; true when the owner consumed the key. */
    fun deliver(intent: RoutedIntent): Boolean
}

/**
 * The single arbiter every key goes through (plan 027 decision 1). Order: reserved keys pass;
 * a press stays with the owner decided at its DOWN; the session gate; global recognition from
 * touchpad contacts; an open session owns navigation; a closed session hands keys to the notice
 * dispatcher, the legacy launcher, the active surface, then the system. No activity island
 * takes input on either backend.
 *
 * Clock: every duration is measured on `KeyEvent.eventTime`, the uptime clock, and the caller
 * drives [onTick] at [nextDeadlineMs] on the same clock, never `elapsedRealtime` (a deep sleep
 * must not expire a tap the wearer never left open).
 *
 * Not thread-safe: call from the main thread, like the accessibility service's `onKeyEvent`.
 */
internal class InputArbiter(private val context: InputContext) {
    private enum class Owner { SESSION, NOTICE, LEGACY, SURFACE, RING, DROPPED, PASSED }

    private data class Press(val deviceId: Int, val keyCode: Int, val downTime: Long)

    /**
     * [viaWindow] marks a press the filter never saw. A press is [completed] by its first UP; the
     * record is then kept until [PRESS_TTL_MS] passes, so a window that receives the UP the filter
     * let through still recognises it, and a duplicate UP finds it.
     */
    private class Held(
        val owner: Owner,
        val consumed: Boolean,
        val viaWindow: Boolean,
        var lastSeenMs: Long,
        /** For a session press, the generation of the session it began in. */
        val generation: Long?,
    ) {
        var completed = false
    }

    private val presses = LinkedHashMap<Press, Held>()
    private var tripleTap = TripleTapDetector()
    private var swipes = DpadPairDedupe()
    private val ringTaps = RingTapPolicy()
    private var ringTapPending = false
    private var ringTapAt = 0L
    private var ringTapGeneration = 0L

    /** Time of the latest contact the detector let through, while its streak may still be replayed. */
    private var contactAt: Long? = null

    /**
     * Whether the detector's post-trigger suppression is in force for the session backend. A
     * trigger that opens the session is followed by the reducer's gate instead; a trigger inside
     * an open session opens no gate, so its own ENTER/BACK classifications must be swallowed.
     */
    private var suppressionHonoured = false

    /** Every key the accessibility filter sees, before any window does. */
    fun onKey(event: RawKeyEvent): InputDecision {
        if (event.keyCode == InputKeys.PROG_BLUE) return PASS
        continuation(event)?.let { return it }
        val out = ArrayList<RoutedIntent>(2)
        return if (event.deviceClass == DeviceClass.R08) {
            ringPress(event, out)
        } else {
            press(event, global = true, out)
        }
    }

    /**
     * A key that reached a Nexus window. The filter sees every key first, so a press it already
     * routed reached the window only because it was let through: the window's own views may
     * have it. A press the filter never saw (keys injected straight into the window) is routed
     * here, without global recognition.
     */
    fun onWindowKey(event: RawKeyEvent): InputDecision {
        if (event.keyCode == InputKeys.PROG_BLUE) return PASS
        presses[pressOf(event)]?.takeUnless { it.viaWindow }?.let { return InputDecision(it.consumed) }
        continuation(event)?.let { return it }
        val out = ArrayList<RoutedIntent>(2)
        return press(event, global = false, out)
    }

    /** Settles a ring tap whose window closed and the contacts of a streak that never tripled. */
    fun onTick(nowMs: Long): InputDecision {
        val out = ArrayList<RoutedIntent>(2)
        resolveRingTaps(nowMs, out)
        flushContacts(nowMs, out)
        return InputDecision(consumed = false, out)
    }

    /** When [onTick] next has something to do; null when idle. */
    fun nextDeadlineMs(): Long? {
        val tap = if (ringTapPending) ringTapAt + RING_TAP_DEADLINE_MS else null
        val contact = contactAt?.let { it + CONTACT_DEADLINE_MS }
        return listOfNotNull(tap, contact).minOrNull()
    }

    /**
     * Drops pending contacts, the triple-tap streak and a pending ring tap, as a backend switch
     * requires. Presses already owned keep their owner: a dying gesture is never retargeted.
     */
    fun reset() {
        ringTaps.reset()
        ringTapPending = false
        contactAt = null
        tripleTap = TripleTapDetector()
        swipes = DpadPairDedupe()
        suppressionHonoured = false
    }

    /**
     * Forgets every press, held ones included. Only for the end of the input lifecycle (the
     * service going away): no key the arbiter decided can arrive after it.
     */
    fun forgetPresses() {
        presses.clear()
    }

    // ---- press ownership -----------------------------------------------------------------------

    /** Repeats and the UP of a known press go to its owner; an UP or repeat of an unknown press is an orphan. */
    private fun continuation(event: RawKeyEvent): InputDecision? {
        if (event.startsPress) return null
        if (!event.isDown && !event.isUp) return null
        val held = presses[pressOf(event)] ?: return InputDecision(consumed = true)
        // A press ends at its first UP: a duplicate UP or a repeat after it means nothing.
        if (held.completed) return InputDecision(consumed = true)
        held.lastSeenMs = event.eventTime
        if (event.isUp) held.completed = true
        val out = ArrayList<RoutedIntent>(1)
        val consumed = when (held.owner) {
            // A session press acts only on the session it began in, and only while that session
            // is up: an aborted session's press must not close the one that replaced it.
            Owner.SESSION -> {
                if (sessionStillUp(held.generation)) {
                    sessionEvent(event)?.let { deliver(RoutedIntent.ToSession(it), out) }
                }
                true
            }
            // The dispatcher keeps its own press bookkeeping and answers for the whole press.
            Owner.NOTICE -> {
                if (context.noticeHandles(event)) out += RoutedIntent.ToNotice(event)
                true
            }
            // Repeats keep reaching the owner. The surface and the launcher have never been sent
            // the UP of a press the filter consumed, so that UP is only swallowed.
            Owner.LEGACY, Owner.SURFACE ->
                if (event.isUp && held.consumed) {
                    true
                } else {
                    val intent = if (held.owner == Owner.LEGACY) {
                        RoutedIntent.ToLegacyLauncher(event)
                    } else {
                        RoutedIntent.ToSurface(event)
                    }
                    deliver(intent, out) || held.consumed
                }
            Owner.RING, Owner.DROPPED -> true
            Owner.PASSED -> {
                out += RoutedIntent.PassThrough
                false
            }
        }
        return InputDecision(consumed, out)
    }

    private fun own(
        event: RawKeyEvent,
        owner: Owner,
        consumed: Boolean,
        out: List<RoutedIntent>,
        viaWindow: Boolean = false,
    ): InputDecision {
        if (event.startsPress) {
            // Only completed presses expire: a key can be held, silently, for as long as the
            // wearer likes (a modifier on a bonded keyboard sends no repeats).
            val stale = event.eventTime - PRESS_TTL_MS
            presses.values.removeAll { it.completed && it.lastSeenMs < stale }
            val generation = if (owner == Owner.SESSION) context.sessionGeneration else null
            presses[pressOf(event)] = Held(owner, consumed, viaWindow, event.eventTime, generation)
        }
        return InputDecision(consumed, out)
    }

    // ---- touchpad and every other non-ring device ----------------------------------------------

    private fun press(event: RawKeyEvent, global: Boolean, out: MutableList<RoutedIntent>): InputDecision {
        val session = context.backend == LauncherBackend.SESSION
        val viaWindow = !global

        // A3: the gate absorbs every contact and classification through the reducer.
        if (session && context.sessionGate) return toSession(event, viaWindow, out)

        // A4: global recognition, from touchpad contacts the filter saw. A focused field turns the
        // detector off entirely, as it always has: its keys are the wearer's typing, suppression
        // included. An armed notice only stops a new triple tap from being recognised; the
        // suppression of one already recognised stays in force under it, or a notice arriving
        // after the trigger would be answered by the taps that completed it.
        if (global && event.isDown && event.keyCode != InputKeys.NOTIFICATION) contactAt = null
        val recognising = global && recognises(event)
        val enforcing = global && event.deviceClass == DeviceClass.TOUCHPAD && !context.editableFocused &&
            event.keyCode != InputKeys.NOTIFICATION
        // A key other than a contact can only be suppressed or passed by the detector, never trigger it.
        val decision = if (recognising || enforcing) {
            tripleTap.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        } else {
            TripleTapDetector.Decision.PASS
        }
        when (decision) {
            TripleTapDetector.Decision.TRIGGER -> return trigger(event, session, out)
            // The legacy launcher has no other gate. On the session backend the detector's own
            // suppression stands unless the session that trigger opened is past its gate, where
            // the reducer still tracks the gate's contacts (PR1 rule 2).
            TripleTapDetector.Decision.CONSUME ->
                if (!session || suppressionHonoured || !context.sessionOpen) {
                    return own(event, Owner.DROPPED, consumed = true, out)
                }
            TripleTapDetector.Decision.PASS ->
                if (recognising && event.keyCode == InputKeys.NOTIFICATION && event.startsPress) {
                    contactAt = event.eventTime
                }
        }

        // A5: an open session owns navigation.
        if (session && context.sessionOpen) return toSession(event, viaWindow, out)

        // A6: the session is closed.
        if (event.startsPress && context.noticeHandles(event)) {
            out += RoutedIntent.ToNotice(event)
            return own(event, Owner.NOTICE, consumed = true, out, viaWindow)
        }
        if (context.legacyShown) {
            val consumed = deliver(RoutedIntent.ToLegacyLauncher(event), out)
            return own(event, Owner.LEGACY, consumed, out, viaWindow)
        }
        if (context.surfaceOwnsKeys) {
            val consumed = deliver(RoutedIntent.ToSurface(event), out)
            return own(event, Owner.SURFACE, consumed, out, viaWindow)
        }
        out += RoutedIntent.PassThrough
        return own(event, Owner.PASSED, consumed = false, out, viaWindow)
    }

    /** A4's guard on new recognition: none from the ring, in a focused field, or under an armed notice. */
    private fun recognises(event: RawKeyEvent): Boolean =
        event.deviceClass == DeviceClass.TOUCHPAD && !context.editableFocused && !context.noticeArmed

    private fun trigger(event: RawKeyEvent, session: Boolean, out: MutableList<RoutedIntent>): InputDecision {
        contactAt = null
        // A second triple tap inside an open session opens no gate, so the taps that complete
        // it stay swallowed by the detector; a session it opens has its gate absorb them.
        suppressionHonoured = session && context.sessionOpen
        if (session) {
            deliver(RoutedIntent.ToSession(SessionEvent.TripleTap(event.eventTime, underneath())), out)
            return own(event, Owner.SESSION, consumed = true, out)
        }
        deliver(RoutedIntent.ToLegacyLauncher(event, open = true), out)
        return own(event, Owner.LEGACY, consumed = true, out)
    }

    /**
     * What the session opens over. Telling the Rokid home from another native scene needs a
     * foreground resolver, so a native base is recorded as [Underneath.NativeApp] for now.
     */
    private fun underneath(): Underneath =
        context.activeSurfaceId?.let { Underneath.NexusSurface(it) }
            ?: if (context.nativeInFront) Underneath.NativeApp else Underneath.Unknown

    private fun toSession(event: RawKeyEvent, viaWindow: Boolean, out: MutableList<RoutedIntent>): InputDecision {
        sessionEvent(event)?.let { deliver(RoutedIntent.ToSession(it), out) }
        return own(event, Owner.SESSION, consumed = true, out, viaWindow)
    }

    /**
     * A contact DOWN is a `Contact`; ENTER and BACK count on their UP, as the firmware's
     * classification completes; a swipe counts once per duplicated pair. Everything else an
     * open session receives is consumed without meaning.
     */
    private fun sessionEvent(event: RawKeyEvent): SessionEvent? {
        val key = event.keyCode
        return when {
            key == InputKeys.NOTIFICATION && event.startsPress -> SessionEvent.Contact(event.eventTime)
            key in InputKeys.CONFIRMS && event.isUp -> SessionEvent.Enter(event.eventTime)
            key == InputKeys.BACK && event.isUp -> SessionEvent.Back(event.eventTime)
            key in InputKeys.DIRECTIONS && event.startsPress ->
                when (swipes.onKey(key, event.action, event.repeatCount, event.eventTime)) {
                    DpadPairDedupe.Direction.FORWARD -> SessionEvent.Step(1, event.eventTime)
                    DpadPairDedupe.Direction.BACKWARD -> SessionEvent.Step(-1, event.eventTime)
                    null -> null
                }
            else -> null
        }
    }

    // ---- ring (R08) ----------------------------------------------------------------------------

    private fun ringPress(event: RawKeyEvent, out: MutableList<RoutedIntent>): InputDecision {
        // A tap that outlived its window is settled before anything newer counts.
        resolveRingTaps(event.eventTime, out)
        val session = context.backend == LauncherBackend.SESSION
        if (session && context.sessionGate) return own(event, Owner.DROPPED, consumed = true, out)
        if (session && context.sessionOpen) {
            when (event.keyCode) {
                InputKeys.RING_FORWARD -> deliver(RoutedIntent.ToSession(SessionEvent.Step(1, event.eventTime)), out)
                InputKeys.RING_BACKWARD -> deliver(RoutedIntent.ToSession(SessionEvent.Step(-1, event.eventTime)), out)
                InputKeys.RING_TAP -> {
                    ringTaps.onTap(event.eventTime)
                    ringTapPending = true
                    ringTapAt = event.eventTime
                    ringTapGeneration = context.sessionGeneration
                }
            }
            return own(event, Owner.SESSION, consumed = true, out)
        }
        // A7: the existing ring policy, minus the activity claims.
        if (!context.legacyShown && !context.surfaceOwnsKeys && !context.noticeArmed) {
            out += RoutedIntent.PassThrough
            return own(event, Owner.PASSED, consumed = false, out)
        }
        deliver(RoutedIntent.ToRing(event), out)
        return own(event, Owner.RING, consumed = true, out)
    }

    /** Ring taps resolve for the session only: the other ring owners keep their own tap timers. */
    private fun resolveRingTaps(nowMs: Long, out: MutableList<RoutedIntent>) {
        if (!ringTapPending) return
        val resolution = ringTaps.resolveExpired(nowMs) ?: return
        ringTapPending = false
        if (!sessionStillUp(ringTapGeneration)) return
        when (resolution) {
            RingTapPolicy.Resolution.SINGLE -> deliver(RoutedIntent.ToSession(SessionEvent.Enter(nowMs)), out)
            RingTapPolicy.Resolution.DOUBLE -> deliver(RoutedIntent.ToSession(SessionEvent.Back(nowMs)), out)
            RingTapPolicy.Resolution.IGNORE -> Unit
        }
    }

    /**
     * One or two contacts the firmware never classified because the streak did not reach three.
     * They are replayed as raw contacts to an active surface, and never to a notice: a band is
     * answered once and must not be answered by a touch that might be the start of a swipe.
     */
    private fun flushContacts(nowMs: Long, out: MutableList<RoutedIntent>) {
        val at = contactAt ?: return
        if (nowMs - at <= TripleTapDetector.DEFAULT_WINDOW_MS) return
        contactAt = null
        val count = tripleTap.consumeExpiredTapCount(nowMs)
        val sessionOwns = context.backend == LauncherBackend.SESSION && (context.sessionGate || context.sessionOpen)
        if (sessionOwns || !context.surfaceOwnsKeys) return
        val contact = RawKeyEvent(
            keyCode = InputKeys.NOTIFICATION,
            action = RawKeyEvent.ACTION_DOWN,
            repeatCount = 0,
            eventTime = nowMs,
            downTime = nowMs,
            deviceId = 0,
            deviceClass = DeviceClass.TOUCHPAD,
        )
        repeat(count) { deliver(RoutedIntent.ToSurface(contact, unclassifiedContact = true), out) }
    }

    private fun sessionStillUp(generation: Long?): Boolean =
        generation == context.sessionGeneration && (context.sessionGate || context.sessionOpen)

    private fun deliver(intent: RoutedIntent, out: MutableList<RoutedIntent>): Boolean {
        out += intent
        return context.deliver(intent)
    }

    private fun pressOf(event: RawKeyEvent) = Press(event.deviceId, event.keyCode, event.downTime)

    companion object {
        /** One past each window, as the timers have always been armed. */
        const val RING_TAP_DEADLINE_MS = RingTapPolicy.DEFAULT_WINDOW_MS + 1L
        const val CONTACT_DEADLINE_MS = TripleTapDetector.DEFAULT_WINDOW_MS + 1L

        /** How long a completed press is remembered, for its window echo and a duplicate UP. */
        private const val PRESS_TTL_MS = 5_000L
        private val PASS = InputDecision(consumed = false, listOf(RoutedIntent.PassThrough))
    }
}
