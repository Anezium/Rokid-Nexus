package com.anezium.rokidbus.glasses

/**
 * Gates for the boot-time repair popup — the one flow allowed to drive Settings across the
 * wearer's view without them asking, because the owner opted in and the alternative is a unit
 * whose bridge-backed effects silently fail until somebody happens to turn Wi-Fi on.
 */
internal object SelfArmBootRepairPolicy {
    /**
     * Wait between the accessibility service connecting and the repair check. Two things earn
     * the head start: the ROM is still bringing up its own stack at connect time (launcher, CXR
     * services, settings writes land for several seconds), and the existing re-arm machinery
     * deserves the first word — a Wi-Fi that was about to come up on its own arrives well inside
     * this window, fails the wifi-off precondition below, and no popup ever fires. 20 s clears
     * the boot churn observed on this ROM with margin, while staying short enough that an owner
     * who rebooted to fix syncing is not left watching a unit that does nothing.
     */
    const val CONNECT_SETTLE_DELAY_MS = 20_000L

    /**
     * How long the background re-arm gets after Wi-Fi comes up before the restore stops waiting.
     * The proven happy path lands in well under a minute (network validate, wireless debugging
     * up, TLS with the persisted key); the margin covers a slow DHCP without holding the radio
     * hostage for the rest of the boot.
     */
    const val ARM_WAIT_TIMEOUT_MS = 3 * 60_000L
    const val ARM_POLL_INTERVAL_MS = 5_000L

    /**
     * Names the first failed precondition so the log says why no popup fired, or null when the
     * attempt may run. Order matters only for the log: every condition must hold.
     *
     * The interactive-display requirement is deliberate: the automation is visible by design,
     * and a dark display means nobody is wearing the unit — Settings churning under a sleeping
     * screen, half-finished when the wearer picks the glasses back up, is worse than waiting.
     * A skipped check does not claim the boot latch, so a later service reconnect retries.
     */
    fun bootAttemptBlocker(
        autoRepairEnabled: Boolean,
        alreadyAttemptedThisBoot: Boolean,
        bridgePresumedDead: Boolean,
        wifiEnabled: Boolean,
        bootstrapComplete: Boolean,
        setupSessionActive: Boolean,
        displayInteractive: Boolean,
    ): String? = when {
        !autoRepairEnabled -> "auto_repair_disabled"
        alreadyAttemptedThisBoot -> "already_attempted_this_boot"
        !bridgePresumedDead -> "bridge_not_presumed_dead"
        // With the radio already up the background re-arm needs no help from Settings.
        wifiEnabled -> "wifi_already_enabled"
        // Mid-onboarding there is nothing to repair yet, and no popup may interleave with setup.
        !bootstrapComplete -> "bootstrap_incomplete"
        setupSessionActive -> "setup_session_active"
        !displayInteractive -> "display_not_interactive"
        else -> null
    }

    enum class WifiRestore {
        /** The repair turned nothing on, or its claim was dropped while it ran. */
        NOT_OWED,

        /** The radio is already off; nothing left to do. */
        SETTLED,

        /** Someone else needs the radio now: drop the claim and leave Wi-Fi on. */
        YIELDED,

        /** Turn it off now. */
        DISABLE,
    }

    /**
     * What to do with Wi-Fi a repair turned on, decided once as that repair ends — success or
     * not: whether the arm worked says nothing about whose radio it is. Turning it off runs
     * through the bridge, with the privileged session as the fallback, never through Settings a
     * second time. Every owner that may have claimed the radio in the meantime wins outright —
     * the same yields the background re-arm honours — because a radio left on is the lesser harm
     * and nothing comes back later to settle it.
     */
    fun restoreWifi(
        repairOwesWifiOff: Boolean,
        wifiEnabledNow: Boolean,
        wifiHubOwned: Boolean,
        setupSessionActive: Boolean,
        mediaSyncSessionActive: Boolean,
        cameraSessionActive: Boolean,
    ): WifiRestore = when {
        !repairOwesWifiOff -> WifiRestore.NOT_OWED
        !wifiEnabledNow -> WifiRestore.SETTLED
        wifiHubOwned || setupSessionActive || mediaSyncSessionActive ||
            // A camera session that found the radio already up never claims hub ownership, so the
            // ownership check alone would let the restore cut the link mid-viewfinder.
            cameraSessionActive -> WifiRestore.YIELDED
        else -> WifiRestore.DISABLE
    }
}
