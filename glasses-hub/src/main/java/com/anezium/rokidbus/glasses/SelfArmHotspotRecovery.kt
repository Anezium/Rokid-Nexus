package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials
import com.anezium.rokidbus.shared.GlassesRepairContract as Result
import com.anezium.rokidbus.shared.GlassesRepairHotspotContract as Wire
import org.json.JSONObject
import java.util.UUID

/** Main-thread lifecycle of the temporary network; the existing controller owns the arm. */
internal object SelfArmHotspotRecovery {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var supported = false
    @Volatile private var run: Run? = null
    private var opportunity: Runnable? = null
    private data class Run(
        val context: Context,
        val id: String,
        val automatic: Boolean,
        val callback: (String) -> Unit,
        var activity: RecoveryNetworkActivity? = null,
        var credentials: GlassesFilesHotspotCredentials? = null,
        @Volatile var network: Network? = null,
        var radioRequested: Boolean = false,
        var armStarted: Boolean = false,
    )

    val isActive: Boolean get() = run != null
    fun isCurrent(id: String): Boolean = run?.id == id

    fun configured(context: Context, payload: JSONObject) {
        main.post {
            supported = payload.optInt(Wire.CAPABILITY_FIELD) == Wire.VERSION
            if (!SelfArmBootRepairStore.isAutoRepairEnabled(context)) {
                if (run?.automatic == true) finish(Result.RESULT_WIFI_UNAVAILABLE)
            } else if (supported) scheduleOpportunity(context.applicationContext,
                SystemClock.elapsedRealtime() + 240_000L)
        }
    }

    private fun scheduleOpportunity(context: Context, deadline: Long) {
        opportunity?.let(main::removeCallbacks)
        opportunity = Runnable {
            opportunity = null
            if (!supported || !SelfArmBootRepairStore.isAutoRepairEnabled(context) || isActive) return@Runnable
            if (SelfArmBootRepairCoordinator.isRepairRunning() || SelfArmController.isOperationRunning() ||
                context.getSystemService(PowerManager::class.java)?.isInteractive != true) {
                if (SystemClock.elapsedRealtime() < deadline) scheduleOpportunity(context, deadline)
            } else start(context, automatic = true) {}
        }.also { main.postDelayed(it, 5_000L) }
    }

    /** False leaves the existing repair path in charge. Owner calls arrive on the hub reader. */
    fun start(context: Context, automatic: Boolean, callback: (String) -> Unit): Boolean {
        if (isActive) { main.post { callback(Result.RESULT_BUSY) }; return true }
        if (!supported || !SelfArmLocalAdbBootstrapper.isBootstrapComplete(context) ||
            SelfArmBridgeLivenessStore.helperRecord(context) == SelfArmOwnerRepairPolicy.HelperRecord.ALIVE_THIS_BOOT ||
            SelfArmWirelessAdbController.isWifiNetworkReady(context)) return false
        main.post {
            if (run != null) { callback(Result.RESULT_BUSY); return@post }
            if (!supported || !SelfArmLocalAdbBootstrapper.isBootstrapComplete(context)) {
                callback(Result.RESULT_WIFI_UNAVAILABLE)
                return@post
            }
            if (SelfArmWirelessAdbController.isWifiNetworkReady(context)) {
                if (!automatic) SelfArmBootRepairCoordinator.runOwnerRepair(context, onResult = callback)
                return@post
            }
            if (SelfArmBootRepairCoordinator.isRepairRunning() ||
                SelfArmOnboardingStore.currentActiveSessionId(context).isNotBlank() || GlassesHub.isCameraSessionActive()) {
                callback(Result.RESULT_BUSY)
                return@post
            }
            val prefs = context.getSharedPreferences("selfarm_wireless", Context.MODE_PRIVATE)
            if (automatic) {
                val attempted = prefs.getLong("hotspot_attempt_boot", 0)
                if (!SelfArmBootRepairStore.isAutoRepairEnabled(context) ||
                    context.getSystemService(PowerManager::class.java)?.isInteractive != true ||
                    !SelfArmBridgeLivenessStore.presumedDead(context) ||
                    (attempted != 0L && !SelfArmBridgeLivenessPolicy.presumedDead(attempted,
                        SelfArmBridgeLivenessStore.currentBootInstantMillis()))) return@post
                if (!prefs.edit().putLong("hotspot_attempt_boot",
                        SelfArmBridgeLivenessStore.currentBootInstantMillis()).commit()) return@post
            }
            val id = UUID.randomUUID().toString().replace("-", "")
            run = Run(context.applicationContext, id, automatic, callback)
            main.post { monitorTakeover(id) }
            main.postDelayed({ if (run?.id == id) finish(Result.RESULT_WIFI_UNAVAILABLE) }, Wire.TIMEOUT_MS)
            log("Hotspot recovery starting automatic=$automatic")
            val error = GlassesHub.sendRepairHotspot(BusEnvelope(path = Wire.PATH, id = id,
                payload = Wire.message("request").put("automatic", automatic)))
            if (error != null) finish(Result.RESULT_WIFI_UNAVAILABLE)
        }
        return true
    }

    private fun monitorTakeover(id: String) {
        val current = run?.takeIf { it.id == id } ?: return
        if (GlassesHub.isCameraSessionActive() ||
            SelfArmOnboardingStore.currentActiveSessionId(current.context).isNotBlank()) {
            finish(Result.RESULT_BUSY)
        } else main.postDelayed({ monitorTakeover(id) }, 500L)
    }

    fun onEnvelope(envelope: BusEnvelope) {
        if (envelope.binary != null || !Wire.validId(envelope.id)) return
        main.post {
            val current = run?.takeIf { it.id == envelope.id } ?: return@post
            when (Wire.kind(envelope.payload)) {
                "unavailable" -> finish(Result.RESULT_WIFI_UNAVAILABLE)
                "offer" -> {
                    if (current.credentials != null || current.activity != null) return@post
                    current.credentials = Wire.credentials(envelope.payload) ?: return@post
                    runCatching {
                        current.context.startActivity(Intent(current.context, RecoveryNetworkActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("recoveryId", current.id))
                    }.onFailure { finish(Result.RESULT_WIFI_UNAVAILABLE) }
                }
            }
        }
    }

    fun attach(activity: RecoveryNetworkActivity, id: String): Boolean {
        val current = run?.takeIf { it.id == id && it.activity == null } ?: return false
        current.activity = activity
        return true
    }

    fun prepareNetwork(id: String) {
        val current = run?.takeIf { it.id == id } ?: return
        if (!current.radioRequested) {
            current.radioRequested = true
            GlassesHub.acquireRecoveryWifi(current.context, id)
        }
        if (!SelfArmWirelessAdbController.isWifiEnabled(current.context)) {
            main.postDelayed({ prepareNetwork(id) }, 500L)
            return
        }
        val credentials = current.credentials ?: return
        current.credentials = null
        SelfArmHotspotConsent.begin(id, credentials.ssid)
        current.activity?.connect(credentials)
    }

    fun networkAvailable(id: String, network: Network) {
        val current = run?.takeIf { it.id == id } ?: return
        current.network = network
        SelfArmHotspotConsent.awaitDebugging(id)
        SelfArmWirelessAdbController.enableAdbWifi(current.context)
        log("Hotspot recovery network available")
        awaitPort(id)
    }

    fun ownsNetwork(context: Context): Boolean {
        val network = run?.network ?: return false
        return context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun awaitPort(id: String) {
        val current = run?.takeIf { it.id == id } ?: return
        if (GlassesHub.isCameraSessionActive() ||
            SelfArmOnboardingStore.currentActiveSessionId(current.context).isNotBlank()) {
            finish(Result.RESULT_BUSY)
            return
        }
        if (current.armStarted) return
        if (SelfArmWirelessAdbController.readWirelessPort() <= 0) {
            main.postDelayed({ awaitPort(id) }, 500L)
            return
        }
        current.armStarted = true
        val accepted = SelfArmController.ensureWatchdog(current.context,
            reason = SelfArmController.BRIDGE_LIVENESS_REASON_PREFIX + "hotspot_recovery",
            restartWatchdog = false, bindSetupSession = false) { result -> main.post {
                if (run?.id != id) return@post
                val armed = result == SelfArmWatchdogEnsureResult.READY
                val bridge = armed && SelfArmBridgeLivenessStore.armedBridgeRunning(current.context)
                finish(when {
                    bridge -> Result.RESULT_REPAIRED
                    armed -> Result.RESULT_PARTIAL
                    else -> Result.RESULT_ARM_FAILED
                })
            } }
        if (!accepted) {
            SelfArmController.runWhenIdle(bindSetupSession = false) { main.post {
                if (run?.id == id) {
                    current.armStarted = false
                    awaitPort(id)
                }
            } }
        }
    }

    fun abandoned(id: String) {
        if (run?.id == id) finish(Result.RESULT_WIFI_UNAVAILABLE)
    }

    fun activityDestroyed(id: String, activity: RecoveryNetworkActivity) {
        if (run?.id == id && run?.activity === activity) finish(Result.RESULT_WIFI_UNAVAILABLE)
    }

    fun onPeerLost() {
        main.post {
            supported = false
            opportunity?.let(main::removeCallbacks)
            opportunity = null
            finish(Result.RESULT_WIFI_UNAVAILABLE)
        }
    }

    private fun finish(result: String) {
        val current = run ?: return
        run = null
        SelfArmHotspotConsent.end(current.id)
        current.credentials = null
        current.activity?.releaseNetwork()
        current.activity?.finish()
        GlassesHub.sendRepairHotspot(BusEnvelope(path = Wire.PATH, id = current.id, payload = Wire.message("release")))
        GlassesHub.releaseRecoveryWifi(current.context)
        log("Hotspot recovery finished result=$result")
        current.callback(result)
    }
}
