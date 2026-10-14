package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Boot repair of the glasses' privileged helper, steered from the phone.
 *
 * The helper is a shell-uid process no reboot spares, and this ROM boots with Wi-Fi off, so the
 * radio edge that would revive it in the background never comes on its own. The glasses can open
 * their Settings and flip Wi-Fi themselves, but whether they may do that unprompted is the
 * owner's call: the phone holds the switch, the glasses hold the behaviour. The config write is
 * persisted glasses-side because the phone is exactly the thing that is absent at boot.
 */
object GlassesRepairContract {
    const val VERSION = 1

    /** An owner who never touched the switch gets the repair: absent config reads as enabled. */
    const val DEFAULT_AUTO_REPAIR = true

    /**
     * First request version whose sender understands the pairing-aware results below and may ask
     * for [MODE_PAIR]. Older phones send version 1 and only ever hear [LEGACY_RESULTS].
     */
    const val PAIRING_AWARE_VERSION = 2

    /** Revive the helper over the glasses' own pairing; ask to pair only if that is missing. */
    const val MODE_REPAIR = "repair"

    /** Skip the arm: the owner asked the glasses to pair themselves again, whatever the state. */
    const val MODE_PAIR = "pair"

    /** The arm just measured the watchdog and the command bridge running. */
    const val RESULT_REPAIRED = "repaired"

    /**
     * The helper was running and still is: measured by this repair, or — with the radio off —
     * armed with its bridge running earlier this boot and not failing anyone since. Never
     * answered from an absent record.
     */
    const val RESULT_ALREADY_HEALTHY = "already_healthy"

    /** The watchdog is up but the command bridge did not start with it. */
    const val RESULT_PARTIAL = "partial"

    /**
     * The glasses cannot revive the helper without pairing again, and that pairing is now
     * running on the lens. The phone may be asked to finish it.
     */
    const val RESULT_PAIRING_PENDING = "pairing_pending"

    /** The glasses cannot revive the helper without pairing again, and could not start it. */
    const val RESULT_PAIRING_REQUIRED = "pairing_required"

    /**
     * The glasses could not be reached over Wi-Fi: the radio would not come on, or came on but
     * never gave the repair a session to measure the helper through. Nothing was measured.
     */
    const val RESULT_WIFI_UNAVAILABLE = "wifi_unavailable"

    /** Wi-Fi came up but the helper could not be restored over it. */
    const val RESULT_ARM_FAILED = "arm_failed"

    /** A repair or a setup flow already owns the machinery this one needs. */
    const val RESULT_BUSY = "busy"

    /** What a version 1 phone understands; anything else reads there as "no answer". */
    val LEGACY_RESULTS: List<String> = listOf(
        RESULT_REPAIRED,
        RESULT_ALREADY_HEALTHY,
        RESULT_WIFI_UNAVAILABLE,
        RESULT_ARM_FAILED,
        RESULT_BUSY,
    )

    val RESULTS: List<String> = LEGACY_RESULTS + listOf(
        RESULT_PARTIAL,
        RESULT_PAIRING_PENDING,
        RESULT_PAIRING_REQUIRED,
    )

    fun configToJson(autoRepairEnabled: Boolean): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("autoRepair", autoRepairEnabled)

    /**
     * Null for anything this build does not recognise — the glasses' stored setting then stands,
     * which beats letting a malformed write silently re-enable or disable the popup.
     */
    fun autoRepairFromConfig(payload: JSONObject?): Boolean? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        return json.opt("autoRepair") as? Boolean
    }

    fun requestToJson(mode: String = MODE_REPAIR): JSONObject = JSONObject()
        .put("version", PAIRING_AWARE_VERSION)
        .put("mode", if (mode == MODE_PAIR) MODE_PAIR else MODE_REPAIR)

    fun requestIsPairingAware(payload: JSONObject?): Boolean =
        (payload?.optInt("version", 0) ?: 0) >= PAIRING_AWARE_VERSION

    /** Anything but an explicit pairing-aware [MODE_PAIR] is the plain repair. */
    fun modeFromRequest(payload: JSONObject?): String =
        if (requestIsPairingAware(payload) && payload?.optString("mode") == MODE_PAIR) {
            MODE_PAIR
        } else {
            MODE_REPAIR
        }

    /**
     * The result as the requester can read it. Every pairing-aware outcome still means "the
     * helper is not fully back", which is what [RESULT_ARM_FAILED] says to an older phone.
     */
    fun resultForRequester(result: String, requesterPairingAware: Boolean): String =
        if (requesterPairingAware || result in LEGACY_RESULTS) result else RESULT_ARM_FAILED

    /**
     * [mode] is what the glasses actually ran, echoed to a pairing-aware requester. Glasses
     * older than [PAIRING_AWARE_VERSION] ignore the requested mode, run the plain repair and
     * echo nothing, which is how the phone tells a re-pair from a repair it did not ask for.
     *
     * [pairingSessionId] names the glasses' pairing session behind a [RESULT_PAIRING_PENDING],
     * so the screen that asked can later end that run and only that one.
     */
    fun replyToJson(
        result: String,
        mode: String? = null,
        pairingSessionId: String? = null,
    ): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("result", result)
        .apply {
            if (mode != null) put("mode", mode)
            if (result == RESULT_PAIRING_PENDING && !pairingSessionId.isNullOrBlank()) {
                put("pairingSession", pairingSessionId)
            }
        }

    /** True only when the answering glasses say they ran [MODE_PAIR]. */
    fun replyRanPairing(payload: JSONObject?): Boolean = payload?.optString("mode") == MODE_PAIR

    /**
     * The pairing session a [MODE_PAIR] request left running, or null. Glasses that do not name
     * one leave the phone nothing to end, which is the safe side: it never ends a run it cannot
     * prove it started.
     */
    fun pairingSessionFromReply(payload: JSONObject?): String? {
        if (!replyRanPairing(payload) || resultFromReply(payload) != RESULT_PAIRING_PENDING) {
            return null
        }
        return payload?.optString("pairingSession")?.takeIf { it.isNotBlank() }
    }

    /** Null for a result this build does not know, so version skew reads as "no answer". */
    fun resultFromReply(payload: JSONObject?): String? =
        payload?.optString("result")?.takeIf(RESULTS::contains)
}
