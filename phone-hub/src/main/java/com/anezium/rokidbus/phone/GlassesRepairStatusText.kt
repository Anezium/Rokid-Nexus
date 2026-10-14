package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesRepairContract

/**
 * What the owner reads after asking the glasses to repair their helper. Each verdict says what
 * the glasses actually found, and points at an entry that exists: "redo the glasses setup" used
 * to send owners looking for a flow the app had hidden once setup was marked complete.
 */
internal object GlassesRepairStatusText {
    /** Long enough for the Wi-Fi popup, the arm and the radio restore; a pairing answers fast. */
    const val REQUEST_TIMEOUT_MS = 150_000L

    const val NO_ANSWER = "The glasses did not answer. Check the connection and try again."

    fun message(result: String?): String = when (result) {
        GlassesRepairContract.RESULT_REPAIRED ->
            "Repaired. The glasses' helper is running again."
        GlassesRepairContract.RESULT_ALREADY_HEALTHY ->
            "Nothing to repair — the glasses' helper is running."
        GlassesRepairContract.RESULT_PARTIAL ->
            "Partly repaired: the watchdog is running, but the command bridge did not start. " +
                "Try again."
        GlassesRepairContract.RESULT_PAIRING_PENDING ->
            "The glasses need to pair again, and have started doing it themselves. Keep them on " +
                "and leave them be; Nexus finishes it from this phone if the glasses ask."
        GlassesRepairContract.RESULT_PAIRING_REQUIRED ->
            "The glasses need to pair again but could not start it. Open Nexus on the glasses, " +
                "then use Re-pair glasses."
        GlassesRepairContract.RESULT_WIFI_UNAVAILABLE ->
            "Could not turn on the glasses' Wi-Fi. Wake the glasses and try again."
        GlassesRepairContract.RESULT_ARM_FAILED ->
            "The glasses could not restore the helper. Try again, or use Re-pair glasses."
        GlassesRepairContract.RESULT_BUSY ->
            "The glasses are busy with another repair or with setup. Try again in a moment."
        else -> "The glasses sent an answer this version does not understand."
    }

    /** Verdicts that leave nothing for the owner to fix. */
    fun isGood(result: String?): Boolean = result == GlassesRepairContract.RESULT_REPAIRED ||
        result == GlassesRepairContract.RESULT_ALREADY_HEALTHY ||
        result == GlassesRepairContract.RESULT_PAIRING_PENDING
}
