package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Locale

internal object SelfArmWirelessAdbController {
    private const val TAG = "NexusWirelessAdb"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_TLS_PORT_PROPERTY = "service.adb.tls.port"
    private const val ADB_SERVICE = "adb"
    private const val ADB_DESCRIPTOR = "android.debug.IAdbManager"
    private const val TRANSACTION_GET_ADB_WIRELESS_PORT = 10
    private var binderPortReadDenied = false

    fun enableAdbWifi(context: Context): Boolean =
        runCatching {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        }.getOrElse {
            Log.d(TAG, "enableAdbWifi failed", it)
            false
        }

    fun ensureEnabledForMaintenance(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!isWifiEnabled(context)) {
            Log.i(TAG, "maintenance enable skipped: Wi-Fi disabled")
            return false
        }
        return enableAdbWifi(context).also { requested ->
            Log.i(TAG, "maintenance wireless debugging enable requested=$requested")
        }
    }

    fun isEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)

    private fun isWifiEnabled(context: Context): Boolean =
        runCatching {
            context.getSystemService(WifiManager::class.java).isWifiEnabled
        }.getOrDefault(false)

    fun areDeveloperOptionsUsable(context: Context): Boolean =
        isDeveloperOptionsEnabled(context) && !resolvesToDisabledDeveloperOptions(context)

    fun readWirelessPort(): Int {
        readWirelessPortProperty().takeIf { it > 0 }?.let { return it }
        if (binderPortReadDenied) return 0

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, ADB_SERVICE) as? IBinder ?: return 0
            data.writeInterfaceToken(ADB_DESCRIPTOR)
            if (!binder.transact(TRANSACTION_GET_ADB_WIRELESS_PORT, data, reply, 0)) return 0
            reply.readException()
            reply.readInt().takeIf { it > 0 } ?: 0
        } catch (_: SecurityException) {
            binderPortReadDenied = true
            Log.d(TAG, "wireless debugging port binder read denied")
            0
        } catch (throwable: Throwable) {
            Log.d(TAG, "read wireless debugging port failed", throwable)
            0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Throws(InterruptedException::class)
    fun waitForWirelessPort(timeoutMs: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val port = readWirelessPort()
            if (port > 0) return port
            Thread.sleep(150L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return 0
    }

    private fun isDeveloperOptionsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1
        }.getOrDefault(false)

    private fun readWirelessPortProperty(): Int =
        runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val value = systemProperties
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, ADB_TLS_PORT_PROPERTY, "") as? String
            value?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
        }.getOrElse {
            Log.d(TAG, "read wireless debugging port property failed", it)
            0
        }

    private fun resolvesToDisabledDeveloperOptions(context: Context): Boolean =
        runCatching {
            val packageManager = context.packageManager
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .setPackage("com.android.settings")
            var resolved = packageManager.resolveActivity(
                settingsIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            if (resolved == null) {
                resolved = packageManager.resolveActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
            }
            val name = resolved?.activityInfo?.name.orEmpty()
            name.lowercase(Locale.US).contains("developmentsettingsdisabled")
        }.getOrDefault(false)
}
