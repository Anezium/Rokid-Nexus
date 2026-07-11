package com.anezium.rokidbus.lyrics.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderSettingsStoreTest {
    @Test
    fun unavailableEncryptedStorageFailsClosedForCredentials() {
        val store = LyricsProviderSettingsStore(preferences = null)

        assertNull(store.getMusixmatchCredentials())
        assertFalse(store.hasMusixmatchCredentials())
        assertFalse(store.saveMusixmatchCredentials("person@example.com", "private-password"))
        assertFalse(store.clearMusixmatchCredentials())
    }

    @Test
    fun spotifySpDc_saveNormalizesPastedCookieAndClearRemovesIt() {
        val preferences = InMemorySharedPreferences()
        val store = LyricsProviderSettingsStore(preferences = preferences)

        assertTrue(store.saveSpotifySpDc("Cookie: sp_key=old; sp_dc=AQD_cookie; other=1"))
        assertEquals("AQD_cookie", store.getSpotifySpDc())
        assertTrue(store.hasSpotifySpDc())

        assertTrue(store.clearSpotifySpDc())
        assertNull(store.getSpotifySpDc())
        assertFalse(store.hasSpotifySpDc())
    }

    @Test
    fun spotifySpDc_invalidPasteRemovesSavedValue() {
        val preferences = InMemorySharedPreferences()
        val store = LyricsProviderSettingsStore(preferences = preferences)
        assertTrue(store.saveSpotifySpDc("sp_dc=AQD_cookie"))

        assertTrue(store.saveSpotifySpDc("sp_key=old; other=1"))

        assertNull(store.getSpotifySpDc())
    }

    @Test
    fun unavailableEncryptedStorageFailsClosedForSpotifyCookie() {
        val store = LyricsProviderSettingsStore(preferences = null)

        assertNull(store.getSpotifySpDc())
        assertFalse(store.hasSpotifySpDc())
        assertFalse(store.saveSpotifySpDc("sp_dc=AQD_cookie"))
        assertFalse(store.clearSpotifySpDc())
    }

    @Test
    fun unavailableEncryptedStorageReturnsNoSessionAndLogsNoTokenValue() {
        val warnings = mutableListOf<String>()
        val store = LyricsProviderSettingsStore(
            preferences = null,
            warningLogger = warnings::add,
        )

        assertNull(store.getMusixmatchSessionToken())
        store.saveMusixmatchSessionToken("private-session-token", 1234L)

        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.none { it.contains("private-session-token") })
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            key?.let { pending[it] = value }
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            key?.let { pending[it] = values?.toSet() }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            key?.let { pending[it] = value }
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            key?.let { pending[it] = value }
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            key?.let { pending[it] = value }
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            key?.let { pending[it] = value }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            key?.let { removals += it }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            if (clearRequested) values.clear()
            removals.forEach(values::remove)
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
