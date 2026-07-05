package com.anezium.rokidbus.lyrics.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class MusixmatchCredentials(
    val email: String,
    val password: String,
)

data class MusixmatchSessionToken(
    val userToken: String,
    val expiresAtMs: Long,
)

interface MusixmatchCredentialsSource {
    fun getMusixmatchCredentials(): MusixmatchCredentials?
}

interface MusixmatchSessionCacheSource {
    fun getMusixmatchSessionToken(): MusixmatchSessionToken?
    fun saveMusixmatchSessionToken(userToken: String, expiresAtMs: Long)
    fun clearMusixmatchSessionToken()
}

class LyricsProviderSettingsStore(
    context: Context,
) : MusixmatchCredentialsSource, MusixmatchSessionCacheSource {
    private val preferences: SharedPreferences = createPreferences(context.applicationContext)

    override fun getMusixmatchCredentials(): MusixmatchCredentials? {
        val email = preferences.getString(KEY_MUSIXMATCH_EMAIL, null)?.trim().orEmpty()
        val password = preferences.getString(KEY_MUSIXMATCH_PASSWORD, null).orEmpty()
        if (email.isBlank() || password.isBlank()) {
            return null
        }
        return MusixmatchCredentials(
            email = email,
            password = password,
        )
    }

    override fun getMusixmatchSessionToken(): MusixmatchSessionToken? {
        val userToken = preferences.getString(KEY_MUSIXMATCH_USER_TOKEN, null)?.trim().orEmpty()
        val expiresAtMs = preferences.getLong(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS, 0L)
        if (userToken.isBlank() || expiresAtMs <= 0L) {
            return null
        }
        return MusixmatchSessionToken(
            userToken = userToken,
            expiresAtMs = expiresAtMs,
        )
    }

    fun hasMusixmatchCredentials(): Boolean =
        getMusixmatchCredentials() != null

    fun saveMusixmatchCredentials(email: String, password: String) {
        preferences.edit()
            .putString(KEY_MUSIXMATCH_EMAIL, email.trim())
            .putString(KEY_MUSIXMATCH_PASSWORD, password)
            .remove(KEY_MUSIXMATCH_USER_TOKEN)
            .remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
            .apply()
    }

    fun clearMusixmatchCredentials() {
        preferences.edit()
            .remove(KEY_MUSIXMATCH_EMAIL)
            .remove(KEY_MUSIXMATCH_PASSWORD)
            .remove(KEY_MUSIXMATCH_USER_TOKEN)
            .remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
            .apply()
    }

    override fun saveMusixmatchSessionToken(userToken: String, expiresAtMs: Long) {
        preferences.edit()
            .putString(KEY_MUSIXMATCH_USER_TOKEN, userToken.trim())
            .putLong(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS, expiresAtMs)
            .apply()
    }

    override fun clearMusixmatchSessionToken() {
        preferences.edit()
            .remove(KEY_MUSIXMATCH_USER_TOKEN)
            .remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
            .apply()
    }

    private fun createPreferences(context: Context): SharedPreferences =
        runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFERENCES_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { error ->
            Log.w(
                TAG,
                "EncryptedSharedPreferences unavailable, falling back to regular SharedPreferences: ${error.message}",
            )
            context.getSharedPreferences("${PREFERENCES_NAME}_fallback", Context.MODE_PRIVATE)
        }

    private companion object {
        private const val TAG = "LyricsProviderSettings"
        private const val PREFERENCES_NAME = "lyrics_provider_settings"
        private const val KEY_MUSIXMATCH_EMAIL = "musixmatch_email"
        private const val KEY_MUSIXMATCH_PASSWORD = "musixmatch_password"
        private const val KEY_MUSIXMATCH_USER_TOKEN = "musixmatch_user_token"
        private const val KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS = "musixmatch_token_expires_at_ms"
    }
}
