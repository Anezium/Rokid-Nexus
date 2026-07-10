package com.anezium.rokidbus.lyrics.settings

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
