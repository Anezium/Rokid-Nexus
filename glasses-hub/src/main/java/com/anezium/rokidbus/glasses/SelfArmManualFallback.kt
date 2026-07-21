package com.anezium.rokidbus.glasses

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class SelfArmManualAction(val wireValue: String) {
    OPEN_DEVELOPER_OPTIONS("open_developer_options"),
    OPEN_WIRELESS_DEBUGGING("open_wireless_debugging"),
    OPEN_PAIRING_DIALOG("open_pairing_dialog"),
    CLOSE("close"),
    ;

    companion object {
        fun fromWireValue(value: String?): SelfArmManualAction? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

internal enum class SelfArmManualTarget {
    DEVELOPER_OPTIONS,
    WIRELESS_DEBUGGING,
    PAIRING_DIALOG,
}

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
    fun confirm(context: Context) {
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                val before = SelfArmOnboardingStore.snapshot(appContext)
                if (!before.secureSettingsGranted || !before.accessibilityEnabled) {
                    throw IOException("Manual arm grant or accessibility verification failed")
                }
                val posture = SelfArmNetworkPostureVerifier.awaitSafe(appContext)
                SelfArmLocalAdbBootstrapper.recordBootstrapComplete(appContext)
                SelfArmOnboardingStore.recordNetworkPosture(appContext, posture)
                SelfArmOnboardingStore.finish(
                    appContext,
                    setupState = "wireless_bootstrap_complete",
                    success = true,
                )
                GlassesHub.resendCapabilitiesNow()
            }
            result.onFailure { failure ->
                val detail = sanitizeSupportDiagnostic(causeChain(failure))
                log("Phone-driven self-arm confirmation failed: $detail")
                SelfArmOnboardingStore.reportProgress(appContext, "manual_pairing_verification_failed")
                GlassesHub.resendCapabilitiesNow()
            }
        }.apply {
            name = "RokidNexusManualArmConfirmation"
            isDaemon = true
            start()
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
