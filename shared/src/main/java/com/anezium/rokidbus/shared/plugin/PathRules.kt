package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusPaths

object PathRules {
    private val reservedRoots = setOf(
        "/core",
        "/launcher",
        "/surface/input",
        "/system",
        "/security",
        "/error",
    )
    private val hubOnlyPaths = setOf(
        BusPaths.TTS_CANCEL,
        BusPaths.VIDEO_SESSION_STATE,
    )
    private val lifecyclePrefixes = setOf("/system/plugin")
    private val httpReplyPrefixes = setOf("/http/request/reply")
    private val audioReplyPrefixes = setOf(
        "/audio/frames",
        "/audio/lease/acquire/reply",
        "/audio/lease/release/reply",
        "/audio/lease/revoked",
    )
    private val sttReceivePrefixes = setOf("/stt")
    private val ttsReceivePrefixes = setOf(BusPaths.TTS_STARTED, BusPaths.TTS_DONE)
    private val cameraReceivePrefixes = setOf(
        BusPaths.CAMERA_SESSION_STATE,
        BusPaths.CAMERA_LINK_OFFER,
        BusPaths.CAMERA_FREEZE_IMAGE_CHUNK,
        BusPaths.CAMERA_SNAPSHOT_RESULT,
        BusPaths.CAMERA_SNAPSHOT_ERROR,
    )
    private val mediaSyncReceivePrefixes = setOf(BusPaths.MEDIA_SYNC_STATUS)
    private val videoReceivePrefixes = setOf(BusPaths.VIDEO_SESSION_STATE)

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
        return isHubOnly(normalized) || reservedRoots.any { matchesPrefix(normalized, it) }
    }

    fun isHubOnly(path: String): Boolean =
        normalizeAbsolute(path)?.let(hubOnlyPaths::contains) == true

    fun isPluginPrivate(path: String, pluginId: String): Boolean =
        matchesPrefix(path, "/plugin/$pluginId")

    /**
     * Paths delivered only to the plugin named by `pluginId` in the payload,
     * never to everything that happens to subscribe to the prefix.
     *
     * Notice traffic coming back from the glasses belongs here: which plugin's
     * banner the wearer dismissed, and what they pressed on it, is that
     * plugin's business and nobody else's.
     */
    /**
     * Replies the hub addresses to one plugin about something that plugin just
     * did, rather than traffic anyone subscribes to. A plugin does not declare a
     * receive prefix to hear the answer to its own banner, so these bypass the
     * declared prefixes -- and stay owner-scoped, so they still reach nobody
     * else. Requiring a prefix here fails silently, which is the worst way for a
     * third-party plugin to discover it needed one.
     */
    fun isDirectReply(path: String): Boolean = when (normalizeAbsolute(path)) {
        BusPaths.NOTICE_CLOSED, BusPaths.NOTICE_INPUT, BusPaths.NOTICE_ACTION,
        BusPaths.ACTIVITY_ACTION, BusPaths.ACTIVITY_CLOSED,
        BusPaths.TTS_STARTED, BusPaths.TTS_DONE,
        BusPaths.CAMERA_SNAPSHOT_RESULT, BusPaths.CAMERA_SNAPSHOT_ERROR,
        BusPaths.WIRELESS_ADB_REPLY,
        BusPaths.INK_EVENT,
        BusPaths.VIDEO_SESSION_STATE,
        -> true
        else -> false
    }

    fun isOwnerScoped(path: String): Boolean = when (normalizeAbsolute(path)) {
        BusPaths.NOTICE_CLOSED, BusPaths.NOTICE_INPUT, BusPaths.NOTICE_ACTION,
        BusPaths.ACTIVITY_ACTION, BusPaths.ACTIVITY_CLOSED,
        BusPaths.TTS_STARTED, BusPaths.TTS_DONE,
        BusPaths.CAMERA_SNAPSHOT_RESULT, BusPaths.CAMERA_SNAPSHOT_ERROR,
        BusPaths.WIRELESS_ADB_REPLY,
        BusPaths.INK_EVENT,
        BusPaths.VIDEO_SESSION_STATE,
        -> true
        else -> matchesPrefix(path, "/system/plugin")
    }

    fun requiredCapability(path: String): PluginCapability? = when (normalizeAbsolute(path)) {
        "/surface/show", "/surface/update", "/surface/hide",
        BusPaths.PIN_SHOW, BusPaths.PIN_HIDE,
        BusPaths.NOTICE_SHOW, BusPaths.NOTICE_UPDATE, BusPaths.NOTICE_HIDE,
        BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE, BusPaths.ACTIVITY_END,
        -> PluginCapability.SURFACES
        BusPaths.INK_SHOW, BusPaths.INK_UPDATE, BusPaths.INK_HIDE ->
            PluginCapability.INK_SURFACE
        "/audio/lease/acquire", "/audio/lease/release" -> PluginCapability.MICROPHONE
        "/stt/session/start", "/stt/session/stop" -> PluginCapability.STT
        BusPaths.TTS_SPEAK, BusPaths.TTS_STOP -> PluginCapability.TTS
        "/http/request" -> PluginCapability.HTTP_PROXY
        BusPaths.CAMERA_LINK_OFFER,
        BusPaths.CAMERA_FREEZE_RESULT,
        BusPaths.CAMERA_OVERLAY,
        BusPaths.CAMERA_SNAPSHOT_REQUEST,
        -> PluginCapability.CAMERA
        BusPaths.MEDIA_SYNC_SETTINGS,
        BusPaths.MEDIA_SYNC_NOW,
        -> PluginCapability.MEDIA_SYNC
        BusPaths.WIRELESS_ADB_REQUEST -> PluginCapability.WIRELESS_DEBUGGING
        BusPaths.VIDEO_SESSION_OPEN, BusPaths.VIDEO_SESSION_CONTROL ->
            PluginCapability.VIDEO_PLAYBACK
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
        if (PluginCapability.STT in requestedCapabilities &&
            sttReceivePrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }
        ) return true
        if (PluginCapability.TTS in requestedCapabilities &&
            normalized in ttsReceivePrefixes
        ) return true
        if (PluginCapability.CAMERA in requestedCapabilities &&
            cameraReceivePrefixes.any { matchesPrefix(normalized, it) }
        ) return true
        if (PluginCapability.MEDIA_SYNC in requestedCapabilities &&
            mediaSyncReceivePrefixes.any { matchesPrefix(normalized, it) }
        ) return true
        if (PluginCapability.VIDEO_PLAYBACK in requestedCapabilities &&
            videoReceivePrefixes.any { matchesPrefix(normalized, it) }
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
        if (sttReceivePrefixes.any { matchesPrefix(normalized, it) || matchesPrefix(it, normalized) }) {
            return PluginCapability.STT
        }
        if (normalized in ttsReceivePrefixes) {
            return PluginCapability.TTS
        }
        if (cameraReceivePrefixes.any { matchesPrefix(normalized, it) }) {
            return PluginCapability.CAMERA
        }
        if (mediaSyncReceivePrefixes.any { matchesPrefix(normalized, it) }) {
            return PluginCapability.MEDIA_SYNC
        }
        if (videoReceivePrefixes.any { matchesPrefix(normalized, it) }) {
            return PluginCapability.VIDEO_PLAYBACK
        }
        return null
    }
}
