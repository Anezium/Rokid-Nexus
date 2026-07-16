package com.anezium.rokidbus.glasses

import android.content.Context
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
        val failures = mutableListOf<String>()
        for (candidate in candidateTargets(SelfArmWirelessAdbController.readWirelessPort())) {
            val result = try {
                SelfArmLocalAdbBootstrapper.configureKadbCert(context.applicationContext)
                val kadb = Kadb(candidate.host, candidate.port, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
                try {
                    val shell = kadb.shell(command)
                    Result(
                        success = shell.exitCode == 0,
                        output = shell.output,
                        error = shell.errorOutput,
                    )
                } finally {
                    runCatching { kadb.close() }
                }
            } catch (exception: Exception) {
                val reason = shortMessage(exception)
                failures += "${candidate.host}:${candidate.port} exception=$reason"
                Log.i(TAG, "KADB Wi-Fi candidate ${candidate.host}:${candidate.port} failed: $reason")
                continue
            }

            if (result.success) {
                Log.i(TAG, "KADB Wi-Fi command succeeded on ${candidate.host}:${candidate.port}")
                return result
            }

            val reason = result.error.trim().ifBlank { "shell exit was non-zero" }.take(MAX_LOG_REASON_LENGTH)
            failures += "${candidate.host}:${candidate.port} $reason"
            Log.i(TAG, "KADB Wi-Fi candidate ${candidate.host}:${candidate.port} failed: $reason")
        }
        return Result(
            success = false,
            error = failures.joinToString(separator = "; ").ifBlank { "no ADB candidates available" },
        )
    }

    internal fun candidateTargets(wirelessPort: Int): List<CandidateTarget> =
        listOfNotNull(wirelessPort.takeIf { it > 0 }?.let { CandidateTarget(LOCALHOST, it) })

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
}
