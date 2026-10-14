package com.anezium.rokidbus.glasses

/** What one request for the glasses to pair themselves again actually did. */
internal enum class SelfPairingOutcome(val logValue: String) {
    /** A setup session now drives the Wireless debugging automation. */
    STARTED("started"),

    /** A run this gate started earlier is still live; nothing new was started. */
    ALREADY_IN_FLIGHT("already_in_flight"),

    /** No connected accessibility service, or no session to run in: nothing can drive Settings. */
    SERVICE_UNAVAILABLE("service_unavailable"),

    /** Background requests spent their budget for this process; only the owner may ask now. */
    BUDGET_EXHAUSTED("budget_exhausted"),
    ;

    /** True when a pairing run is underway because of, or despite, this request. */
    val pending: Boolean get() = this == STARTED || this == ALREADY_IN_FLIGHT
}

/**
 * Single flight and budget for self-pairing requests.
 *
 * The in-flight latch belongs to the session the run was started in, not to the process: once
 * that session is no longer live — finished, failed, expired, superseded — the latch is gone too.
 * A latch that outlived its run is what turned every later "Repair now" into a silent no-op.
 * Background requests are budgeted so a unit that cannot pair does not drive Settings over the
 * wearer forever; the owner's request never counts against that budget.
 */
internal class SelfArmSelfPairingGate(private val backgroundBudget: Int) {
    data class Admission(
        /** Null when the caller may start a run now. */
        val refusal: SelfPairingOutcome?,
        /** Background attempts made this process, this one included; 0 for an owner request. */
        val backgroundAttempt: Int,
        /** The session of a latch found dead and dropped by this call, for the log. */
        val releasedSessionId: String?,
    )

    private var inFlightSessionId: String? = null
    private var backgroundAttempts = 0

    @Synchronized
    fun admit(owner: Boolean, isLive: (String) -> Boolean): Admission {
        var released: String? = null
        inFlightSessionId?.let { sessionId ->
            if (isLive(sessionId)) {
                return Admission(SelfPairingOutcome.ALREADY_IN_FLIGHT, backgroundAttempts, null)
            }
            inFlightSessionId = null
            released = sessionId
        }
        if (owner) return Admission(null, 0, released)
        if (backgroundAttempts >= backgroundBudget) {
            return Admission(SelfPairingOutcome.BUDGET_EXHAUSTED, backgroundAttempts, released)
        }
        backgroundAttempts += 1
        return Admission(null, backgroundAttempts, released)
    }

    @Synchronized
    fun onStarted(sessionId: String) {
        inFlightSessionId = sessionId
    }

    /** True when [sessionId] held the latch and no longer does. */
    @Synchronized
    fun release(sessionId: String): Boolean {
        if (sessionId.isBlank() || inFlightSessionId != sessionId) return false
        inFlightSessionId = null
        return true
    }

    @Synchronized
    fun inFlightSessionId(): String? = inFlightSessionId
}
