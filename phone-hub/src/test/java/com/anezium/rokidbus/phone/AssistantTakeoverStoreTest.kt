package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AssistantTakeoverStoreTest {
    @Test
    fun `takeover is on by default and persists the last flip`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = AssistantTakeoverStore(context)

        assertTrue(store.isEnabled())
        store.setEnabled(false)
        assertFalse(AssistantTakeoverStore(context).isEnabled())
        store.setEnabled(true)
        assertTrue(AssistantTakeoverStore(context).isEnabled())
    }
}
