package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * What the phone hub owes the glasses' notification history, by owner id only: the owners the
 * glasses may hold entries for, and the purges not yet delivered. No notification text, thread
 * or destination is ever kept here.
 */
internal data class PhoneNoticeOwners(
    val historyOwners: Set<String> = emptySet(),
    val pendingPurges: Set<String> = emptySet(),
)

internal interface PhoneNoticeOwnerLedger {
    fun load(): PhoneNoticeOwners

    fun save(owners: PhoneNoticeOwners)
}

/** The ledger in the hub's private preferences, written synchronously so a purge owed survives a kill. */
internal class SharedPreferencesNoticeOwnerLedger(private val preferences: SharedPreferences) : PhoneNoticeOwnerLedger {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun load(): PhoneNoticeOwners = PhoneNoticeOwners(
        historyOwners = preferences.getStringSet(KEY_HISTORY_OWNERS, null).orEmpty().toSet(),
        pendingPurges = preferences.getStringSet(KEY_PENDING_PURGES, null).orEmpty().toSet(),
    )

    override fun save(owners: PhoneNoticeOwners) {
        preferences.edit()
            .putStringSet(KEY_HISTORY_OWNERS, owners.historyOwners.toSet())
            .putStringSet(KEY_PENDING_PURGES, owners.pendingPurges.toSet())
            .commit()
    }

    private companion object {
        const val PREFS = "notice_history_owners"
        const val KEY_HISTORY_OWNERS = "history_owners"
        const val KEY_PENDING_PURGES = "pending_purges"
    }
}
