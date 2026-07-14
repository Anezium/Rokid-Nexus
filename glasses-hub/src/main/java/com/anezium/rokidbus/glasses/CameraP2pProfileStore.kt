package com.anezium.rokidbus.glasses

import android.content.Context
import java.security.SecureRandom

internal data class CameraP2pProfile(
    val networkName: String,
    val passphrase: String,
)

/** Stable P2P credentials; the per-session TCP token remains ephemeral. */
internal class CameraP2pProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadOrCreate(): CameraP2pProfile {
        val networkName = preferences.getString(KEY_NETWORK_NAME, null)
        val passphrase = preferences.getString(KEY_PASSPHRASE, null)
        if (!networkName.isNullOrBlank() && !passphrase.isNullOrBlank()) {
            return CameraP2pProfile(networkName, passphrase)
        }
        return CameraP2pProfile(
            networkName = "DIRECT-RN-${randomText(6)}",
            passphrase = randomText(24),
        ).also(::save)
    }

    /**
     * The phone's wpa_supplicant temporarily blocklists an SSID after consecutive failed
     * handshakes (measured ~10-15s on the S23). Recreating a deaf group under the SAME name
     * keeps every join inside that blocklist window; fresh credentials bypass it entirely.
     */
    fun rotate(): CameraP2pProfile = CameraP2pProfile(
        networkName = "DIRECT-RN-${randomText(6)}",
        passphrase = randomText(24),
    ).also(::save)

    fun save(profile: CameraP2pProfile) {
        preferences.edit()
            .putInt(KEY_VERSION, VERSION)
            .putString(KEY_NETWORK_NAME, profile.networkName)
            .putString(KEY_PASSPHRASE, profile.passphrase)
            .apply()
    }

    private fun randomText(length: Int): String = buildString(length) {
        repeat(length) { append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]) }
    }

    private companion object {
        const val PREFS = "camera_p2p_profile"
        const val VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_NETWORK_NAME = "networkName"
        const val KEY_PASSPHRASE = "passphrase"
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val RANDOM = SecureRandom()
    }
}
