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
import com.anezium.rokidbus.shared.GlassesRepairContract
import com.anezium.rokidbus.shared.GlyphContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.ink.InkWire
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.PhoneHubCapabilities
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteNavigationContract
import com.anezium.rokidbus.shared.RemotePointerContract
import com.anezium.rokidbus.shared.SetupNoteContract
import com.anezium.rokidbus.shared.SetupNoteMessage
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import com.anezium.rokidbus.shared.SetupStage
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbContract
import com.anezium.rokidbus.shared.WirelessAdbReply
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MANUAL_CONNECT_PORT_WAIT_MS = 2_000L

internal fun SelfArmManualAction.connectPortWaitTimeoutMs(): Long =
    if (this == SelfArmManualAction.OPEN_WIRELESS_DEBUGGING ||
        this == SelfArmManualAction.OPEN_PAIRING_DIALOG
    ) {
        MANUAL_CONNECT_PORT_WAIT_MS
    } else {
        0L
    }

object GlassesHub {
    private const val LOCAL_BINARY_MAX_BYTES = 512 * 1024
    private const val WIRELESS_ADB_REQUEST_SLOTS = 2
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
    @Volatile private var wifiOwnership: GlassesWifiOwnership? = null
    // A lambda, not a method reference: the :camera process also loads this object, and a
    // reference would drag MediaSyncEngine's class init (and its executor thread) in with it.
    private val cameraSessionTracker = CameraSessionTracker { active ->
        MediaSyncEngine.onCameraSessionChanged(active)
        appContext?.let { context ->
            wifiRequestExecutor.execute {
                if (active) {
                    cancelPendingWifiDisable("camera_session_opened")
                } else {
                    reconcileWifiOwnership(
                        context = context,
                        trigger = "camera_session_closed",
                        cameraGraceRequested = true,
                    )
                }
            }
        }
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
    private val wirelessAdbExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusWirelessAdb").apply { isDaemon = true }
    }
    private val manualConnectPortReporter = SelfArmConnectPortReporter(
        waitForPort = { timeoutMs ->
            SelfArmWirelessAdbController.waitForWirelessPort(timeoutMs)
        },
    )
    private val wirelessAdbSlots = Semaphore(WIRELESS_ADB_REQUEST_SLOTS, true)
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
            removeRegistrationsByBinder(cb.asBinder(), "client_unregister")
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

        /** Nothing is ever approved here, since [registerPlugin] denies everything. */
        override fun approvedCapabilities(pluginId: String): String = ""
    }

    fun start(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        RemoteInputImeProvisioner.ensureConfigured(applicationContext)
        RemoteInputHubBridge.initialize { path, payload ->
            sendRemote(BusEnvelope(path = path, payload = payload)) == null
        }
        RemotePointerHubBridge.initialize { path, payload ->
            sendRemote(BusEnvelope(path = path, payload = payload)) == null
        }
        if (wifiOwnership == null) {
            synchronized(this) {
                if (wifiOwnership == null) {
                    wifiOwnership = GlassesWifiOwnership(GlassesWifiLeaseStore(applicationContext))
                }
            }
        }
        if (started.compareAndSet(false, true)) {
            log("Glasses hub starting")
            wirelessAdbExecutor.execute {
                WirelessAdbController.restorePairingExpiry(applicationContext)
            }
            SppServerManager.ensureStarted(context.applicationContext)
            CxrBusBridge.start(context.applicationContext)
            // Rokid's firmware blocks MY_PACKAGE_REPLACED (and other manifest broadcasts)
            // to third-party apps, so BootReceiver cannot re-arm accessibility after an
            // update. Every process entry point funnels through here — including the
            // launcher's boot auto-open — making this the reliable re-arm hook.
            AccessibilityRearmWatcher.start(context.applicationContext, "hub_start")
            MediaSyncEngine.start(context.applicationContext)
            requestWifiOwnershipReconciliation(applicationContext, "hub_start")
        }
    }

    fun binder(context: Context): IBinder {
        start(context)
        return aidl
    }

    fun onSppConnected(connected: Boolean) {
        phoneConnected = connected || CxrBusBridge.isUp()
        if (!phoneConnected) {
            clearRemotePhoneCapabilities()
            AssistantDisplayEpisode.end(DisplayHoldReleaseReason.LINK_LOSS)
            RemotePointerHubBridge.onLinkLost()
            SurfaceController.onPhoneLinkLost()
            NoticeController.onPhoneLinkLost()
        }
        notifyLinkState()
        if (connected) {
            TtsController.onPhoneLinkAvailable()
            announceRendererCapabilities()
            RemoteInputHubBridge.onLinkAvailable()
        }
    }

    fun onCxrState(connected: Boolean) {
        cxrUp = connected
        phoneConnected = connected || SppServerManager.isConnected()
        if (!phoneConnected) {
            clearRemotePhoneCapabilities()
            AssistantDisplayEpisode.end(DisplayHoldReleaseReason.LINK_LOSS)
            RemotePointerHubBridge.onLinkLost()
            SurfaceController.onPhoneLinkLost()
            NoticeController.onPhoneLinkLost()
        }
        notifyLinkState()
        if (connected) {
            TtsController.onPhoneLinkAvailable()
            announceRendererCapabilities()
            RemoteInputHubBridge.onLinkAvailable()
        }
    }

    fun onRemoteEnvelope(envelope: BusEnvelope) {
        log("remote RX ${envelope.path} id=${envelope.id}")
        if (envelope.path == RemoteInputContract.COMMAND_PATH ||
            envelope.path == RemoteNavigationContract.REQUEST_PATH
        ) {
            val handled = envelope.binary == null &&
                RemoteInputHubBridge.handle(envelope.path, envelope.payload)
            if (!handled) sendRemote(errorEnvelope(envelope.id, "INVALID_CORE_REQUEST"))
            return
        }
        if (envelope.path == RemotePointerContract.COMMAND_PATH) {
            val handled = envelope.binary == null && RemotePointerHubBridge.handle(envelope.payload)
            if (!handled) sendRemote(errorEnvelope(envelope.id, "INVALID_CORE_REQUEST"))
            return
        }
        if (envelope.path == NativeAppContract.REQUEST_PATH) {
            val context = appContext
            val handled = context != null && envelope.binary == null &&
                NativeAppsController.handle(context, envelope.payload) { payload ->
                    sendRemote(
                        BusEnvelope(
                            path = NativeAppContract.RESULT_PATH,
                            id = envelope.id,
                            payload = payload,
                        ),
                    ) == null
                }
            if (!handled) sendRemote(errorEnvelope(envelope.id, "INVALID_NATIVE_APP_REQUEST"))
            return
        }
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
        if (envelope.path == BusPaths.GLASSES_ASSISTANT_DISMISS) {
            val serviceConnected = RokidBusAccessibilityService.requestNativeAssistantDismiss()
            log("native assistant dismiss armed serviceConnected=$serviceConnected")
            return
        }
        if (envelope.path == BusPaths.GLASSES_REPAIR_CONFIG) {
            val context = appContext
            val enabled = GlassesRepairContract.autoRepairFromConfig(envelope.payload)
            if (context == null || enabled == null) {
                log("glassesRepairConfig ignored reason=invalid_payload_or_no_context")
                return
            }
            SelfArmBootRepairStore.setAutoRepairEnabled(context, enabled)
            log("glassesRepairConfig autoRepair=$enabled")
            return
        }
        if (envelope.path == BusPaths.GLASSES_REPAIR_REQUEST) {
            val context = appContext
            if (context == null) {
                sendRemote(errorEnvelope(envelope.id, "HUB_NOT_READY"))
                return
            }
            SelfArmBootRepairCoordinator.runOwnerRepair(context) { result ->
                val error = sendRemote(
                    BusEnvelope(
                        path = BusPaths.GLASSES_REPAIR_REPLY,
                        id = envelope.id,
                        payload = GlassesRepairContract.replyToJson(result),
                    ),
                )
                log("glassesRepair result=$result replyError=${error ?: "none"}")
            }
            return
        }
        if (envelope.path == BusPaths.WIRELESS_ADB_REQUEST) {
            handleWirelessAdbRequest(envelope)
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
            if (TtsController.handleEnvelope(context, envelope)) return
            if (PinController.handlePinEnvelope(envelope)) return
            if (NoticeController.handleNoticeEnvelope(context, envelope)) return
            if (ActivityController.handleActivityEnvelope(context, envelope)) return
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

    private fun handleWirelessAdbRequest(envelope: BusEnvelope) {
        val pluginId = WirelessAdbContract.pluginId(envelope.payload)
        val action = WirelessAdbContract.requestAction(envelope.payload)
        if (pluginId == null || action == null || envelope.binary != null) {
            log("wireless ADB request rejected reason=INVALID_REQUEST")
            if (pluginId != null) {
                sendWirelessAdbReply(
                    envelope.id,
                    pluginId,
                    WirelessAdbReply(
                        action = action ?: WirelessAdbAction.STATUS,
                        success = false,
                        wifiConnected = false,
                        enabled = false,
                        pairingActive = false,
                        errorCode = "INVALID_REQUEST",
                        message = "The wireless debugging request was invalid.",
                    ),
                )
            }
            return
        }
        val context = appContext
        if (context == null) {
            sendWirelessAdbReply(
                envelope.id,
                pluginId,
                WirelessAdbReply(
                    action = action,
                    success = false,
                    wifiConnected = false,
                    enabled = false,
                    pairingActive = false,
                    errorCode = "HUB_NOT_READY",
                    message = "The glasses hub is not ready.",
                ),
            )
            return
        }
        if (!wirelessAdbSlots.tryAcquire()) {
            sendWirelessAdbReply(
                envelope.id,
                pluginId,
                WirelessAdbReply(
                    action = action,
                    success = false,
                    wifiConnected = SelfArmWirelessAdbController.isWifiNetworkReady(context),
                    enabled = SelfArmWirelessAdbController.readWirelessPort() > 0 &&
                        SelfArmWirelessAdbController.isEnabled(context),
                    pairingActive = WirelessAdbController.isPairingActive(),
                    errorCode = "WIRELESS_ADB_BUSY",
                    message = "Another wireless debugging request is still running.",
                ),
            )
            return
        }
        wirelessAdbExecutor.execute {
            try {
                val result = runCatching {
                    WirelessAdbController.handle(context.applicationContext, action)
                }.getOrElse { failure ->
                    log("wireless ADB action=${action.wireValue} failed type=${failure.javaClass.simpleName}")
                    WirelessAdbReply(
                        action = action,
                        success = false,
                        wifiConnected = SelfArmWirelessAdbController.isWifiNetworkReady(context),
                        enabled = SelfArmWirelessAdbController.readWirelessPort() > 0 &&
                            SelfArmWirelessAdbController.isEnabled(context),
                        pairingActive = WirelessAdbController.isPairingActive(),
                        errorCode = "INTERNAL_ERROR",
                        message = "Wireless debugging failed unexpectedly.",
                    )
                }
                sendWirelessAdbReply(envelope.id, pluginId, result)
                log(
                    "wireless ADB action=${action.wireValue} success=${result.success} " +
                        "code=${result.errorCode ?: "none"}",
                )
            } finally {
                wirelessAdbSlots.release()
            }
        }
    }

    private fun sendWirelessAdbReply(requestId: String, pluginId: String, reply: WirelessAdbReply) {
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.WIRELESS_ADB_REPLY,
                id = requestId,
                payload = WirelessAdbContract.reply(pluginId, reply),
            ),
        )
        if (error != null) log("wireless ADB reply failed code=$error")
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

    fun sendInkEvent(payload: JSONObject): String? =
        sendRemote(BusEnvelope(path = BusPaths.INK_EVENT, payload = payload))

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
        val ttsAvailable = TtsController.isServiceAvailable(context)
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
                BusCapabilityBits.NOTICE_TEXT_INPUT or
                BusCapabilityBits.ACTIVITY_SURFACE or
                BusCapabilityBits.INK_SURFACE or
                (if (ttsAvailable) BusCapabilityBits.TTS else 0),
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            pinSurfaceVersion = PinSurfaceContract.VERSION,
            noticeSurfaceVersion = NoticeSurfaceContract.VERSION,
            activitySurfaceVersion = ActivitySurfaceContract.VERSION,
            inkSurfaceVersion = InkWire.VERSION,
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
            ttsVersion = if (ttsAvailable) TtsContract.VERSION else 0,
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
                    "activityVersion=${ActivitySurfaceContract.VERSION} " +
                    "inkVersion=${InkWire.VERSION} " +
                    "ttsVersion=${if (ttsAvailable) TtsContract.VERSION else 0}",
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
                val sessionId = envelope.payload.optString("sessionId")
                val ssid = envelope.payload.optString("ssid")
                val passphrase = envelope.payload.optString("passphrase")
                val security = WifiConnectSecurity.fromCommandKeyword(
                    envelope.payload.optString("security", WifiConnectSecurity.WPA2.commandKeyword),
                )
                if (sessionId.isBlank() || ssid.isBlank() || ssid.length > 128 || security == null ||
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
                    cancelPendingWifiDisable("camera_join")
                    handleGlassesWifiRequest(context, sessionId)
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
            val sessionId = envelope.payload.optString("sessionId")
            if (rawEnabled !is Boolean) {
                log("glassesWifiRequest rejected reason=invalid_payload")
                return
            }
            val context = appContext
            if (context == null) {
                log(
                    "glassesWifiRequest enabled=$rawEnabled " +
                        "hubOwned=${wifiOwnership?.isHubOwned() == true} applied=false",
                )
                return
            }
            wifiRequestExecutor.execute {
                if (rawEnabled) {
                    if (sessionId.isBlank()) {
                        log("glassesWifiRequest enabled=true rejected reason=missing_session_id")
                        return@execute
                    }
                    wifiEnableReleasePending.set(false)
                    cancelPendingWifiDisable("camera_request")
                    handleGlassesWifiRequest(context, sessionId)
                } else {
                    if (!deferWifiDisableUntilAccessibilityEnableCompletes()) {
                        reconcileWifiOwnership(
                            context = context,
                            trigger = "camera_release",
                            cameraGraceRequested = true,
                        )
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
        if (action == SelfArmManualAction.CLOSE) manualConnectPortReporter.clear()
        // Reading a pairing code off the glasses and typing it on the phone takes longer than the
        // screen stays on, and the display going dark dismisses the pairing dialog — which cancels
        // the pairing the wearer was halfway through. Hold the screen for the manual flow, and give
        // it back the moment the flow closes.
        if (context != null) {
            when (action) {
                SelfArmManualAction.CLOSE -> {
                    releaseManualSetupScreen()
                }
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
            manualConnectPortReporter.begin(envelope.id, action.wireValue)
            // The accessibility callback runs on its main handler. Polling adbd belongs on the
            // existing Wireless ADB worker, even with a short bounded deadline.
            wirelessAdbExecutor.execute {
                manualConnectPortReporter.initialReport(action.connectPortWaitTimeoutMs())
                    ?.let(::sendManualConnectPortReport)
            }
        }
        log("manual self-arm action=${action.wireValue} accepted=$accepted armed=$armed")
        if (!accepted) {
            sendRemote(errorEnvelope(envelope.id, "ACCESSIBILITY_UNAVAILABLE"))
            return
        }
        // Completion (and therefore acknowledgement) is asynchronous for the six-tap action.
    }

    internal fun reportWirelessConnectPort(port: Int) {
        manualConnectPortReporter.pushKnownPort(port)?.let(::sendManualConnectPortReport)
    }

    internal fun reportPhoneAssistedConnectPort(
        correlation: SelfArmPhonePairingCorrelation,
        port: Int,
    ): Boolean {
        if (port !in 1..65535) return false
        return sendRemote(
            BusEnvelope(
                path = BusPaths.GLASSES_SELFARM_MANUAL_REPLY,
                id = correlation.offerId,
                payload = JSONObject()
                    .put("version", 1)
                    .put("accepted", true)
                    .put("connectPortUpdate", true)
                    .put("sessionId", correlation.sessionId)
                    .put("offerId", correlation.offerId)
                    .put("connectPort", port),
            ),
        ) == null
    }

    private fun sendManualConnectPortReport(report: SelfArmConnectPortReport) {
        val payload = JSONObject()
            .put("version", 1)
            .put("action", report.action)
            .put("accepted", true)
            .putOpt("connectPort", report.connectPort)
        if (report.updateOnly) {
            payload
                .put("connectPortUpdate", true)
                .put("forId", report.requestId)
        }
        sendRemote(
            BusEnvelope(
                path = BusPaths.GLASSES_SELFARM_MANUAL_REPLY,
                id = report.requestId,
                payload = payload,
            ),
        )
    }

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
        val deathRecipient = IBinder.DeathRecipient {
            removeRegistrationsByBinder(callbackBinder, "binder_death")
        }
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

    private fun removeRegistrationsByBinder(callbackBinder: IBinder, reason: String = "replacement") {
        registrations.filter { it.callbackBinder == callbackBinder }.forEach { registration ->
            removeRegistration(registration, reason)
        }
    }

    private fun removeRegistration(registration: Registration, reason: String = "callback_failure") {
        if (!registrations.remove(registration)) return
        runCatching { registration.callbackBinder.unlinkToDeath(registration.deathRecipient, 0) }
        if (registration.clientId == CAMERA_CLIENT_ID) {
            val reset = cameraSessionTracker.reset()
            appContext?.let { context ->
                wifiRequestExecutor.execute {
                    reconcileWifiOwnership(
                        context = context,
                        trigger = "camera_${reason}",
                        cameraGraceRequested = true,
                    )
                }
            }
            log("camera client disconnected reason=$reason trackerReset=$reset")
        }
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
        // CXR is enough for control JSON, but every photo session needs the SPP data plane. Using
        // the aggregate CXR-or-SPP state loses the rising edge when SPP returns while CXR stayed up.
        MediaSyncEngine.onLinkStateChanged(MediaSyncLinkPolicy.isDataPlaneUp(state))
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

    internal fun isWifiHubOwned(): Boolean {
        val context = appContext ?: return wifiOwnership?.isHubOwned() == true
        return wifiOwnership?.isHubOwned() == true ||
            SelfArmSetupWifiOwnershipStore.isNexusOwned(context)
    }

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

    internal fun requestWifiOwnershipReconciliation(context: Context, trigger: String) {
        val applicationContext = context.applicationContext
        wifiRequestExecutor.execute {
            reconcileWifiOwnership(applicationContext, trigger, cameraGraceRequested = false)
        }
    }

    internal fun onSetupSessionTerminal(context: Context, sessionId: String) {
        SelfArmSetupWifiOwnershipStore.discardUnissued(context, sessionId)
        requestWifiOwnershipReconciliation(context, "setup_terminal:$sessionId")
    }

    private fun updateRemotePhoneCapabilities(payload: JSONObject) {
        val advertised = PhoneHubCapabilitiesContract.parse(payload)
        val next = PhoneHubCapabilitiesContract.create(
            features = supportedPhoneCapabilities(advertised.features),
            cameraConsumerName = advertised.cameraConsumerName,
            activityAlwaysExpanded = advertised.activityAlwaysExpanded,
            hudTopInsetDp = advertised.hudTopInsetDp,
            hudPositionAuto = advertised.hudPositionAuto,
        )
        appContext?.let {
            ActivityController.setAlwaysExpanded(it, next.activityAlwaysExpanded)
            HudTopInset.set(
                context = it,
                manualDp = next.hudTopInsetDp,
                auto = next.hudPositionAuto,
            )
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

    private fun scheduleGlassesWifiDisable(context: Context, trigger: String) {
        if (wifiDisableFuture != null) {
            log("glassesWifiGrace scheduled=false trigger=$trigger reason=already_pending")
            return
        }
        log("glassesWifiGrace scheduled=true trigger=$trigger delayMs=$WIFI_DISABLE_GRACE_MS")
        wifiDisableFuture = wifiRequestExecutor.schedule(
            {
                wifiDisableFuture = null
                reconcileWifiOwnership(
                    context = context,
                    trigger = "${trigger}_grace_elapsed",
                    cameraGraceRequested = false,
                    cameraGraceSatisfied = true,
                )
            },
            WIFI_DISABLE_GRACE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPendingWifiDisable(trigger: String) {
        val cancelled = wifiDisableFuture?.cancel(false) == true
        wifiDisableFuture = null
        if (cancelled) log("glassesWifiGrace cancelled trigger=$trigger")
    }

    private fun handleGlassesWifiRequest(context: Context, sessionId: String) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            log("glassesWifiRequest enabled=true hubOwned=${wifiOwnership?.isHubOwned() == true} applied=false")
            return
        }
        val wifiCurrentlyEnabled = runCatching { wifiManager.isWifiEnabled }
            .onFailure { logError("glassesWifiRequest state read failed", it) }
            .getOrNull()
        if (wifiCurrentlyEnabled == null) {
            log("glassesWifiRequest enabled=true hubOwned=${wifiOwnership?.isHubOwned() == true} applied=false")
            return
        }
        val ownership = wifiOwnership ?: return
        val result = ownership.acquire(
            sessionId = sessionId,
            wifiCurrentlyEnabled = wifiCurrentlyEnabled,
            requestWifiEnable = {
                val applied = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, true) }
                    .onFailure { logError("glassesWifiRequest bridge failed", it) }
                    .getOrDefault(false)
                // Camera-owned Wi-Fi acquisition must not start the onboarding-only
                // wireless-debugging bootstrap; it gets only the Wi-Fi toggle mode.
                applied || attemptWifiAccessibilityEnable(context)
            },
        )
        log("glassesWifiRequest enabled=true hubOwned=${result.hubOwned} applied=${result.applied}")
    }

    private fun attemptWifiAccessibilityEnable(context: Context): Boolean {
        val attempted = wifiEnableA11yInFlight.compareAndSet(false, true)
        val serviceConnected = attempted && RokidBusAccessibilityService.requestWifiEnable(context)
        if (attempted && !serviceConnected) wifiEnableA11yInFlight.set(false)
        log("glassesWifi a11y-enable attempted=$attempted serviceConnected=$serviceConnected")
        return serviceConnected
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
        if (!enabled) {
            appContext?.let { context ->
                requestWifiOwnershipReconciliation(context, "camera_a11y_enable_failed")
            }
        }
        if (enabled && requested && releasePending) {
            appContext?.let { context ->
                wifiRequestExecutor.execute {
                    reconcileWifiOwnership(
                        context = context,
                        trigger = "camera_release_after_a11y_enable",
                        cameraGraceRequested = true,
                    )
                }
            }
        }
        log(
            "glassesWifi a11y-enable completed requested=$requested " +
                "enabled=$enabled releasePending=$releasePending",
        )
    }

    private fun reconcileWifiOwnership(
        context: Context,
        trigger: String,
        cameraGraceRequested: Boolean,
        cameraGraceSatisfied: Boolean = false,
    ) {
        val ownership = wifiOwnership ?: return
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wifiManager?.let { manager ->
            runCatching { manager.isWifiEnabled }
                .onFailure { logError("glassesWifi reconcile state read failed", it) }
                .getOrNull()
        }
        val setupEnableRequestActive =
            SelfArmSetupWifiOwnershipStore.isEnableRequestInFlight(context)
        if (wifiEnabled == false) {
            val cameraEnableRequestActive =
                ownership.isEnableRequestPossiblyInFlight() ||
                    wifiEnableA11yInFlight.get() ||
                    cameraSessionTracker.isActive()
            if (setupEnableRequestActive || cameraEnableRequestActive) {
                log(
                    "glassesWifi reconcile trigger=$trigger action=none " +
                        "setupEnableRequestActive=$setupEnableRequestActive " +
                        "cameraEnableRequestActive=$cameraEnableRequestActive",
                )
                return
            }
            cancelPendingWifiDisable("radio_observed_off")
            ownership.observeRadioState(wifiEnabled = false)
            SelfArmSetupWifiOwnershipStore.clearAfterRadioDisabled(context)
            log("glassesWifi reconcile trigger=$trigger result=already_off")
            return
        }
        val action = WifiOwnershipReconciliationPolicy.decide(
            cameraLeaseOwned = ownership.isHubOwned(),
            setupWifiOwned = SelfArmSetupWifiOwnershipStore.isNexusOwned(context),
            cameraSessionActive = cameraSessionTracker.isActive(),
            setupSessionActive = SelfArmOnboardingStore.isWifiStillNeededBySetup(context),
            mediaSyncSessionActive = MediaSyncEngine.isSessionActive(),
            selfArmOperationActive = SelfArmController.isOperationRunning(),
            setupEnableRequestActive = setupEnableRequestActive,
            cameraGraceRequested = cameraGraceRequested,
            cameraGracePending = wifiDisableFuture != null,
            cameraGraceSatisfied = cameraGraceSatisfied,
        )
        when (action) {
            WifiOwnershipReconciliationAction.NONE ->
                log("glassesWifi reconcile trigger=$trigger action=none")
            WifiOwnershipReconciliationAction.SCHEDULE_CAMERA_GRACE ->
                scheduleGlassesWifiDisable(context, trigger)
            WifiOwnershipReconciliationAction.DISABLE_NOW ->
                disableWifiOwnedByNexus(context, ownership, wifiManager, trigger)
        }
    }

    private fun disableWifiOwnedByNexus(
        context: Context,
        ownership: GlassesWifiOwnership,
        wifiManager: WifiManager?,
        trigger: String,
    ) {
        if (wifiManager == null) {
            log("glassesWifi disable trigger=$trigger observedOff=false reason=no_wifi_manager")
            return
        }
        val viaBridge = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, false) }
            .onFailure { logError("glassesWifi disable bridge call failed", it) }
            .getOrDefault(false)
        val fallbackApplied = !viaBridge && SelfArmController.setWifiEnabled(context, false)
        val observedOff = awaitWifiDisabled(wifiManager)
        if (observedOff) {
            ownership.observeRadioState(wifiEnabled = false)
            SelfArmSetupWifiOwnershipStore.clearAfterRadioDisabled(context)
        }
        log(
            "glassesWifi disable trigger=$trigger viaBridge=$viaBridge " +
                "fallbackApplied=$fallbackApplied observedOff=$observedOff",
        )
    }

    private fun awaitWifiDisabled(wifiManager: WifiManager): Boolean {
        val deadline = System.nanoTime() + WIFI_DISABLE_OBSERVE_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (runCatching { !wifiManager.isWifiEnabled }.getOrDefault(false)) return true
            try {
                Thread.sleep(WIFI_STATE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return runCatching { !wifiManager.isWifiEnabled }.getOrDefault(false)
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
    private const val WIFI_DISABLE_OBSERVE_TIMEOUT_MS = 3_000L
    private const val WIFI_STATE_POLL_MS = 50L
    private const val SETUP_CAPABILITIES_DEBOUNCE_MS = 250L
    /** Long enough to read a code and type it on the phone; short enough to never strand the screen. */
    private const val MANUAL_SETUP_SCREEN_TIMEOUT_MS = 5 * 60_000L
    private const val CAMERA_LAUNCHER_ID = "camera"
    private const val CAMERA_CLIENT_ID = "glasses-camera-domain"
}
