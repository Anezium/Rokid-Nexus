package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Build
import android.util.Log
import com.flyfishxu.kadb.Kadb

internal object WirelessAdbShell {
    internal data class CandidateTarget(
        val host: String,
        val port: Int,
    )

    data class Result(
        val success: Boolean,
        val output: String = "",
        val error: String = "",
    )

    fun setWifiEnabled(context: Context, enabled: Boolean): Result {
        val command = if (enabled) WIFI_ENABLE_COMMAND else WIFI_DISABLE_COMMAND
        return executeFixed(context, command, "Wi-Fi", redactOutput = false)
    }

    /** Fixed command: lets the hub's own file explorer reach all of shared storage. */
    fun grantAllFilesAccess(context: Context): Result = executeFixed(
        context = context,
        command = "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        operation = "all-files access",
        redactOutput = false,
    )

    fun startPairing(context: Context, serviceName: String, pairingCode: String): Result {
        if (!supportsApiLevel(Build.VERSION.SDK_INT)) {
            return Result(success = false, error = "unsupported Android API")
        }
        if (!PAIRING_SERVICE_PATTERN.matches(serviceName) ||
            !PAIRING_CODE_PATTERN.matches(pairingCode)
        ) {
            return Result(success = false, error = "invalid pairing arguments")
        }
        return executeFixed(
            context = context,
            command = "service call adb $ADB_PAIRING_START_TRANSACTION s16 $serviceName s16 $pairingCode",
            operation = "ADB pairing start",
            redactOutput = true,
        )
    }

    fun stopPairing(context: Context): Result {
        if (!supportsApiLevel(Build.VERSION.SDK_INT)) {
            return Result(success = false, error = "unsupported Android API")
        }
        return executeFixed(
            context = context,
            command = "service call adb $ADB_PAIRING_STOP_TRANSACTION",
            operation = "ADB pairing stop",
            redactOutput = true,
        )
    }

    private fun executeFixed(
        context: Context,
        command: String,
        operation: String,
        redactOutput: Boolean,
    ): Result {
        val failures = mutableListOf<String>()
        for (candidate in candidateTargets(SelfArmWirelessAdbController.readWirelessPort())) {
            val result = try {
                SelfArmLocalAdbBootstrapper.configureKadbCert(context.applicationContext)
                val kadb = Kadb(candidate.host, candidate.port, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
                try {
                    val shell = kadb.shell(command)
                    Result(
                        success = shell.exitCode == 0,
                        output = if (redactOutput) "" else shell.output,
                        error = if (redactOutput) {
                            if (shell.exitCode == 0) "" else "shell exit was non-zero"
                        } else {
                            shell.errorOutput
                        },
                    )
                } finally {
                    runCatching { kadb.close() }
                }
            } catch (exception: Exception) {
                val reason = if (redactOutput) {
                    exception::class.java.simpleName.ifBlank { "Exception" }
                } else {
                    shortMessage(exception)
                }
                failures += "exception=$reason"
                Log.i(TAG, "KADB $operation candidate failed: $reason")
                continue
            }

            if (result.success) {
                Log.i(TAG, "KADB $operation command succeeded")
                return result
            }

            val reason = result.error.trim().ifBlank { "shell exit was non-zero" }.take(MAX_LOG_REASON_LENGTH)
            failures += reason
            Log.i(TAG, "KADB $operation candidate failed: $reason")
        }
        return Result(
            success = false,
            error = failures.joinToString(separator = "; ").ifBlank { "no ADB candidates available" },
        )
    }

    internal fun candidateTargets(wirelessPort: Int): List<CandidateTarget> =
        listOfNotNull(wirelessPort.takeIf { it > 0 }?.let { CandidateTarget(LOCALHOST, it) })

    internal fun supportsApiLevel(apiLevel: Int): Boolean = apiLevel == SUPPORTED_API_LEVEL

    private fun shortMessage(exception: Exception): String =
        exception.message.orEmpty()
            .trim()
            .ifBlank { exception::class.java.simpleName }
            .take(MAX_LOG_REASON_LENGTH)

    private const val TAG = "NexusWirelessAdb"
    private const val MAX_LOG_REASON_LENGTH = 160
    private const val LOCALHOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val SHELL_TIMEOUT_MS = 15_000
    private const val WIFI_ENABLE_COMMAND = "svc wifi enable"
    private const val WIFI_DISABLE_COMMAND = "svc wifi disable"
    private const val ADB_PAIRING_START_TRANSACTION = 9
    private const val ADB_PAIRING_STOP_TRANSACTION = 11
    private const val SUPPORTED_API_LEVEL = 32
    private val PAIRING_SERVICE_PATTERN = Regex("[A-Za-z0-9_-]{8,48}")
    private val PAIRING_CODE_PATTERN = Regex("[0-9]{6}")
}
