package com.anezium.rokidbus.glasses

/**
 * Mirrors the `:camera` process' session state into the main hub process.
 *
 * `CameraActivity` and `CameraLink` are unreachable from here by design (separate process,
 * separate statics), so the main process learns about a live camera session the only way it
 * can: by watching the `/camera/session/state` envelopes that already pass through
 * [GlassesHub.routeLocal] on their way to the phone. Photo sync consults this before touching
 * Wi-Fi Direct.
 */
class CameraSessionTracker(private val onChanged: (Boolean) -> Unit = {}) {
    private var activeSessionId: String? = null

    @Synchronized
    fun isActive(): Boolean = activeSessionId != null

    @Synchronized
    fun activeSessionId(): String? = activeSessionId

    /**
     * Applies one `/camera/session/state` payload. Returns true when the active/idle edge moved.
     * Unknown states and blank session ids are ignored rather than guessed at: a missed close is
     * recovered by [reset] when the camera process dies.
     */
    @Synchronized
    fun onSessionState(sessionId: String, state: String): Boolean {
        if (sessionId.isBlank()) return false
        val wasActive = activeSessionId != null
        when (state) {
            STATE_OPENED -> activeSessionId = sessionId
            STATE_CLOSED -> if (activeSessionId == sessionId) activeSessionId = null
            else -> return false
        }
        val nowActive = activeSessionId != null
        if (wasActive == nowActive) return false
        onChanged(nowActive)
        return true
    }

    @Synchronized
    fun reset(): Boolean {
        if (activeSessionId == null) return false
        activeSessionId = null
        onChanged(false)
        return true
    }

    companion object {
        const val STATE_OPENED = "opened"
        const val STATE_CLOSED = "closed"
    }
}

/**
 * Recovers the tracker when the `:camera` process dies without closing its session.
 *
 * A crashed or force-stopped camera process never sends `closed`, so the main process would
 * believe a session is live forever and skip every future sync with `camera_active` until the hub
 * itself restarts. Reconciliation is deliberately lazy — it runs at the one moment the stale flag
 * actually costs something, not on a poller.
 */
object CameraSessionLivenessPolicy {
    /**
     * [cameraProcessAlive] must be null when liveness could not be determined: an unknown answer
     * keeps the current belief rather than cancelling a real camera session.
     */
    fun shouldResetTracker(
        skipReason: MediaSyncSkipReason,
        cameraProcessAlive: Boolean?,
    ): Boolean = skipReason == MediaSyncSkipReason.CAMERA_ACTIVE && cameraProcessAlive == false
}
