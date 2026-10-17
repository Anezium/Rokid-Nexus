package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesRepairContract

/**
 * What the owner's "repair now" may conclude, and from what.
 *
 * The helper's heartbeat files are shell-owned and unreadable from the app, so the only real
 * measurement is the one an arm takes: its sentinel reports the watchdog and the bridge as it
 * leaves them. Everything else is a record, and a record is only ever allowed to say "alive"
 * when it is from this boot and nothing has contradicted it since. An absent record — exactly
 * what a reinstall leaves — is unknown, never healthy.
 */
internal object SelfArmOwnerRepairPolicy {
    enum class HelperRecord {
        /** Armed this boot with its bridge running, and no arm or bridge demand has failed since. */
        ALIVE_THIS_BOOT,

        /**
         * Armed on an earlier boot, armed without its bridge, or contradicted since: a later arm
         * failed, or the bridge failed a demand.
         */
        NOT_ALIVE,

        /** No arm on record at all. */
        ABSENT,
    }

    enum class SessionDisposition {
        /** No setup session in the way. */
        PROCEED,

        /** A session exists but nothing is driving it; the owner's request replaces it. */
        SUPERSEDE,

        /** A run is genuinely in progress; answer busy rather than tear it down. */
        BUSY,
    }

    fun helperRecord(
        armedBootInstantMs: Long?,
        armedBridgeRunning: Boolean,
        currentBootInstantMs: Long,
        bridgeDemandPending: Boolean,
        armFailedSince: Boolean = false,
    ): HelperRecord = when {
        armedBootInstantMs == null -> HelperRecord.ABSENT
        SelfArmBridgeLivenessPolicy.presumedDead(armedBootInstantMs, currentBootInstantMs) ->
            HelperRecord.NOT_ALIVE
        !armedBridgeRunning || bridgeDemandPending || armFailedSince -> HelperRecord.NOT_ALIVE
        else -> HelperRecord.ALIVE_THIS_BOOT
    }

    /**
     * An owner pressing the button deserves a fresh attempt, so a session that nothing is driving
     * — paused on Wi-Fi, left behind by a request whose run never started, or running with an
     * expired lease — is no reason to answer busy. Only a heartbeating run or a manual arm whose
     * session still heartbeats is; a manual flow left open without a lease is abandoned.
     */
    fun sessionDisposition(
        sessionActive: Boolean,
        sessionRunningWithValidLease: Boolean,
        manualArmLeased: Boolean,
    ): SessionDisposition = when {
        !sessionActive -> SessionDisposition.PROCEED
        sessionRunningWithValidLease || manualArmLeased -> SessionDisposition.BUSY
        else -> SessionDisposition.SUPERSEDE
    }

    /**
     * With the radio down, the arm needs it brought up first. The bridge does that silently, but
     * only a record that says alive makes it worth waiting on before the Settings popup; an absent
     * or contradicted record goes straight to the popup. Either way the arm then measures: a
     * record that says alive cannot tell a helper still running from one stopped since.
     */
    fun mayTryBridgeBeforePopup(before: HelperRecord): Boolean =
        before == HelperRecord.ALIVE_THIS_BOOT

    /**
     * Only an arm that reached the helper and failed says anything about it. One that could not
     * open its session — the routine case with the radio off, on every accessibility reconnect —
     * measured nothing, and marking the record would send the next owner repair to the popup.
     */
    fun armContradictsRecord(result: SelfArmWatchdogEnsureResult): Boolean =
        result == SelfArmWatchdogEnsureResult.FAILED

    /**
     * How long an arm waits, after the repair brought Wi-Fi up, for what it actually talks over: a
     * Wi-Fi network and adbd's TLS port. The radio reports "on" seconds before either exists, and
     * an arm started on that report measures nothing — it answered "could not restore" for a
     * helper that was running (issue #53, round 8 on device).
     */
    const val WIFI_READY_TIMEOUT_MS = 20_000L
    const val WIFI_READY_POLL_MS = 500L

    /** An arm that still cannot reach its session right after the bring-up is tried again, boundedly. */
    const val UNREACHABLE_ARM_ATTEMPTS = 3
    const val UNREACHABLE_RETRY_WINDOW_MS = 30_000L
    const val UNREACHABLE_RETRY_DELAY_MS = 2_000L

    enum class ArmReadiness(val logValue: String) {
        READY("ready"),
        NO_NETWORK("no_network"),
        NO_TLS_PORT("no_tls_port"),
    }

    /**
     * Waits up to [timeoutMs] for a Wi-Fi network, then for adbd's TLS port. Not a gate: the arm
     * runs either way and stays the only measurement; this only keeps it from running too early.
     */
    fun awaitArmReadiness(
        networkReady: () -> Boolean,
        enableWirelessDebugging: () -> Unit,
        readTlsPort: () -> Int,
        nowMs: () -> Long,
        sleep: (Long) -> Unit,
        timeoutMs: Long = WIFI_READY_TIMEOUT_MS,
        pollMs: Long = WIFI_READY_POLL_MS,
    ): ArmReadiness {
        val deadline = nowMs() + timeoutMs
        while (!networkReady()) {
            if (nowMs() >= deadline) return ArmReadiness.NO_NETWORK
            sleep(pollMs)
        }
        // The framework drops wireless debugging while the radio is off and may not restore it
        // by itself; the arm would ask for it anyway, only later.
        enableWirelessDebugging()
        while (readTlsPort() <= 0) {
            if (nowMs() >= deadline) return ArmReadiness.NO_TLS_PORT
            sleep(pollMs)
        }
        return ArmReadiness.READY
    }

    /**
     * Whether an arm that just finished deserves another try. Only right after this repair
     * brought Wi-Fi up ([msSinceWifiUp] non-null), and only when it never reached the helper:
     * its session was unreachable, or — [result] null — another arm held the single flight and
     * did not land, so this repair measured nothing itself.
     */
    fun mayRetryArm(
        result: SelfArmWatchdogEnsureResult?,
        attempt: Int,
        msSinceWifiUp: Long?,
        bootstrapComplete: Boolean,
    ): Boolean = msSinceWifiUp != null &&
        msSinceWifiUp < UNREACHABLE_RETRY_WINDOW_MS &&
        attempt < UNREACHABLE_ARM_ATTEMPTS &&
        // A refused credential the arm just dropped needs a pairing, not another arm.
        bootstrapComplete &&
        (result == null || result == SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE)

    /**
     * [armed] and [bridgeRunning] are what the arm measured as it left the helper;
     * [helperRunningBeforeArm] is what the same arm found before it started anything, and wins
     * over the record whenever it is known — a record that says alive cannot tell a helper still
     * running from one stopped since. [pairing] is the answer to the pairing request made when no
     * arm was possible without one, or null when none was made. [sessionUnreachable] says the
     * arm never reached the helper at all: the glasses could not be reached over Wi-Fi, which
     * is not the same as a helper that would not come back.
     */
    fun verdict(
        armed: Boolean,
        bridgeRunning: Boolean,
        before: HelperRecord,
        pairing: SelfPairingOutcome?,
        helperRunningBeforeArm: Boolean? = null,
        sessionUnreachable: Boolean = false,
    ): String = when {
        armed && bridgeRunning &&
            (helperRunningBeforeArm ?: (before == HelperRecord.ALIVE_THIS_BOOT)) ->
            GlassesRepairContract.RESULT_ALREADY_HEALTHY
        armed && bridgeRunning -> GlassesRepairContract.RESULT_REPAIRED
        armed -> GlassesRepairContract.RESULT_PARTIAL
        pairing?.pending == true -> GlassesRepairContract.RESULT_PAIRING_PENDING
        pairing != null -> GlassesRepairContract.RESULT_PAIRING_REQUIRED
        sessionUnreachable -> GlassesRepairContract.RESULT_WIFI_UNAVAILABLE
        else -> GlassesRepairContract.RESULT_ARM_FAILED
    }
}
