package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet

internal object NexusPhoneState {
    const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
    const val AUTH_REQUEST = 42
    const val PREFS = "rokidbus_phone"
    const val PREF_TOKEN = "cxrl_token"
    const val PREF_GLASSES_APP_INSTALLED = "glasses_app_installed"
    const val PREF_GLASSES_SETUP_COMPLETE = "glasses_setup_complete"
    const val PREF_GLASSES_SETUP_FAILURE_STATE = "glasses_setup_failure_state"
    const val PREF_GLASSES_SETUP_FAILURE_DIAGNOSTIC = "glasses_setup_failure_diagnostic"
    const val PREF_INSTALLED_GLASSES_VERSION_NAME = "installed_glasses_version_name"
    const val EXTRA_GLASSES_APP_STATE = "glasses_app_state"
    const val EXTRA_GLASSES_APP_DOWNLOADED = "glasses_app_downloaded"
    const val EXTRA_GLASSES_APP_TOTAL = "glasses_app_total"
    const val EXTRA_GLASSES_APP_MESSAGE = "glasses_app_message"
    const val EXTRA_GLASSES_APP_RETRY = "glasses_app_retry"
    const val EXTRA_GLASSES_APP_VERSION_NAME = "glasses_app_version_name"
    const val EXTRA_GLASSES_APP_UPDATE_STATE = "glasses_app_update_state"
    const val EXTRA_GLASSES_APP_LATEST_VERSION_NAME = "glasses_app_latest_version_name"
    const val EXTRA_GLASSES_SETUP_COMPLETE = "glasses_setup_complete"
    const val EXTRA_GLASSES_SETUP_FAILURE_STATE = "glasses_setup_failure_state"
    const val EXTRA_GLASSES_SETUP_FAILURE_DIAGNOSTIC = "glasses_setup_failure_diagnostic"

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
    @Volatile var installedGlassesVersionName: String? = null
        private set
    @Volatile var glassesAppUpdateState: GlassesAppUpdateState = GlassesAppUpdateState.Unknown
        private set
    @Volatile var glassesAppInstalled: Boolean = false
        private set
    @Volatile var glassesSetupComplete: Boolean = false
        private set
    @Volatile var glassesSetupFailureState: String = ""
        private set
    @Volatile var glassesSetupFailureDiagnostic: String = ""
        private set

    @Volatile private var appContext: Context? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun restore(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val applicationContext = context.applicationContext
            val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            glassesAppInstalled = preferences.getBoolean(PREF_GLASSES_APP_INSTALLED, false)
            glassesSetupComplete = preferences.getBoolean(PREF_GLASSES_SETUP_COMPLETE, false)
            glassesSetupFailureState = preferences.getString(
                PREF_GLASSES_SETUP_FAILURE_STATE,
                "",
            ).orEmpty()
            glassesSetupFailureDiagnostic = ManualPairingSupportDiagnostic.sanitize(
                preferences.getString(PREF_GLASSES_SETUP_FAILURE_DIAGNOSTIC, "").orEmpty(),
            )
            installedGlassesVersionName = preferences.getString(PREF_INSTALLED_GLASSES_VERSION_NAME, null)
            appContext = applicationContext
        }
    }

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

    fun setInstalledGlassesVersionName(versionName: String?) {
        val normalized = versionName?.trim()?.takeIf { it.isNotEmpty() }
        if (installedGlassesVersionName == normalized) return
        installedGlassesVersionName = normalized
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_INSTALLED_GLASSES_VERSION_NAME, normalized)
            ?.apply()
        notifyListeners()
    }

    fun setGlassesAppUpdateState(state: GlassesAppUpdateState) {
        if (glassesAppUpdateState == state) return
        glassesAppUpdateState = state
        notifyListeners()
    }

    fun setGlassesSetupComplete(complete: Boolean) {
        if (glassesSetupComplete == complete) return
        glassesSetupComplete = complete
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PREF_GLASSES_SETUP_COMPLETE, complete)
            ?.apply()
        notifyListeners()
    }

    fun setGlassesSetupFailure(state: String, diagnostic: String) {
        val cleanState = state.trim().take(96)
        val cleanDiagnostic = ManualPairingSupportDiagnostic.sanitize(diagnostic)
        if (glassesSetupFailureState == cleanState &&
            glassesSetupFailureDiagnostic == cleanDiagnostic
        ) return
        glassesSetupFailureState = cleanState
        glassesSetupFailureDiagnostic = cleanDiagnostic
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_GLASSES_SETUP_FAILURE_STATE, cleanState)
            ?.putString(PREF_GLASSES_SETUP_FAILURE_DIAGNOSTIC, cleanDiagnostic)
            ?.apply()
        notifyListeners()
    }

    fun updateGlassesAppInstallState(intent: Intent): Boolean {
        var updated = false
        if (intent.hasExtra(EXTRA_GLASSES_APP_VERSION_NAME)) {
            setInstalledGlassesVersionName(intent.getStringExtra(EXTRA_GLASSES_APP_VERSION_NAME))
            updated = true
        }
        if (intent.hasExtra(EXTRA_GLASSES_SETUP_COMPLETE)) {
            setGlassesSetupComplete(intent.getBooleanExtra(EXTRA_GLASSES_SETUP_COMPLETE, false))
            updated = true
        }
        if (intent.hasExtra(EXTRA_GLASSES_SETUP_FAILURE_STATE) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_FAILURE_DIAGNOSTIC)
        ) {
            setGlassesSetupFailure(
                intent.getStringExtra(EXTRA_GLASSES_SETUP_FAILURE_STATE).orEmpty(),
                intent.getStringExtra(EXTRA_GLASSES_SETUP_FAILURE_DIAGNOSTIC).orEmpty(),
            )
            updated = true
        }
        if (intent.hasExtra(EXTRA_GLASSES_APP_UPDATE_STATE)) {
            val installed = installedGlassesVersionName?.let(NexusSemVersion::parse)
            val latest = intent.getStringExtra(EXTRA_GLASSES_APP_LATEST_VERSION_NAME)
                ?.let(NexusSemVersion::parse)
            val updateState = when (intent.getStringExtra(EXTRA_GLASSES_APP_UPDATE_STATE)) {
                "up_to_date" -> if (installed != null && latest != null) {
                    GlassesAppUpdateState.UpToDate(installed, latest)
                } else {
                    GlassesAppUpdateState.Unknown
                }
                "update_available" -> if (installed != null && latest != null) {
                    GlassesAppUpdateState.UpdateAvailable(installed, latest)
                } else {
                    GlassesAppUpdateState.Unknown
                }
                else -> GlassesAppUpdateState.Unknown
            }
            setGlassesAppUpdateState(updateState)
            updated = true
        }
        val value = intent.getStringExtra(EXTRA_GLASSES_APP_STATE) ?: return updated
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
            else -> return updated
        }
        val installStateChanged = glassesAppInstallState != state
        val wasInstalled = glassesAppInstalled
        glassesAppInstalled = GlassesAppPresencePolicy.reduce(glassesAppInstalled, state)
        val installedChanged = wasInstalled != glassesAppInstalled
        if (installedChanged) {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_GLASSES_APP_INSTALLED, glassesAppInstalled)
                ?.apply()
        }
        glassesAppInstallState = state
        if (installStateChanged || installedChanged) notifyListeners()
        return true
    }

    fun glassesUpdateVersionLabel(): String? = when (val state = glassesAppUpdateState) {
        is GlassesAppUpdateState.UpdateAvailable -> "Update glasses to v${state.latest}"
        GlassesAppUpdateState.Unknown -> if (
            glassesAppInstallState == GlassesAppInstallState.Installed &&
            installedGlassesVersionName == null
        ) {
            "Reinstall latest glasses app"
        } else {
            null
        }
        is GlassesAppUpdateState.UpToDate -> null
    }

    fun glassesUpdateActionLabel(): String = when (val state = glassesAppInstallState) {
        GlassesAppInstallState.Resolving -> "Finding..."
        is GlassesAppInstallState.Downloading -> state.totalBytes?.takeIf { it > 0L }?.let { total ->
            "${(state.downloadedBytes * 100L / total).coerceIn(0L, 100L)}%"
        } ?: "Downloading"
        GlassesAppInstallState.Installing -> "Installing"
        is GlassesAppInstallState.Error -> "Retry"
        else -> if (glassesAppUpdateState == GlassesAppUpdateState.Unknown) "Reinstall" else "Update"
    }

    fun glassesUpdateActionEnabled(): Boolean =
        (glassesAppUpdateState is GlassesAppUpdateState.UpdateAvailable ||
            glassesAppUpdateState == GlassesAppUpdateState.Unknown) &&
            when (val state = glassesAppInstallState) {
            GlassesAppInstallState.Installed -> true
            is GlassesAppInstallState.Error -> state.retry == GlassesAppRetry.INSTALL
            else -> false
        }

    fun glassesInstalledStatusLabel(): String? = when (val state = glassesAppUpdateState) {
        is GlassesAppUpdateState.UpToDate -> "v${state.installed}, up to date"
        is GlassesAppUpdateState.UpdateAvailable -> null
        GlassesAppUpdateState.Unknown -> when {
            installedGlassesVersionName != null -> "v$installedGlassesVersionName, installed"
            glassesAppInstalled -> "Installed, version unknown"
            else -> null
        }
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
