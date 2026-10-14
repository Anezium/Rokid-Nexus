package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupNote
import com.anezium.rokidbus.shared.SetupNoteMessage
import com.anezium.rokidbus.shared.SetupStage
import java.security.SecureRandom
import java.util.Locale

internal object SelfArmOnboardingStore {
    const val ACTION_CHANGED = "com.anezium.rokidbus.glasses.ACTION_SELFARM_ONBOARDING_CHANGED"
    const val LEASE_TIMEOUT_MS = 45_000L
    const val HEARTBEAT_CADENCE_MS = 5_000L
    const val LEASE_EXPIRED_FAILURE = "setup_lease_expired"

    fun snapshot(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): SelfArmOnboardingSnapshot {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val accessibilityEnabled = runCatching {
            val resolver = appContext.contentResolver
            !SelfArmController.accessibilityRepairNeeded(
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0),
            )
        }.getOrDefault(false)
        // What checkSelfPermission does internally, spelled out: same process-local answer, but
        // reachable from Robolectric, which does not carry Context.checkSelfPermission.
        val secureSettingsGranted = appContext.checkPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS,
            Process.myPid(),
            Process.myUid(),
        ) == PackageManager.PERMISSION_GRANTED
        val bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(appContext)
        val storedRunning = prefs.getBoolean(KEY_RUNNING, false)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        val leaseValid = !storedRunning || SelfArmSessionPolicy.leaseValid(
            nowMillis = nowMillis,
            lastHeartbeatMillis = lastHeartbeat,
            timeoutMillis = LEASE_TIMEOUT_MS,
        )
        val leaseExpired = storedRunning && !leaseValid
        val storedStage = SetupStage.normalize(prefs.getString(KEY_STAGE, ""))
        val stage = if (leaseExpired) SetupStage.FAILED else storedStage
        val completionMode = SetupCompletionMode.normalize(
            prefs.getString(KEY_COMPLETION_MODE, ""),
        ).ifBlank {
            if (accessibilityEnabled &&
                secureSettingsGranted &&
                prefs.getBoolean(KEY_LEGACY_ADB_SAFE, false) &&
                !bootstrapComplete
            ) {
                SetupCompletionMode.PM_GRANT
            } else {
                SetupCompletionMode.UNKNOWN
            }
        }
        return SelfArmOnboardingSnapshot(
            wirelessDebuggingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            accessibilityEnabled = accessibilityEnabled,
            secureSettingsGranted = secureSettingsGranted,
            bootstrapComplete = bootstrapComplete,
            legacyAdbSafe = prefs.getBoolean(KEY_LEGACY_ADB_SAFE, false),
            setupRunning = storedRunning && leaseValid,
            failureState = if (leaseExpired) {
                LEASE_EXPIRED_FAILURE
            } else {
                prefs.getString(KEY_FAILURE_STATE, "").orEmpty()
            },
            failureDiagnostic = prefs.getString(KEY_FAILURE_DIAGNOSTIC, "").orEmpty(),
            progressState = prefs.getString(KEY_PROGRESS_STATE, "").orEmpty(),
            sessionId = prefs.getString(KEY_SESSION_ID, "").orEmpty(),
            stage = stage,
            coreReady = accessibilityEnabled && secureSettingsGranted,
            maintenanceReady = bootstrapComplete,
            completionMode = completionMode,
            leaseValid = leaseValid,
            wifiReady = isWifiReady(appContext),
        )
    }

    fun beginSession(context: Context): String {
        RokidBusAccessibilityService.cancelSetupSessionWork()
        SelfArmPhoneArmConfirmation.cancel()
        cancelNetworkPostureRefresh()
        val now = System.currentTimeMillis()
        val sessionId = ByteArray(8)
            .also(secureRandom::nextBytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        val prefs = prefs(context)
        val generation = prefs.getInt(KEY_GENERATION, 0) + 1
        prefs.edit()
            .putInt(KEY_GENERATION, generation)
            .putString(KEY_SESSION_ID, sessionId)
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putBoolean(KEY_REQUESTED, true)
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_LEGACY_ADB_SAFE, false)
            .putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false)
            .putString(KEY_PAIRING_SESSION_ID, "")
            .putLong(KEY_RUNNING_SINCE, now)
            .putLong(KEY_LAST_HEARTBEAT, now)
            .putString(KEY_FAILURE_STATE, "")
            .putString(KEY_FAILURE_DIAGNOSTIC, "")
            .putString(KEY_PROGRESS_STATE, "waiting_for_nexus_accessibility")
            .putString(KEY_STAGE, SetupStage.WAITING_FOR_ACCESSIBILITY)
            .putString(KEY_COMPLETION_MODE, SetupCompletionMode.UNKNOWN)
            .apply()
        notifyChanged(context, SetupStage.WAITING_FOR_ACCESSIBILITY)
        return sessionId
    }

    fun currentSessionId(context: Context): String =
        prefs(context).getString(KEY_SESSION_ID, "").orEmpty()

    fun currentGeneration(context: Context): Int = prefs(context).getInt(KEY_GENERATION, 0)

    fun currentActiveSessionId(context: Context): String =
        currentSessionId(context).takeIf { canMutateSession(context, it) }.orEmpty()

    fun isCurrentSession(context: Context, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        return canMutateSession(context, sessionId)
    }

    /**
     * The session is current and, if it claims to be running, still heartbeating. A running
     * session whose lease expired is dead even though nothing has finished it yet.
     */
    fun isSessionLive(
        context: Context,
        sessionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isCurrentSession(context, sessionId)) return false
        val preferences = prefs(context)
        return !preferences.getBoolean(KEY_RUNNING, false) ||
            SelfArmSessionPolicy.leaseValid(
                nowMillis = nowMillis,
                lastHeartbeatMillis = preferences.getLong(KEY_LAST_HEARTBEAT, 0L),
                timeoutMillis = LEASE_TIMEOUT_MS,
            )
    }

    /** Running work uses its normal lease; paused user work gets a longer abandonment window. */
    fun isWifiStillNeededBySetup(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_SESSION_ACTIVE, false) ||
            preferences.getString(KEY_SESSION_ID, "").isNullOrBlank()
        ) {
            return false
        }
        val running = preferences.getBoolean(KEY_RUNNING, false)
        return SelfArmSessionPolicy.leaseValid(
            nowMillis = nowMillis,
            lastHeartbeatMillis = preferences.getLong(KEY_LAST_HEARTBEAT, 0L),
            timeoutMillis = if (running) LEASE_TIMEOUT_MS else PAUSED_WIFI_NEED_TIMEOUT_MS,
        )
    }

    fun heartbeat(
        context: Context,
        sessionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (!canMutateSession(context, sessionId)) return
        prefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT, nowMillis)
            .apply()
    }

    fun reportStage(context: Context, sessionId: String, stage: String) {
        if (!canMutateSession(context, sessionId)) return
        val normalizedStage = SetupStage.normalize(stage)
        if (normalizedStage == SetupStage.UNKNOWN) return
        val prefs = prefs(context)
        val changed = prefs.getString(KEY_STAGE, "") != normalizedStage
        prefs.edit()
            .putString(KEY_STAGE, normalizedStage)
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply()
        if (changed) notifyChanged(context, normalizedStage)
    }

    /** Compatibility entry point for older callers that still report detailed local state. */
    fun reportProgress(context: Context, setupState: String) {
        val sessionId = currentActiveSessionId(context)
        if (sessionId.isNotBlank()) {
            reportProgress(context, sessionId, setupState)
        } else {
            writeProgress(context, setupState, heartbeat = true)
        }
    }

    fun reportProgress(context: Context, sessionId: String, setupState: String) {
        if (!canMutateSession(context, sessionId)) return
        writeProgress(context, setupState, heartbeat = true)
    }

    private fun writeProgress(
        context: Context,
        setupState: String,
        heartbeat: Boolean,
    ) {
        val cleanState = setupState.trim().take(MAX_STATE_LENGTH)
        if (cleanState.isBlank()) return
        val stage = canonicalStageForProgress(cleanState)
        val prefs = prefs(context)
        val detailChanged = prefs.getString(KEY_PROGRESS_STATE, "") != cleanState
        val stageChanged = stage != SetupStage.UNKNOWN &&
            prefs.getString(KEY_STAGE, "") != stage
        prefs.edit()
            .putString(KEY_PROGRESS_STATE, cleanState)
            .also { editor ->
                if (heartbeat) editor.putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                if (stage != SetupStage.UNKNOWN) editor.putString(KEY_STAGE, stage)
            }
            .apply()
        if (detailChanged || stageChanged) {
            notifyChanged(context, stage.takeUnless { it == SetupStage.UNKNOWN })
        }
    }

    /**
     * Legacy request API. A live session is preserved; callers without one get a fresh session.
     */
    fun requestSetup(context: Context) {
        val sessionId = currentSessionId(context)
        if (!canMutateSession(context, sessionId)) {
            beginSession(context)
            return
        }
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    fun isSetupRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUESTED, false)

    /**
     * Records that [sessionId] exists to pair the glasses again, not merely to finish setup. Lives
     * in prefs because toggling Wireless debugging recreates the accessibility service mid-run:
     * a resumed session that forgot why it was opened sees core setup already done and closes
     * itself, leaving the glasses unpaired after the owner was told pairing had started.
     */
    fun markPairingRequested(context: Context, sessionId: String) {
        if (!canMutateSession(context, sessionId)) return
        prefs(context).edit().putString(KEY_PAIRING_SESSION_ID, sessionId).apply()
    }

    fun isPairingRequested(context: Context, sessionId: String): Boolean =
        canMutateSession(context, sessionId) &&
            prefs(context).getString(KEY_PAIRING_SESSION_ID, "") == sessionId

    /** What a (re)connected accessibility service owes [sessionId], given what it observes. */
    fun resumeStep(
        context: Context,
        sessionId: String,
        snapshot: SelfArmOnboardingSnapshot,
    ): SelfArmSetupResumePolicy.Step = SelfArmSetupResumePolicy.next(
        coreReady = snapshot.coreReady,
        pairingRequested = isPairingRequested(context, sessionId),
        wifiReady = snapshot.wifiReady,
    )

    /** Set right before we send the user into Accessibility settings, so the
     *  service can pull them straight back the moment they enable it. */
    fun markAwaitingAccessibility(context: Context) {
        prefs(context).edit().putBoolean(KEY_AWAITING_A11Y, true).apply()
    }

    /** True (once) if we were waiting for the user to enable accessibility. */
    fun consumeAwaitingAccessibility(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_AWAITING_A11Y, false)) return false
        prefs.edit().putBoolean(KEY_AWAITING_A11Y, false).apply()
        return true
    }

    /**
     * Set once the manual self-arm assets are staged, and held true until the phone finishes (or
     * abandons) the arm. While set, a transient AccessibilityService teardown must NOT delete the
     * staged watchdog/bridge scripts: the ROM churns the service during the Wireless Debugging
     * toggle, and the phone still needs to read those files to arm. Lives in prefs (not on the
     * service instance) so it survives the service being destroyed and recreated mid-pairing.
     */
    fun markManualArmInProgress(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, true)
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun clearManualArmInProgress(context: Context) {
        prefs(context).edit().putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false).apply()
    }

    fun isManualArmInProgress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false)

    /**
     * A manual arm counts as work in progress only while its session still heartbeats. The flag
     * alone is raised the moment manual navigation opens, before anything is armed, and outlives
     * a flow paused on Wi-Fi or simply walked away from; on its own it answered "busy" to every
     * later owner repair. The navigation run heartbeats while it is open, and so does the
     * phone-arm confirmation, so real work keeps its lease.
     */
    fun isManualArmLeased(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val preferences = prefs(context)
        return preferences.getBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false) &&
            preferences.getBoolean(KEY_SESSION_ACTIVE, false) &&
            SelfArmSessionPolicy.leaseValid(
                nowMillis = nowMillis,
                lastHeartbeatMillis = preferences.getLong(KEY_LAST_HEARTBEAT, 0L),
                timeoutMillis = LEASE_TIMEOUT_MS,
            )
    }

    fun markRunning(context: Context) {
        val sessionId = currentActiveSessionId(context)
        if (sessionId.isNotBlank()) {
            markRunning(context, sessionId)
        } else {
            writeRunning(context)
        }
    }

    fun markRunning(context: Context, sessionId: String) {
        if (!canMutateSession(context, sessionId)) return
        writeRunning(context)
    }

    private fun writeRunning(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, true)
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_LEGACY_ADB_SAFE, false)
            .putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false)
            .putString(KEY_FAILURE_STATE, "")
            .putString(KEY_FAILURE_DIAGNOSTIC, "")
            .putString(KEY_PROGRESS_STATE, "starting_wireless_debugging_setup")
            .putString(KEY_STAGE, SetupStage.OPENING_WIRELESS_DEBUGGING)
            .putLong(KEY_RUNNING_SINCE, now)
            .putLong(KEY_LAST_HEARTBEAT, now)
            .apply()
        notifyChanged(context, SetupStage.OPENING_WIRELESS_DEBUGGING)
    }

    fun pause(context: Context, progressState: String) {
        val sessionId = currentActiveSessionId(context)
        if (sessionId.isNotBlank()) {
            pause(context, sessionId, progressState)
        } else {
            writePaused(context, progressState)
        }
    }

    fun pause(context: Context, sessionId: String, progressState: String) {
        if (!canMutateSession(context, sessionId)) return
        writePaused(context, progressState)
    }

    private fun writePaused(context: Context, progressState: String) {
        val stage = canonicalStageForProgress(progressState)
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putString(KEY_PROGRESS_STATE, progressState.trim().take(MAX_STATE_LENGTH))
            .also { editor ->
                if (stage != SetupStage.UNKNOWN) editor.putString(KEY_STAGE, stage)
            }
            .apply()
        notifyChanged(context, stage.takeUnless { it == SetupStage.UNKNOWN })
    }

    fun finish(context: Context, setupState: String, success: Boolean, diagnostic: String = "") {
        val sessionId = currentActiveSessionId(context)
        if (sessionId.isNotBlank()) {
            finish(
                context = context,
                sessionId = sessionId,
                setupState = setupState,
                success = success,
                diagnostic = diagnostic,
            )
        } else {
            writeFinished(
                context = context,
                setupState = setupState,
                success = success,
                diagnostic = diagnostic,
                completionMode = SetupCompletionMode.UNKNOWN,
            )
            SelfArmSetupWifiOwnershipStore.read(context)?.sessionId?.let { sessionId ->
                GlassesHub.onSetupSessionTerminal(context, sessionId)
            }
        }
    }

    fun finish(
        context: Context,
        sessionId: String,
        setupState: String,
        success: Boolean,
        diagnostic: String = "",
        completionMode: String = SetupCompletionMode.UNKNOWN,
    ) {
        if (!canMutateSession(context, sessionId)) return
        writeFinished(context, setupState, success, diagnostic, completionMode)
        GlassesHub.onSetupSessionTerminal(context, sessionId)
        RokidBusAccessibilityService.onSetupSessionEnded(sessionId)
        SelfArmLocalAdbBootstrapper.onSetupSessionEnded(sessionId)
    }

    private fun writeFinished(
        context: Context,
        setupState: String,
        success: Boolean,
        diagnostic: String,
        completionMode: String,
    ) {
        SelfArmPhoneArmConfirmation.cancel()
        cancelNetworkPostureRefresh()
        val terminalStage = when {
            success -> SetupStage.COMPLETE
            setupState.contains("manual_step_needed") -> SetupStage.MANUAL_REQUIRED
            else -> SetupStage.FAILED
        }
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, false)
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false)
            .putString(KEY_PAIRING_SESSION_ID, "")
            .putString(KEY_PROGRESS_STATE, setupState.trim().take(MAX_STATE_LENGTH))
            .putString(KEY_FAILURE_STATE, if (success) "" else setupState.trim().take(MAX_STATE_LENGTH))
            .putString(KEY_STAGE, terminalStage)
            .putString(KEY_COMPLETION_MODE, SetupCompletionMode.normalize(completionMode))
            .putString(
                KEY_FAILURE_DIAGNOSTIC,
                if (success) "" else sanitizeSupportDiagnostic(diagnostic)
                    .take(MAX_SUPPORT_DIAGNOSTIC_LENGTH),
            )
            .apply()
        notifyChanged(context, terminalStage)
    }

    fun invalidateSession(context: Context) {
        RokidBusAccessibilityService.cancelSetupSessionWork()
        SelfArmPhoneArmConfirmation.cancel()
        cancelNetworkPostureRefresh()
        val prefs = prefs(context)
        val invalidatedSessionId = prefs.getString(KEY_SESSION_ID, "").orEmpty()
        prefs.edit()
            .putInt(KEY_GENERATION, prefs.getInt(KEY_GENERATION, 0) + 1)
            .putString(KEY_SESSION_ID, "")
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .putBoolean(KEY_REQUESTED, false)
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_MANUAL_ARM_IN_PROGRESS, false)
            .putString(KEY_PAIRING_SESSION_ID, "")
            .apply()
        if (invalidatedSessionId.isNotBlank()) {
            GlassesHub.onSetupSessionTerminal(context, invalidatedSessionId)
            SelfArmLocalAdbBootstrapper.onSetupSessionEnded(invalidatedSessionId)
        }
        notifyChanged(context)
    }

    /**
     * Files one diagnostic note and pushes it to the phone, which is the only place an owner can
     * ever read it. Nothing about the run changes: a note explains, it does not decide.
     */
    fun note(context: Context, sessionId: String, code: String, detail: String = "") {
        if (sessionId.isNotBlank() && !canMutateSession(context, sessionId)) return
        val normalized = SetupNote.normalize(code)
        if (normalized.isEmpty()) return
        val stage = SetupStage.normalize(prefs(context).getString(KEY_STAGE, ""))
        GlassesHub.onSetupNote(SetupNoteMessage(normalized, stage, detail))
    }

    fun notifyChanged(context: Context, stage: String? = null) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_CHANGED).setPackage(context.packageName),
        )
        GlassesHub.onSetupProgressChanged(stage)
    }

    fun refreshNetworkPosture(context: Context) {
        val appContext = context.applicationContext
        val sessionId = currentSessionId(appContext).takeIf {
            canMutateSession(appContext, it)
        }
        // Keep the last known posture until the async capture below produces a
        // definitive value. Resetting to false here made an already-onboarded
        // launcher flash the first-run screen on every resume/cold start while
        // the re-check ran. recordNetworkPosture writes the real value and
        // notifies; a genuinely unsafe posture still surfaces then.
        val postureWorker = Thread {
            try {
                val posture = SelfArmNetworkPostureVerifier.capture(appContext)
                if (sessionId == null || canMutateSession(appContext, sessionId)) {
                    recordNetworkPosture(
                        appContext,
                        posture,
                        sessionId,
                    )
                }
            } finally {
                synchronized(networkPostureWorkerLock) {
                    if (networkPostureRefreshThread === Thread.currentThread()) {
                        networkPostureRefreshThread = null
                    }
                }
            }
        }.apply {
            name = "RokidNexusAdbPosture"
            isDaemon = true
        }
        synchronized(networkPostureWorkerLock) {
            if (networkPostureRefreshThread?.isAlive == true) return
            networkPostureRefreshThread = postureWorker
        }
        postureWorker.start()
    }

    fun recordNetworkPosture(
        context: Context,
        posture: SelfArmNetworkPosture,
        sessionId: String? = null,
    ) {
        if (sessionId != null && !canMutateSession(context, sessionId)) return
        val wasComplete = SelfArmOnboardingStateMachine.evaluate(snapshot(context)).stage ==
            SelfArmOnboardingState.Stage.COMPLETE
        val safe = posture.teardownDecision() == SelfArmNetworkPosture.TeardownDecision.SAFE
        if (sessionId != null && !canMutateSession(context, sessionId)) return
        prefs(context).edit().putBoolean(KEY_LEGACY_ADB_SAFE, safe).apply()
        if (sessionId != null && !canMutateSession(context, sessionId)) return
        notifyChanged(context)
        val setupComplete = SelfArmOnboardingStateMachine.evaluate(snapshot(context)).stage ==
            SelfArmOnboardingState.Stage.COMPLETE
        if (!wasComplete &&
            setupComplete &&
            (sessionId == null || canMutateSession(context, sessionId))
        ) {
            GlassesHub.resendCapabilitiesNow()
        }
    }

    private fun canMutateSession(context: Context, sessionId: String): Boolean {
        val prefs = prefs(context)
        return SelfArmSessionPolicy.accepts(
            storedSessionId = prefs.getString(KEY_SESSION_ID, "").orEmpty(),
            sessionActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false),
            candidateSessionId = sessionId,
        )
    }

    private fun cancelNetworkPostureRefresh() {
        synchronized(networkPostureWorkerLock) {
            networkPostureRefreshThread
                ?.takeIf { it !== Thread.currentThread() }
                ?.interrupt()
            networkPostureRefreshThread = null
        }
    }

    internal fun isWifiReady(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun canonicalStageForProgress(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return when {
            normalized == "wifi_network_required" -> SetupStage.WAITING_FOR_WIFI
            normalized == SetupStage.ENABLING_WIFI ||
                normalized == "starting_wifi_enable" ||
                normalized == "wifi_on" -> SetupStage.ENABLING_WIFI
            normalized == "manual_pairing_timeout" ||
                normalized == "manual_pairing_verification_failed" ||
                normalized == "wireless_setup_timeout" ||
                normalized == "pairing_code_expired" -> SetupStage.FAILED
            normalized == SetupStage.WAITING_FOR_ACCESSIBILITY ||
                normalized.contains("accessibility") -> SetupStage.WAITING_FOR_ACCESSIBILITY
            normalized == SetupStage.WAITING_FOR_WIFI ||
                normalized.contains("wifi") -> SetupStage.WAITING_FOR_WIFI
            normalized == SetupStage.ENABLING_DEVELOPER_OPTIONS ||
                normalized.contains("developer") || normalized.contains("build_number") ->
                SetupStage.ENABLING_DEVELOPER_OPTIONS
            normalized == SetupStage.OPENING_WIRELESS_DEBUGGING ||
                normalized.contains("wireless_debugging") || normalized == "waiting_for_settings" ->
                SetupStage.OPENING_WIRELESS_DEBUGGING
            normalized == SetupStage.READING_PAIRING_DIALOG ||
                normalized.contains("pairing_code") -> SetupStage.READING_PAIRING_DIALOG
            normalized == SetupStage.PAIRING_LOCALLY ||
                normalized.contains("self_pairing") -> SetupStage.PAIRING_LOCALLY
            normalized == SetupStage.PAIRING_VIA_PHONE ||
                normalized.startsWith("manual_pairing_waiting") -> SetupStage.PAIRING_VIA_PHONE
            normalized == SetupStage.ARMING ||
                normalized.contains("verifying") || normalized.contains("arming") ->
                SetupStage.ARMING
            normalized == SetupStage.COMPLETE ||
                normalized.contains("bootstrap_complete") -> SetupStage.COMPLETE
            normalized == SetupStage.MANUAL_REQUIRED ||
                normalized.contains("manual_step_needed") -> SetupStage.MANUAL_REQUIRED
            normalized == SetupStage.FAILED -> SetupStage.FAILED
            else -> SetupStage.UNKNOWN
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "selfarm_onboarding"
    private const val KEY_REQUESTED = "setup_requested"
    private const val KEY_RUNNING = "setup_running"
    private const val KEY_FAILURE_STATE = "failure_state"
    private const val KEY_FAILURE_DIAGNOSTIC = "failure_diagnostic"
    private const val KEY_PROGRESS_STATE = "progress_state"
    private const val KEY_LEGACY_ADB_SAFE = "legacy_adb_safe"
    private const val KEY_AWAITING_A11Y = "awaiting_accessibility_enable"
    private const val KEY_MANUAL_ARM_IN_PROGRESS = "manual_arm_in_progress"
    private const val KEY_SESSION_ID = "setup_session_id"
    private const val KEY_SESSION_ACTIVE = "setup_session_active"
    private const val KEY_GENERATION = "setup_generation"
    private const val KEY_RUNNING_SINCE = "setup_running_since"
    private const val KEY_LAST_HEARTBEAT = "setup_last_heartbeat"
    private const val KEY_STAGE = "setup_stage"
    private const val KEY_COMPLETION_MODE = "setup_completion_mode"
    private const val KEY_PAIRING_SESSION_ID = "setup_pairing_session_id"
    internal const val PAUSED_WIFI_NEED_TIMEOUT_MS = 5 * 60_000L
    private const val MAX_STATE_LENGTH = 96
    private val networkPostureWorkerLock = Any()
    private var networkPostureRefreshThread: Thread? = null
    private val secureRandom = SecureRandom()
}

internal object SelfArmSetupResumePolicy {
    enum class Step {
        /** Core setup is already done and nothing more was asked of this session. */
        FINISH,
        WAIT_FOR_WIFI,
        RUN_BOOTSTRAP,
    }

    /** Core readiness closes a session only when it was not opened to pair the glasses again. */
    fun next(coreReady: Boolean, pairingRequested: Boolean, wifiReady: Boolean): Step = when {
        coreReady && !pairingRequested -> Step.FINISH
        !wifiReady -> Step.WAIT_FOR_WIFI
        else -> Step.RUN_BOOTSTRAP
    }
}

internal object SelfArmSessionPolicy {
    fun isCurrent(
        storedSessionId: String,
        candidateSessionId: String,
    ): Boolean = candidateSessionId.isNotBlank() && storedSessionId == candidateSessionId

    fun leaseValid(
        nowMillis: Long,
        lastHeartbeatMillis: Long,
        timeoutMillis: Long,
    ): Boolean =
        lastHeartbeatMillis > 0L &&
            nowMillis >= lastHeartbeatMillis &&
            nowMillis - lastHeartbeatMillis < timeoutMillis

    fun accepts(
        storedSessionId: String,
        sessionActive: Boolean,
        candidateSessionId: String,
    ): Boolean =
        sessionActive && isCurrent(storedSessionId, candidateSessionId)
}
