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
        /** Armed this boot with its bridge running, and no bridge demand has failed since. */
        ALIVE_THIS_BOOT,

        /** Armed on an earlier boot, armed without its bridge, or its bridge failed a demand. */
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
    ): HelperRecord = when {
        armedBootInstantMs == null -> HelperRecord.ABSENT
        SelfArmBridgeLivenessPolicy.presumedDead(armedBootInstantMs, currentBootInstantMs) ->
            HelperRecord.NOT_ALIVE
        !armedBridgeRunning || bridgeDemandPending -> HelperRecord.NOT_ALIVE
        else -> HelperRecord.ALIVE_THIS_BOOT
    }

    /**
     * An owner pressing the button deserves a fresh attempt, so a session that nothing is driving
     * — paused on Wi-Fi, left behind by a request whose run never started, or running with an
     * expired lease — is no reason to answer busy. Only a heartbeating run or a phone-driven arm
     * in progress is.
     */
    fun sessionDisposition(
        sessionActive: Boolean,
        sessionRunningWithValidLease: Boolean,
        manualArmInProgress: Boolean,
    ): SessionDisposition = when {
        !sessionActive -> SessionDisposition.PROCEED
        sessionRunningWithValidLease || manualArmInProgress -> SessionDisposition.BUSY
        else -> SessionDisposition.SUPERSEDE
    }

    /**
     * With the radio down, the only way to measure is the Settings popup. Spare it only when the
     * record already says alive; an absent or contradicted record has to be checked.
     */
    fun mayAnswerHealthyWithoutMeasuring(before: HelperRecord): Boolean =
        before == HelperRecord.ALIVE_THIS_BOOT

    /**
     * [armed] and [bridgeRunning] are what the arm measured; [pairing] is the answer to the
     * pairing request made when no arm was possible without one, or null when none was made.
     */
    fun verdict(
        armed: Boolean,
        bridgeRunning: Boolean,
        before: HelperRecord,
        pairing: SelfPairingOutcome?,
    ): String = when {
        armed && bridgeRunning && before == HelperRecord.ALIVE_THIS_BOOT ->
            GlassesRepairContract.RESULT_ALREADY_HEALTHY
        armed && bridgeRunning -> GlassesRepairContract.RESULT_REPAIRED
        armed -> GlassesRepairContract.RESULT_PARTIAL
        pairing?.pending == true -> GlassesRepairContract.RESULT_PAIRING_PENDING
        pairing != null -> GlassesRepairContract.RESULT_PAIRING_REQUIRED
        else -> GlassesRepairContract.RESULT_ARM_FAILED
    }
}
