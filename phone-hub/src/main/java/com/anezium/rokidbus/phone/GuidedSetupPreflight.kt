package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.SetupStage

/**
 * What guided setup checks before it asks the owner to do anything.
 *
 * The old manual fallback re-ran the same automaton that had just failed, from the top, and made
 * the owner walk every step again even when most of them were already satisfied. This decides
 * what is genuinely missing, so the screen can skip what is done and ask for one thing at a time.
 */
internal enum class GuidedCheckId {
    /** The phone can reach the glasses at all. Nothing else is worth trying without it. */
    LINK,

    /**
     * Nexus is actually running on the lens.
     *
     * Every button on this screen is a message to the glasses app. With the app installed but
     * never opened there is nobody to receive them, and the screen used to sit on "Waiting for the
     * glasses…" forever without ever saying what was missing.
     */
    GLASSES_APP,

    /** The one switch only a human can flip. */
    ACCESSIBILITY,

    /** Pairing rides the local network; without it the ports below are meaningless. */
    WIFI,

    /** Not blocking: Nexus unlocks this itself during the run. Shown so the owner sees why. */
    DEVELOPER,

    /**
     * Re-pair only: the glasses hold a pairing of their own, which is what lets them revive their
     * helper after every restart. Core readiness says nothing about it — a phone-driven arm
     * brings the helper back once without it.
     */
    SELF_PAIRING,
}

internal data class GuidedCheck(
    val id: GuidedCheckId,
    val satisfied: Boolean,
)

internal data class GuidedPreflight(
    val checks: List<GuidedCheck>,
    /** The first thing standing in the way, or null when the run can start. */
    val blocking: GuidedCheckId?,
    /** True when the observed state already satisfies setup: offer a way out, not a wizard. */
    val alreadyComplete: Boolean,
    /** Re-pair only: the glasses cannot maintain themselves until they pair again. */
    val needsPairing: Boolean = false,
)

internal object GuidedSetupPreflightPolicy {
    /**
     * [coreReady] is the glasses' own verdict (accessibility on and secure settings granted). When
     * it holds there is nothing left to guide, whatever the other signals say — a unit armed
     * through the documented pm grant path never needed the pairing at all.
     *
     * [repair] is the maintenance entry an owner opens after setup, when the helper keeps dying:
     * there, core readiness is exactly what is not in question, and only [maintenanceReady] —
     * the glasses' own pairing — says whether anything is left to do.
     */
    fun evaluate(
        linkReady: Boolean,
        glassesAppRunning: Boolean,
        accessibilityEnabled: Boolean,
        wifiReady: Boolean,
        developerOptionsReady: Boolean,
        coreReady: Boolean,
        repair: Boolean = false,
        maintenanceReady: Boolean = false,
    ): GuidedPreflight {
        val checks = listOfNotNull(
            GuidedCheck(GuidedCheckId.LINK, linkReady),
            GuidedCheck(GuidedCheckId.GLASSES_APP, glassesAppRunning),
            GuidedCheck(GuidedCheckId.ACCESSIBILITY, accessibilityEnabled),
            GuidedCheck(GuidedCheckId.WIFI, wifiReady),
            GuidedCheck(GuidedCheckId.DEVELOPER, developerOptionsReady),
            GuidedCheck(GuidedCheckId.SELF_PAIRING, maintenanceReady).takeIf { repair },
        )
        val needsPairing = repair && !maintenanceReady
        if (coreReady) {
            return GuidedPreflight(
                checks = checks,
                blocking = null,
                alreadyComplete = !needsPairing,
                needsPairing = needsPairing,
            )
        }
        val blocking = BLOCKING_ORDER.firstOrNull { id ->
            checks.first { it.id == id }.satisfied.not()
        }
        return GuidedPreflight(
            checks = checks,
            blocking = blocking,
            alreadyComplete = false,
            needsPairing = needsPairing,
        )
    }

    /**
     * The same verdict, read from what the glasses have actually told us.
     *
     * A blank stage means the lens has never reported anything, which is not the same as "all
     * clear". Treating that silence as satisfied is what offered a pairing to an owner whose
     * accessibility service had never been switched on, and then blamed the link when the command
     * went nowhere.
     */
    fun fromReportedStage(
        linkReady: Boolean,
        reportedStage: String,
        coreReady: Boolean,
        repair: Boolean = false,
        maintenanceReady: Boolean = false,
    ): GuidedPreflight {
        val reported = reportedStage.isNotBlank()
        return evaluate(
            linkReady = linkReady,
            // A lens that has said nothing has not started its app. Anything this screen sends it
            // would be dropped on the floor.
            glassesAppRunning = coreReady || reported,
            accessibilityEnabled = coreReady ||
                (reported && reportedStage != SetupStage.WAITING_FOR_ACCESSIBILITY),
            wifiReady = coreReady ||
                (
                    reported && reportedStage != SetupStage.ENABLING_WIFI &&
                        reportedStage != SetupStage.WAITING_FOR_WIFI
                    ),
            // The glasses do not advertise this, and Nexus unlocks it during the run anyway.
            developerOptionsReady = true,
            coreReady = coreReady,
            repair = repair,
            maintenanceReady = maintenanceReady,
        )
    }

    /** Developer options are deliberately absent: Nexus turns them on, it does not ask. */
    private val BLOCKING_ORDER = listOf(
        GuidedCheckId.LINK,
        GuidedCheckId.GLASSES_APP,
        GuidedCheckId.ACCESSIBILITY,
        GuidedCheckId.WIFI,
    )
}
