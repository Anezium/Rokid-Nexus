package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the app reliably knows about the command bridge, and what still owes it an answer.
 *
 * The bridge's heartbeat file is shell-owned and unreadable from the app on this ROM, so the
 * record kept here is the one fact the app can measure by itself: which boot the bridge was last
 * armed on. The demand latch is process-local on purpose — after a reboot the boot-instant
 * comparison already says everything a persisted latch would.
 */
internal object SelfArmBridgeLivenessStore {
    /** Shares the wireless-bootstrap prefs file: the arm epoch belongs to the same lifecycle. */
    private const val PREFS_NAME = "selfarm_wireless"
    private const val KEY_ARMED_BOOT_INSTANT = "armed_boot_instant"
    private const val KEY_ARMED_BRIDGE_VERSION = "armed_bridge_version"
    private const val KEY_ARMED_TLS_PORT = "armed_tls_port"
    private const val KEY_ARMED_BRIDGE_RUNNING = "armed_bridge_running"
    private const val KEY_ARM_FAILED_SINCE = "arm_failed_since_armed"
    private const val KEY_ARMED_HELPER_WAS_RUNNING = "armed_helper_was_running"

    private val bridgeDemandUnanswered = AtomicBoolean(false)

    fun recordArmed(
        context: Context,
        tlsPort: Int,
        bridgeRunning: Boolean,
        helperRunningBefore: Boolean? = null,
    ) {
        prefs(context).edit()
            .putLong(KEY_ARMED_BOOT_INSTANT, currentBootInstantMillis())
            .putString(KEY_ARMED_BRIDGE_VERSION, SelfArmConstants.BRIDGE_VERSION)
            .putInt(KEY_ARMED_TLS_PORT, tlsPort)
            .putBoolean(KEY_ARMED_BRIDGE_RUNNING, bridgeRunning)
            .putBoolean(KEY_ARM_FAILED_SINCE, false)
            .apply {
                if (helperRunningBefore == null) {
                    remove(KEY_ARMED_HELPER_WAS_RUNNING)
                } else {
                    putBoolean(KEY_ARMED_HELPER_WAS_RUNNING, helperRunningBefore)
                }
            }
            .commit()
        // A bridge the arm could not start leaves the demand standing, so the next radio
        // opportunity keeps trying instead of declaring victory on the watchdog alone.
        if (bridgeRunning) bridgeDemandUnanswered.set(false)
    }

    /**
     * An arm that did not reach READY is a measurement too, and it contradicts the record: the
     * helper the record says is alive could not be reached or restored. The arm epoch itself is
     * kept, because it still says which boot the helper was last seen on.
     */
    fun recordArmFailed(context: Context) {
        prefs(context).edit().putBoolean(KEY_ARM_FAILED_SINCE, true).commit()
    }

    fun armFailedSinceArmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARM_FAILED_SINCE, false)

    fun armedBootInstantMillis(context: Context): Long? =
        prefs(context).getLong(KEY_ARMED_BOOT_INSTANT, 0L).takeIf { it != 0L }

    /** Absent on installs armed before it was recorded: read as not running, never as running. */
    fun armedBridgeRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARMED_BRIDGE_RUNNING, false)

    /**
     * Whether the last successful arm found the watchdog and the bridge already running before it
     * started anything; null when that arm could not tell.
     */
    fun armedHelperWasRunning(context: Context): Boolean? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ARMED_HELPER_WAS_RUNNING)) return null
        return prefs.getBoolean(KEY_ARMED_HELPER_WAS_RUNNING, false)
    }

    fun helperRecord(context: Context): SelfArmOwnerRepairPolicy.HelperRecord =
        SelfArmOwnerRepairPolicy.helperRecord(
            armedBootInstantMs = armedBootInstantMillis(context),
            armedBridgeRunning = armedBridgeRunning(context),
            currentBootInstantMs = currentBootInstantMillis(),
            bridgeDemandPending = isBridgeDemandPending(),
            armFailedSince = armFailedSinceArmed(context),
        )

    fun presumedDead(context: Context): Boolean =
        SelfArmBridgeLivenessPolicy.presumedDead(
            armedBootInstantMillis(context),
            currentBootInstantMillis(),
        )

    /** Wall time at elapsedRealtime zero, whole seconds: stable within a boot, never across one. */
    fun currentBootInstantMillis(): Long {
        val raw = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return raw - raw % 1_000L
    }

    /** Called when the bridge was the only route to an effect and did not deliver it. */
    fun noteBridgeDemandUnanswered() {
        bridgeDemandUnanswered.set(true)
    }

    fun isBridgeDemandPending(): Boolean = bridgeDemandUnanswered.get()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
