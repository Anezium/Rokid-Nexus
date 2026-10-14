package com.anezium.rokidbus.glasses

import android.content.Context

internal data class GlassesWifiLease(
    val sessionId: String,
    val nexusEnabledWifi: Boolean,
    val acquiredAtMillis: Long,
)

internal interface GlassesWifiLeasePersistence {
    fun read(): GlassesWifiLease?
    fun write(lease: GlassesWifiLease): Boolean
    fun clear()
}

internal class GlassesWifiLeaseStore(context: Context) : GlassesWifiLeasePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): GlassesWifiLease? {
        if (!preferences.getBoolean(KEY_NEXUS_ENABLED_WIFI, false)) return null
        val sessionId = preferences.getString(KEY_SESSION_ID, "").orEmpty()
        val acquiredAtMillis = preferences.getLong(KEY_ACQUIRED_AT_MILLIS, 0L)
        if (sessionId.isBlank() || acquiredAtMillis <= 0L) return null
        return GlassesWifiLease(
            sessionId = sessionId,
            nexusEnabledWifi = true,
            acquiredAtMillis = acquiredAtMillis,
        )
    }

    override fun write(lease: GlassesWifiLease): Boolean =
        preferences.edit()
            .putString(KEY_SESSION_ID, lease.sessionId)
            .putBoolean(KEY_NEXUS_ENABLED_WIFI, lease.nexusEnabledWifi)
            .putLong(KEY_ACQUIRED_AT_MILLIS, lease.acquiredAtMillis)
            .commit()

    override fun clear() {
        preferences.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_NEXUS_ENABLED_WIFI)
            .remove(KEY_ACQUIRED_AT_MILLIS)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "glasses_wifi_ownership"
        const val KEY_SESSION_ID = "camera_session_id"
        const val KEY_NEXUS_ENABLED_WIFI = "camera_nexus_enabled_wifi"
        const val KEY_ACQUIRED_AT_MILLIS = "camera_acquired_at_millis"
    }
}

internal data class GlassesWifiRequestResult(
    val hubOwned: Boolean,
    val applied: Boolean,
)

/** Crash-safe ownership of Wi-Fi enabled specifically for a camera session. */
internal class GlassesWifiOwnership(
    private val persistence: GlassesWifiLeasePersistence,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun acquire(
        sessionId: String,
        wifiCurrentlyEnabled: Boolean,
        requestWifiEnable: () -> Boolean,
    ): GlassesWifiRequestResult {
        if (sessionId.isBlank()) {
            return GlassesWifiRequestResult(hubOwned = isHubOwned(), applied = false)
        }
        val existing = persistence.read()
        if (wifiCurrentlyEnabled && existing == null) {
            return GlassesWifiRequestResult(hubOwned = false, applied = false)
        }
        val lease = if (wifiCurrentlyEnabled && existing != null) {
            existing.copy(sessionId = sessionId)
        } else {
            GlassesWifiLease(
                sessionId = sessionId,
                nexusEnabledWifi = true,
                acquiredAtMillis = nowMillis(),
            )
        }
        if (!persistence.write(lease)) {
            return GlassesWifiRequestResult(hubOwned = existing != null, applied = false)
        }
        if (wifiCurrentlyEnabled) {
            return GlassesWifiRequestResult(hubOwned = true, applied = false)
        }
        return GlassesWifiRequestResult(hubOwned = true, applied = requestWifiEnable())
    }

    /**
     * Clears the lease only after the caller can observe the radio off. A failed or unverifiable
     * disable deliberately leaves durable evidence for the next maintenance sweep.
     */
    @Synchronized
    fun release(
        wifiCurrentlyEnabled: Boolean,
        requestWifiDisable: () -> Boolean,
        readWifiEnabled: () -> Boolean?,
    ): GlassesWifiRequestResult {
        if (persistence.read() == null) {
            return GlassesWifiRequestResult(hubOwned = false, applied = false)
        }
        if (!wifiCurrentlyEnabled) {
            persistence.clear()
            return GlassesWifiRequestResult(hubOwned = false, applied = false)
        }
        requestWifiDisable()
        val enabledAfterRequest = readWifiEnabled()
        if (enabledAfterRequest == false) persistence.clear()
        return GlassesWifiRequestResult(
            hubOwned = persistence.read() != null,
            applied = enabledAfterRequest == false,
        )
    }

    /**
     * Wi-Fi the wearer asked for through an app is theirs to keep: drop any session lease so no
     * later reconciliation switches the radio off underneath that app.
     */
    @Synchronized
    fun handOverToUser(): Boolean {
        if (persistence.read() == null) return false
        persistence.clear()
        return true
    }

    @Synchronized
    fun observeRadioState(wifiEnabled: Boolean): Boolean {
        if (wifiEnabled || persistence.read() == null) return false
        persistence.clear()
        return true
    }

    @Synchronized
    fun isHubOwned(): Boolean = persistence.read()?.nexusEnabledWifi == true

    @Synchronized
    fun currentLease(): GlassesWifiLease? = persistence.read()

    /**
     * A new process may start after the durable write but before the bridge finishes enabling the
     * radio. Do not discard that evidence merely because the first state read still says off.
     */
    @Synchronized
    fun isEnableRequestPossiblyInFlight(
        currentTimeMillis: Long = nowMillis(),
    ): Boolean = persistence.read()?.let { lease ->
        currentTimeMillis >= lease.acquiredAtMillis &&
            currentTimeMillis - lease.acquiredAtMillis < ENABLE_REQUEST_STALE_MS
    } == true

    private companion object {
        const val ENABLE_REQUEST_STALE_MS = 30_000L
    }
}

internal enum class WifiOwnershipReconciliationAction {
    NONE,
    DISABLE_NOW,
    SCHEDULE_CAMERA_GRACE,
}

internal object WifiOwnershipReconciliationPolicy {
    fun decide(
        cameraLeaseOwned: Boolean,
        setupWifiOwned: Boolean,
        cameraSessionActive: Boolean,
        setupSessionActive: Boolean,
        mediaSyncSessionActive: Boolean,
        selfArmOperationActive: Boolean,
        setupEnableRequestActive: Boolean,
        cameraGraceRequested: Boolean,
        cameraGracePending: Boolean,
        cameraGraceSatisfied: Boolean,
    ): WifiOwnershipReconciliationAction {
        if (!cameraLeaseOwned && !setupWifiOwned) return WifiOwnershipReconciliationAction.NONE
        if (cameraSessionActive || setupSessionActive || mediaSyncSessionActive ||
            selfArmOperationActive || setupEnableRequestActive
        ) {
            return WifiOwnershipReconciliationAction.NONE
        }
        if (cameraGracePending) return WifiOwnershipReconciliationAction.NONE
        return if (!cameraGraceSatisfied && (cameraGraceRequested || cameraLeaseOwned)) {
            WifiOwnershipReconciliationAction.SCHEDULE_CAMERA_GRACE
        } else {
            WifiOwnershipReconciliationAction.DISABLE_NOW
        }
    }
}
