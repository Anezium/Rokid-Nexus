package com.anezium.rokidbus.shared.plugin

object PathRules {
    private val reservedRoots = setOf("/launcher", "/surface/input", "/system", "/security", "/error")
    private val lifecyclePrefixes = setOf("/system/plugin")
    private val httpReplyPrefixes = setOf("/http/request/reply")
    private val audioReplyPrefixes = setOf(
        "/audio/frames",
        "/audio/lease/acquire/reply",
        "/audio/lease/release/reply",
        "/audio/lease/revoked",
    )

    fun normalizeAbsolute(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith('/') || trimmed == "/") return null
        val segments = trimmed.substring(1).split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return "/${segments.joinToString("/")}"
    }

    fun matchesPrefix(path: String, prefix: String): Boolean {
        val normalizedPath = normalizeAbsolute(path) ?: return false
        val normalizedPrefix = normalizeAbsolute(prefix) ?: return false
        return normalizedPath == normalizedPrefix || normalizedPath.startsWith("$normalizedPrefix/")
    }

    fun isReserved(path: String): Boolean {
        val normalized = normalizeAbsolute(path) ?: return true
        return reservedRoots.any { matchesPrefix(normalized, it) }
    }

    fun isPluginPrivate(path: String, pluginId: String): Boolean =
        matchesPrefix(path, "/plugin/$pluginId")

    fun requiredCapability(path: String): PluginCapability? = when (normalizeAbsolute(path)) {
        "/surface/show", "/surface/update", "/surface/hide" -> PluginCapability.SURFACES
        "/audio/lease/acquire", "/audio/lease/release" -> PluginCapability.MICROPHONE
        "/http/request" -> PluginCapability.HTTP_PROXY
        else -> null
    }

    fun isAllowedReceivePrefix(
        prefix: String,
        pluginId: String,
        requestedCapabilities: Set<PluginCapability>,
    ): Boolean {
        val normalized = normalizeAbsolute(prefix) ?: return false
        if (isPluginPrivate(normalized, pluginId)) return true
        if (lifecyclePrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }) return true
        if (PluginCapability.HTTP_PROXY in requestedCapabilities &&
            httpReplyPrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }
        ) return true
        if (PluginCapability.MICROPHONE in requestedCapabilities &&
            audioReplyPrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }
        ) return true
        return false
    }

    fun requiredCapabilityForReceivePrefix(prefix: String): PluginCapability? {
        val normalized = normalizeAbsolute(prefix) ?: return null
        if (httpReplyPrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }) {
            return PluginCapability.HTTP_PROXY
        }
        if (audioReplyPrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }) {
            return PluginCapability.MICROPHONE
        }
        return null
    }
}
