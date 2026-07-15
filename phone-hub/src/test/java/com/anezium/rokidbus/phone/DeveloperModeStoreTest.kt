package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeveloperModeStoreTest {
    @Test
    fun `journal follows persisted developer mode changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val store = DeveloperModeStore(context)
        val journal = PluginBusJournal().apply { enabled.set(true) }

        val subscription = bindDeveloperModeToJournal(store, journal)
        assertFalse(store.isEnabled())
        assertFalse(journal.enabled.get())

        store.setEnabled(true)
        assertTrue(journal.enabled.get())

        store.setEnabled(false)
        assertFalse(journal.enabled.get())
        subscription.close()
    }
}
