package com.anezium.rokidbus.glasses

import android.content.Context

/**
 * Durable evidence that a repair turned the glasses' Wi-Fi on and owes turning it back off.
 *
 * Kept apart from whether the arm worked: a bridge that brings the radio up and an arm that then
 * fails still leave the wearer with a radio they had off. Taken before the enable, so a hub that
 * dies mid-repair leaves the debt for [GlassesHub]'s reconciliation, and cleared only once the
 * radio is seen off. Not hub ownership: it never blocks a re-arm, which is what revives the bridge
 * the restore goes through. Scoped to the boot it was taken on — this ROM boots with Wi-Fi off.
 */
internal object SelfArmRepairWifiObligationStore {
    fun recordBeforeEnable(
        context: Context,
        bootInstantMs: Long = SelfArmBridgeLivenessStore.currentBootInstantMillis(),
    ): Boolean = prefs(context).edit()
        .putLong(KEY_OWED_BOOT_INSTANT, bootInstantMs)
        .commit()

    fun isOwed(
        context: Context,
        bootInstantMs: Long = SelfArmBridgeLivenessStore.currentBootInstantMillis(),
    ): Boolean {
        val owedBootInstant = prefs(context).getLong(KEY_OWED_BOOT_INSTANT, 0L)
        return owedBootInstant > 0L &&
            !SelfArmBridgeLivenessPolicy.presumedDead(owedBootInstant, bootInstantMs)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_OWED_BOOT_INSTANT).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "selfarm_wireless"
    private const val KEY_OWED_BOOT_INSTANT = "repair_wifi_off_owed_boot_instant"
}
