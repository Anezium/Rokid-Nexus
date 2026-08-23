package com.anezium.rokidbus.plugin.agents

import java.net.URI
import java.security.MessageDigest

/**
 * A Codex app-server reached by `ws(s)://` with no Alleycat node id or token.
 * [url] may carry userinfo; never log it — use [DirectComputerUrl.redactUserinfo].
 */
data class DirectComputer(
    val computerId: String,
    val name: String,
    val url: String,
    val lastSeenAtMs: Long? = null,
) {
    fun asListed(): TrustedMachine = TrustedMachine(computerId, name, lastSeenAtMs)

    companion object {
        const val ID_PREFIX = "direct-"
    }
}

sealed interface DirectComputerParseResult {
    data class Valid(val computer: DirectComputer) : DirectComputerParseResult
    data class Invalid(val reason: String) : DirectComputerParseResult
}

object DirectComputerUrl {
    fun parse(raw: String): DirectComputerParseResult {
        val input = raw.trim()
        if (input.isEmpty()) {
            return DirectComputerParseResult.Invalid("Enter a ws:// or wss:// address.")
        }
        if (input.length > MAX_DIRECT_URL_CHARS) {
            return DirectComputerParseResult.Invalid("Address is too long.")
        }
        val scheme = when {
            input.startsWith("ws://", ignoreCase = true) -> "ws"
            input.startsWith("wss://", ignoreCase = true) -> "wss"
            else -> return DirectComputerParseResult.Invalid("Address must start with ws:// or wss://.")
        }
        val uri = try {
            URI(input)
        } catch (_: Exception) {
            return DirectComputerParseResult.Invalid("Address is not a valid URL.")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return DirectComputerParseResult.Invalid("Address is missing a host.")
        if (host.length > MAX_HOST_CHARS) {
            return DirectComputerParseResult.Invalid("Host is too long.")
        }
        val port = uri.port
        if (port != -1 && port !in 1..65535) {
            return DirectComputerParseResult.Invalid("Port is invalid.")
        }
        val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }
        val userInfo = uri.userInfo
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val portPart = if (port != -1) ":$port" else ""
        val queryPart = if (query != null) "?$query" else ""
        val identity = "$scheme://$renderedHost$portPart$path$queryPart"
        val stored = if (userInfo.isNullOrEmpty()) {
            identity
        } else {
            "$scheme://$userInfo@$renderedHost$portPart$path$queryPart"
        }
        val name = if (port != -1) "$host:$port" else host
        return DirectComputerParseResult.Valid(
            DirectComputer(
                computerId = computerIdFor(identity),
                name = name.take(MAX_HUD_LABEL_CHARS),
                url = stored,
            ),
        )
    }

    fun redactUserinfo(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return "ws://<invalid>"
        }
        val scheme = uri.scheme?.lowercase() ?: "ws"
        val host = uri.host ?: return "$scheme://<invalid>"
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "$scheme://$renderedHost$portPart$path$query"
    }

    fun computerIdFor(identityUrlWithoutUserinfo: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identityUrlWithoutUserinfo.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return DirectComputer.ID_PREFIX + hex.take(16)
    }

    private const val MAX_DIRECT_URL_CHARS = 1_024
}
