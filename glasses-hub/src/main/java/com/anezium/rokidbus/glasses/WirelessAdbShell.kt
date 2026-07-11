package com.anezium.rokidbus.glasses

import android.content.Context
import com.flyfishxu.kadb.Kadb

internal object WirelessAdbShell {
    data class Result(
        val success: Boolean,
        val output: String = "",
        val error: String = "",
    )

    fun setWifiEnabled(context: Context, enabled: Boolean): Result {
        val connectPort = SelfArmWirelessAdbController.readWirelessPort()
        if (connectPort <= 0) return Result(success = false, error = "wireless ADB port unavailable")

        val command = if (enabled) WIFI_ENABLE_COMMAND else WIFI_DISABLE_COMMAND
        return runCatching {
            SelfArmLocalAdbBootstrapper.configureKadbCert(context.applicationContext)
            val kadb = Kadb(LOCALHOST, connectPort, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
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
        }.getOrElse { throwable ->
            Result(
                success = false,
                error = throwable.message.orEmpty().ifBlank { throwable::class.java.simpleName },
            )
        }
    }

    private const val LOCALHOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val SHELL_TIMEOUT_MS = 15_000
    private const val WIFI_ENABLE_COMMAND = "svc wifi enable"
    private const val WIFI_DISABLE_COMMAND = "svc wifi disable"
}
