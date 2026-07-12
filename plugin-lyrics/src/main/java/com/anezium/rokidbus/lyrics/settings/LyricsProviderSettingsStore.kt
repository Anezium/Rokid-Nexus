package com.anezium.rokidbus.lyrics.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.anezium.rokidbus.lyrics.lyrics.SpotifySpDcCookie

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

interface SpotifySpDcSource {
    fun getSpotifySpDc(): String?
}

class LyricsProviderSettingsStore internal constructor(
    private val preferences: SharedPreferences?,
    private val warningLogger: (String) -> Unit = {},
) : MusixmatchCredentialsSource, MusixmatchSessionCacheSource, SpotifySpDcSource {
    constructor(context: Context) : this(
        preferences = createPreferences(context.applicationContext),
        warningLogger = { message -> Log.w(TAG, message) },
    )

    override fun getMusixmatchCredentials(): MusixmatchCredentials? {
        val securePreferences = preferences ?: return null
        return runCatching {
            val email = securePreferences.getString(KEY_MUSIXMATCH_EMAIL, null)?.trim().orEmpty()
            val password = securePreferences.getString(KEY_MUSIXMATCH_PASSWORD, null).orEmpty()
            if (email.isBlank() || password.isBlank()) {
                null
            } else {
                MusixmatchCredentials(
                    email = email,
                    password = password,
                )
            }
        }.getOrElse {
            warningLogger("Secure credential read failed")
            null
        }
    }

    override fun getMusixmatchSessionToken(): MusixmatchSessionToken? {
        val securePreferences = preferences ?: return null
        return runCatching {
            val userToken = securePreferences.getString(KEY_MUSIXMATCH_USER_TOKEN, null)?.trim().orEmpty()
            val expiresAtMs = securePreferences.getLong(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS, 0L)
            if (userToken.isBlank() || expiresAtMs <= 0L) {
                null
            } else {
                MusixmatchSessionToken(
                    userToken = userToken,
                    expiresAtMs = expiresAtMs,
                )
            }
        }.getOrElse {
            warningLogger("Secure session cache read failed")
            null
        }
    }

    override fun getSpotifySpDc(): String? {
        val securePreferences = preferences ?: return null
        return runCatching {
            securePreferences.getString(KEY_SPOTIFY_SP_DC, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrElse {
            warningLogger("Secure Spotify cookie read failed")
            null
        }
    }

    fun hasSpotifySpDc(): Boolean =
        getSpotifySpDc() != null

    fun saveSpotifySpDc(value: String): Boolean {
        val normalized = SpotifySpDcCookie.extractValue(value)
        return secureCommit("Secure Spotify cookie write failed") {
            if (normalized == null) {
                remove(KEY_SPOTIFY_SP_DC)
            } else {
                putString(KEY_SPOTIFY_SP_DC, normalized)
            }
        }
    }

    fun clearSpotifySpDc(): Boolean =
        secureCommit("Secure Spotify cookie clear failed") {
            remove(KEY_SPOTIFY_SP_DC)
        }

    fun hasMusixmatchCredentials(): Boolean =
        getMusixmatchCredentials() != null

    fun saveMusixmatchCredentials(email: String, password: String): Boolean =
        secureCommit("Secure credential write failed") {
            putString(KEY_MUSIXMATCH_EMAIL, email.trim())
            putString(KEY_MUSIXMATCH_PASSWORD, password)
            remove(KEY_MUSIXMATCH_USER_TOKEN)
            remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
        }

    fun clearMusixmatchCredentials(): Boolean =
        secureCommit("Secure credential clear failed") {
            remove(KEY_MUSIXMATCH_EMAIL)
            remove(KEY_MUSIXMATCH_PASSWORD)
            remove(KEY_MUSIXMATCH_USER_TOKEN)
            remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
        }

    override fun saveMusixmatchSessionToken(userToken: String, expiresAtMs: Long) {
        if (preferences == null) {
            warningLogger("Secure session cache unavailable; token was not saved")
            return
        }
        if (!secureCommit("Secure session cache write failed") {
                putString(KEY_MUSIXMATCH_USER_TOKEN, userToken.trim())
                putLong(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS, expiresAtMs)
            }
        ) {
            warningLogger("Secure session cache write skipped")
        }
    }

    override fun clearMusixmatchSessionToken() {
        secureCommit("Secure session cache clear failed") {
            remove(KEY_MUSIXMATCH_USER_TOKEN)
            remove(KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS)
        }
    }

    private fun secureCommit(
        failureMessage: String,
        changes: SharedPreferences.Editor.() -> Unit,
    ): Boolean {
        val securePreferences = preferences ?: return false
        return runCatching {
            securePreferences.edit().apply(changes).commit()
        }.getOrElse {
            warningLogger(failureMessage)
            false
        }
    }

    private companion object {
        private const val TAG = "LyricsProviderSettings"
        private const val PREFERENCES_NAME = "nexus_plugin_lyrics_credentials"
        private const val KEY_SPOTIFY_SP_DC = "spotify_sp_dc"
        private const val KEY_MUSIXMATCH_EMAIL = "musixmatch_email"
        private const val KEY_MUSIXMATCH_PASSWORD = "musixmatch_password"
        private const val KEY_MUSIXMATCH_USER_TOKEN = "musixmatch_user_token"
        private const val KEY_MUSIXMATCH_TOKEN_EXPIRES_AT_MS = "musixmatch_token_expires_at_ms"

        private fun createPreferences(context: Context): SharedPreferences? =
            runCatching {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFERENCES_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                Log.w(TAG, "Encrypted storage unavailable; secure settings disabled")
                null
            }
    }
}
