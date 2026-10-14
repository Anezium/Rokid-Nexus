package com.anezium.rokidbus.glasses

/**
 * The Wi-Fi one repair turned on and owes turning back off — in memory, and only for that repair.
 *
 * Never persisted and never handed to a later repair or to the hub's reconciliation: an
 * obligation that outlives its repair ends up turning off a radio the wearer has since wanted.
 * Leaving Wi-Fi on is the lesser harm, so anything that makes the repair's claim doubtful drops
 * it: another owner taking the radio, or the radio going off after the repair saw it come on,
 * which the repair did not do. A hub that dies mid-repair forgets it the same way.
 */
internal class SelfArmRepairWifiObligation {
    private var owed = false
    private var radioSeenOn = false
    private var droppedBecause: String? = null

    /** Taken before the repair asks for the radio, while it is still off. */
    @Synchronized
    fun take() {
        owed = true
        radioSeenOn = false
        droppedBecause = null
    }

    /** Someone else now owns the radio. True when this dropped a live obligation. */
    @Synchronized
    fun revoke(reason: String): Boolean {
        if (!owed) return false
        owed = false
        droppedBecause = reason
        return true
    }

    /**
     * A radio state edge seen while the repair runs. True when it dropped the obligation: an off
     * after the repair's on was someone else's doing, so whatever comes up next is theirs too.
     */
    @Synchronized
    fun onRadioState(enabled: Boolean): Boolean {
        if (!owed) return false
        if (enabled) {
            radioSeenOn = true
            return false
        }
        return radioSeenOn && revoke("radio_turned_off_elsewhere")
    }

    /** Ends the obligation for good; returns whether it was still owed, else why it was dropped. */
    @Synchronized
    fun release(): Release {
        val release = Release(owed = owed, droppedBecause = droppedBecause)
        owed = false
        radioSeenOn = false
        droppedBecause = null
        return release
    }

    @Synchronized
    fun isOwed(): Boolean = owed

    data class Release(val owed: Boolean, val droppedBecause: String?)
}
