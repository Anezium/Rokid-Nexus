package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.PowerManager

/** Why a Settings-automation run stopped holding the display. */
internal enum class SetupDisplayHoldEnd(val logValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    STOPPED("stopped"),
    SESSION_ENDED("session_ended"),
    SERVICE_DESTROYED("service_destroyed"),
    RUN_EXPIRED("run_expired"),
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

    data class Acquire(override val runToken: Long, val leaseMs: Long) : SetupDisplayHoldTransition

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

/**
 * The display hold for the wireless setup automator's Settings runs.
 *
 * The vendor re-asserts a 5 s screen-off timeout at every boot, and a sleeping
 * panel shows the accessibility service no Settings window, so an unattended
 * run stalls on `waiting_for_settings` until its own timeout. Window flags do
 * not hold this firmware's panel; a screen wake lock does. Each lease is short
 * and only a live run renews it, never past that run's deadline, so a run that
 * stops stepping for any reason lets the panel go within [leaseMs] even when
 * no end path ran.
 */
internal class SetupDisplayHoldLifecycle(
    private val leaseMs: Long = LEASE_MS,
    private val renewIntervalMs: Long = RENEW_INTERVAL_MS,
) {
    private class Hold(
        var runToken: Long,
        val startedAtMs: Long,
        var lastRenewedAtMs: Long,
        var renewals: Int,
        val lease: DisplayHoldLease,
    )

    private var hold: Hold? = null

    fun heldRunToken(): Long? = hold?.runToken

    /**
     * Takes or keeps the hold for [runToken] until at most [runDeadlineAtMs].
     * Every acquisition also wakes the panel, so a renewal relights a display
     * that slept anyway.
     */
    fun renew(
        runToken: Long,
        nowMs: Long,
        runDeadlineAtMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): SetupDisplayHoldTransition? {
        val remainingMs = runDeadlineAtMs - nowMs
        val current = hold
        if (remainingMs <= 0L) {
            return if (current?.runToken == runToken) {
                release(SetupDisplayHoldEnd.RUN_EXPIRED, nowMs, runToken)
            } else {
                null
            }
        }
        val nextLeaseMs = minOf(leaseMs, remainingMs)
        if (current == null) return acquire(runToken, nowMs, nextLeaseMs, leaseFactory)

        val handover = current.runToken != runToken
        if (!handover && nowMs - current.lastRenewedAtMs < renewIntervalMs) return null
        if (runCatching { current.lease.acquire(nextLeaseMs) }.isFailure) {
            return release(SetupDisplayHoldEnd.HOLD_FAILURE, nowMs, current.runToken)
        }
        val fromRunToken = current.runToken
        current.runToken = runToken
        current.lastRenewedAtMs = nowMs
        current.renewals += 1
        return if (handover) {
            SetupDisplayHoldTransition.Handover(runToken, fromRunToken, nextLeaseMs)
        } else {
            SetupDisplayHoldTransition.Renew(runToken, nextLeaseMs)
        }
    }

    /** Releases only the hold [runToken] owns; a run that was handed over keeps it. */
    fun release(
        reason: SetupDisplayHoldEnd,
        nowMs: Long,
        runToken: Long? = null,
    ): SetupDisplayHoldTransition.Release? {
        val current = hold ?: return null
        if (runToken != null && current.runToken != runToken) return null
        // Cleared first so a duplicate end callback cannot release twice even
        // if the platform release throws.
        hold = null
        runCatching { current.lease.release() }
        return SetupDisplayHoldTransition.Release(
            runToken = current.runToken,
            reason = reason,
            ageMs = (nowMs - current.startedAtMs).coerceAtLeast(0L),
            renewals = current.renewals,
        )
    }

    private fun acquire(
        runToken: Long,
        nowMs: Long,
        nextLeaseMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): SetupDisplayHoldTransition {
        val lease = runCatching(leaseFactory).getOrNull()
            ?: return SetupDisplayHoldTransition.Refused(
                runToken,
                DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE,
            )
        if (runCatching { lease.acquire(nextLeaseMs) }.isFailure) {
            runCatching { lease.release() }
            return SetupDisplayHoldTransition.Refused(runToken, DisplayHoldFailure.ACQUIRE_FAILED)
        }
        hold = Hold(
            runToken = runToken,
            startedAtMs = nowMs,
            lastRenewedAtMs = nowMs,
            renewals = 0,
            lease = lease,
        )
        return SetupDisplayHoldTransition.Acquire(runToken, nextLeaseMs)
    }

    internal companion object {
        /** Several step ticks long, so one slow tick never drops the panel mid-run. */
        const val LEASE_MS = 10_000L

        /** Steps tick every few hundred ms; re-acquiring on each would only spam the power service. */
        const val RENEW_INTERVAL_MS = 2_000L

        @Suppress("DEPRECATION")
        fun newLease(context: Context): DisplayHoldLease? {
            val power = context.getSystemService(PowerManager::class.java) ?: return null
            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rokidbus:setup-automation",
            ).apply { setReferenceCounted(false) }
            return object : DisplayHoldLease {
                override fun acquire(timeoutMs: Long) {
                    wakeLock.acquire(timeoutMs)
                }

                override fun release() {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }
}

internal fun formatSetupDisplayHoldTransition(transition: SetupDisplayHoldTransition): String {
    val detail = when (transition) {
        is SetupDisplayHoldTransition.Acquire ->
            "decision=acquire leaseMs=${transition.leaseMs}"
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
