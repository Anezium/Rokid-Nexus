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
    const val PREF_GLASSES_SETUP_SESSION_ID = "glasses_setup_session_id"
    const val PREF_GLASSES_SETUP_STAGE = "glasses_setup_stage"
    const val PREF_GLASSES_SETUP_RUNNING = "glasses_setup_running"
    const val PREF_GLASSES_SETUP_REQUIRES_USER_ACTION = "glasses_setup_requires_user_action"
    const val PREF_GLASSES_SETUP_SUPPORT_CODE = "glasses_setup_support_code"
    const val PREF_GLASSES_SETUP_COMPLETION_MODE = "glasses_setup_completion_mode"
    const val PREF_GLASSES_CORE_READY = "glasses_core_ready"
    const val PREF_GLASSES_MAINTENANCE_READY = "glasses_maintenance_ready"
    const val PREF_INSTALLED_GLASSES_VERSION_NAME = "installed_glasses_version_name"
    const val PREF_LATEST_GLASSES_VERSION_NAME = "latest_glasses_version_name"
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
    const val EXTRA_GLASSES_SETUP_SESSION_ID = "glasses_setup_session_id"
    const val EXTRA_GLASSES_SETUP_STAGE = "glasses_setup_stage"
    const val EXTRA_GLASSES_SETUP_RUNNING = "glasses_setup_running"
    const val EXTRA_GLASSES_SETUP_REQUIRES_USER_ACTION = "glasses_setup_requires_user_action"
    const val EXTRA_GLASSES_SETUP_SUPPORT_CODE = "glasses_setup_support_code"
    const val EXTRA_GLASSES_SETUP_COMPLETION_MODE = "glasses_setup_completion_mode"
    const val EXTRA_GLASSES_CORE_READY = "glasses_core_ready"
    const val EXTRA_GLASSES_MAINTENANCE_READY = "glasses_maintenance_ready"

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
    @Volatile var latestGlassesVersionName: String? = null
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
    @Volatile var glassesSetupSessionId: String = ""
        private set
    @Volatile var glassesSetupStage: String = ""
        private set
    @Volatile var glassesSetupRunning: Boolean = false
        private set
    @Volatile var glassesSetupRequiresUserAction: Boolean = false
        private set
    @Volatile var glassesSetupSupportCode: String = ""
        private set
    @Volatile var glassesSetupCompletionMode: String = ""
        private set
    @Volatile var glassesCoreReady: Boolean = false
        private set
    @Volatile var glassesMaintenanceReady: Boolean = false
        private set
    @Volatile var backgroundAudioPluginId: String? = null
        private set

    fun setBackgroundAudioPluginId(pluginId: String?) {
        if (backgroundAudioPluginId == pluginId) return
        backgroundAudioPluginId = pluginId
        notifyListeners()
    }

    /**
     * Whether the "Start setup" hand-off actually reached the glasses. Deliberately transient and
     * unpersisted: it describes one tap, and a stale "failed" surviving a restart would push the
     * owner to the fallback for a link that has since come back.
     */
    enum class SetupHandoff { IDLE, SENDING, FAILED }

    @Volatile var glassesSetupHandoff: SetupHandoff = SetupHandoff.IDLE
        private set

    fun setGlassesSetupHandoff(state: SetupHandoff) {
        if (glassesSetupHandoff == state) return
        glassesSetupHandoff = state
        notifyListeners()
    }

    /**
     * The log lines are broadcast, so any screen that isn't open when a line fires never sees
     * it. This backlog is what lets the console show what happened before it was opened —
     * everything since the process started, capped so a chatty hub can't grow it forever.
     */
    private const val LOG_BACKLOG_CAPACITY = 400
    private val logBacklogLock = Any()
    private val logBacklog = ArrayDeque<String>()

    fun recordLogLine(line: String) {
        if (line.isBlank()) return
        synchronized(logBacklogLock) {
            logBacklog.addLast(line)
            while (logBacklog.size > LOG_BACKLOG_CAPACITY) logBacklog.removeFirst()
        }
    }

    fun logBacklog(): List<String> = synchronized(logBacklogLock) { logBacklog.toList() }

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
            glassesSetupSessionId = preferences.getString(PREF_GLASSES_SETUP_SESSION_ID, "").orEmpty()
            glassesSetupStage = preferences.getString(PREF_GLASSES_SETUP_STAGE, "").orEmpty()
            glassesSetupRunning = preferences.getBoolean(PREF_GLASSES_SETUP_RUNNING, false)
            glassesSetupRequiresUserAction = preferences.getBoolean(
                PREF_GLASSES_SETUP_REQUIRES_USER_ACTION,
                false,
            )
            glassesSetupSupportCode = preferences.getString(PREF_GLASSES_SETUP_SUPPORT_CODE, "").orEmpty()
            glassesSetupCompletionMode = preferences.getString(
                PREF_GLASSES_SETUP_COMPLETION_MODE,
                "",
            ).orEmpty()
            glassesCoreReady = preferences.getBoolean(PREF_GLASSES_CORE_READY, false)
            glassesMaintenanceReady = preferences.getBoolean(
                PREF_GLASSES_MAINTENANCE_READY,
                false,
            )
            installedGlassesVersionName = preferences.getString(PREF_INSTALLED_GLASSES_VERSION_NAME, null)
            latestGlassesVersionName = preferences.getString(PREF_LATEST_GLASSES_VERSION_NAME, null)
            // Both version names outlived the hub, so the verdict does too. Starting
            // from Unknown would ask the wearer to reinstall an app we know is current.
            glassesAppUpdateState = GlassesAppUpdatePolicy.compareVersionNames(
                installedGlassesVersionName,
                latestGlassesVersionName,
            )
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

    fun setLatestGlassesVersionName(versionName: String?) {
        val normalized = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (latestGlassesVersionName == normalized) return
        latestGlassesVersionName = normalized
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_LATEST_GLASSES_VERSION_NAME, normalized)
            ?.apply()
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

    fun setGlassesSetupProgress(
        sessionId: String,
        stage: String,
        running: Boolean,
        requiresUserAction: Boolean,
        supportCode: String,
        completionMode: String,
        coreReady: Boolean,
        maintenanceReady: Boolean,
    ) {
        if (glassesSetupSessionId == sessionId &&
            glassesSetupStage == stage &&
            glassesSetupRunning == running &&
            glassesSetupRequiresUserAction == requiresUserAction &&
            glassesSetupSupportCode == supportCode &&
            glassesSetupCompletionMode == completionMode &&
            glassesCoreReady == coreReady &&
            glassesMaintenanceReady == maintenanceReady
        ) {
            return
        }
        // The lens just spoke, so whatever we concluded from a hand-off that looked like it failed
        // is stale. Leaving FAILED latched here is what stranded owners: nothing else ever cleared
        // it, and the screen it produced had no way back to the button that would.
        if (glassesSetupHandoff == SetupHandoff.FAILED) {
            glassesSetupHandoff = SetupHandoff.IDLE
        }
        glassesSetupSessionId = sessionId
        glassesSetupStage = stage
        glassesSetupRunning = running
        glassesSetupRequiresUserAction = requiresUserAction
        glassesSetupSupportCode = supportCode
        glassesSetupCompletionMode = completionMode
        glassesCoreReady = coreReady
        glassesMaintenanceReady = maintenanceReady
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_GLASSES_SETUP_SESSION_ID, sessionId)
            ?.putString(PREF_GLASSES_SETUP_STAGE, stage)
            ?.putBoolean(PREF_GLASSES_SETUP_RUNNING, running)
            ?.putBoolean(PREF_GLASSES_SETUP_REQUIRES_USER_ACTION, requiresUserAction)
            ?.putString(PREF_GLASSES_SETUP_SUPPORT_CODE, supportCode)
            ?.putString(PREF_GLASSES_SETUP_COMPLETION_MODE, completionMode)
            ?.putBoolean(PREF_GLASSES_CORE_READY, coreReady)
            ?.putBoolean(PREF_GLASSES_MAINTENANCE_READY, maintenanceReady)
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
        if (intent.hasExtra(EXTRA_GLASSES_SETUP_SESSION_ID) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_STAGE) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_RUNNING) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_REQUIRES_USER_ACTION) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_SUPPORT_CODE) ||
            intent.hasExtra(EXTRA_GLASSES_SETUP_COMPLETION_MODE) ||
            intent.hasExtra(EXTRA_GLASSES_CORE_READY) ||
            intent.hasExtra(EXTRA_GLASSES_MAINTENANCE_READY)
        ) {
            setGlassesSetupProgress(
                sessionId = intent.getStringExtra(EXTRA_GLASSES_SETUP_SESSION_ID).orEmpty(),
                stage = intent.getStringExtra(EXTRA_GLASSES_SETUP_STAGE).orEmpty(),
                running = intent.getBooleanExtra(EXTRA_GLASSES_SETUP_RUNNING, false),
                requiresUserAction = intent.getBooleanExtra(
                    EXTRA_GLASSES_SETUP_REQUIRES_USER_ACTION,
                    false,
                ),
                supportCode = intent.getStringExtra(EXTRA_GLASSES_SETUP_SUPPORT_CODE).orEmpty(),
                completionMode = intent.getStringExtra(
                    EXTRA_GLASSES_SETUP_COMPLETION_MODE,
                ).orEmpty(),
                coreReady = intent.getBooleanExtra(EXTRA_GLASSES_CORE_READY, false),
                maintenanceReady = intent.getBooleanExtra(
                    EXTRA_GLASSES_MAINTENANCE_READY,
                    false,
                ),
            )
            updated = true
        }
        if (intent.hasExtra(EXTRA_GLASSES_APP_UPDATE_STATE)) {
            val installed = installedGlassesVersionName?.let(NexusSemVersion::parse)
            setLatestGlassesVersionName(
                intent.getStringExtra(EXTRA_GLASSES_APP_LATEST_VERSION_NAME),
            )
            val latest = latestGlassesVersionName?.let(NexusSemVersion::parse)
            // A hub that has just started has not asked GitHub anything yet and says
            // "unknown". That is a statement about the hub, not about the glasses,
            // so fall back to what both remembered version names already prove.
            val remembered = GlassesAppUpdatePolicy.compareVersionNames(
                installedGlassesVersionName,
                latestGlassesVersionName,
            )
            val updateState = when (intent.getStringExtra(EXTRA_GLASSES_APP_UPDATE_STATE)) {
                "up_to_date" -> if (installed != null && latest != null) {
                    GlassesAppUpdateState.UpToDate(installed, latest)
                } else {
                    remembered
                }
                "update_available" -> if (installed != null && latest != null) {
                    GlassesAppUpdateState.UpdateAvailable(installed, latest)
                } else {
                    remembered
                }
                else -> remembered
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

    /**
     * Whether not knowing is worth acting on.
     *
     * Unknown covers two different situations, and only one of them is the
     * wearer's problem: an app whose version we cannot read is worth reinstalling,
     * while a hub that has not reported in yet is worth waiting for. Offering to
     * reinstall in the second case asks for a fix to something that isn't broken.
     */
    private fun unknownVersionIsActionable(): Boolean =
        glassesAppUpdateState == GlassesAppUpdateState.Unknown &&
            installedGlassesVersionName == null

    fun glassesUpdateVersionLabel(): String? {
        val base = when (val state = glassesAppUpdateState) {
            is GlassesAppUpdateState.UpdateAvailable -> "Update glasses to v${state.latest}"
            GlassesAppUpdateState.Unknown -> if (
                glassesAppInstallState == GlassesAppInstallState.Installed &&
                unknownVersionIsActionable()
            ) {
                "Reinstall latest glasses app"
            } else {
                null
            }
            is GlassesAppUpdateState.UpToDate -> null
        }
        // A failed attempt must explain itself where the button lives; the onboarding
        // step shows the same message, but most users only ever see this banner. Only
        // swap the subtitle while the banner is showing anyway — an Error must not
        // resurrect a banner the update state has already dismissed.
        val install = glassesAppInstallState
        return if (base != null && install is GlassesAppInstallState.Error) install.message else base
    }

    fun glassesUpdateActionLabel(): String = when (val state = glassesAppInstallState) {
        GlassesAppInstallState.Resolving -> "Finding..."
        is GlassesAppInstallState.Downloading -> state.totalBytes?.takeIf { it > 0L }?.let { total ->
            "${(state.downloadedBytes * 100L / total).coerceIn(0L, 100L)}%"
        } ?: "Downloading"
        GlassesAppInstallState.Installing -> "Installing"
        is GlassesAppInstallState.Error -> "Retry"
        else -> if (unknownVersionIsActionable()) "Reinstall" else "Update"
    }

    fun glassesUpdateActionEnabled(): Boolean =
        (glassesAppUpdateState is GlassesAppUpdateState.UpdateAvailable ||
            unknownVersionIsActionable()) &&
            when (glassesAppInstallState) {
            GlassesAppInstallState.Installed -> true
            // QUERY-retry errors stay actionable too: the service routes the press
            // back through queryGlassesApp, which is exactly the recovery step.
            is GlassesAppInstallState.Error -> true
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
