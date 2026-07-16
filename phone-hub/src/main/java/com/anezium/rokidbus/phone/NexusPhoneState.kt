package com.anezium.rokidbus.phone

import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet

internal object NexusPhoneState {
    const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
    const val AUTH_REQUEST = 42
    const val PREFS = "rokidbus_phone"
    const val PREF_TOKEN = "cxrl_token"
    const val EXTRA_GLASSES_APP_STATE = "glasses_app_state"
    const val EXTRA_GLASSES_APP_DOWNLOADED = "glasses_app_downloaded"
    const val EXTRA_GLASSES_APP_TOTAL = "glasses_app_total"
    const val EXTRA_GLASSES_APP_MESSAGE = "glasses_app_message"
    const val EXTRA_GLASSES_APP_RETRY = "glasses_app_retry"

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
    @Volatile var glassesAppInstallState: GlassesAppInstallState = GlassesAppInstallState.Unknown
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

    fun updateGlassesAppInstallState(intent: Intent): Boolean {
        val value = intent.getStringExtra(EXTRA_GLASSES_APP_STATE) ?: return false
        val state = when (value) {
            "unknown" -> GlassesAppInstallState.Unknown
            "querying" -> GlassesAppInstallState.Querying
            "not_installed" -> GlassesAppInstallState.NotInstalled
            "resolving" -> GlassesAppInstallState.Resolving
            "downloading" -> GlassesAppInstallState.Downloading(
                downloadedBytes = intent.getLongExtra(EXTRA_GLASSES_APP_DOWNLOADED, 0L),
                totalBytes = if (intent.hasExtra(EXTRA_GLASSES_APP_TOTAL)) {
                    intent.getLongExtra(EXTRA_GLASSES_APP_TOTAL, 0L)
                } else {
                    null
                },
            )
            "installing" -> GlassesAppInstallState.Installing
            "installed" -> GlassesAppInstallState.Installed
            "error" -> GlassesAppInstallState.Error(
                message = intent.getStringExtra(EXTRA_GLASSES_APP_MESSAGE)
                    ?: "The glasses app operation failed.",
                retry = if (intent.getStringExtra(EXTRA_GLASSES_APP_RETRY) == "query") {
                    GlassesAppRetry.QUERY
                } else {
                    GlassesAppRetry.INSTALL
                },
            )
            else -> return false
        }
        glassesAppInstallState = state
        return true
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
