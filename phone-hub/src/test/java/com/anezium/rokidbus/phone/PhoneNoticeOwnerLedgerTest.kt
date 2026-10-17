package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The notice owner ledger over the hub's real preferences, read back by a new process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneNoticeOwnerLedgerTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `what one process saves the next one loads, and only owner ids are stored`() {
        SharedPreferencesNoticeOwnerLedger(app).save(PhoneNoticeOwners(setOf("relay", "assistant"), setOf("media")))

        assertEquals(
            PhoneNoticeOwners(setOf("relay", "assistant"), setOf("media")),
            SharedPreferencesNoticeOwnerLedger(app).load(),
        )
        val stored = app.getSharedPreferences("notice_history_owners", 0).all
        assertEquals(setOf("history_owners", "pending_purges"), stored.keys)
        assertTrue(stored.values.flatMap { it as Set<*> }.all { it in setOf("relay", "assistant", "media") })
    }

    @Test
    fun `an empty ledger loads as nothing owed`() {
        assertEquals(PhoneNoticeOwners(), SharedPreferencesNoticeOwnerLedger(app).load())
    }
}
