package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Why a Settings-automation run stopped holding the display. */
internal enum class SetupDisplayHoldEnd(val logValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    STOPPED("stopped"),
    SESSION_ENDED("session_ended"),
    SERVICE_DESTROYED("service_destroyed"),
    RUN_EXPIRED("run_expired"),
    LEASE_EXPIRED("lease_expired"),
    WEARER_SLEPT("wearer_slept"),
    HOLD_FAILURE("hold_failure"),
    ;

    companion object {
        fun forFinish(setupState: String, success: Boolean): SetupDisplayHoldEnd = when {
            success -> SUCCEEDED
            setupState.endsWith("_timeout") -> TIMED_OUT
            else -> FAILED
        }
    }
}

internal sealed interface SetupDisplayHoldTransition {
    val runToken: Long

    data class Acquire(
        override val runToken: Long,
        val leaseMs: Long,
        val wake: Boolean = true,
    ) : SetupDisplayHoldTransition

    data class Renew(override val runToken: Long, val leaseMs: Long) : SetupDisplayHoldTransition

    /** A run that starts while the previous one still holds the panel takes the same lease over. */
    data class Handover(
        override val runToken: Long,
        val fromRunToken: Long,
        val leaseMs: Long,
    ) : SetupDisplayHoldTransition

    data class Release(
        override val runToken: Long,
        val reason: SetupDisplayHoldEnd,
        val ageMs: Long,
        val renewals: Int,
    ) : SetupDisplayHoldTransition

    data class Refused(
        override val runToken: Long,
        val reason: DisplayHoldFailure,
    ) : SetupDisplayHoldTransition
}

/** The setup hold's panel lease; unlike [DisplayHoldLease], each acquisition says whether it wakes. */
internal interface SetupDisplayLease {
    fun acquire(timeoutMs: Long, wake: Boolean)

    fun release()
}

/** Runs an action once after a delay on a thread of its own; the returned function cancels it. */
internal fun interface SetupDisplayHoldBackstop {
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * The one request scope whose wearer slept the panel mid-hold. It outlives any single
 * [SetupDisplayHoldLifecycle]: the ROM recreates the accessibility service mid-session, and a
 * fresh lifecycle that forgot the wearer's choice would wake the panel they just put to sleep.
 */
internal class SetupDisplaySleptScope {
    private var scope: String? = null
    private var checkedAtMs = 0L

    @Synchronized
    fun record(scope: String, atMs: Long) {
        this.scope = scope
        checkedAtMs = atMs
    }

    /** When [scope] was last found dark, or null when its wearer has not slept the panel. */
    @Synchronized
    fun checkedAtMs(scope: String): Long? = if (this.scope == scope) checkedAtMs else null

    @Synchronized
    fun forget(scope: String? = null) {
        if (scope == null || this.scope == scope) this.scope = null
    }

    companion object {
        /** Process-wide, so a recreated service still honours it; forgotten when its session ends. */
        val PROCESS = SetupDisplaySleptScope()
    }
}

/**
 * The display hold for the wireless setup automator's Settings runs.
 *
 * The vendor re-asserts a 5 s screen-off timeout at every boot, and a sleeping
 * panel shows the accessibility service no Settings window, so an unattended
 * run stalls on `waiting_for_settings` until its own timeout. Window flags do
 * not hold this firmware's panel; a screen wake lock does. Each lease is short
 * and only a live run renews it, never past that run's deadline. The wake
 * lock's own timeout is delivered on the main looper the automator runs on, so
 * [backstop] also releases every lease at its expiry from another thread: a run
 * that stops stepping for any reason, a stuck main thread included, lets the
 * panel go within [leaseMs].
 *
 * Only the first acquisition of a hold wakes the panel. While a lease keeps the
 * vendor timeout away, a panel that goes dark was put to sleep by the wearer,
 * so the hold lets go and the runs of that scope leave the panel alone until
 * the wearer lights it again — across a backstop expiry too, and across a
 * recreated lifecycle when the caller shares [sleptScope].
 */
internal class SetupDisplayHoldLifecycle(
    private val isInteractive: () -> Boolean,
    private val backstop: SetupDisplayHoldBackstop,
    private val onBackstopRelease: (SetupDisplayHoldTransition.Release) -> Unit = {},
    private val sleptScope: SetupDisplaySleptScope = SetupDisplaySleptScope(),
    private val leaseMs: Long = LEASE_MS,
    private val renewIntervalMs: Long = RENEW_INTERVAL_MS,
) {
    private class Hold(
        var runToken: Long,
        var scope: String,
        val startedAtMs: Long,
        var lastRenewedAtMs: Long,
        var leaseEndsAtMs: Long,
        var renewals: Int,
        val lease: SetupDisplayLease,
    ) {
        var backstopGeneration = 0L
        var cancelBackstop: () -> Unit = {}
    }

    // The backstop fires on its own thread; every hold transition happens under this lock.
    private val lock = Any()
    private var hold: Hold? = null
    private var backstopGenerations = 0L

    fun heldRunToken(): Long? = synchronized(lock) { hold?.runToken }

    /**
     * Takes or keeps the hold for [runToken] until at most [runDeadlineAtMs].
     * [scope] groups the runs of one request — a setup session's chained runs
     * share it — so a panel the wearer slept stays asleep across them.
     */
    fun renew(
        runToken: Long,
        scope: String,
        nowMs: Long,
        runDeadlineAtMs: Long,
        leaseFactory: () -> SetupDisplayLease?,
    ): SetupDisplayHoldTransition? {
        synchronized(lock) {
            val remainingMs = runDeadlineAtMs - nowMs
            val current = hold
            if (remainingMs <= 0L) {
                return if (current?.runToken == runToken) {
                    releaseLocked(SetupDisplayHoldEnd.RUN_EXPIRED, nowMs, runToken)
                } else {
                    null
                }
            }
            val nextLeaseMs = minOf(leaseMs, remainingMs)
            if (current == null) return acquireLocked(runToken, scope, nowMs, nextLeaseMs, leaseFactory)

            val handover = current.runToken != runToken
            if (!handover && nowMs - current.lastRenewedAtMs < renewIntervalMs) return null
            if (!panelInteractive()) {
                sleptScope.record(current.scope, nowMs)
                return releaseLocked(SetupDisplayHoldEnd.WEARER_SLEPT, nowMs, current.runToken)
            }
            if (runCatching { current.lease.acquire(nextLeaseMs, wake = false) }.isFailure) {
                return releaseLocked(SetupDisplayHoldEnd.HOLD_FAILURE, nowMs, current.runToken)
            }
            val fromRunToken = current.runToken
            current.runToken = runToken
            current.scope = scope
            current.lastRenewedAtMs = nowMs
            current.leaseEndsAtMs = nowMs + nextLeaseMs
            current.renewals += 1
            if (!armBackstopLocked(current, nextLeaseMs)) {
                return releaseLocked(SetupDisplayHoldEnd.HOLD_FAILURE, nowMs, runToken)
            }
            return if (handover) {
                SetupDisplayHoldTransition.Handover(runToken, fromRunToken, nextLeaseMs)
            } else {
                SetupDisplayHoldTransition.Renew(runToken, nextLeaseMs)
            }
        }
    }

    /** Releases only the hold [runToken] owns; a run that was handed over keeps it. */
    fun release(
        reason: SetupDisplayHoldEnd,
        nowMs: Long,
        runToken: Long? = null,
    ): SetupDisplayHoldTransition.Release? = synchronized(lock) { releaseLocked(reason, nowMs, runToken) }

    private fun releaseLocked(
        reason: SetupDisplayHoldEnd,
        nowMs: Long,
        runToken: Long?,
    ): SetupDisplayHoldTransition.Release? {
        val current = hold ?: return null
        if (runToken != null && current.runToken != runToken) return null
        // Cleared first so a duplicate end callback cannot release twice even
        // if the platform release throws.
        hold = null
        runCatching { current.cancelBackstop() }
        runCatching { current.lease.release() }
        return SetupDisplayHoldTransition.Release(
            runToken = current.runToken,
            reason = reason,
            ageMs = (nowMs - current.startedAtMs).coerceAtLeast(0L),
            renewals = current.renewals,
        )
    }

    private fun acquireLocked(
        runToken: Long,
        scope: String,
        nowMs: Long,
        nextLeaseMs: Long,
        leaseFactory: () -> SetupDisplayLease?,
    ): SetupDisplayHoldTransition? {
        val sleptCheckedAtMs = sleptScope.checkedAtMs(scope)
        val resuming = sleptCheckedAtMs != null
        if (sleptCheckedAtMs != null) {
            if (nowMs - sleptCheckedAtMs < renewIntervalMs) return null
            sleptScope.record(scope, nowMs)
            if (!panelInteractive()) return null
        }
        sleptScope.forget()
        // Lit again by the wearer, the panel needs holding, not waking.
        val wake = !resuming
        val lease = runCatching(leaseFactory).getOrNull()
            ?: return SetupDisplayHoldTransition.Refused(
                runToken,
                DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE,
            )
        if (runCatching { lease.acquire(nextLeaseMs, wake) }.isFailure) {
            runCatching { lease.release() }
            return SetupDisplayHoldTransition.Refused(runToken, DisplayHoldFailure.ACQUIRE_FAILED)
        }
        val acquired = Hold(
            runToken = runToken,
            scope = scope,
            startedAtMs = nowMs,
            lastRenewedAtMs = nowMs,
            leaseEndsAtMs = nowMs + nextLeaseMs,
            renewals = 0,
            lease = lease,
        )
        hold = acquired
        if (!armBackstopLocked(acquired, nextLeaseMs)) {
            hold = null
            runCatching { lease.release() }
            return SetupDisplayHoldTransition.Refused(runToken, DisplayHoldFailure.ACQUIRE_FAILED)
        }
        return SetupDisplayHoldTransition.Acquire(runToken, nextLeaseMs, wake)
    }

    /** A lease without a backstop could outlive a stuck main thread, so failing to arm one fails the lease. */
    private fun armBackstopLocked(current: Hold, delayMs: Long): Boolean {
        runCatching { current.cancelBackstop() }
        val generation = ++backstopGenerations
        current.backstopGeneration = generation
        current.cancelBackstop = {}
        return runCatching {
            current.cancelBackstop = backstop.schedule(delayMs) { onBackstop(current, generation) }
        }.isSuccess
    }

    private fun onBackstop(expected: Hold, generation: Long) {
        val released = synchronized(lock) {
            // A renewal, a handover or a release since this was armed owns the lease now.
            if (hold !== expected || expected.backstopGeneration != generation) return
            // Dark while our screen lease still held it: the wearer slept it, even though the
            // stalled run never got to see that. Its next tick must not wake it again.
            val reason = if (panelInteractive()) {
                SetupDisplayHoldEnd.LEASE_EXPIRED
            } else {
                sleptScope.record(expected.scope, expected.leaseEndsAtMs)
                SetupDisplayHoldEnd.WEARER_SLEPT
            }
            releaseLocked(reason, expected.leaseEndsAtMs, expected.runToken)
        }
        released?.let { runCatching { onBackstopRelease(it) } }
    }

    // A probe that fails says nothing about the wearer, so it never ends a hold.
    private fun panelInteractive(): Boolean = runCatching(isInteractive).getOrDefault(true)

    internal companion object {
        /** Several step ticks long, so one slow tick never drops the panel mid-run. */
        const val LEASE_MS = 10_000L

        /** Steps tick every few hundred ms; re-acquiring on each would only spam the power service. */
        const val RENEW_INTERVAL_MS = 2_000L

        private const val BACKSTOP_IDLE_KEEP_ALIVE_MS = 30_000L

        private val backstopExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "RokidNexusSetupHoldBackstop").apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
                setKeepAliveTime(BACKSTOP_IDLE_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
                allowCoreThreadTimeOut(true)
            }
        }

        val EXECUTOR_BACKSTOP = SetupDisplayHoldBackstop { delayMs, action ->
            val scheduled = backstopExecutor.schedule(Runnable { action() }, delayMs, TimeUnit.MILLISECONDS)
            return@SetupDisplayHoldBackstop { scheduled.cancel(false) }
        }

        @Suppress("DEPRECATION")
        fun newLease(context: Context): SetupDisplayLease? {
            val power = context.getSystemService(PowerManager::class.java) ?: return null
            fun wakeLock(flags: Int) = power.newWakeLock(flags, "rokidbus:setup-automation")
                .apply { setReferenceCounted(false) }
            // ACQUIRE_CAUSES_WAKEUP wakes on every acquire of its lock, renewals included, so the
            // panel is woken through one lock and kept on through another that never wakes it.
            val waking = wakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP)
            val keeping = wakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)
            return object : SetupDisplayLease {
                override fun acquire(timeoutMs: Long, wake: Boolean) {
                    val next = if (wake) waking else keeping
                    val previous = if (wake) keeping else waking
                    // Taken before the other goes, so the panel is never unheld in between.
                    next.acquire(timeoutMs)
                    if (previous.isHeld) previous.release()
                }

                override fun release() {
                    if (waking.isHeld) waking.release()
                    if (keeping.isHeld) keeping.release()
                }
            }
        }
    }
}

internal fun formatSetupDisplayHoldTransition(transition: SetupDisplayHoldTransition): String {
    val detail = when (transition) {
        is SetupDisplayHoldTransition.Acquire ->
            "decision=acquire leaseMs=${transition.leaseMs} wake=${transition.wake}"
        is SetupDisplayHoldTransition.Renew ->
            "decision=renew leaseMs=${transition.leaseMs}"
        is SetupDisplayHoldTransition.Handover ->
            "decision=handover fromRun=${transition.fromRunToken} leaseMs=${transition.leaseMs}"
        is SetupDisplayHoldTransition.Release ->
            "decision=release reason=${transition.reason.logValue} " +
                "ageMs=${transition.ageMs} renewals=${transition.renewals}"
        is SetupDisplayHoldTransition.Refused ->
            "decision=refused reason=${transition.reason.logValue}"
    }
    return "setup display hold run=${transition.runToken} $detail"
}
