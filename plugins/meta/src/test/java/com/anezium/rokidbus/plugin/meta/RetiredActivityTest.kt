package com.anezium.rokidbus.plugin.meta

import android.content.SharedPreferences
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredActivityTest {
    @Test
    fun `an upgrade from a kept card ends it once and forgets the choice`() {
        val prefs = FakePreferences(mutableMapOf("keep_in_activities" to true))
        var ends = 0
        assertTrue(RetiredActivity.settle(prefs) { ends++; NexusSdkResult.SENT })
        assertEquals(1, ends)
        assertFalse(RetiredActivity.owesEnd(prefs))
        // Nothing is owed any more: later registrations send nothing.
        assertTrue(RetiredActivity.settle(prefs) { ends++; NexusSdkResult.SENT })
        assertEquals(1, ends)
    }

    @Test
    fun `an end the hub did not take stays owed`() {
        val prefs = FakePreferences(mutableMapOf("activity_end_pending" to true))
        assertFalse(RetiredActivity.settle(prefs) { NexusSdkResult.NOT_REGISTERED })
        assertTrue(RetiredActivity.owesEnd(prefs))
        // Registered before the glasses' capabilities are known: nothing was sent, so it stays owed.
        assertFalse(RetiredActivity.settle(prefs) { NexusSdkResult.CAPABILITY_NOT_AVAILABLE })
        assertTrue(RetiredActivity.owesEnd(prefs))
    }

    @Test
    fun `a phone that never kept the card sends nothing`() {
        var ends = 0
        assertTrue(RetiredActivity.settle(FakePreferences(mutableMapOf())) { ends++; NexusSdkResult.SENT })
        assertEquals(0, ends)
    }

    /** Just what [RetiredActivity] reads and removes. */
    private class FakePreferences(val values: MutableMap<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val removed = mutableSetOf<String>()
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { key?.let(removed::add) }
            override fun clear() = this
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removed.forEach(values::remove) }
        }
    }
}
