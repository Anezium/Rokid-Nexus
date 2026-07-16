package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

internal object SelfArmOnboardingStore {
    const val ACTION_CHANGED = "com.anezium.rokidbus.glasses.ACTION_SELFARM_ONBOARDING_CHANGED"

    fun snapshot(context: Context): SelfArmOnboardingSnapshot {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val accessibilityEnabled = runCatching {
            val resolver = appContext.contentResolver
            !SelfArmController.accessibilityRepairNeeded(
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0),
            )
        }.getOrDefault(false)
        return SelfArmOnboardingSnapshot(
            wirelessDebuggingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            accessibilityEnabled = accessibilityEnabled,
            secureSettingsGranted = appContext.checkSelfPermission(
                Manifest.permission.WRITE_SECURE_SETTINGS,
            ) == PackageManager.PERMISSION_GRANTED,
            bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(appContext),
            legacyAdbSafe = prefs.getBoolean(KEY_LEGACY_ADB_SAFE, false),
            setupRunning = prefs.getBoolean(KEY_RUNNING, false),
            failureState = prefs.getString(KEY_FAILURE_STATE, "").orEmpty(),
            progressState = prefs.getString(KEY_PROGRESS_STATE, "").orEmpty(),
        )
    }

    fun requestSetup(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, true)
            .putBoolean(KEY_RUNNING, false)
            .putString(KEY_FAILURE_STATE, "")
            .putString(KEY_PROGRESS_STATE, "waiting_for_nexus_accessibility")
            .apply()
        notifyChanged(context)
    }

    fun isSetupRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markRunning(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, true)
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_LEGACY_ADB_SAFE, false)
            .putString(KEY_FAILURE_STATE, "")
            .putString(KEY_PROGRESS_STATE, "starting_wireless_debugging_setup")
            .apply()
        notifyChanged(context)
    }

    fun reportProgress(context: Context, setupState: String) {
        val cleanState = setupState.trim().take(MAX_STATE_LENGTH)
        if (cleanState.isBlank()) return
        val prefs = prefs(context)
        if (prefs.getString(KEY_PROGRESS_STATE, "") == cleanState) return
        prefs.edit().putString(KEY_PROGRESS_STATE, cleanState).apply()
        notifyChanged(context)
    }

    fun pause(context: Context, progressState: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putString(KEY_PROGRESS_STATE, progressState.trim().take(MAX_STATE_LENGTH))
            .apply()
        notifyChanged(context)
    }

    fun finish(context: Context, setupState: String, success: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_REQUESTED, false)
            .putBoolean(KEY_RUNNING, false)
            .putString(KEY_PROGRESS_STATE, setupState.trim().take(MAX_STATE_LENGTH))
            .putString(KEY_FAILURE_STATE, if (success) "" else setupState.trim().take(MAX_STATE_LENGTH))
            .apply()
        notifyChanged(context)
    }

    fun notifyChanged(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_CHANGED).setPackage(context.packageName),
        )
    }

    fun refreshNetworkPosture(context: Context) {
        val appContext = context.applicationContext
        if (!networkPostureRefreshRunning.compareAndSet(false, true)) return
        prefs(appContext).edit().putBoolean(KEY_LEGACY_ADB_SAFE, false).apply()
        notifyChanged(appContext)
        Thread {
            try {
                recordNetworkPosture(
                    appContext,
                    SelfArmNetworkPostureVerifier.capture(appContext),
                )
            } finally {
                networkPostureRefreshRunning.set(false)
            }
        }.apply {
            name = "RokidNexusAdbPosture"
            isDaemon = true
            start()
        }
    }

    fun recordNetworkPosture(context: Context, posture: SelfArmNetworkPosture) {
        val safe = posture.teardownDecision() == SelfArmNetworkPosture.TeardownDecision.SAFE
        prefs(context).edit().putBoolean(KEY_LEGACY_ADB_SAFE, safe).apply()
        notifyChanged(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "selfarm_onboarding"
    private const val KEY_REQUESTED = "setup_requested"
    private const val KEY_RUNNING = "setup_running"
    private const val KEY_FAILURE_STATE = "failure_state"
    private const val KEY_PROGRESS_STATE = "progress_state"
    private const val KEY_LEGACY_ADB_SAFE = "legacy_adb_safe"
    private const val MAX_STATE_LENGTH = 96
    private val networkPostureRefreshRunning = AtomicBoolean(false)
}
