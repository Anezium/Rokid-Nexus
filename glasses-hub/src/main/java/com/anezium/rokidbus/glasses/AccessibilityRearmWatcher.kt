package com.anezium.rokidbus.glasses

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

internal object AccessibilityRearmWatcher {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WATCHDOG_RETRY_DELAY_MS = 1_500L
    private val registered = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val watchdogRetryPolicy = SelfArmWatchdogRetryPolicy()
    private var retryContext: Context? = null
    private var retrySignalReason = "reachability"
    private val watchdogRetryRunnable = Runnable {
        val context = retryContext ?: return@Runnable
        if (!watchdogRetryPolicy.onRetryDelayElapsed()) return@Runnable
        val reason = retrySignalReason
        log("Watchdog re-arm retry starting trigger=$reason")
        val started = requestWatchdogEnsure(
            context = context,
            reason = "watchdog_reachability:$reason",
        )
        if (!started) {
            watchdogRetryPolicy.onRetryStartRejected()
            SelfArmController.runWhenIdle {
                signalWatchdogReachability(context, "self_arm_idle")
            }
        }
    }

    fun start(context: Context, reason: String) {
        val appContext = context.applicationContext
        registerEventListeners(appContext)
        ensureWatchdog(appContext, reason)
        repairIfNeeded(appContext, "$reason:initial_state")
    }

    fun ensureWatchdog(context: Context, reason: String, onComplete: (() -> Unit)? = null) {
        if (!requestWatchdogEnsure(context.applicationContext, reason, onComplete)) {
            onComplete?.invoke()
        }
    }

    private fun registerEventListeners(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager.addAccessibilityStateChangeListener { enabled ->
            log("Accessibility state changed enabled=$enabled")
            repairIfNeeded(context, "accessibility_manager")
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    repairIfNeeded(context, "enabled_services_setting")
                }
            },
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_WIFI_ENABLED),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val enabled = SelfArmWirelessAdbController.isEnabled(context)
                    log("Wireless debugging setting changed enabled=$enabled")
                    if (enabled) signalWatchdogReachability(context, "adb_wifi_enabled")
                }
            },
        )
        runCatching {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        signalWatchdogReachability(context, "wifi_network_available")
                    }
                },
            )
        }.onFailure {
            logError("Watchdog Wi-Fi network callback registration failed", it)
        }
        log("Accessibility and watchdog re-arm event listeners registered")
    }

    private fun requestWatchdogEnsure(
        context: Context,
        reason: String,
        onComplete: (() -> Unit)? = null,
    ): Boolean = SelfArmController.ensureWatchdog(context, reason) { result ->
        handler.post {
            handler.removeCallbacks(watchdogRetryRunnable)
            val shouldSchedule = watchdogRetryPolicy.onEnsureFinished(result)
            log("Watchdog ensure finished reason=$reason result=$result retryScheduled=$shouldSchedule")
            if (shouldSchedule) postWatchdogRetry(context, "deferred_reachability")
            onComplete?.invoke()
        }
    }

    private fun signalWatchdogReachability(context: Context, reason: String) {
        handler.post {
            val shouldSchedule = watchdogRetryPolicy.onReachabilitySignal()
            log("Watchdog reachability signal reason=$reason retryScheduled=$shouldSchedule")
            if (shouldSchedule) postWatchdogRetry(context, reason)
        }
    }

    private fun postWatchdogRetry(context: Context, reason: String) {
        retryContext = context.applicationContext
        retrySignalReason = reason
        handler.removeCallbacks(watchdogRetryRunnable)
        handler.postDelayed(watchdogRetryRunnable, WATCHDOG_RETRY_DELAY_MS)
    }

    private fun repairIfNeeded(context: Context, reason: String) {
        // Decide on the raw secure settings, not AccessibilityManager. Its cached enabled-service
        // list lags the setting write that triggers our ContentObserver, so gating on it here
        // silently dropped legitimate repairs. SelfArmController.repairNow re-reads the setting and
        // no-ops when nothing is wrong, so delegating unconditionally is both correct and cheap.
        SelfArmController.repairNow(context, reason)
    }
}
