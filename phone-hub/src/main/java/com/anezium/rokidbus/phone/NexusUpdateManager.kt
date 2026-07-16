package com.anezium.rokidbus.phone

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast

internal object NexusUpdateManager {
    private const val TAG = "NexusAppUpdate"
    private const val PREF_LAST_CHECK_EPOCH_MILLIS = "app_update_last_check_epoch_millis"
    private const val CHECK_INTERVAL_MILLIS = 4L * 60L * 60L * 1000L

    private var checker: NexusUpdateChecker? = null
    private var updater: NexusSelfUpdater? = null
    private var checkCompletedInProcess = false
    private var activeInstallOperation: PluginInstallOperation? = null

    fun checkForUpdates(
        context: Context,
        force: Boolean = false,
        callback: ((NexusUpdateCheckResult) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val preferences = appContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
        val lastCheck = preferences.getLong(PREF_LAST_CHECK_EPOCH_MILLIS, 0L)
        val checkIsDue = lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MILLIS

        synchronized(this) {
            if (NexusPhoneState.checkingForUpdate) {
                callback?.invoke(
                    NexusUpdateCheckResult.Failure(IllegalStateException("An update check is already running")),
                )
                return
            }
            if (!force && checkCompletedInProcess && !checkIsDue) return
            NexusPhoneState.setCheckingForUpdate(true)
        }

        val installed = runCatching { installedVersion(appContext) }.getOrElse { failure ->
            synchronized(this) { checkCompletedInProcess = true }
            NexusPhoneState.setCheckingForUpdate(false)
            val result = NexusUpdateCheckResult.Failure(failure)
            callback?.invoke(result)
            Log.w(TAG, "Could not read installed app version", failure)
            return
        }
        val allowNetwork = force || checkIsDue
        if (allowNetwork) {
            // Persist the attempt before I/O so rapid resumes and process restarts cannot hammer GitHub.
            preferences.edit().putLong(PREF_LAST_CHECK_EPOCH_MILLIS, now).apply()
        }
        val updateChecker = synchronized(this) {
            checker ?: NexusUpdateChecker.create(appContext).also { checker = it }
        }
        updateChecker.checkAsync(installed, allowNetwork) { result ->
            synchronized(this) { checkCompletedInProcess = true }
            when (result) {
                is NexusUpdateCheckResult.Available -> NexusPhoneState.setAvailableUpdate(result.release)
                is NexusUpdateCheckResult.Current -> NexusPhoneState.clearAvailableUpdate()
                is NexusUpdateCheckResult.Failure ->
                    Log.w(TAG, "App update check failed: ${result.error.message}")
            }
            NexusPhoneState.setCheckingForUpdate(false)
            callback?.invoke(result)
        }
    }

    fun performUpdateAction(context: Context) {
        when (NexusPhoneState.updateInstallState) {
            is PluginInstallState.Downloading -> {
                synchronized(this) { activeInstallOperation }?.cancel()
                return
            }
            PluginInstallState.Verifying,
            PluginInstallState.Installing,
            PluginInstallState.AwaitingUserConfirmation,
            is PluginInstallState.Success,
            -> return
            else -> Unit
        }

        val release = NexusPhoneState.availableRelease ?: return
        val appContext = context.applicationContext
        val selfUpdater = synchronized(this) {
            if (activeInstallOperation != null) return
            updater ?: NexusSelfUpdater.create(appContext).also { updater = it }
        }
        val operation = selfUpdater.install(release, appContext.packageName) { state ->
            NexusPhoneState.setUpdateInstallState(state)
            when (state) {
                PluginInstallState.Cancelled -> {
                    synchronized(this) { activeInstallOperation = null }
                    Toast.makeText(appContext, "Update cancelled", Toast.LENGTH_SHORT).show()
                }
                is PluginInstallState.Failure -> {
                    synchronized(this) { activeInstallOperation = null }
                    Toast.makeText(appContext, state.message, Toast.LENGTH_LONG).show()
                }
                is PluginInstallState.Success -> {
                    synchronized(this) { activeInstallOperation = null }
                    NexusPhoneState.clearAvailableUpdate()
                    Toast.makeText(appContext, "Rokid Nexus updated", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
        synchronized(this) { activeInstallOperation = operation }
    }

    private fun installedVersion(context: Context): InstalledNexusVersion {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return InstalledNexusVersion(
            versionName = info.versionName ?: throw IllegalStateException("Installed app version is missing"),
            versionCode = info.longVersionCode,
        )
    }
}
