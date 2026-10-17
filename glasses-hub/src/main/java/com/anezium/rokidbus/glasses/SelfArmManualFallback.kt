package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.anezium.rokidbus.shared.SetupCompletionMode
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class SelfArmManualAction(val wireValue: String) {
    ENABLE_DEVELOPER_OPTIONS("enable_developer_options"),
    OPEN_DEVELOPER_OPTIONS("open_developer_options"),
    OPEN_WIRELESS_DEBUGGING("open_wireless_debugging"),
    OPEN_PAIRING_DIALOG("open_pairing_dialog"),
    OPEN_ACCESSIBILITY_SETTINGS("open_accessibility_settings"),
    CLOSE("close"),
    ;

    companion object {
        fun fromWireValue(value: String?): SelfArmManualAction? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

internal const val WIRELESS_DEBUGGING_PREFERENCE_KEY = "toggle_adb_wireless"
internal const val BUILD_NUMBER_PREFERENCE_KEY = "build_number"

internal enum class SelfArmManualTarget(
    val settingsAction: String,
    val settingsPreferenceKey: String?,
    val directFragmentClassName: String? = null,
) {
    ENABLE_DEVELOPER_OPTIONS(Settings.ACTION_DEVICE_INFO_SETTINGS, BUILD_NUMBER_PREFERENCE_KEY),
    DEVELOPER_OPTIONS(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, null),
    WIRELESS_DEBUGGING(
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        WIRELESS_DEBUGGING_PREFERENCE_KEY,
        WIRELESS_DEBUGGING_FRAGMENT_CLASS,
    ),
    // Kept for older phone builds. It now opens the manual Wireless Debugging route instead of
    // starting the locale-sensitive Settings automator.
    PAIRING_DIALOG(
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        WIRELESS_DEBUGGING_PREFERENCE_KEY,
        WIRELESS_DEBUGGING_FRAGMENT_CLASS,
    ),
    ;
}

/** Opens only public Android Settings surfaces; it never clicks or traverses Settings UI. */
internal object SelfArmManualSettingsLauncher {
    fun open(context: Context, target: SelfArmManualTarget): Boolean {
        target.directFragmentClassName?.let { fragmentClassName ->
            val direct = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(SETTINGS_PACKAGE, SETTINGS_SUB_SETTINGS_CLASS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_SHOW_FRAGMENT, fragmentClassName)
                .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle())
            if (runCatching { context.startActivity(direct) }.isSuccess) return true
        }
        // Older Settings builds may not export SubSettings. Preserve the public developer-screen
        // route as a launch fallback, while the caller still verifies the requested page before ACK.
        val explicit = intent(target).setPackage(SETTINGS_PACKAGE)
        if (runCatching { context.startActivity(explicit) }.isSuccess) return true
        return runCatching { context.startActivity(intent(target)) }.isSuccess
    }

    private fun intent(target: SelfArmManualTarget): Intent =
        Intent(target.settingsAction)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .also { intent ->
                target.settingsPreferenceKey?.let { key ->
                    val args = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) }
                    intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                    intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args)
                }
            }

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val SETTINGS_SUB_SETTINGS_CLASS = "com.android.settings.SubSettings"
    private const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
}

internal const val WIRELESS_DEBUGGING_FRAGMENT_CLASS =
    "com.android.settings.development.WirelessDebuggingFragment"

/**
 * Stages the glasses-owned watchdog and rendered command bridge for the authenticated phone ADB
 * session. The scoped external directory is readable to adb shell but remains unavailable to
 * ordinary apps on Android 11+. The phone removes both files immediately after reading them; the
 * glasses also remove them on close and timeout.
 */
internal object SelfArmManualArmAssets {
    const val WATCHDOG_SHELL_PATH =
        "/sdcard/Android/data/com.anezium.rokidbus.glasses/files/cmd_bridge/manual_selfarm/watchdog.sh"
    const val BRIDGE_SHELL_PATH =
        "/sdcard/Android/data/com.anezium.rokidbus.glasses/files/cmd_bridge/manual_selfarm/bridge.sh"

    fun stage(context: Context) {
        val appContext = context.applicationContext
        val channel = SelfArmCommandBridgeClient.ensureChannelDir(appContext)
            ?: throw IOException("Could not prepare the manual self-arm channel")
        val directory = File(channel, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the manual self-arm directory")
        }
        stageFile(SelfArmController.ensureInternalWatchdog(appContext), File(directory, WATCHDOG_NAME))
        stageFile(SelfArmController.ensureInternalCommandBridge(appContext), File(directory, BRIDGE_NAME))
    }

    fun cleanup(context: Context) {
        val channel = SelfArmCommandBridgeClient.ensureChannelDir(context.applicationContext) ?: return
        val directory = File(channel, DIRECTORY_NAME)
        File(directory, WATCHDOG_NAME).delete()
        File(directory, BRIDGE_NAME).delete()
        directory.delete()
    }

    private fun stageFile(source: File, target: File) {
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            source.copyTo(temporary, overwrite = true)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            target.setReadable(true, false)
        } finally {
            temporary.delete()
        }
    }

    private const val DIRECTORY_NAME = "manual_selfarm"
    private const val WATCHDOG_NAME = "watchdog.sh"
    private const val BRIDGE_NAME = "bridge.sh"
}

/** Converts a trusted phone arm result into a locally verified setup-complete announcement. */
internal object SelfArmPhoneArmConfirmation {
    private val workerLock = Any()
    private var worker: Thread? = null

    fun confirm(
        context: Context,
        sessionId: String = SelfArmOnboardingStore.currentSessionId(context),
        completionMode: String = SetupCompletionMode.PHONE_MANUAL,
        onFailure: (() -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return
        cancel()
        val confirmationWorker = Thread {
            val result = runCatching {
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@runCatching
                SelfArmOnboardingStore.heartbeat(appContext, sessionId)
                val before = SelfArmOnboardingStore.snapshot(appContext)
                if (!before.secureSettingsGranted || !before.accessibilityEnabled) {
                    throw IOException("Manual arm grant or accessibility verification failed")
                }
                val posture = SelfArmNetworkPostureVerifier.awaitSafe(appContext)
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@runCatching
                SelfArmOnboardingStore.heartbeat(appContext, sessionId)
                recordVerified(appContext, sessionId, posture, completionMode)
            }
            result.onFailure { failure ->
                if (!SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)) return@onFailure
                val detail = sanitizeSupportDiagnostic(causeChain(failure))
                log("Phone-driven self-arm confirmation failed: $detail")
                if (onFailure != null) {
                    onFailure()
                } else {
                    SelfArmOnboardingStore.reportProgress(
                        appContext,
                        sessionId,
                        "manual_pairing_verification_failed",
                    )
                }
            }
            synchronized(workerLock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }.apply {
            name = "RokidNexusManualArmConfirmation"
            isDaemon = true
        }
        synchronized(workerLock) { worker = confirmationWorker }
        confirmationWorker.start()
    }

    /**
     * Closes the session as set up without claiming the glasses paired themselves: the phone
     * armed over its own pairing, and the glasses' key is still unknown to adbd. Recording
     * bootstrap completion here revived the helper once and then sent every later re-arm down a
     * TLS path that could only fail, while every screen said the unit maintained itself. The
     * glasses stay "pairing required" until a bootstrap they ran themselves says otherwise.
     */
    internal fun recordVerified(
        context: Context,
        sessionId: String,
        posture: SelfArmNetworkPosture,
        completionMode: String,
    ) {
        SelfArmOnboardingStore.recordNetworkPosture(context, posture, sessionId)
        if (!SelfArmOnboardingStore.isCurrentSession(context, sessionId)) return
        SelfArmOnboardingStore.finish(
            context,
            sessionId = sessionId,
            setupState = "wireless_bootstrap_complete",
            success = true,
            completionMode = completionMode,
        )
    }

    fun cancel() {
        synchronized(workerLock) {
            worker?.takeIf { it !== Thread.currentThread() }?.interrupt()
            worker = null
        }
    }

    private fun causeChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current) && parts.size < 5) {
            parts += current.message.orEmpty().trim().ifBlank { current.javaClass.simpleName }
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }
}
