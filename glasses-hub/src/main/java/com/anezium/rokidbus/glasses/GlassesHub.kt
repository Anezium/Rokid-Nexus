package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.ui.NexusGlyphs
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.GlyphContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PhoneHubCapabilities
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.SetupNoteContract
import com.anezium.rokidbus.shared.SetupNoteMessage
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import com.anezium.rokidbus.shared.SetupStage
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
    internal const val LOHS_REVERSE_JOIN_TIMEOUT_MS = 15_000L

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
    private val pluginGlyphCache = PluginGlyphCache()
    private val wifiOwnership = GlassesWifiOwnership()
    // A lambda, not a method reference: the :camera process also loads this object, and a
    // reference would drag MediaSyncEngine's class init (and its executor thread) in with it.
    private val cameraSessionTracker = CameraSessionTracker { active ->
        MediaSyncEngine.onCameraSessionChanged(active)
    }
    private val autoEnrollAttempted = AtomicBoolean(false)
    private val wifiEnableA11yInFlight = AtomicBoolean(false)
    private val wifiEnableReleasePending = AtomicBoolean(false)
    private val wifiRequestExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RokidNexusWifi").apply { isDaemon = true }
    }
    private val setupCapabilitiesExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RokidNexusSetupCapabilities").apply { isDaemon = true }
    }
    private val setupCapabilitiesLock = Any()
    private var wifiDisableFuture: ScheduledFuture<*>? = null
    private var setupCapabilitiesFuture: ScheduledFuture<*>? = null
    private var manualSetupScreenLock: PowerManager.WakeLock? = null
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
            supportedPhoneCapabilities(remotePhoneCapabilities.features)

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
            MediaSyncEngine.start(context.applicationContext)
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
        if (envelope.path == BusPaths.GLASSES_SELFARM_MANUAL) {
            handleManualSelfArmRequest(envelope)
            return
        }
        if (envelope.path == BusPaths.GLASSES_SETUP_PAIRING_RESULT) {
            val validation = SetupPairingOfferContract.validateResult(envelope.payload)
            if (validation !is SetupPairingOfferContract.ResultValidationResult.Valid) {
                log("phone-assisted pairing result ignored reason=INVALID_RESULT")
                return
            }
            val dispatched = appContext?.let { context ->
                RokidBusAccessibilityService.onPhoneAssistedPairingResult(
                    context,
                    validation.result,
                )
            } == true
            if (!dispatched) {
                log("phone-assisted pairing result ignored reason=NO_LIVE_SERVICE")
            }
            return
        }
        MediaSyncEngine.trafficMonitor.note(envelope.path)
        if (envelope.path == BusPaths.MEDIA_SYNC_CONFIG) {
            MediaSyncEngine.onConfig(envelope.payload)
            return
        }
        if (envelope.path == BusPaths.MEDIA_SYNC_TRIGGER) {
            MediaSyncEngine.onTriggerRequest()
            return
        }
        if (BusPaths.isMediaSyncTransferPath(envelope.path)) {
            MediaSyncEngine.onTransferEnvelope(envelope.path, envelope.payload)
            return
        }
        if (PhoneBatteryController.handleEnvelope(envelope)) return
        appContext?.let { context ->
            if (PinController.handlePinEnvelope(envelope)) return
            if (NoticeController.handleNoticeEnvelope(envelope)) return
            if (ActivityController.handleActivityEnvelope(envelope)) return
            if (SurfaceController.handleSurfaceEnvelope(context, envelope)) return
        }
        if (envelope.path == BusPaths.LAUNCHER_LIST) {
            updateLauncherEntries(envelope.payload)
            return
        }
        if (envelope.path == BusPaths.LAUNCHER_GLYPHS) {
            updateLauncherGlyphs(envelope.payload)
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

    internal fun launcherEntryOpensSurface(pluginId: String): Boolean = pluginId != CAMERA_LAUNCHER_ID

    fun launcherDrawable(context: Context, entry: LauncherEntry): Drawable {
        NexusPluginIcons.drawableForBuiltIn(entry.iconKey)?.let { resourceId ->
            return requireNotNull(context.getDrawable(resourceId))
        }
        entry.iconKey?.let { iconKey ->
            pluginGlyphCache.drawableFor(entry.id, iconKey)?.let { return it }
        }
        return requireNotNull(
            context.getDrawable(NexusPluginIcons.drawableFor(entry.iconKey, entry.id)),
        )
    }

    /**
     * Activity verbs use the platform vocabulary first, then the owner's
     * registered glyphs, then the required `dot` fallback.
     */
    internal fun activityGlyphDrawable(
        context: Context,
        ownerPluginId: String,
        glyph: String,
    ): Drawable {
        if (NexusGlyphs.isBuiltIn(glyph)) {
            return requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(glyph)))
        }
        pluginGlyphCache.drawableFor(ownerPluginId, glyph)?.let { return it }
        return requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(null)))
    }

    fun sendSurfaceInput(payload: JSONObject): String? =
        sendRemote(BusEnvelope(path = BusPaths.SURFACE_INPUT, payload = payload))

    fun resendCapabilitiesNow() {
        synchronized(setupCapabilitiesLock) {
            setupCapabilitiesFuture?.cancel(false)
            setupCapabilitiesFuture = null
        }
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (linkState() and transportBits != 0) announceRendererCapabilities()
    }

    /**
     * Sends one note to the phone, where the setup journal keeps it.
     *
     * Fire and forget: a note is a diagnostic, and losing one to a link that happens to be down is
     * strictly better than making setup wait on it or retry it.
     */
    internal fun onSetupNote(message: SetupNoteMessage) {
        runCatching {
            sendRemote(
                BusEnvelope(
                    path = BusPaths.GLASSES_SETUP_NOTE,
                    payload = SetupNoteContract.toJson(message),
                ),
            )
        }
    }

    internal fun onSetupProgressChanged(reportedStage: String?) {
        val stage = SetupStage.normalize(reportedStage).ifBlank {
            appContext?.let { context -> SelfArmOnboardingStore.snapshot(context).stage }.orEmpty()
        }
        if (SetupStage.isTerminal(stage)) {
            resendCapabilitiesNow()
            return
        }
        synchronized(setupCapabilitiesLock) {
            setupCapabilitiesFuture?.cancel(false)
            setupCapabilitiesFuture = setupCapabilitiesExecutor.schedule(
                {
                    synchronized(setupCapabilitiesLock) {
                        setupCapabilitiesFuture = null
                    }
                    val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
                    if (linkState() and transportBits != 0) announceRendererCapabilities()
                },
                SETUP_CAPABILITIES_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun announceRendererCapabilities() {
        val context = appContext ?: return
        val onboarding = SelfArmOnboardingStore.snapshot(context)
        val onboardingState = SelfArmOnboardingStateMachine.evaluate(onboarding)
        val setupStage = when (onboardingState.stage) {
            SelfArmOnboardingState.Stage.COMPLETE -> SetupStage.COMPLETE
            SelfArmOnboardingState.Stage.FAILED -> SetupStage.FAILED
            SelfArmOnboardingState.Stage.MANUAL_REQUIRED -> SetupStage.MANUAL_REQUIRED
            SelfArmOnboardingState.Stage.WAITING_FOR_WIFI -> SetupStage.WAITING_FOR_WIFI
            SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY -> SetupStage.WAITING_FOR_ACCESSIBILITY
            else -> SetupStage.normalize(onboarding.stage)
        }
        val capabilities = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.IMAGE_SURFACE or
                BusCapabilityBits.PIN_SURFACE or
                BusCapabilityBits.NOTICE_SURFACE or
                BusCapabilityBits.ACTIVITY_SURFACE,
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            pinSurfaceVersion = PinSurfaceContract.VERSION,
            noticeSurfaceVersion = NoticeSurfaceContract.VERSION,
            activitySurfaceVersion = ActivitySurfaceContract.VERSION,
            maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
            versionName = BuildConfig.VERSION_NAME,
            setupComplete = onboardingState.stage == SelfArmOnboardingState.Stage.COMPLETE,
            setupFailureState = onboarding.failureState,
            setupFailureDiagnostic = onboarding.failureDiagnostic,
            setupSessionId = onboarding.sessionId,
            setupStage = setupStage,
            setupRunning = onboarding.setupRunning,
            setupRequiresUserAction = SetupStage.requiresUserAction(setupStage),
            setupSupportCode = GlassesHubCapabilitiesContract.deriveSetupSupportCode(
                onboarding.sessionId,
            ),
            setupCompletionMode = onboarding.completionMode,
            coreReady = onboarding.coreReady,
            maintenanceReady = onboarding.maintenanceReady,
        )
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.HUB_CAPABILITIES,
                payload = GlassesHubCapabilitiesContract.toJson(capabilities),
            ),
        )
        if (error == null) {
            log(
                "renderer capabilities announced imageVersion=${ImageSurfaceContract.VERSION} " +
                    "pinVersion=${PinSurfaceContract.VERSION} " +
                    "activityVersion=${ActivitySurfaceContract.VERSION}",
            )
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
        if (BusPaths.isProtectedMediaSyncPath(envelope.path) && !isTrustedUid(senderUid)) {
            log("blocked untrusted protected media sync send uid=$senderUid")
            return
        }
        if (envelope.path == BusPaths.CAMERA_SESSION_STATE) {
            // The camera session lives in the :camera process; this envelope is the only way the
            // main process can know a session is live, which photo sync must never fight.
            cameraSessionTracker.onSessionState(
                envelope.payload.optString("sessionId"),
                envelope.payload.optString("state"),
            )
        }
        if (envelope.path == BusPaths.GLASSES_SELFARM_MANUAL) {
            if (!isTrustedUid(senderUid)) {
                log("blocked untrusted manual self-arm request uid=$senderUid")
                return
            }
            handleManualSelfArmRequest(envelope)
            return
        }
        if (envelope.path == BusPaths.GLASSES_WIFI_REQUEST) {
            if (!isTrustedUid(senderUid)) {
                log("blocked untrusted glasses Wi-Fi request uid=$senderUid")
                return
            }
            if (envelope.payload.optString("action") == "join") {
                val ssid = envelope.payload.optString("ssid")
                val passphrase = envelope.payload.optString("passphrase")
                val security = WifiConnectSecurity.fromCommandKeyword(
                    envelope.payload.optString("security", WifiConnectSecurity.WPA2.commandKeyword),
                )
                if (ssid.isBlank() || ssid.length > 128 || security == null ||
                    !security.isValidPassphrase(passphrase)
                ) {
                    log("glassesWifiJoin rejected reason=invalid_payload")
                    return
                }
                val context = appContext
                if (context == null) {
                    log("glassesWifiJoin applied=false reason=no_context")
                    return
                }
                wifiRequestExecutor.execute {
                    wifiEnableReleasePending.set(false)
                    wifiDisableFuture?.cancel(false)
                    wifiDisableFuture = null
                    handleGlassesWifiRequest(context, true)
                    val applied = runCatching {
                        SelfArmCommandBridgeClient.connectWifiNetwork(
                            context,
                            ssid,
                            passphrase,
                            security,
                            timeoutMs = LOHS_REVERSE_JOIN_TIMEOUT_MS,
                        )
                    }.onFailure {
                        logError("glassesWifiJoin bridge failed", it)
                    }.getOrDefault(false)
                    log("glassesWifiJoin applied=$applied")
                }
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
                    wifiEnableReleasePending.set(false)
                    wifiDisableFuture?.cancel(false)
                    wifiDisableFuture = null
                    handleGlassesWifiRequest(context, true)
                } else {
                    if (!deferWifiDisableUntilAccessibilityEnableCompletes()) {
                        scheduleGlassesWifiDisable(context)
                    }
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

    private fun handleManualSelfArmRequest(envelope: BusEnvelope) {
        val action = SelfArmManualAction.fromWireValue(envelope.payload.optString("action"))
        if (action == null) {
            log("manual self-arm request rejected reason=invalid_action")
            sendRemote(errorEnvelope(envelope.id, "INVALID_ACTION"))
            return
        }
        if (action == SelfArmManualAction.OPEN_ACCESSIBILITY_SETTINGS) {
            handleOpenAccessibilitySettings(envelope, action)
            return
        }
        val armed = action == SelfArmManualAction.CLOSE && envelope.payload.optBoolean("armed", false)
        val context = appContext
        // Reading a pairing code off the glasses and typing it on the phone takes longer than the
        // screen stays on, and the display going dark dismisses the pairing dialog — which cancels
        // the pairing the wearer was halfway through. Hold the screen for the manual flow, and give
        // it back the moment the flow closes.
        if (context != null) {
            when (action) {
                SelfArmManualAction.CLOSE -> releaseManualSetupScreen()
                else -> holdManualSetupScreen(context)
            }
        }
        if (context != null && action.requiresDeveloperOptions() &&
            !SelfArmWirelessAdbController.areDeveloperOptionsUsable(context)
        ) {
            log("manual self-arm action=${action.wireValue} rejected reason=developer_options_disabled")
            sendRemote(errorEnvelope(envelope.id, "DEVELOPER_OPTIONS_DISABLED"))
            return
        }
        val accepted = context != null && RokidBusAccessibilityService.requestManualAction(
            context,
            action,
            armed,
        ) { completed ->
            log("manual self-arm action=${action.wireValue} completed=$completed armed=$armed")
            if (action == SelfArmManualAction.CLOSE) return@requestManualAction
            if (!completed) {
                val code = if (action == SelfArmManualAction.ENABLE_DEVELOPER_OPTIONS) {
                    "DEVELOPER_OPTIONS_ENABLE_FAILED"
                } else if (
                    action == SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ||
                    action == SelfArmManualAction.OPEN_PAIRING_DIALOG
                ) {
                    "WIRELESS_DEBUGGING_UNAVAILABLE"
                } else {
                    "SETTINGS_UNAVAILABLE"
                }
                sendRemote(errorEnvelope(envelope.id, code))
                return@requestManualAction
            }
            sendRemote(
                BusEnvelope(
                    path = BusPaths.GLASSES_SELFARM_MANUAL_REPLY,
                    id = envelope.id,
                    payload = JSONObject()
                        .put("version", 1)
                        .put("action", action.wireValue)
                        .put("accepted", true)
                        // The phone otherwise has to find this port by mDNS, and a router that
                        // does not forward multicast makes the whole manual setup fail after a
                        // perfectly good pairing. We already know it here; send it.
                        .putOpt("connectPort", wirelessConnectPort()),
                ),
            )
        }
        log("manual self-arm action=${action.wireValue} accepted=$accepted armed=$armed")
        if (!accepted) {
            sendRemote(errorEnvelope(envelope.id, "ACCESSIBILITY_UNAVAILABLE"))
            return
        }
        // Completion (and therefore acknowledgement) is asynchronous for the six-tap action.
    }

    /** The live Wireless Debugging connect port, or null when the daemon is not listening. */
    private fun wirelessConnectPort(): Int? =
        SelfArmWirelessAdbController.readWirelessPort().takeIf { it > 0 }

    private fun SelfArmManualAction.requiresDeveloperOptions(): Boolean =
        this == SelfArmManualAction.OPEN_DEVELOPER_OPTIONS ||
            this == SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ||
            this == SelfArmManualAction.OPEN_PAIRING_DIALOG

    /**
     * Opens the Accessibility settings screen without going through the AccessibilityService: the
     * whole point of this action is to let the user grant accessibility when it is still off.
     */
    private fun handleOpenAccessibilitySettings(envelope: BusEnvelope, action: SelfArmManualAction) {
        val context = appContext
        if (context == null) {
            sendRemote(errorEnvelope(envelope.id, "SETTINGS_UNAVAILABLE"))
            return
        }
        if (!RokidBusAccessibilityService.isLive()) {
            // Bring the glasses back to Nexus the moment the user flips the toggle. Skipped when
            // the service is already connected so the flag cannot go stale and fire on a later
            // service reconnect.
            SelfArmOnboardingStore.markAwaitingAccessibility(context)
        }
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        log("manual self-arm action=${action.wireValue} opened=$opened")
        if (!opened) {
            sendRemote(errorEnvelope(envelope.id, "SETTINGS_UNAVAILABLE"))
            return
        }
        sendRemote(
            BusEnvelope(
                path = BusPaths.GLASSES_SELFARM_MANUAL_REPLY,
                id = envelope.id,
                payload = JSONObject()
                    .put("version", 1)
                    .put("action", action.wireValue)
                    .put("accepted", true),
            ),
        )
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

    private fun updateLauncherGlyphs(payload: JSONObject) {
        val pluginId = payload.optString("pluginId")
        if (pluginId.isBlank()) {
            log("launcher glyphs rejected reason=INVALID_PLUGIN_ID")
            return
        }
        val array = payload.optJSONArray("glyphs")
        if (array == null) {
            log("launcher glyphs rejected id=$pluginId reason=INVALID_GLYPH_ARRAY")
            return
        }
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    log("launcher glyphs rejected id=$pluginId reason=INVALID_GLYPH_ENTRY")
                    return
                }
                add("${item.optString("name")}|${item.optString("pathData")}")
            }
        }
        when (val result = GlyphContract.parse(entries)) {
            is GlyphContract.ParseResult.Valid -> {
                pluginGlyphCache.put(pluginId, result.glyphs)
                notifyLauncherEntries()
                log("launcher glyphs synced id=$pluginId count=${result.glyphs.size}")
            }
            is GlyphContract.ParseResult.Invalid -> {
                log("launcher glyphs rejected id=$pluginId reason=${result.reason}")
            }
        }
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        // Everything that is not our own bulk transfer buys the link a quiet window.
        MediaSyncEngine.trafficMonitor.note(envelope.path)
        if (envelope.binary != null) {
            if (!SppServerManager.isConnected()) return "NO_DATA_PLANE"
            return if (SppServerManager.send(envelope)) null else "NO_DATA_PLANE"
        }
        val bytes = FrameProtocol.toJsonBytes(envelope)
        GlassesOutboundTransportPolicy.order(
            sppConnected = SppServerManager.isConnected(),
            cxrUp = CxrBusBridge.isUp(),
            payloadBytes = bytes.size,
        ).forEach { transport ->
            val sent = when (transport) {
                GlassesOutboundTransport.SPP -> SppServerManager.send(envelope)
                GlassesOutboundTransport.CXR -> CxrBusBridge.send(envelope)
            }
            if (sent) return null
        }
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
        MediaSyncEngine.onLinkStateChanged(phoneConnected)
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { removeRegistration(registration) }
        }
    }

    /**
     * Hub-to-hub send for in-process features; returns true when the envelope reached a transport.
     * A non-null [binary] forces SPP, which is the only transport that carries bytes.
     */
    internal fun sendToPhone(
        path: String,
        payload: JSONObject,
        binary: ByteArray? = null,
    ): Boolean = sendRemote(BusEnvelope(path = path, payload = payload, binary = binary)) == null

    internal fun isCameraSessionActive(): Boolean = cameraSessionTracker.isActive()

    internal fun supportsPhoneAssistedSetup(): Boolean =
        supportsPhoneAssistedSetup(remotePhoneCapabilities.features)

    /**
     * Keeps the glasses display awake for the manual setup flow. Bounded by a timeout as well as by
     * the closing action, so a flow abandoned halfway can never leave the screen on for good.
     */
    private fun holdManualSetupScreen(context: Context) {
        if (manualSetupScreenLock?.isHeld == true) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        manualSetupScreenLock = runCatching {
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "RokidNexus:manualSetup",
            ).apply { acquire(MANUAL_SETUP_SCREEN_TIMEOUT_MS) }
        }.onFailure { logError("manual setup screen hold failed", it) }.getOrNull()
    }

    private fun releaseManualSetupScreen() {
        val lock = manualSetupScreenLock ?: return
        manualSetupScreenLock = null
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { logError("manual setup screen release failed", it) }
    }

    /**
     * Releases a camera session the `:camera` process never closed — it crashed or was
     * force-stopped. Callers must have established that the process is genuinely gone; this only
     * clears the main process' belief and never reaches into `:camera`.
     */
    internal fun resetCameraSession() {
        if (cameraSessionTracker.reset()) log("camera session reset: :camera process is gone")
    }

    private fun updateRemotePhoneCapabilities(payload: JSONObject) {
        val advertised = PhoneHubCapabilitiesContract.parse(payload)
        val next = PhoneHubCapabilitiesContract.create(
            features = supportedPhoneCapabilities(advertised.features),
            cameraConsumerName = advertised.cameraConsumerName,
            activityAlwaysExpanded = advertised.activityAlwaysExpanded,
        )
        appContext?.let {
            ActivityController.setAlwaysExpanded(it, next.activityAlwaysExpanded)
        }
        val previous = remotePhoneCapabilities
        if (next == previous) return
        remotePhoneCapabilities = next
        log(
            "phone capabilities cameraConsumerReady=" +
                (next.features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0) +
                " phoneAssistedSetup=${supportsPhoneAssistedSetup(next.features)}",
        )
        if (next.features != previous.features) notifyLinkState()
        if (cameraLauncherEntry(next) != cameraLauncherEntry(previous)) notifyLauncherEntries()
    }

    private fun allLauncherEntries(): List<LauncherEntry> =
        listOfNotNull(cameraLauncherEntry(remotePhoneCapabilities)) +
            launcherEntries.filterNot { it.id == CAMERA_LAUNCHER_ID }

    private fun cameraLauncherEntry(capabilities: PhoneHubCapabilities): LauncherEntry? {
        val ready = capabilities.features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        val consumerName = capabilities.cameraConsumerName
        return if (ready && consumerName != null) {
            LauncherEntry(CAMERA_LAUNCHER_ID, consumerName, iconKey = "lens")
        } else {
            null
        }
    }

    private fun notifyLauncherEntries() {
        val visibleEntries = allLauncherEntries()
        launcherListeners.forEach { listener ->
            runCatching { listener(visibleEntries) }
        }
    }

    private fun clearRemotePhoneCapabilities() {
        val previous = remotePhoneCapabilities
        remotePhoneCapabilities = PhoneHubCapabilities(0, null)
        if (cameraLauncherEntry(previous) != null) notifyLauncherEntries()
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
        if (wifiCurrentlyEnabled && wifiEnableA11yInFlight.getAndSet(false)) {
            wifiOwnership.markEnabledByHub()
            log("glassesWifi a11y enable observed=true")
        }
        val result = wifiOwnership.handleRequest(
            enabled = enabled,
            wifiCurrentlyEnabled = wifiCurrentlyEnabled,
            setWifiEnabled = { requested ->
                val applied = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, requested) }
                    .onFailure { logError("glassesWifiRequest bridge failed", it) }
                    .getOrDefault(false)
                // Camera-owned Wi-Fi acquisition must not start the onboarding-only
                // wireless-debugging bootstrap; it gets only the Wi-Fi toggle mode.
                if (requested && !applied) attemptWifiAccessibilityEnable(context)
                applied
            },
        )
        log("glassesWifiRequest enabled=$enabled hubOwned=${result.hubOwned} applied=${result.applied}")
    }

    private fun attemptWifiAccessibilityEnable(context: Context) {
        val attempted = wifiEnableA11yInFlight.compareAndSet(false, true)
        val serviceConnected = attempted && RokidBusAccessibilityService.requestWifiEnable(context)
        if (attempted && !serviceConnected) wifiEnableA11yInFlight.set(false)
        log("glassesWifi a11y-enable attempted=$attempted serviceConnected=$serviceConnected")
    }

    private fun deferWifiDisableUntilAccessibilityEnableCompletes(): Boolean {
        wifiEnableReleasePending.set(true)
        if (wifiEnableA11yInFlight.get()) {
            log("glassesWifi a11y-enable release deferred=true")
            return true
        }
        wifiEnableReleasePending.set(false)
        return false
    }

    internal fun onWifiEnableAutomationFinished(enabled: Boolean) {
        val requested = wifiEnableA11yInFlight.getAndSet(false)
        val releasePending = wifiEnableReleasePending.getAndSet(false)
        if (enabled && requested) wifiOwnership.markEnabledByHub()
        if (enabled && requested && releasePending) {
            appContext?.let { context ->
                wifiRequestExecutor.execute { scheduleGlassesWifiDisable(context) }
            }
        }
        log(
            "glassesWifi a11y-enable completed requested=$requested " +
                "enabled=$enabled releasePending=$releasePending",
        )
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
    private const val SETUP_CAPABILITIES_DEBOUNCE_MS = 250L
    /** Long enough to read a code and type it on the phone; short enough to never strand the screen. */
    private const val MANUAL_SETUP_SCREEN_TIMEOUT_MS = 5 * 60_000L
    private const val CAMERA_LAUNCHER_ID = "camera"
}
