package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PhoneHubCapabilities
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GlassesHub {
    private const val LOCAL_BINARY_MAX_BYTES = 512 * 1024

    data class LauncherEntry(
        val id: String,
        val displayName: String,
        val iconKey: String? = null,
    )

    private data class Registration(
        val clientId: String,
        val prefixes: List<String>,
        val uid: Int,
        val trusted: Boolean,
        val callbackBinder: IBinder,
        val callback: IBusCallback,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val started = AtomicBoolean(false)
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val launcherListeners = CopyOnWriteArrayList<(List<LauncherEntry>) -> Unit>()
    private val wifiOwnership = GlassesWifiOwnership()
    private val autoEnrollAttempted = AtomicBoolean(false)
    private val wifiRequestExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RokidNexusWifi").apply { isDaemon = true }
    }
    private var wifiDisableFuture: ScheduledFuture<*>? = null
    @Volatile private var launcherEntries: List<LauncherEntry> = emptyList()
    @Volatile private var appContext: Context? = null
    @Volatile private var cxrUp = false
    @Volatile private var phoneConnected = false
    @Volatile private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)

    private val aidl = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != Process.myUid() && !isDebuggableBuild()) {
                log("legacy client registration rejected status=release_external")
                return
            }
            val cleanPrefixes = pathPrefixes.mapNotNull(PathRules::normalizeAbsolute)
            if (cleanPrefixes.size != pathPrefixes.size) return
            if (callingUid != Process.myUid()) {
                log("legacy client registration allowed status=debug_compatibility")
            }
            addRegistration(clientId, cleanPrefixes, callingUid, cb)
        }

        override fun unregister(cb: IBusCallback) {
            removeRegistrationsByBinder(cb.asBinder())
        }

        override fun send(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json), Binder.getCallingUid())
        }

        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) {
            val json = runCatching { JSONObject(String(meta, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json, binary = data), Binder.getCallingUid())
        }

        override fun linkState(): Int = this@GlassesHub.linkState()

        override fun capabilities(): Int =
            remotePhoneCapabilities.features and
                (BusCapabilityBits.CAMERA_CONSUMER_READY or BusCapabilityBits.CAMERA_FROZEN_SPP)

        override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int =
            PluginRegistrationResult.DENIED
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (started.compareAndSet(false, true)) {
            log("Glasses hub starting")
            SppServerManager.ensureStarted(context.applicationContext)
            CxrBusBridge.start(context.applicationContext)
            // Rokid's firmware blocks MY_PACKAGE_REPLACED (and other manifest broadcasts)
            // to third-party apps, so BootReceiver cannot re-arm accessibility after an
            // update. Every process entry point funnels through here — including the
            // launcher's boot auto-open — making this the reliable re-arm hook.
            AccessibilityRearmWatcher.start(context.applicationContext, "hub_start")
        }
    }

    fun binder(context: Context): IBinder {
        start(context)
        return aidl
    }

    fun onSppConnected(connected: Boolean) {
        phoneConnected = connected || CxrBusBridge.isUp()
        if (!phoneConnected) clearRemotePhoneCapabilities()
        notifyLinkState()
        if (connected) announceRendererCapabilities()
    }

    fun onCxrState(connected: Boolean) {
        cxrUp = connected
        phoneConnected = connected || SppServerManager.isConnected()
        if (!phoneConnected) clearRemotePhoneCapabilities()
        notifyLinkState()
        if (connected) announceRendererCapabilities()
    }

    fun onRemoteEnvelope(envelope: BusEnvelope) {
        log("remote RX ${envelope.path} id=${envelope.id}")
        if (envelope.path == BusPaths.HUB_CAPABILITIES) {
            updateRemotePhoneCapabilities(envelope.payload)
            return
        }
        appContext?.let { context ->
            if (SurfaceController.handleSurfaceEnvelope(context, envelope)) return
        }
        if (envelope.path == BusPaths.LAUNCHER_LIST) {
            updateLauncherEntries(envelope.payload)
            return
        }
        if (deliverLocal(envelope)) return
        if (envelope.path == BusPaths.ERROR) {
            log("dropping undeliverable remote error id=${envelope.id}")
            return
        }
        if (envelope.binary != null) {
            log("dropping undeliverable binary ${envelope.path} id=${envelope.id}; no live registration")
            return
        }
        val context = appContext
        if (context != null && GlassesClientSupervisor.enqueue(context, envelope)) return
        sendRemote(errorEnvelope(envelope.id, "NO_LOCAL_CLIENT"))
    }

    fun deliverQueued(envelope: BusEnvelope): Boolean =
        deliverLocal(envelope)

    fun debugWake(context: Context, path: String): String {
        start(context)
        val envelope = BusEnvelope(path = path, payload = JSONObject().put("debugWake", true))
        return "wakeQueued=${GlassesClientSupervisor.enqueue(context.applicationContext, envelope)} path=$path"
    }

    fun debugPhoneWakeEcho(context: Context): String {
        start(context)
        val envelope = BusEnvelope(
            path = BusPaths.PROBE_ECHO,
            payload = JSONObject().put("message", "hello from glasses phone wake probe"),
        )
        val error = sendRemote(envelope)
        return if (error == null) {
            "phoneWakeEchoSent=true path=${BusPaths.PROBE_ECHO} id=${envelope.id}"
        } else {
            "phoneWakeEchoSent=false path=${BusPaths.PROBE_ECHO} id=${envelope.id} code=$error"
        }
    }

    fun observeLauncher(listener: (List<LauncherEntry>) -> Unit): () -> Unit {
        launcherListeners += listener
        listener(allLauncherEntries())
        return { launcherListeners.remove(listener) }
    }

    fun openLauncherEntry(pluginId: String): String {
        if (pluginId.isBlank()) return "launcherOpen=false reason=blank"
        if (pluginId == CAMERA_LAUNCHER_ID) {
            val context = appContext ?: return "launcherOpen=false reason=hub_not_started"
            return runCatching {
                context.startActivity(
                    Intent(context, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "launcherOpen=true pluginId=$pluginId"
            }.getOrElse { "launcherOpen=false pluginId=$pluginId code=ACTIVITY_START_FAILED" }
        }
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.LAUNCHER_OPEN,
                payload = JSONObject().put("pluginId", pluginId),
            ),
        )
        return if (error == null) {
            "launcherOpen=true pluginId=$pluginId"
        } else {
            "launcherOpen=false pluginId=$pluginId code=$error"
        }
    }

    fun sendSurfaceInput(payload: JSONObject): String? =
        sendRemote(BusEnvelope(path = BusPaths.SURFACE_INPUT, payload = payload))

    fun resendCapabilitiesNow() {
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (linkState() and transportBits != 0) announceRendererCapabilities()
    }

    private fun announceRendererCapabilities() {
        val context = appContext ?: return
        val capabilities = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.IMAGE_SURFACE,
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
            versionName = BuildConfig.VERSION_NAME,
            setupComplete = SelfArmOnboardingStateMachine.evaluate(
                SelfArmOnboardingStore.snapshot(context),
            ).stage == SelfArmOnboardingState.Stage.COMPLETE,
        )
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.HUB_CAPABILITIES,
                payload = GlassesHubCapabilitiesContract.toJson(capabilities),
            ),
        )
        if (error == null) {
            log("renderer capabilities announced imageVersion=${ImageSurfaceContract.VERSION}")
        } else {
            log("renderer capability announcement failed code=$error")
        }
    }

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        val allowed = senderUid == Process.myUid() ||
            (isDebuggableBuild() && registrations.any { it.uid == senderUid })
        if (!allowed) {
            log("local send rejected status=unregistered_or_release_external")
            return
        }
        if (BusPaths.isProtectedCameraPath(envelope.path) && !isTrustedUid(senderUid)) {
            log("blocked untrusted protected camera send uid=$senderUid")
            return
        }
        if (envelope.path == BusPaths.GLASSES_WIFI_REQUEST) {
            if (!isTrustedUid(senderUid)) {
                log("blocked untrusted glasses Wi-Fi request uid=$senderUid")
                return
            }
            val rawEnabled = envelope.payload.opt("enabled")
            if (rawEnabled !is Boolean) {
                log("glassesWifiRequest rejected reason=invalid_payload")
                return
            }
            val context = appContext
            if (context == null) {
                log("glassesWifiRequest enabled=$rawEnabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
                return
            }
            wifiRequestExecutor.execute {
                if (rawEnabled) {
                    wifiDisableFuture?.cancel(false)
                    wifiDisableFuture = null
                    handleGlassesWifiRequest(context, true)
                } else {
                    scheduleGlassesWifiDisable(context)
                }
            }
            return
        }
        if (deliverLocal(envelope, excludeUid = senderUid)) return
        val errorCode = sendRemote(envelope)
        if (errorCode != null) {
            deliverLocal(errorEnvelope(envelope.id, errorCode))
        }
    }

    private fun deliverLocal(envelope: BusEnvelope, excludeUid: Int? = null): Boolean {
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        val binary = envelope.binary
        var delivered = false
        registrations.forEach { registration ->
            if (excludeUid != null && registration.uid == excludeUid) return@forEach
            if (BusPaths.isProtectedCameraPath(envelope.path) && !registration.trusted) return@forEach
            if (registration.prefixes.any { PathRules.matchesPrefix(envelope.path, it) }) {
                if (binary != null && binary.size > LOCAL_BINARY_MAX_BYTES) {
                    log("drop local binary ${envelope.path} id=${envelope.id} bytes=${binary.size} over cap=$LOCAL_BINARY_MAX_BYTES")
                    delivered = true
                    return@forEach
                }
                runCatching {
                    if (binary == null) {
                        registration.callback.onMessage(envelope.path, envelope.id, payload)
                    } else {
                        registration.callback.onBinaryMessage(envelope.path, envelope.id, payload, binary)
                    }
                    delivered = true
                    GlassesClientSupervisor.touch()
                }.onFailure {
                    removeRegistration(registration)
                }
            }
        }
        return delivered
    }

    private fun addRegistration(
        clientId: String,
        prefixes: List<String>,
        uid: Int,
        callback: IBusCallback,
    ): Boolean {
        removeRegistrationsByBinder(callback.asBinder())
        val callbackBinder = callback.asBinder()
        val deathRecipient = IBinder.DeathRecipient { removeRegistrationsByBinder(callbackBinder) }
        if (runCatching { callbackBinder.linkToDeath(deathRecipient, 0) }.isFailure) return false
        registrations += Registration(
            clientId,
            prefixes,
            uid,
            isTrustedUid(uid),
            callbackBinder,
            callback,
            deathRecipient,
        )
        runCatching { callback.onLinkState(linkState()) }
        appContext?.let { GlassesClientSupervisor.onClientRegistered(it, prefixes) }
        return true
    }

    private fun removeRegistrationsByBinder(callbackBinder: IBinder) {
        registrations.filter { it.callbackBinder == callbackBinder }.forEach(::removeRegistration)
    }

    private fun removeRegistration(registration: Registration) {
        if (!registrations.remove(registration)) return
        runCatching { registration.callbackBinder.unlinkToDeath(registration.deathRecipient, 0) }
    }

    private fun isDebuggableBuild(): Boolean =
        appContext?.applicationInfo?.flags?.let { flags ->
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        } == true

    private fun updateLauncherEntries(payload: JSONObject) {
        val array = payload.optJSONArray("plugins") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item != null) {
                    val id = item.optString("id")
                    if (id.isNotBlank()) {
                        add(
                            LauncherEntry(
                                id = id,
                                displayName = item.optString("displayName", id),
                                iconKey = item.optString("iconKey").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }
        launcherEntries = entries
        notifyLauncherEntries()
        log("launcher list synced count=${entries.size}")
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        if (envelope.binary != null) {
            if (!SppServerManager.isConnected()) return "NO_DATA_PLANE"
            return if (SppServerManager.send(envelope)) null else "NO_DATA_PLANE"
        }
        val bytes = FrameProtocol.toJsonBytes(envelope)
        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && CxrBusBridge.isUp()) {
            if (CxrBusBridge.send(envelope)) return null
        }
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && !SppServerManager.isConnected()) {
            return "NO_DATA_PLANE"
        }
        if (SppServerManager.send(envelope)) return null
        return if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES) "NO_DATA_PLANE" else "NO_LINK"
    }

    private fun linkState(): Int {
        var state = 0
        if (cxrUp) state = state or LinkStateBits.CXR_CONTROL_UP
        if (SppServerManager.isConnected()) state = state or LinkStateBits.SPP_DATA_UP
        if (phoneConnected) state = state or LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED
        return state
    }

    private fun notifyLinkState() {
        val state = linkState()
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { removeRegistration(registration) }
        }
    }

    private fun updateRemotePhoneCapabilities(payload: JSONObject) {
        val advertised = PhoneHubCapabilitiesContract.parse(payload)
        val next = PhoneHubCapabilitiesContract.create(
            features = advertised.features and
                (BusCapabilityBits.CAMERA_CONSUMER_READY or BusCapabilityBits.CAMERA_FROZEN_SPP),
            cameraConsumerName = advertised.cameraConsumerName,
        )
        val previous = remotePhoneCapabilities
        if (next == previous) return
        remotePhoneCapabilities = next
        log("phone capabilities cameraConsumerReady=${next.features != 0}")
        if (next.features != previous.features) notifyLinkState()
        if (PhoneHubCapabilitiesContract.cameraLauncherLabel(next) !=
            PhoneHubCapabilitiesContract.cameraLauncherLabel(previous)
        ) {
            notifyLauncherEntries()
        }
    }

    private fun allLauncherEntries(): List<LauncherEntry> =
        listOf(
            LauncherEntry(
                CAMERA_LAUNCHER_ID,
                PhoneHubCapabilitiesContract.cameraLauncherLabel(remotePhoneCapabilities),
                iconKey = "lens",
            ),
        ) +
            launcherEntries.filterNot { it.id == CAMERA_LAUNCHER_ID }

    private fun notifyLauncherEntries() {
        val visibleEntries = allLauncherEntries()
        launcherListeners.forEach { listener ->
            runCatching { listener(visibleEntries) }
        }
    }

    private fun clearRemotePhoneCapabilities() {
        val previous = remotePhoneCapabilities
        remotePhoneCapabilities = PhoneHubCapabilities(0, null)
        if (PhoneHubCapabilitiesContract.cameraLauncherLabel(previous) !=
            PhoneHubCapabilitiesContract.DEFAULT_CAMERA_LABEL
        ) {
            notifyLauncherEntries()
        }
    }

    private fun errorEnvelope(id: String, code: String): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id),
        )

    private fun isTrustedUid(uid: Int): Boolean {
        val context = appContext ?: return false
        return context.packageManager.checkSignatures(uid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
    }

    private fun scheduleGlassesWifiDisable(context: Context) {
        if (!wifiOwnership.isHubOwned() || wifiDisableFuture != null) {
            log("glassesWifiGrace scheduled=false hubOwned=${wifiOwnership.isHubOwned()}")
            return
        }
        log("glassesWifiGrace scheduled=true delayMs=$WIFI_DISABLE_GRACE_MS")
        wifiDisableFuture = wifiRequestExecutor.schedule(
            {
                wifiDisableFuture = null
                handleGlassesWifiRequest(context, false)
            },
            WIFI_DISABLE_GRACE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun handleGlassesWifiRequest(context: Context, enabled: Boolean) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            log("glassesWifiRequest enabled=$enabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
            return
        }
        val wifiCurrentlyEnabled = runCatching { wifiManager.isWifiEnabled }
            .onFailure { logError("glassesWifiRequest state read failed", it) }
            .getOrNull()
        if (wifiCurrentlyEnabled == null) {
            log("glassesWifiRequest enabled=$enabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
            return
        }
        val result = wifiOwnership.handleRequest(
            enabled = enabled,
            wifiCurrentlyEnabled = wifiCurrentlyEnabled,
            setWifiEnabled = { requested ->
                val applied = runCatching { SelfArmController.setWifiEnabled(context, requested) }
                    .onFailure { logError("glassesWifiRequest shell failed", it) }
                    .getOrDefault(false)
                if (requested && !applied) attemptWifiAutoEnroll(context)
                applied
            },
        )
        log("glassesWifiRequest enabled=$enabled hubOwned=${result.hubOwned} applied=${result.applied}")
    }

    private fun attemptWifiAutoEnroll(context: Context) {
        if (SelfArmLocalAdbBootstrapper.isBootstrapComplete(context)) return
        if (!SelfArmOnboardingStore.isSetupRequested(context)) {
            log("glassesWifi auto-enroll skipped: wireless setup was not explicitly requested")
            return
        }
        val attempted = autoEnrollAttempted.compareAndSet(false, true)
        val serviceConnected = attempted && RokidBusAccessibilityService.requestWirelessBootstrap(context)
        log("glassesWifi auto-enroll attempted=$attempted serviceConnected=$serviceConnected")
    }

    private const val WIFI_DISABLE_GRACE_MS = 40_000L
    private const val CAMERA_LAUNCHER_ID = "camera"
}
