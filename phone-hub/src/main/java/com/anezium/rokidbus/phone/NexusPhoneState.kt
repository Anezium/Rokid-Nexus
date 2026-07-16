package com.anezium.rokidbus.phone

import java.util.concurrent.CopyOnWriteArraySet

internal object NexusPhoneState {
    const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
    const val AUTH_REQUEST = 42
    const val PREFS = "rokidbus_phone"
    const val PREF_TOKEN = "cxrl_token"

    @Volatile var updateAvailable: Boolean = false
        private set
    @Volatile var updateVersionLabel: String = "Rokid Nexus"
        private set
    @Volatile var availableRelease: NexusAppRelease? = null
        private set
    @Volatile var updateInstallState: PluginInstallState? = null
        private set
    @Volatile var checkingForUpdate: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addUpdateListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeUpdateListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun setCheckingForUpdate(checking: Boolean) {
        if (checkingForUpdate == checking) return
        checkingForUpdate = checking
        notifyListeners()
    }

    fun setAvailableUpdate(release: NexusAppRelease?) {
        availableRelease = release
        updateAvailable = release != null
        updateVersionLabel = release?.versionLabel ?: "Rokid Nexus"
        if (release == null) updateInstallState = null
        notifyListeners()
    }

    fun setUpdateInstallState(state: PluginInstallState?) {
        updateInstallState = state
        notifyListeners()
    }

    fun clearAvailableUpdate() {
        setAvailableUpdate(null)
    }

    fun updateActionLabel(): String = when (val state = updateInstallState) {
        is PluginInstallState.Downloading -> state.totalBytes?.takeIf { it > 0L }?.let { total ->
            "${(state.downloadedBytes * 100L / total).coerceIn(0L, 100L)}% · Cancel"
        } ?: "Downloading · Cancel"
        PluginInstallState.Verifying -> "Verifying"
        PluginInstallState.Installing -> "Preparing"
        PluginInstallState.AwaitingUserConfirmation -> "Confirm install"
        PluginInstallState.Cancelled -> "Retry"
        is PluginInstallState.Failure -> "Retry"
        is PluginInstallState.Success -> "Installed"
        null -> "Install"
    }

    fun updateActionEnabled(): Boolean = when (updateInstallState) {
        null,
        is PluginInstallState.Downloading,
        PluginInstallState.Cancelled,
        is PluginInstallState.Failure,
        -> true
        else -> false
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> listener() }
    }
}
