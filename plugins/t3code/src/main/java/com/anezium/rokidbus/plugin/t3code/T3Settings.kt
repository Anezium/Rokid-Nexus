package com.anezium.rokidbus.plugin.t3code

import android.content.Context

internal data class T3Endpoint(
    val host: String,
    val port: Int,
    val token: String,
    val label: String,
)

internal class T3Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun endpoint(): T3Endpoint? {
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val token = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
        if (host.isEmpty() || token.isEmpty()) return null
        return T3Endpoint(
            host = host,
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535),
            token = token,
            label = prefs.getString(KEY_LABEL, null)?.trim().orEmpty().ifBlank { host },
        )
    }

    fun savedHost(): String = prefs.getString(KEY_HOST, "").orEmpty()

    fun savedPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)

    fun save(endpoint: T3Endpoint) {
        prefs.edit()
            .putString(KEY_HOST, endpoint.host)
            .putInt(KEY_PORT, endpoint.port)
            .putString(KEY_TOKEN, endpoint.token)
            .putString(KEY_LABEL, endpoint.label)
            .apply()
    }

    fun updateLabel(label: String) {
        if (label.isBlank() || endpoint() == null) return
        prefs.edit().putString(KEY_LABEL, label.trim()).apply()
    }

    fun forget() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_PORT = 3773
        private const val PREFS = "t3code_settings"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "bearer"
        private const val KEY_LABEL = "label"
    }
}
