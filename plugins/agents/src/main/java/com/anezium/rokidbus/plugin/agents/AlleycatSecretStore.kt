package com.anezium.rokidbus.plugin.agents

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Bearer-token store. The token is a host-ownership secret: never log it,
 * never put it on [AlleycatComputer], never send it to the glasses.
 */
interface AlleycatSecretStore {
    fun putToken(computerId: String, token: String)

    fun getToken(computerId: String): String?

    fun removeToken(computerId: String)

    fun putEndpointSecret(bytes: ByteArray)

    fun getEndpointSecret(): ByteArray?
}

class MemoryAlleycatSecretStore : AlleycatSecretStore {
    private val tokens = linkedMapOf<String, String>()
    private var endpointSecret: ByteArray? = null

    @Synchronized
    override fun putToken(computerId: String, token: String) {
        tokens[computerId] = token
    }

    override fun toString(): String = "MemoryAlleycatSecretStore(size=${tokens.size})"

    @Synchronized
    override fun getToken(computerId: String): String? = tokens[computerId]

    @Synchronized
    override fun removeToken(computerId: String) {
        tokens.remove(computerId)
    }

    @Synchronized
    override fun putEndpointSecret(bytes: ByteArray) {
        endpointSecret = bytes.copyOf()
    }

    @Synchronized
    override fun getEndpointSecret(): ByteArray? = endpointSecret?.copyOf()
}

/**
 * Keystore-backed encrypted prefs. There is no plaintext fallback: if the
 * Keystore cannot be opened, pairing cannot persist a token.
 */
class EncryptedAlleycatSecretStore(context: Context) : AlleycatSecretStore {
    private val prefs: SharedPreferences = encryptedPrefs(context.applicationContext)

    override fun toString(): String = "EncryptedAlleycatSecretStore(redacted)"

    override fun putToken(computerId: String, token: String) {
        prefs.edit().putString(tokenKey(computerId), token).apply()
    }

    override fun getToken(computerId: String): String? =
        prefs.getString(tokenKey(computerId), null)?.takeIf { it.isNotBlank() }

    override fun removeToken(computerId: String) {
        prefs.edit().remove(tokenKey(computerId)).apply()
    }

    override fun putEndpointSecret(bytes: ByteArray) {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_ENDPOINT_SECRET, hex).apply()
    }

    override fun getEndpointSecret(): ByteArray? {
        val hex = prefs.getString(KEY_ENDPOINT_SECRET, null) ?: return null
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun tokenKey(computerId: String): String = "$KEY_TOKEN_PREFIX$computerId"

    companion object {
        private const val PREFS = "nexus_plugin_agents_alleycat_secrets"
        private const val KEY_TOKEN_PREFIX = "token."
        private const val KEY_ENDPOINT_SECRET = "endpoint.secret"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                PREFS,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
