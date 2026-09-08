package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbReply
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class WirelessAdbWifiPreparationResult {
    READY,
    ENABLE_FAILED,
    NETWORK_UNAVAILABLE,
}

internal fun prepareWifiForWirelessAdb(
    isNetworkReady: () -> Boolean,
    isWifiEnabled: () -> Boolean,
    enableWifi: () -> Boolean,
    awaitNetworkReady: () -> Boolean,
): WirelessAdbWifiPreparationResult {
    if (isNetworkReady()) return WirelessAdbWifiPreparationResult.READY
    if (isWifiEnabled()) return WirelessAdbWifiPreparationResult.NETWORK_UNAVAILABLE
    if (!enableWifi()) return WirelessAdbWifiPreparationResult.ENABLE_FAILED
    return if (awaitNetworkReady()) {
        WirelessAdbWifiPreparationResult.READY
    } else {
        WirelessAdbWifiPreparationResult.NETWORK_UNAVAILABLE
    }
}

internal object WirelessAdbController {
    private data class PairingSession(
        val serviceName: String,
        val expiresAtMillis: Long,
    )

    private data class PairingEndpoint(val port: Int)

    private val random = SecureRandom()
    private val pairingOperationLock = Any()
    private val expiryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NexusAdbPairingExpiry").apply { isDaemon = true }
    }
    @Volatile
    private var activePairing: PairingSession? = null

    fun isPairingActive(): Boolean = activePairing != null

    fun restorePairingExpiry(context: Context) = synchronized(pairingOperationLock) {
        val preferences = context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
        val serviceName = preferences.getString(KEY_SERVICE_NAME, null).orEmpty()
        val expiresAtMillis = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (serviceName.isBlank() || expiresAtMillis <= 0L) {
            activePairing = null
            clearStoredPairing(context)
            return@synchronized
        }
        val session = PairingSession(serviceName, expiresAtMillis)
        activePairing = session
        scheduleExpiry(context.applicationContext, session)
    }

    fun handle(context: Context, action: WirelessAdbAction): WirelessAdbReply =
        synchronized(pairingOperationLock) {
            when (action) {
                WirelessAdbAction.STATUS -> status(context, action, success = true)
                WirelessAdbAction.ENABLE -> enable(context, action)
                WirelessAdbAction.START_PAIRING -> startPairing(context)
                WirelessAdbAction.CANCEL_PAIRING -> cancelPairing(context, action)
                WirelessAdbAction.DISABLE -> disable(context)
            }
        }

    private fun enable(context: Context, action: WirelessAdbAction): WirelessAdbReply {
        enablePreconditionFailure(context, action)?.let { return it }
        val enabled = SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, true)
        return if (enabled) {
            status(context, action, success = true)
        } else {
            failure(
                context,
                action,
                "PRIVILEGED_BRIDGE_UNAVAILABLE",
                "Nexus could not enable wireless debugging. Repair the glasses helper and try again.",
            )
        }
    }

    private fun startPairing(context: Context): WirelessAdbReply {
        val action = WirelessAdbAction.START_PAIRING
        enablePreconditionFailure(context, action)?.let { return it }
        if (!SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, true)) {
            return failure(
                context,
                action,
                "PRIVILEGED_BRIDGE_UNAVAILABLE",
                "Nexus could not enable wireless debugging. Repair the glasses helper and try again.",
            )
        }
        val host = localIpv4Address(context) ?: return failure(
            context,
            action,
            "NO_IPV4_ADDRESS",
            "The glasses do not have a usable IPv4 address on this Wi-Fi network.",
        )
        if (!cancelPairingInternal(context)) {
            return failure(
                context,
                action,
                "PAIRING_CANCEL_FAILED",
                "The previous pairing window could not be closed.",
            )
        }
        val serviceName = "Nexus_${randomHex(8)}"
        val pairingCode = (random.nextInt(900_000) + 100_000).toString()
        val session = PairingSession(
            serviceName = serviceName,
            expiresAtMillis = System.currentTimeMillis() + PAIRING_LIFETIME_MS,
        )
        val endpoint = discoverPairingEndpoint(context, serviceName) {
            activatePairing(context, session) &&
                WirelessAdbShell.startPairing(context, serviceName, pairingCode).success
        }
        if (endpoint == null) {
            val stopped = cancelPairingInternal(context)
            return failure(
                context,
                action,
                if (stopped) "PAIRING_SERVICE_NOT_FOUND" else "PAIRING_CLEANUP_FAILED",
                if (stopped) {
                    "The pairing service did not become visible on the local network."
                } else {
                    "Pairing failed and the temporary service could not be closed. Try cancelling it again."
                },
            )
        }
        val connectPort = SelfArmWirelessAdbController.readWirelessPort()
        if (connectPort <= 0) {
            cancelPairingInternal(context)
            return failure(
                context,
                action,
                "WIRELESS_DEBUGGING_STOPPED",
                "Wireless debugging stopped before pairing was ready.",
            )
        }
        return WirelessAdbReply(
            action = action,
            success = true,
            wifiConnected = true,
            enabled = true,
            pairingActive = true,
            host = host,
            connectPort = connectPort,
            pairingPort = endpoint.port,
            pairingCode = pairingCode,
            expiresAtMillis = session.expiresAtMillis,
        )
    }

    private fun activatePairing(context: Context, session: PairingSession): Boolean {
        val stored = context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_NAME, session.serviceName)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMillis)
            .commit()
        if (!stored) {
            Log.w(TAG, "temporary ADB pairing metadata could not be stored")
            return false
        }
        activePairing = session
        scheduleExpiry(context.applicationContext, session)
        return true
    }

    private fun scheduleExpiry(context: Context, session: PairingSession) {
        val delayMillis = (session.expiresAtMillis - System.currentTimeMillis())
            .coerceIn(0L, PAIRING_LIFETIME_MS)
        schedulePairingStop(context, session, delayMillis)
    }

    private fun schedulePairingStop(
        context: Context,
        session: PairingSession,
        delayMillis: Long,
        attempt: Int = 0,
    ) {
        expiryExecutor.schedule(
            {
                synchronized(pairingOperationLock) {
                    val isCurrent = activePairing?.serviceName == session.serviceName
                    if (isCurrent) {
                        if (cancelPairingInternal(context)) {
                            Log.i(TAG, "temporary ADB pairing window closed at expiry")
                        } else if (SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, false)) {
                            clearPairingState(context)
                            Log.w(TAG, "temporary ADB pairing stop failed; disabled wireless debugging")
                        } else if (attempt + 1 < MAX_PAIRING_STOP_ATTEMPTS) {
                            Log.w(TAG, "temporary ADB pairing stop failed; retrying attempt=${attempt + 1}")
                            schedulePairingStop(context, session, PAIRING_STOP_RETRY_MS, attempt + 1)
                        } else {
                            // Both routes need the command bridge, and a bridge that is gone
                            // (every reboot kills it) stays gone: each retry burned a full poll
                            // timeout for nothing. Give the state up so the next request can
                            // start clean, and say so once instead of every five seconds forever.
                            clearPairingState(context)
                            Log.w(
                                TAG,
                                "temporary ADB pairing stop failed after $MAX_PAIRING_STOP_ATTEMPTS attempts; " +
                                    "giving up (command bridge unavailable)",
                            )
                        }
                    }
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPairing(context: Context, action: WirelessAdbAction): WirelessAdbReply {
        val stopped = cancelPairingInternal(context)
        return if (stopped) {
            status(context, action, success = true)
        } else {
            failure(context, action, "PAIRING_CANCEL_FAILED", "The temporary pairing window could not be closed.")
        }
    }

    private fun disable(context: Context): WirelessAdbReply {
        cancelPairingInternal(context)
        val disabled = SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, false)
        return if (disabled) {
            clearPairingState(context)
            status(context, WirelessAdbAction.DISABLE, success = true)
        } else {
            failure(
                context,
                WirelessAdbAction.DISABLE,
                "DISABLE_FAILED",
                "Wireless debugging could not be disabled.",
            )
        }
    }

    private fun cancelPairingInternal(context: Context): Boolean {
        val stopped = WirelessAdbShell.stopPairing(context).success ||
            SelfArmWirelessAdbController.readWirelessPort() <= 0
        if (stopped) clearPairingState(context)
        return stopped
    }

    private fun clearPairingState(context: Context) {
        activePairing = null
        clearStoredPairing(context)
    }

    private fun clearStoredPairing(context: Context) {
        context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SERVICE_NAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun enablePreconditionFailure(
        context: Context,
        action: WirelessAdbAction,
    ): WirelessAdbReply? {
        if (!WirelessAdbShell.supportsApiLevel(Build.VERSION.SDK_INT)) {
            return failure(
                context,
                action,
                "UNSUPPORTED_ANDROID_VERSION",
                "This glasses firmware is not supported for automatic ADB pairing.",
            )
        }
        if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(context)) {
            return failure(
                context,
                action,
                "DEVELOPER_OPTIONS_DISABLED",
                "Developer options are not enabled on the glasses.",
            )
        }
        return when (
            prepareWifiForWirelessAdb(
                isNetworkReady = { SelfArmWirelessAdbController.isWifiNetworkReady(context) },
                isWifiEnabled = { SelfArmWirelessAdbController.isWifiEnabled(context) },
                enableWifi = { SelfArmCommandBridgeClient.setWifiEnabled(context, true) },
                awaitNetworkReady = {
                    SelfArmWirelessAdbController.waitForWifiNetworkReady(
                        context,
                        WIFI_NETWORK_TIMEOUT_MS,
                    )
                },
            )
        ) {
            WirelessAdbWifiPreparationResult.READY -> null
            WirelessAdbWifiPreparationResult.ENABLE_FAILED -> failure(
                context,
                action,
                "WIFI_ENABLE_FAILED",
                "Nexus could not enable Wi-Fi on the glasses. Repair the glasses helper and try again.",
            )
            WirelessAdbWifiPreparationResult.NETWORK_UNAVAILABLE -> failure(
                context,
                action,
                "WIFI_REQUIRED",
                "Wi-Fi is on, but the glasses did not connect to a saved network.",
            )
        }
    }

    private fun status(
        context: Context,
        action: WirelessAdbAction,
        success: Boolean,
    ): WirelessAdbReply {
        val wifiConnected = SelfArmWirelessAdbController.isWifiNetworkReady(context)
        val connectPort = SelfArmWirelessAdbController.readWirelessPort().takeIf { it > 0 }
        val pairingActive = activePairing != null
        return WirelessAdbReply(
            action = action,
            success = success,
            wifiConnected = wifiConnected,
            enabled = connectPort != null && SelfArmWirelessAdbController.isEnabled(context),
            pairingActive = pairingActive,
            host = if (connectPort != null) localIpv4Address(context) else null,
            connectPort = connectPort,
        )
    }

    private fun failure(
        context: Context,
        action: WirelessAdbAction,
        code: String,
        message: String,
    ): WirelessAdbReply = status(context, action, success = false).copy(
        errorCode = code,
        message = message,
    )

    private fun localIpv4Address(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun discoverPairingEndpoint(
        context: Context,
        expectedServiceName: String,
        startPairing: () -> Boolean,
    ): PairingEndpoint? {
        val manager = context.getSystemService(NsdManager::class.java) ?: return null
        val localAddresses = localNetworkAddresses(context)
        if (localAddresses.isEmpty()) return null
        val discoveryReady = CountDownLatch(1)
        val discoveryStarted = AtomicBoolean(false)
        val endpointReady = CountDownLatch(1)
        val endpoint = AtomicReference<PairingEndpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted.set(true)
                discoveryReady.countDown()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                discoveryReady.countDown()

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val discoveredName = serviceInfo.serviceName
                if (discoveredName != expectedServiceName &&
                    !discoveredName.startsWith("$expectedServiceName (")
                ) {
                    return
                }
                @Suppress("DEPRECATION")
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val resolvedAddress = serviceInfo.host
                                ?.hostAddress
                                ?.substringBefore('%')
                            serviceInfo.port.takeIf {
                                it in 1..65535 &&
                                    resolvedAddress != null &&
                                    resolvedAddress in localAddresses
                            }?.let { port ->
                                endpoint.compareAndSet(null, PairingEndpoint(port))
                                endpointReady.countDown()
                            }
                        }
                    },
                )
            }
        }
        return try {
            manager.discoverServices(PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            if (!discoveryReady.await(DISCOVERY_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            if (!discoveryStarted.get()) return null
            if (!startPairing()) return null
            endpointReady.await(PAIRING_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            endpoint.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (failure: Exception) {
            Log.i(TAG, "ADB pairing discovery failed: ${failure.javaClass.simpleName}")
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun localNetworkAddresses(context: Context): Set<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching emptySet()
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.mapNotNull { link ->
                link.address
                    ?.takeIf { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?.substringBefore('%')
            }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount)
        .also(random::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val TAG = "NexusWirelessAdb"
    private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val DISCOVERY_START_TIMEOUT_MS = 2_000L
    private const val PAIRING_DISCOVERY_TIMEOUT_MS = 12_000L
    private const val WIFI_NETWORK_TIMEOUT_MS = 12_000L
    private const val PAIRING_LIFETIME_MS = 2L * 60L * 1_000L
    private const val PAIRING_STOP_RETRY_MS = 5_000L
    private const val MAX_PAIRING_STOP_ATTEMPTS = 6
    private const val PAIRING_PREFERENCES = "wireless_adb_pairing"
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_EXPIRES_AT = "expires_at"
}
