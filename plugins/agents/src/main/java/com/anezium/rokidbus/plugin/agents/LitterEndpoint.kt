package com.anezium.rokidbus.plugin.agents

import java.net.URI
import java.net.InetAddress

/** Credentials deliberately have no generated toString/copy/component methods. */
class LitterEndpoint(
    val name: String,
    val url: String,
    val token: String = "",
    val allowInsecureNetwork: Boolean = false,
    val cwd: String = "",
) {
    fun validate(): String? {
        if (name.isBlank() || name.length > 80) return "Give the server a name (80 characters maximum)."
        if (url.length > 2_048) return "The server URL is too long."
        val uri = runCatching { URI(url) }.getOrNull() ?: return "Enter a valid WebSocket URL."
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) {
            return "Use a ws:// or wss:// server URL."
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return "Keep credentials in the token field; URL credentials, queries and fragments are unsupported."
        }
        if (uri.port != -1 && uri.port !in 1..65535) return "The server port is invalid."
        if (token.length > 8_192 || token.any { it.code !in 0x21..0x7e }) {
            return "The bearer token must contain printable characters without spaces."
        }
        if (cwd.length > 4_096 || cwd.any { it.code < 32 }) return "The project folder is invalid."
        if (uri.scheme == "ws" && !isLoopback(uri.host) && !allowInsecureNetwork) {
            return "Use wss://, a loopback tunnel, or explicitly allow unencrypted network access."
        }
        return null
    }

    override fun toString(): String = "LitterEndpoint(redacted)"

    private fun isLoopback(host: String): Boolean {
        val literal = host.lowercase().removePrefix("[").removeSuffix("]")
        if (literal in setOf("localhost", "127.0.0.1", "::1")) return true
        // Only a numeric IPv6 literal reaches InetAddress; never resolve a hostname
        // here and then authorize an independent DNS lookup by the HTTP client.
        if (':' !in literal || literal.any { it !in "0123456789abcdef:" }) return false
        return runCatching { InetAddress.getByName(literal).isLoopbackAddress }.getOrDefault(false)
    }
}
