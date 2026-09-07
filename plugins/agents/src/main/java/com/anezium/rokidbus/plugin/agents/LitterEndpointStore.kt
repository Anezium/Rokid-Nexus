package com.anezium.rokidbus.plugin.agents

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** A separate encrypted record leaves prototype machine/project preferences intact. */
class LitterEndpointStore(context: Context) {
    private val prefs = context.getSharedPreferences("agents_litter_endpoint", Context.MODE_PRIVATE)

    fun load(): LitterEndpoint? {
        val record = prefs.getString("sealed", null) ?: return null
        val bytes = Base64.decode(record, Base64.NO_WRAP)
        require(bytes.size > 28) { "Saved server credentials are unavailable. Save the server again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return LitterEndpoint(
            name = json.getString("name"), url = json.getString("url"),
            token = json.optString("token"), allowInsecureNetwork = json.optBoolean("insecure"),
            cwd = json.optString("cwd"),
        ).also { require(it.validate() == null) { "Save the server again to update its configuration." } }
    }

    fun save(endpoint: LitterEndpoint) {
        require(endpoint.validate() == null) { "The server configuration is invalid." }
        val json = JSONObject().put("name", endpoint.name).put("url", endpoint.url)
            .put("token", endpoint.token).put("insecure", endpoint.allowInsecureNetwork)
            .put("cwd", endpoint.cwd)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        check(cipher.iv.size == 12)
        val sealed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            "The phone could not save the server."
        }
    }

    fun forget() {
        check(prefs.edit().clear().commit()) { "The phone could not remove the saved server." }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "nexus.agents.litter.endpoint.v1"
        val AAD = ALIAS.toByteArray(Charsets.UTF_8)
    }
}
