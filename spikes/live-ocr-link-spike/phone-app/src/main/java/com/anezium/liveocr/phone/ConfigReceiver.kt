package com.anezium.liveocr.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal data class LinkCredentials(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
) {
    fun isValid(): Boolean = ssid.isNotBlank() && ssid.length <= 128 &&
        passphrase.length in 8..128 && port in 1..65_535 &&
        token.length in 16..256 && goIp.isNotBlank() && goIp.length <= 64
}

class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIGURE) return
        val previous = loadCredentials(context)
        val credentials = LinkCredentials(
            ssid = intent.getStringExtra(EXTRA_SSID) ?: previous?.ssid.orEmpty(),
            passphrase = intent.getStringExtra(EXTRA_PASSPHRASE)
                ?: intent.getStringExtra("pass")
                ?: previous?.passphrase.orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, previous?.port ?: DEFAULT_PORT),
            token = intent.getStringExtra(EXTRA_TOKEN) ?: previous?.token.orEmpty(),
            goIp = intent.getStringExtra(EXTRA_GO_IP) ?: previous?.goIp ?: DEFAULT_GO_IP,
        )
        if (!credentials.isValid()) return
        saveCredentials(context, credentials)
        context.sendBroadcast(Intent(ACTION_CONFIG_UPDATED).setPackage(context.packageName))
    }

    companion object {
        const val ACTION_CONFIGURE = "com.anezium.liveocr.CONFIGURE"
        const val ACTION_CONFIG_UPDATED = "com.anezium.liveocr.CONFIG_UPDATED"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSPHRASE = "passphrase"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_GO_IP = "goIp"
        const val DEFAULT_PORT = 38_401
        const val DEFAULT_GO_IP = "192.168.49.1"
        private const val PREFERENCES = "live_ocr_link"

        internal fun loadCredentials(context: Context): LinkCredentials? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val credentials = LinkCredentials(
                ssid = preferences.getString(EXTRA_SSID, "").orEmpty(),
                passphrase = preferences.getString(EXTRA_PASSPHRASE, "").orEmpty(),
                port = preferences.getInt(EXTRA_PORT, DEFAULT_PORT),
                token = preferences.getString(EXTRA_TOKEN, "").orEmpty(),
                goIp = preferences.getString(EXTRA_GO_IP, DEFAULT_GO_IP).orEmpty(),
            )
            return credentials.takeIf(LinkCredentials::isValid)
        }

        internal fun saveCredentials(context: Context, credentials: LinkCredentials) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(EXTRA_SSID, credentials.ssid)
                .putString(EXTRA_PASSPHRASE, credentials.passphrase)
                .putInt(EXTRA_PORT, credentials.port)
                .putString(EXTRA_TOKEN, credentials.token)
                .putString(EXTRA_GO_IP, credentials.goIp)
                .apply()
        }
    }
}
