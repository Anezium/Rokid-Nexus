package com.anezium.rokidbus.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.BitmapFactory
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceMetadata
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.PhoneHubCapabilities
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginCapability.Companion.serialize
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ROKIDBUS-PHONE"
private const val CHANNEL_ID = "rokidbus_phone"
private const val NOTIFICATION_ID = 1
private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val ACTION_SET_TOKEN = "com.anezium.rokidbus.phone.SET_TOKEN"
private const val ACTION_STOP = "com.anezium.rokidbus.phone.STOP"
private const val ACTION_DEBUG_IMAGE = "com.anezium.rokidbus.phone.DEBUG_IMAGE_SURFACE"
private const val EXTRA_AUTH_TOKEN = "auth_token"
private const val PREF_ENABLED = "hub_enabled"
private const val GLASSES_MAC = "AC:86:D1:55:1E:ED"
private const val GLASSES_NAME = "Glasses_3723"
private const val GLASSES_HUB_PACKAGE = "com.anezium.rokidbus.glasses"
private const val PREFS = "rokidbus_phone"
private const val PREF_TOKEN = "cxrl_token"
private const val LOCAL_BINARY_MAX_BYTES = 512 * 1024
private const val AUDIO_LEASE_ACQUIRE = "/audio/lease/acquire"
private const val AUDIO_LEASE_RELEASE = "/audio/lease/release"
private const val AUDIO_FRAMES = "/audio/frames"
private const val AUDIO_LEASE_REVOKED = "/audio/lease/revoked"
private const val CXR_AUDIO_PCM = 1
private const val AUDIO_SAMPLE_RATE = 16_000
private const val AUDIO_CHANNELS = 1
private const val AUDIO_ENCODING = "pcm16le"

class BusHubService : Service() {
    private data class Registration(
        val clientId: String,
        val prefixes: List<String>,
        val uid: Int,
        val callbackBinder: IBinder,
        val callback: IBusCallback,
        val deathRecipient: IBinder.DeathRecipient,
        val principal: PhonePluginPrincipal? = null,
        val grantedCapabilities: Set<PluginCapability> = emptySet(),
    )

    private data class AuthorizedSender(
        val caller: PluginRouteCaller,
        val replyBinder: IBinder?,
        val principal: PhonePluginPrincipal? = null,
    )

    private enum class AudioLeaseSide { LOCAL, REMOTE }

    private data class AudioLease(
        val leaseId: String,
        val side: AudioLeaseSide,
        val localCallbackBinder: IBinder?,
        var seq: Long = 0L,
    )

    private val executor = Executors.newCachedThreadPool()
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val externalSurfaceSeq = ConcurrentHashMap<String, AtomicLong>()
    private val debugImageSeq = AtomicLong(System.currentTimeMillis())
    private val externalSurfaceIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val imageSurfaceRateLimiter = ImageSurfaceRateLimiter()
    private val pluginBusJournal = PluginBusJournal()
    private val sppLoopStarted = AtomicBoolean(false)
    private val audioLeaseLock = Any()
    @Volatile private var sppLoopStop = false
    @Volatile private var hubEnabled = true
    @Volatile private var audioLease: AudioLease? = null
    private val writeLock = Any()
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var cxrLink: CXRLink? = null
    private lateinit var pluginRegistry: PhonePluginRegistry
    private lateinit var pluginDiscovery: PhonePluginDiscovery
    private lateinit var pluginGrantStore: PluginGrantStore
    private lateinit var pluginGrantReconciler: PluginGrantReconciler
    private lateinit var developerModeStore: DeveloperModeStore
    private var developerModeJournalSubscription: DeveloperModeStore.Subscription? = null
    private lateinit var externalPluginController: ExternalPluginController
    private lateinit var cameraConsumerReadiness: CameraConsumerReadiness
    private lateinit var cameraCompanionController: CameraCompanionController
    private lateinit var transitLegacyStateExporter: TransitLegacyStateExporter
    @Volatile private var cxrConnected = false
    @Volatile private var glassBtConnected = false
    @Volatile private var lastAnnouncedPhoneCapabilities: PhoneHubCapabilities? = null
    @Volatile private var lastNotifiedStatus: String? = null
    @Volatile private var remoteImageSurfaceVersion = 0
    @Volatile private var remoteMaxImageBytes = 0
    @Volatile private var lastTransportLinkState = 0
    private var pluginPackageReceiverRegistered = false

    private val pluginPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!PluginPackageChangePolicy.shouldReconcile(
                    action = intent?.action,
                    replacing = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true,
                )
            ) return
            val packageName = intent?.data?.schemeSpecificPart.orEmpty()
            if (packageName.isNotBlank()) reconcilePluginPackage(packageName)
        }
    }

    private val binder = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != Process.myUid() && !isDebuggableBuild()) {
                log("legacy client registration rejected status=release_external")
                return
            }
            val prefixes = pathPrefixes.mapNotNull(PathRules::normalizeAbsolute)
            if (prefixes.size != pathPrefixes.size) {
                log("legacy client registration rejected status=invalid_paths")
                return
            }
            if (callingUid != Process.myUid()) {
                log("legacy client registration allowed status=debug_compatibility")
            }
            addRegistration(clientId, prefixes, callingUid, cb)
        }

        override fun unregister(cb: IBusCallback) {
            removeRegistrationsByBinder(cb.asBinder(), "unregister")
        }

        override fun send(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json), Binder.getCallingUid())
        }

        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) {
            val json = runCatching { JSONObject(String(meta, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json, binary = data), Binder.getCallingUid())
        }

        override fun linkState(): Int = this@BusHubService.linkState()

        override fun capabilities(): Int = this@BusHubService.capabilities()

        override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int {
            val callingUid = Binder.getCallingUid()
            val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
            if (packageName !in packages) {
                return pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.IDENTITY_MISMATCH,
                    "IDENTITY_MISMATCH",
                )
            }

            val candidates = pluginDiscovery.discoverPackage(packageName)
            if (candidates.size != 1) {
                return pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.INVALID_DESCRIPTOR,
                    "INVALID_DESCRIPTOR",
                )
            }
            val candidate = candidates.single()
            if (candidate is PhonePluginCandidate.Invalid) {
                val result = if (candidate.reason == "UNSUPPORTED_API" ||
                    candidate.reason == "SHARED_UID_UNSUPPORTED"
                ) {
                    PluginRegistrationResult.UNSUPPORTED_API
                } else {
                    PluginRegistrationResult.INVALID_DESCRIPTOR
                }
                return pluginRegistrationResult(pluginId, result, candidate.reason)
            }
            val principal = (candidate as PhonePluginCandidate.Valid).principal
            if (principal.uid != callingUid || principal.descriptor.id != pluginId) {
                return pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.IDENTITY_MISMATCH,
                    "IDENTITY_MISMATCH",
                )
            }
            return when (val state = pluginGrantStore.stateFor(principal)) {
                PluginGrantState.Pending -> pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.PENDING_USER_APPROVAL,
                    "PENDING_USER_APPROVAL",
                )
                PluginGrantState.Denied -> pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.DENIED,
                    "DENIED",
                )
                PluginGrantState.Disabled -> pluginRegistrationResult(
                    pluginId,
                    PluginRegistrationResult.DENIED,
                    "DISABLED",
                )
                is PluginGrantState.Approved -> {
                    val prefixes = principal.descriptor.receivePrefixes.filter { prefix ->
                        PathRules.requiredCapabilityForReceivePrefix(prefix)?.let { it in state.capabilities } != false
                    }
                    if (addRegistration(
                            clientId = principal.descriptor.id,
                            prefixes = prefixes,
                            uid = callingUid,
                            cb = cb,
                            principal = principal,
                            grantedCapabilities = state.capabilities,
                        )
                    ) {
                        notifyPluginRegistration(principal, state.capabilities, cb)
                        if (::externalPluginController.isInitialized) {
                            externalPluginController.onRegistered(principal)
                        }
                        if (::cameraCompanionController.isInitialized) {
                            cameraCompanionController.onRegistered(principal)
                        }
                        log("plugin registered package=$packageName plugin=$pluginId status=approved")
                        pluginRegistrationResult(pluginId, PluginRegistrationResult.APPROVED)
                    } else {
                        pluginRegistrationResult(
                            pluginId,
                            PluginRegistrationResult.REGISTRATION_FAILED,
                            "CALLBACK_UNAVAILABLE",
                        )
                    }
                }
            }
        }
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            log("CXR-L connected=$connected")
            notifyLinkState()
            if (!isCxrUp()) revokeAudioLease("LINK_DOWN")
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassBtConnected = connected
            log("Hi Rokid glass BT connected=$connected")
            notifyLinkState()
            if (!isCxrUp()) revokeAudioLease("LINK_DOWN")
        }
    }

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
            forwardAudioFrame(data, offset, length)
        }

        override fun onAudioError(code: Int, msg: String?) {
            log("CXR audio error code=$code msg=${msg.orEmpty()}")
            revokeAudioLease("LINK_DOWN")
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            log("CXR audio stream state started=$started")
        }
    }

    private val customCmdCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            if (key != BusConstants.CXR_KEY) return
            val envelope = decodeCxrPayload(payload) ?: return
            log("CXR RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        developerModeStore = DeveloperModeStore(applicationContext)
        developerModeJournalSubscription = bindDeveloperModeToJournal(developerModeStore, pluginBusJournal)
        PhoneClientSupervisor.attach(this)
        pluginDiscovery = PhonePluginDiscovery(packageManager)
        pluginGrantStore = PluginGrantStore(applicationContext)
        pluginGrantReconciler = PluginGrantReconciler(
            discoverCandidates = pluginDiscovery::discover,
            reconcileGrants = pluginGrantStore::reconcile,
        )
        executor.execute { pluginGrantReconciler.reconcile() }
        cameraConsumerReadiness = CameraConsumerReadiness(
            installedPrincipals = ::installedPluginPrincipals,
            grantState = pluginGrantStore::stateFor,
        ).also { it.recompute() }
        transitLegacyStateExporter = TransitLegacyStateExporter(
            AndroidTransitLegacyStateStorage(applicationContext),
        )
        val externalRuntime = AndroidExternalPluginRuntime(
            context = applicationContext,
            isRegisteredCallback = ::isExternalPrincipalRegistered,
            deliverCallback = ::deliverExternalLifecycle,
            hideCallback = ::hideExternalSurfaces,
            disconnectedCallback = { principal ->
                if (::externalPluginController.isInitialized) {
                    externalPluginController.onBinderDied(principal.grantKey())
                }
            },
        )
        externalPluginController = ExternalPluginController(
            runtime = externalRuntime,
            scheduler = MainThreadExternalPluginScheduler(),
            logger = ::log,
            onRegisteredPrincipal = ::offerTransitLegacyMigration,
            onForegroundChanged = { updateStatusNotification(linkState()) },
            journal = pluginBusJournal,
        )
        val cameraRuntime = AndroidExternalPluginRuntime(
            context = applicationContext,
            isRegisteredCallback = ::isExternalPrincipalRegistered,
            deliverCallback = ::deliverExternalLifecycle,
            deliverBinaryCallback = ::deliverExternalBinary,
            hideCallback = {},
            disconnectedCallback = { principal ->
                if (::cameraCompanionController.isInitialized) {
                    cameraCompanionController.onBinderDied(principal.grantKey())
                }
            },
        )
        cameraCompanionController = CameraCompanionController(
            runtime = cameraRuntime,
            scheduler = MainThreadExternalPluginScheduler(),
            resolveApprovedConsumer = cameraConsumerReadiness::resolveApproved,
            logger = ::log,
        )
        pluginRegistry = PhonePluginRegistry(
            context = applicationContext,
            plugins = emptyList(),
            sendEnvelope = ::sendBuiltInPluginEnvelope,
            capabilitiesProvider = ::capabilities,
            logger = { message -> log(message) },
            catalogProvider = {
                PluginCatalog.build(
                    builtIns = emptyList(),
                    candidates = pluginDiscovery.discover(),
                    grantState = pluginGrantStore::stateFor,
                )
            },
            externalController = externalPluginController,
            journal = pluginBusJournal,
        )
        registerPluginPackageReceiver()
        hubEnabled = prefs().getBoolean(PREF_ENABLED, true)
        if (hubEnabled) {
            startForegroundWithType()
            startCxrIfTokenAvailable()
        }
        connectSpp()
        log("BusHubService created enabled=$hubEnabled")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHub()
                return START_NOT_STICKY
            }
            ACTION_SET_TOKEN -> {
                val token = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
                if (token.isNotBlank()) {
                    prefs().edit().putString(PREF_TOKEN, token).apply()
                    enableHub()
                    startCxr(token)
                }
            }
            ACTION_DEBUG_IMAGE -> {
                if (isDebuggableBuild()) {
                    enableHub()
                    startCxrIfTokenAvailable()
                    executor.execute(::pushDebugImageWhenReady)
                } else {
                    log("debug image probe rejected status=release_build")
                }
            }
            else -> {
                enableHub()
                startCxrIfTokenAvailable()
            }
        }
        connectSpp()
        return START_STICKY
    }

    private fun enableHub() {
        prefs().edit().putBoolean(PREF_ENABLED, true).apply()
        hubEnabled = true
        startForegroundWithType()
    }

    /** Release every radio resource: SPP socket, CXR-L session, foreground state. */
    private fun stopHub() {
        prefs().edit().putBoolean(PREF_ENABLED, false).apply()
        hubEnabled = false
        stopAudioLease()
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        cxrConnected = false
        glassBtConnected = false
        closeSocket()
        notifyLinkState()
        log("Hub stopped; SPP socket and CXR-L session released")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        sppLoopStop = true
        stopAudioLease()
        runCatching { cxrLink?.disconnect() }
        closeSocket()
        PhoneClientSupervisor.detach(applicationContext, this)
        if (pluginPackageReceiverRegistered) {
            runCatching { unregisterReceiver(pluginPackageReceiver) }
            pluginPackageReceiverRegistered = false
        }
        developerModeJournalSubscription?.close()
        developerModeJournalSubscription = null
        if (::pluginRegistry.isInitialized) pluginRegistry.close()
        if (::cameraCompanionController.isInitialized) cameraCompanionController.close()
        registrations.clear()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    fun deliverQueued(envelope: BusEnvelope): Boolean =
        deliverLocal(envelope)

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        val sender = resolveSender(senderUid)
        if (!protectedPathAllowed(envelope.path, senderUid, sender.principal)) {
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, "PROTECTED_CAMERA_PATH")
            deliverError(sender.replyBinder, envelope.id, "PROTECTED_CAMERA_PATH")
            return
        }
        val decision = PluginRoutePolicy.authorize(sender.caller, envelope.path)
        if (decision is PluginRouteDecision.Denied) {
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, decision.code)
            deliverError(sender.replyBinder, envelope.id, decision.code)
            return
        }
        val ownedEnvelope = if (
            sender.principal != null &&
            PathRules.requiredCapability(envelope.path) == PluginCapability.SURFACES
        ) {
            val payload = PluginRoutePolicy.injectSurfaceOwner(sender.principal.descriptor.id, envelope.payload)
            if (payload == null) {
                recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, "INVALID_SURFACE_ID")
                deliverError(sender.replyBinder, envelope.id, "INVALID_SURFACE_ID")
                return
            }
            envelope.copy(payload = payload)
        } else {
            envelope
        }
        if (ownedEnvelope.path == BusPaths.SURFACE_SHOW || ownedEnvelope.path == BusPaths.SURFACE_UPDATE) {
            val imageError = validateSurfaceImageEnvelope(ownedEnvelope)
            if (imageError != null) {
                recordLocalRoute(ownedEnvelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, imageError)
                deliverError(sender.replyBinder, ownedEnvelope.id, imageError)
                return
            }
        }
        if (
            sender.principal != null &&
            (ownedEnvelope.path == BusPaths.SURFACE_SHOW || ownedEnvelope.path == BusPaths.SURFACE_UPDATE) &&
            ::pluginRegistry.isInitialized &&
            !pluginRegistry.allowExternalSurface(sender.principal, ownedEnvelope.path)
        ) {
            recordLocalRoute(ownedEnvelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, "SURFACE_BUSY")
            deliverError(sender.replyBinder, ownedEnvelope.id, "SURFACE_BUSY")
            log("surface rejected path=${ownedEnvelope.path} plugin=${sender.principal.descriptor.id} reason=foreground_busy")
            return
        }
        if (
            sender.principal != null &&
            ownedEnvelope.path in setOf(BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE, BusPaths.SURFACE_HIDE) &&
            ::externalPluginController.isInitialized
        ) {
            externalPluginController.onPluginActivity(sender.principal.descriptor.id)
        }
        val authorizedEnvelope = if (
            sender.principal != null &&
            PathRules.requiredCapability(ownedEnvelope.path) == PluginCapability.SURFACES
        ) {
            val payload = ownedEnvelope.payload
            val wireSurfaceId = payload.getString("surfaceId")
            val sequence = externalSurfaceSeq.computeIfAbsent(wireSurfaceId) {
                AtomicLong(System.currentTimeMillis())
            }.incrementAndGet()
            val pluginSurfaces = externalSurfaceIds.computeIfAbsent(sender.principal.descriptor.id) {
                ConcurrentHashMap.newKeySet()
            }
            if (ownedEnvelope.path == BusPaths.SURFACE_HIDE) {
                pluginSurfaces.remove(wireSurfaceId)
                if (pluginSurfaces.isEmpty() && ::externalPluginController.isInitialized) {
                    externalPluginController.onPluginSelfHid(sender.principal.descriptor.id)
                }
            } else {
                pluginSurfaces += wireSurfaceId
            }
            ownedEnvelope.copy(payload = payload.put("seq", sequence))
        } else {
            ownedEnvelope
        }
        if (authorizedEnvelope.path == TransitLegacyStateExporter.ACK_PATH && sender.principal != null) {
            val acknowledged = transitLegacyStateExporter.acknowledge(
                sender.principal,
                pluginGrantStore.stateFor(sender.principal),
                authorizedEnvelope.payload,
            )
            if (acknowledged) {
                recordLocalRoute(authorizedEnvelope, senderUid, sender, PluginBusJournal.Verdict.OK)
            } else {
                recordLocalRoute(
                    authorizedEnvelope,
                    senderUid,
                    sender,
                    PluginBusJournal.Verdict.REJECTED,
                    "INVALID_MIGRATION_ACK",
                )
                deliverError(sender.replyBinder, authorizedEnvelope.id, "INVALID_MIGRATION_ACK")
            }
            return
        }
        recordLocalRoute(authorizedEnvelope, senderUid, sender, PluginBusJournal.Verdict.OK)
        if (handleHubPath(
                authorizedEnvelope,
                replyRemote = false,
                senderUid = senderUid,
                replyBinder = sender.replyBinder,
            )
        ) return
        if (deliverLocal(authorizedEnvelope, excludeUid = senderUid)) return
        if (authorizedEnvelope.path != BusPaths.ERROR &&
            PhoneClientSupervisor.enqueue(applicationContext, authorizedEnvelope, excludeUid = senderUid)
        ) return
        val errorCode = sendRemote(authorizedEnvelope)
        if (errorCode != null) {
            deliverError(sender.replyBinder, authorizedEnvelope.id, errorCode)
        }
    }

    private fun routeRemote(envelope: BusEnvelope) {
        if (envelope.path == "/hub/probe") {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            log("hub probe received from glasses")
            return
        }
        if (envelope.path == BusPaths.HUB_CAPABILITIES) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            updateRemoteCapabilities(envelope.payload)
            return
        }
        if (::cameraCompanionController.isInitialized &&
            cameraCompanionController.onRemoteEnvelope(envelope)
        ) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (::pluginRegistry.isInitialized && pluginRegistry.handleRemote(envelope)) return
        if (handleHubPath(envelope, replyRemote = true)) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (deliverLocal(envelope)) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (envelope.path == BusPaths.ERROR) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "UNDELIVERABLE_ERROR")
            log("dropping undeliverable remote error id=${envelope.id}")
            return
        }
        if (envelope.binary != null) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "NO_LIVE_REGISTRATION")
            log("dropping undeliverable binary ${envelope.path} id=${envelope.id}; no live registration")
            return
        }
        if (PhoneClientSupervisor.enqueue(applicationContext, envelope)) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK, "QUEUED_LOCAL")
            return
        }
        recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "NO_LOCAL_CLIENT")
        sendRemote(errorEnvelope(envelope.id, "NO_LOCAL_CLIENT"))
    }

    private fun recordLocalRoute(
        envelope: BusEnvelope,
        senderUid: Int,
        sender: AuthorizedSender,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        if (senderUid == Process.myUid() || !pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = sender.principal?.descriptor?.id,
                category = journalCategory(envelope.path, envelope.binary != null),
                direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
                path = envelope.path,
                sizeBytes = envelope.binary?.size,
                verdict = verdict,
                reason = journalRouteReason(reason),
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun recordRemoteRoute(
        envelope: BusEnvelope,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        if (!pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = journalPluginId(envelope),
                category = journalCategory(envelope.path, envelope.binary != null),
                direction = PluginBusJournal.Direction.GLASSES_TO_HUB,
                path = envelope.path,
                sizeBytes = envelope.binary?.size,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun recordLocalDelivery(
        registration: Registration,
        envelope: BusEnvelope,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        if (!pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = registration.principal?.descriptor?.id,
                category = journalCategory(envelope.path, envelope.binary != null),
                direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
                path = envelope.path,
                sizeBytes = envelope.binary?.size,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun recordExternalDelivery(
        principal: PhonePluginPrincipal,
        path: String,
        sizeBytes: Int?,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        if (!pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = principal.descriptor.id,
                category = journalCategory(path, sizeBytes != null),
                direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
                path = path,
                sizeBytes = sizeBytes,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun recordOversizedBinary(
        pluginId: String?,
        direction: PluginBusJournal.Direction,
        path: String,
        sizeBytes: Int,
    ) {
        if (!pluginBusJournal.enabled.get()) return
        try {
            val sizeKiB = (sizeBytes.toLong() + 1023L) / 1024L
            val limitKiB = LOCAL_BINARY_MAX_BYTES / 1024
            pluginBusJournal.record(
                pluginId = pluginId,
                category = PluginBusJournal.Category.BINARY,
                direction = direction,
                path = path,
                sizeBytes = sizeBytes,
                verdict = PluginBusJournal.Verdict.REJECTED,
                reason = "binary too large: ${sizeKiB}KiB > ${limitKiB}KiB",
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun recordRemoteTransport(
        envelope: BusEnvelope,
        verdict: PluginBusJournal.Verdict,
        reason: String,
    ) {
        if (!pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = journalPluginId(envelope),
                category = journalCategory(envelope.path, envelope.binary != null),
                direction = PluginBusJournal.Direction.HUB_TO_GLASSES,
                path = envelope.path,
                sizeBytes = envelope.binary?.size,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    private fun journalRouteReason(reason: String?): String? = when {
        reason == "PENDING_APPROVAL" -> "PENDING_USER_APPROVAL"
        reason?.startsWith("CAPABILITY_REQUIRED_") == true ->
            "capability denied: ${reason.removePrefix("CAPABILITY_REQUIRED_").lowercase()}"
        else -> reason
    }

    private fun journalPluginId(envelope: BusEnvelope): String? {
        val explicit = envelope.payload.optString("ownerPluginId")
            .ifBlank { envelope.payload.optString("pluginId") }
        if (explicit.isNotBlank()) return explicit
        val surfaceId = envelope.payload.optString("surfaceId")
        return surfaceId.substringBefore(':').takeIf { ':' in surfaceId && it.isNotBlank() }
    }

    private fun journalCategory(path: String, hasBinary: Boolean): PluginBusJournal.Category = when (path) {
        BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE, BusPaths.SURFACE_HIDE -> PluginBusJournal.Category.SURFACE
        BusPaths.SURFACE_INPUT, BusPaths.PLUGIN_INPUT -> PluginBusJournal.Category.INPUT
        BusPaths.PLUGIN_OPEN, BusPaths.PLUGIN_CLOSE -> PluginBusJournal.Category.LIFECYCLE
        BusPaths.PLUGIN_REGISTRATION -> PluginBusJournal.Category.REGISTRATION
        BusPaths.LAUNCHER_LIST, BusPaths.LAUNCHER_OPEN -> PluginBusJournal.Category.LAUNCHER
        else -> if (hasBinary) PluginBusJournal.Category.BINARY else PluginBusJournal.Category.TRANSPORT
    }

    private fun handleHubPath(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        senderUid: Int? = null,
        replyBinder: IBinder? = null,
    ): Boolean {
        when (envelope.path) {
            BusPaths.HTTP_REQUEST -> executor.execute { fetchAndStream(envelope, replyRemote, replyBinder) }
            AUDIO_LEASE_ACQUIRE -> executor.execute {
                acquireAudioLease(envelope, replyRemote, senderUid, replyBinder)
            }
            AUDIO_LEASE_RELEASE -> executor.execute { releaseAudioLease(envelope, replyRemote, replyBinder) }
            else -> return false
        }
        return true
    }

    private fun deliverLocal(
        envelope: BusEnvelope,
        excludeUid: Int? = null,
        targetBinder: IBinder? = null,
    ): Boolean {
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        val binary = envelope.binary
        var delivered = false
        registrations.forEach { registration ->
            if (targetBinder != null && registration.callbackBinder != targetBinder) return@forEach
            if (excludeUid != null && registration.uid == excludeUid) return@forEach
            if (registrationMatches(registration, envelope)) {
                if (binary != null && binary.size > LOCAL_BINARY_MAX_BYTES) {
                    recordOversizedBinary(
                        pluginId = registration.principal?.descriptor?.id,
                        direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
                        path = envelope.path,
                        sizeBytes = binary.size,
                    )
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
                    PhoneClientSupervisor.touch()
                }.onSuccess {
                    recordLocalDelivery(registration, envelope, PluginBusJournal.Verdict.OK, "LOCAL")
                }.onFailure {
                    recordLocalDelivery(registration, envelope, PluginBusJournal.Verdict.REJECTED, "DEAD_CALLBACK")
                    removeRegistration(registration, "dead callback")
                }
            }
        }
        return delivered
    }

    private fun registrationMatches(registration: Registration, envelope: BusEnvelope): Boolean {
        if (!protectedPathAllowed(envelope.path, registration.uid, registration.principal)) return false
        if (registration.prefixes.none { PathRules.matchesPrefix(envelope.path, it) }) return false
        val principal = registration.principal ?: return true
        if (PathRules.isPluginPrivate(envelope.path, principal.descriptor.id)) return true
        if (PathRules.matchesPrefix(envelope.path, "/system/plugin")) {
            return envelope.payload.optString("pluginId") == principal.descriptor.id
        }
        return true
    }

    private fun protectedPathAllowed(
        path: String,
        uid: Int,
        principal: PhonePluginPrincipal?,
    ): Boolean = ProtectedPathAccessPolicy.isAllowed(
        path = path,
        isHubUid = uid == Process.myUid(),
        principal = principal,
        grantState = principal?.let(pluginGrantStore::stateFor),
    )

    private fun resolveSender(senderUid: Int): AuthorizedSender {
        if (senderUid == Process.myUid()) return AuthorizedSender(PluginRouteCaller.Internal, null)
        val matching = registrations.filter { it.uid == senderUid }
        if (matching.isEmpty()) return AuthorizedSender(PluginRouteCaller.Unregistered, null)
        val principals = matching.mapNotNull(Registration::principal)
            .distinctBy { it.grantKey() }
        if (principals.size > 1) {
            return AuthorizedSender(PluginRouteCaller.Ambiguous, matching.first().callbackBinder)
        }
        val principal = principals.singleOrNull()
        if (principal == null) {
            return if (isDebuggableBuild()) {
                AuthorizedSender(PluginRouteCaller.DebugLegacy, matching.first().callbackBinder)
            } else {
                AuthorizedSender(PluginRouteCaller.Unregistered, matching.first().callbackBinder)
            }
        }
        val pluginRegistration = matching.first { it.principal?.grantKey() == principal.grantKey() }
        return when (val state = pluginGrantStore.stateFor(principal)) {
            PluginGrantState.Pending -> AuthorizedSender(
                PluginRouteCaller.Pending,
                pluginRegistration.callbackBinder,
                principal,
            )
            PluginGrantState.Denied,
            PluginGrantState.Disabled,
            -> AuthorizedSender(PluginRouteCaller.Revoked, pluginRegistration.callbackBinder, principal)
            is PluginGrantState.Approved -> AuthorizedSender(
                PluginRouteCaller.Plugin(principal.descriptor.id, state.capabilities),
                pluginRegistration.callbackBinder,
                principal,
            )
        }
    }

    private fun deliverError(targetBinder: IBinder?, id: String, code: String) {
        val target = targetBinder?.let { binder -> registrations.firstOrNull { it.callbackBinder == binder } }
            ?: return
        val envelope = errorEnvelope(id, code)
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        runCatching { target.callback.onMessage(envelope.path, envelope.id, payload) }
            .onFailure { removeRegistration(target, "dead callback") }
    }

    private fun notifyPluginRegistration(
        principal: PhonePluginPrincipal,
        capabilities: Set<PluginCapability>,
        callback: IBusCallback,
    ) {
        val eventId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("version", 1)
            .put("type", "registration")
            .put("id", eventId)
            .put("pluginId", principal.descriptor.id)
            .put("result", PluginRegistrationResult.APPROVED)
            .put("capabilities", serialize(capabilities))
            .toString()
            .toByteArray(Charsets.UTF_8)
        runCatching { callback.onMessage(BusPaths.PLUGIN_REGISTRATION, eventId, payload) }
    }

    private fun removeRegistrationsByBinder(callbackBinder: IBinder, reason: String) {
        registrations.filter { it.callbackBinder == callbackBinder }.forEach { registration ->
            removeRegistration(registration, reason)
        }
    }

    private fun removeRegistration(registration: Registration, reason: String) {
        if (!registrations.remove(registration)) return
        if (pluginBusJournal.enabled.get()) {
            pluginBusJournal.record(
                pluginId = registration.principal?.descriptor?.id,
                category = PluginBusJournal.Category.REGISTRATION,
                direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
                path = BusPaths.PLUGIN_REGISTRATION,
                reason = "CLIENT_REMOVED: $reason",
            )
        }
        runCatching { registration.callbackBinder.unlinkToDeath(registration.deathRecipient, 0) }
        releaseAudioLeaseForLocalBinder(registration.callbackBinder, reason)
        if (reason in setOf("binderDied", "dead callback", "unregister")) {
            registration.principal?.let { principal ->
                if (::externalPluginController.isInitialized) {
                    externalPluginController.onBinderDied(principal.grantKey())
                }
                if (::cameraCompanionController.isInitialized) {
                    cameraCompanionController.onBinderDied(principal.grantKey())
                }
            }
        }
    }

    private fun revokePrincipal(key: PluginGrantKey) {
        if (::cameraCompanionController.isInitialized) cameraCompanionController.onRevoked(key)
        if (::externalPluginController.isInitialized) externalPluginController.onRevoked(key)
        registrations.filter { it.principal?.grantKey() == key }.forEach { registration ->
            removeRegistration(registration, "authorizationChanged")
        }
    }

    private fun authorizationChanged(key: PluginGrantKey) {
        revokePrincipal(key)
        cameraConsumerReadiness.recompute()
        notifyLinkState()
    }

    private fun registerPluginPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pluginPackageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pluginPackageReceiver, filter)
        }
        pluginPackageReceiverRegistered = true
    }

    private fun reconcilePluginPackage(packageName: String) {
        val validPrincipals = pluginGrantReconciler.reconcile().validPrincipals
        cameraConsumerReadiness.recompute()
        val available = validPrincipals.any { principal ->
            principal.packageName == packageName &&
                pluginGrantStore.stateFor(principal) is PluginGrantState.Approved
        }
        if (!available) externalPluginController.onPackageUnavailable(packageName)
        val cameraAvailable = validPrincipals.any { principal ->
            principal.packageName == packageName &&
                cameraConsumerReadiness.isApprovedCameraConsumer(principal)
        }
        if (!cameraAvailable) cameraCompanionController.onPackageUnavailable(packageName)
        notifyLinkState()
        if (::pluginRegistry.isInitialized && isCxrUp()) pluginRegistry.syncLauncherList()
    }

    private fun installedPluginPrincipals(): List<PhonePluginPrincipal> =
        pluginDiscovery.discover().mapNotNull { candidate ->
            (candidate as? PhonePluginCandidate.Valid)?.principal
        }

    private fun isExternalPrincipalRegistered(principal: PhonePluginPrincipal): Boolean =
        registrations.any { it.principal?.grantKey() == principal.grantKey() }

    private fun deliverExternalLifecycle(
        principal: PhonePluginPrincipal,
        path: String,
        id: String,
        payload: JSONObject,
    ): Boolean {
        if (!protectedPathAllowed(path, principal.uid, principal)) {
            recordExternalDelivery(principal, path, null, PluginBusJournal.Verdict.REJECTED, "PROTECTED_CAMERA_PATH")
            return false
        }
        val registration = registrations.singleOrNull { it.principal?.grantKey() == principal.grantKey() }
        if (registration == null) {
            recordExternalDelivery(principal, path, null, PluginBusJournal.Verdict.REJECTED, "NO_LIVE_REGISTRATION")
            return false
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        return runCatching {
            registration.callback.onMessage(path, id, bytes)
            recordExternalDelivery(principal, path, null, PluginBusJournal.Verdict.OK, "LOCAL")
            true
        }.getOrElse {
            recordExternalDelivery(principal, path, null, PluginBusJournal.Verdict.REJECTED, "DEAD_CALLBACK")
            removeRegistration(registration, "dead callback")
            false
        }
    }

    private fun deliverExternalBinary(
        principal: PhonePluginPrincipal,
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ): Boolean {
        if (data.size > LOCAL_BINARY_MAX_BYTES) {
            recordOversizedBinary(
                pluginId = principal.descriptor.id,
                direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
                path = path,
                sizeBytes = data.size,
            )
            log("drop external binary $path id=$id bytes=${data.size} over cap=$LOCAL_BINARY_MAX_BYTES")
            return false
        }
        if (!protectedPathAllowed(path, principal.uid, principal)) {
            recordExternalDelivery(principal, path, data.size, PluginBusJournal.Verdict.REJECTED, "PROTECTED_CAMERA_PATH")
            return false
        }
        val registration = registrations.singleOrNull { it.principal?.grantKey() == principal.grantKey() }
        if (registration == null) {
            recordExternalDelivery(principal, path, data.size, PluginBusJournal.Verdict.REJECTED, "NO_LIVE_REGISTRATION")
            return false
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        return runCatching {
            registration.callback.onBinaryMessage(path, id, bytes, data)
            recordExternalDelivery(principal, path, data.size, PluginBusJournal.Verdict.OK, "LOCAL")
            true
        }.getOrElse {
            recordExternalDelivery(principal, path, data.size, PluginBusJournal.Verdict.REJECTED, "DEAD_CALLBACK")
            removeRegistration(registration, "dead callback")
            false
        }
    }

    private fun offerTransitLegacyMigration(principal: PhonePluginPrincipal) {
        val pending = transitLegacyStateExporter.prepare(
            principal,
            pluginGrantStore.stateFor(principal),
        ) ?: return
        deliverExternalLifecycle(
            principal = principal,
            path = TransitLegacyStateExporter.IMPORT_PATH,
            id = pending.eventId,
            payload = pending.payload,
        )
    }

    private fun hideExternalSurfaces(pluginId: String) {
        val surfaceIds = externalSurfaceIds.remove(pluginId).orEmpty().toList()
        surfaceIds.forEach { surfaceId ->
            val sequence = externalSurfaceSeq.computeIfAbsent(surfaceId) {
                AtomicLong(System.currentTimeMillis())
            }.incrementAndGet()
            sendRemote(
                BusEnvelope(
                    path = BusPaths.SURFACE_HIDE,
                    payload = JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("ownerPluginId", pluginId)
                        .put("seq", sequence),
                ),
            )
        }
    }

    private fun acquireAudioLease(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        senderUid: Int?,
        replyBinder: IBinder?,
    ) {
        val link = cxrLink
        if (audioLease != null) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"), replyBinder)
            return
        }
        if (link == null || !isCxrUp()) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "NO_CXR"), replyBinder)
            return
        }

        val holderBinder = if (replyRemote) null else replyBinder ?: findLocalAudioHolder(senderUid)
        val lease = AudioLease(
            leaseId = UUID.randomUUID().toString(),
            side = if (replyRemote) AudioLeaseSide.REMOTE else AudioLeaseSide.LOCAL,
            localCallbackBinder = holderBinder,
        )
        synchronized(audioLeaseLock) {
            if (audioLease != null) {
                replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"), replyBinder)
                return
            }
            audioLease = lease
        }

        val started = runCatching {
            link.setInterruptAiWake(true)
            link.setCXRAudioCbk(audioCallback)
            link.startAudioStream(CXR_AUDIO_PCM)
        }.getOrElse {
            log("CXR audio start failed ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        if (!started) {
            synchronized(audioLeaseLock) {
                if (audioLease?.leaseId == lease.leaseId) audioLease = null
            }
            stopAudioStreamQuietly()
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "START_FAILED"), replyBinder)
            return
        }
        if (synchronized(audioLeaseLock) { audioLease?.leaseId != lease.leaseId }) {
            // A concurrent revoke may have run stopAudioStreamQuietly() before our
            // startAudioStream() landed; stop again so no orphan stream survives.
            stopAudioStreamQuietly()
            val reason = if (isCxrUp()) "START_FAILED" else "NO_CXR"
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", reason), replyBinder)
            return
        }

        replyToAudioRequest(
            envelope,
            replyRemote,
            JSONObject()
                .put("granted", true)
                .put("leaseId", lease.leaseId)
                .put("sampleRate", AUDIO_SAMPLE_RATE)
                .put("channels", AUDIO_CHANNELS)
                .put("encoding", AUDIO_ENCODING),
            replyBinder,
        )
    }

    private fun releaseAudioLease(envelope: BusEnvelope, replyRemote: Boolean, replyBinder: IBinder?) {
        val leaseId = envelope.payload.optString("leaseId")
        val leaseToStop = synchronized(audioLeaseLock) {
            val current = audioLease
            if (current != null && current.leaseId == leaseId) {
                audioLease = null
                current
            } else {
                null
            }
        }
        if (leaseToStop != null) stopAudioStreamQuietly()
        replyToAudioRequest(envelope, replyRemote, JSONObject().put("released", true), replyBinder)
    }

    private fun releaseAudioLeaseForLocalBinder(callbackBinder: IBinder, reason: String) {
        val leaseToStop = synchronized(audioLeaseLock) {
            val current = audioLease
            if (current?.side == AudioLeaseSide.LOCAL && current.localCallbackBinder == callbackBinder) {
                audioLease = null
                current
            } else {
                null
            }
        }
        if (leaseToStop != null) {
            log("Audio lease ${leaseToStop.leaseId} released after $reason")
            stopAudioStreamQuietly()
        }
    }

    private fun revokeAudioLease(reason: String) {
        val leaseToRevoke = synchronized(audioLeaseLock) {
            val current = audioLease ?: return
            audioLease = null
            current
        }
        stopAudioStreamQuietly()
        val revoked = BusEnvelope(
            path = AUDIO_LEASE_REVOKED,
            id = leaseToRevoke.leaseId,
            payload = JSONObject().put("leaseId", leaseToRevoke.leaseId).put("reason", reason),
        )
        deliverAudioToHolder(leaseToRevoke, revoked)
    }

    private fun stopAudioLease() {
        val leaseToStop = synchronized(audioLeaseLock) {
            val current = audioLease
            audioLease = null
            current
        }
        if (leaseToStop != null) stopAudioStreamQuietly()
    }

    private fun stopAudioStreamQuietly() {
        runCatching { cxrLink?.stopAudioStream() }
        runCatching { cxrLink?.setCXRAudioCbk(null) }
        runCatching { cxrLink?.setInterruptAiWake(false) }
    }

    private fun forwardAudioFrame(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val chunk = data.copyOfRange(offset, offset + length)
        val leaseSnapshot = synchronized(audioLeaseLock) {
            val current = audioLease ?: return
            val seq = current.seq
            current.seq += 1
            current.copy(seq = seq)
        }
        val frame = BusEnvelope(
            path = AUDIO_FRAMES,
            id = leaseSnapshot.leaseId,
            payload = JSONObject()
                .put("leaseId", leaseSnapshot.leaseId)
                .put("seq", leaseSnapshot.seq)
                .put("elapsedRealtime", SystemClock.elapsedRealtime()),
            binary = chunk,
        )
        deliverAudioToHolder(leaseSnapshot, frame)
    }

    private fun deliverAudioToHolder(lease: AudioLease, envelope: BusEnvelope) {
        when (lease.side) {
            AudioLeaseSide.LOCAL -> lease.localCallbackBinder?.let { deliverLocal(envelope, targetBinder = it) }
            AudioLeaseSide.REMOTE -> sendRemote(envelope)
        }
    }

    private fun replyToAudioRequest(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        payload: JSONObject,
        replyBinder: IBinder?,
    ) {
        val response = BusEnvelope(path = envelope.path + "/reply", id = envelope.id, payload = payload)
        if (replyRemote) sendRemote(response) else deliverLocal(response, targetBinder = replyBinder)
    }

    private fun findLocalAudioHolder(senderUid: Int?): IBinder? {
        if (senderUid == null) return null
        val audioRegistration = registrations.firstOrNull { registration ->
            registration.uid == senderUid && registration.prefixes.any { prefix ->
                PathRules.matchesPrefix(AUDIO_FRAMES, prefix) ||
                    PathRules.matchesPrefix(AUDIO_LEASE_REVOKED, prefix)
            }
        }
        return audioRegistration?.callbackBinder ?: registrations.firstOrNull { it.uid == senderUid }?.callbackBinder
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        if (envelope.binary != null) {
            if (output == null) {
                recordRemoteTransport(envelope, PluginBusJournal.Verdict.REJECTED, "NO_DATA_PLANE")
                return "NO_DATA_PLANE"
            }
            return if (writeSpp(envelope)) {
                recordRemoteTransport(envelope, PluginBusJournal.Verdict.OK, "SPP")
                null
            } else {
                recordRemoteTransport(envelope, PluginBusJournal.Verdict.REJECTED, "NO_DATA_PLANE")
                "NO_DATA_PLANE"
            }
        }
        val bytes = FrameProtocol.toJsonBytes(envelope)
        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp()) {
            if (sendCxr(envelope)) {
                recordRemoteTransport(envelope, PluginBusJournal.Verdict.OK, "CXR")
                return null
            }
        }
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && output == null) {
            recordRemoteTransport(envelope, PluginBusJournal.Verdict.REJECTED, "NO_DATA_PLANE")
            return "NO_DATA_PLANE"
        }
        if (writeSpp(envelope)) {
            recordRemoteTransport(envelope, PluginBusJournal.Verdict.OK, "SPP")
            return null
        }
        val error = if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES) "NO_DATA_PLANE" else "NO_LINK"
        recordRemoteTransport(envelope, PluginBusJournal.Verdict.REJECTED, error)
        return error
    }

    private fun sendBuiltInPluginEnvelope(envelope: BusEnvelope): String? {
        if (envelope.path == BusPaths.SURFACE_SHOW || envelope.path == BusPaths.SURFACE_UPDATE) {
            validateSurfaceImageEnvelope(envelope)?.let { return it }
        }
        return sendRemote(envelope)
    }

    private fun sendCxr(envelope: BusEnvelope): Boolean =
        runCatching {
            val json = FrameProtocol.toJson(envelope).toString()
            val result = cxrLink?.sendCustomCmd(
                BusConstants.CXR_KEY,
                Caps().apply { write(json) }.serialize(),
            )
            log("CXR TX ${envelope.path} id=${envelope.id} result=$result")
            result != null && result >= 0
        }.getOrElse {
            log("CXR TX failed ${it.javaClass.simpleName}: ${it.message}")
            false
        }

    private fun writeSpp(envelope: BusEnvelope): Boolean {
        val out = output ?: return false
        return runCatching {
            synchronized(writeLock) { FrameProtocol.write(out, envelope) }
            log("SPP TX ${envelope.path} id=${envelope.id}")
            true
        }.getOrElse {
            log("SPP TX failed ${it.javaClass.simpleName}: ${it.message}")
            // Close the broken socket; the permanent connect thread notices and retries.
            closeSocket()
            false
        }
    }

    private fun fetchAndStream(envelope: BusEnvelope, replyRemote: Boolean, replyBinder: IBinder?) {
        val request = envelope.payload
        val reply = { meta: JSONObject, data: ByteArray ->
            val response = BusEnvelope(path = BusPaths.HTTP_REPLY, id = envelope.id, payload = meta, binary = data)
            if (replyRemote) sendRemote(response) else deliverLocal(response, targetBinder = replyBinder)
        }
        var terminalSent = false
        fun terminal(status: Int, totalBytes: Long, errorCode: String? = null) {
            if (terminalSent) return
            terminalSent = true
            val meta = JSONObject()
                .put("status", status)
                .put("bytes", 0)
                .put("done", true)
                .put("totalBytes", totalBytes)
            errorCode?.let { meta.put("error", it) }
            reply(meta, ByteArray(0))
        }

        val callerHeaders = linkedMapOf<String, String>()
        request.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name -> callerHeaders[name] = headers.optString(name) }
        }
        val validation = HttpProxyPolicy.validate(
            urlText = request.optString("url"),
            methodText = request.optString("method", "GET").ifBlank { "GET" },
            callerHeaders = callerHeaders,
            body = request.optString("body").toByteArray(Charsets.UTF_8),
        )
        if (validation is HttpProxyPolicy.Validation.Rejected) {
            terminal(status = 0, totalBytes = 0L, errorCode = validation.errorCode)
            log("HTTP proxy rejected code=${validation.errorCode}")
            return
        }
        val allowed = (validation as HttpProxyPolicy.Validation.Allowed).request
        var connection: HttpURLConnection? = null
        var totalBytes = 0L
        try {
            connection = (allowed.url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = allowed.followRedirects
                requestMethod = allowed.method
                allowed.headers.forEach(::setRequestProperty)
                if (allowed.body.isNotEmpty()) {
                    doOutput = true
                    outputStream.use { it.write(allowed.body) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val budget = HttpProxyPolicy.ResponseBudget()
            val buffer = ByteArray(16 * 1024)
            stream?.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (!budget.accept(read)) {
                        terminal(
                            status = status,
                            totalBytes = budget.totalBytes,
                            errorCode = "RESPONSE_TOO_LARGE",
                        )
                        log("HTTP proxy failed code=RESPONSE_TOO_LARGE totalBytes=${budget.totalBytes}")
                        return
                    }
                    totalBytes = budget.totalBytes
                    reply(
                        JSONObject()
                            .put("status", status)
                            .put("bytes", read)
                            .put("done", false),
                        buffer.copyOf(read),
                    )
                }
            }
            terminal(status = status, totalBytes = budget.totalBytes)
            log("HTTP proxy complete status=$status totalBytes=${budget.totalBytes}")
        } catch (_: Throwable) {
            terminal(status = 0, totalBytes = totalBytes, errorCode = "UPSTREAM_FAILURE")
            log("HTTP proxy failed code=UPSTREAM_FAILURE")
        } finally {
            connection?.disconnect()
        }
    }

    private fun pluginRegistrationResult(pluginId: String, result: Int, reason: String? = null): Int {
        pluginBusJournal.record(
            pluginId = pluginId.ifBlank { null },
            category = PluginBusJournal.Category.REGISTRATION,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
            path = BusPaths.PLUGIN_REGISTRATION,
            verdict = if (result == PluginRegistrationResult.APPROVED) {
                PluginBusJournal.Verdict.OK
            } else {
                PluginBusJournal.Verdict.REJECTED
            },
            reason = reason,
        )
        return result
    }

    private fun addRegistration(
        clientId: String,
        prefixes: List<String>,
        uid: Int,
        cb: IBusCallback,
        principal: PhonePluginPrincipal? = null,
        grantedCapabilities: Set<PluginCapability> = emptySet(),
    ): Boolean {
        removeRegistrationsByBinder(cb.asBinder(), "replace")
        val callbackBinder = cb.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            removeRegistrationsByBinder(callbackBinder, "binderDied")
        }
        if (runCatching { callbackBinder.linkToDeath(deathRecipient, 0) }.isFailure) {
            pluginBusJournal.record(
                pluginId = principal?.descriptor?.id,
                category = PluginBusJournal.Category.REGISTRATION,
                direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
                path = BusPaths.PLUGIN_REGISTRATION,
                verdict = PluginBusJournal.Verdict.REJECTED,
                reason = "CALLBACK_UNAVAILABLE",
            )
            log("client registration rejected status=callback_unavailable")
            return false
        }
        val registration = Registration(
            clientId = clientId,
            prefixes = prefixes,
            uid = uid,
            callbackBinder = callbackBinder,
            callback = cb,
            deathRecipient = deathRecipient,
            principal = principal,
            grantedCapabilities = grantedCapabilities,
        )
        registrations += registration
        pluginBusJournal.record(
            pluginId = principal?.descriptor?.id,
            category = PluginBusJournal.Category.REGISTRATION,
            direction = PluginBusJournal.Direction.PLUGIN_TO_HUB,
            path = BusPaths.PLUGIN_REGISTRATION,
            reason = "CLIENT_REGISTERED",
        )
        runCatching { cb.onLinkState(linkState()) }
        PhoneClientSupervisor.onClientRegistered(applicationContext, prefixes, principal?.grantKey())
        return true
    }

    /**
     * Single permanent connection thread: the only place that ever creates,
     * assigns or retires the SPP socket. A parallel connect attempt against a
     * live RFCOMM link kills it at the stack level, so there must be exactly one.
     */
    @SuppressLint("MissingPermission")
    private fun connectSpp() {
        if (!sppLoopStarted.compareAndSet(false, true)) return
        Thread({
            var backoffMs = 1_000L
            while (!sppLoopStop) {
                if (!hubEnabled) {
                    sleepQuietly(750L)
                    continue
                }
                if (!hasBluetoothConnect()) {
                    log("Missing BLUETOOTH_CONNECT; SPP loop waiting")
                    sleepQuietly(5_000L)
                    continue
                }
                val device = pickBondedDevice()
                if (device == null) {
                    log("No bonded glasses device found; SPP loop waiting")
                    sleepQuietly(10_000L)
                    continue
                }
                var current: BluetoothSocket? = null
                try {
                    log("SPP connecting to bonded glasses")
                    current = device.createInsecureRfcommSocketToServiceRecord(BusConstants.SPP_UUID)
                    current.connect()
                    socket = current
                    output = current.outputStream
                    backoffMs = 1_000L
                    log("SPP connected")
                    notifyLinkState()
                    readSppLoop(current)
                    log("SPP link closed")
                } catch (t: Throwable) {
                    log("SPP connect failed: ${t.javaClass.simpleName}; retrying in ${backoffMs}ms")
                } finally {
                    runCatching { current?.close() }
                    if (socket === current) {
                        socket = null
                        output = null
                    }
                    notifyLinkState()
                }
                sleepQuietly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }, "rokidbus-spp").apply { isDaemon = true }.start()
    }

    private fun readSppLoop(activeSocket: BluetoothSocket) {
        val input = activeSocket.inputStream
        while (true) {
            val envelope = FrameProtocol.read(input) ?: return
            log("SPP RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickBondedDevice(): BluetoothDevice? {
        val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        return bonded.firstOrNull { it.address.equals(GLASSES_MAC, ignoreCase = true) }
            ?: bonded.firstOrNull { it.name.equals(GLASSES_NAME, ignoreCase = true) }
    }

    private fun startCxrIfTokenAvailable() {
        val token = prefs().getString(PREF_TOKEN, "").orEmpty()
        if (token.isNotBlank()) startCxr(token)
    }

    private fun startCxr(token: String) {
        val link = cxrLink ?: CXRLink(applicationContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    GLASSES_HUB_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(customCmdCallback)
        }.also { cxrLink = it }
        val bound = runCatching { link.connect(token) }.getOrDefault(false)
        log("CXR-L connect requested bound=$bound")
        if (!bound) {
            cxrConnected = false
            glassBtConnected = false
            notifyLinkState()
        }
    }

    private fun decodeCxrPayload(payload: ByteArray): BusEnvelope? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            FrameProtocol.fromJson(JSONObject(caps.at(0).string))
        }.onFailure {
            log("CXR decode failed: ${it.message}")
        }.getOrNull()

    private fun startForegroundWithType() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW),
        )
        val state = linkState()
        lastNotifiedStatus = statusText(state)
        startForeground(NOTIFICATION_ID, buildStatusNotification(state), ServiceInfoCompat.hubTypes())
    }

    private fun buildStatusNotification(state: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rokid Nexus")
            .setContentText(statusText(state))
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun statusText(state: Int): String = when {
        state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0 -> {
            val livePlugin = if (::externalPluginController.isInitialized) {
                externalPluginController.activeDisplayName()
            } else {
                null
            }
            if (livePlugin != null) "$livePlugin is live on the glasses" else "Connected to glasses"
        }
        state and LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED != 0 ->
            "Waiting for glasses"
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == false ->
            "Bluetooth is off"
        else -> "Pair your glasses to get started"
    }

    private fun updateStatusNotification(state: Int) {
        if (!hubEnabled) return
        val text = statusText(state)
        if (text == lastNotifiedStatus) return
        lastNotifiedStatus = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildStatusNotification(state))
    }

    private fun linkState(): Int {
        var state = 0
        if (isCxrUp()) state = state or LinkStateBits.CXR_CONTROL_UP
        if (output != null && socket?.isConnected == true) state = state or LinkStateBits.SPP_DATA_UP
        if (isGlassesBonded()) state = state or LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED
        return state
    }

    @SuppressLint("MissingPermission")
    private fun isGlassesBonded(): Boolean =
        hasBluetoothConnect() &&
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().any {
                it.address.equals(GLASSES_MAC, ignoreCase = true)
            }

    private fun isCxrUp(): Boolean =
        cxrConnected && glassBtConnected && cxrLink?.isServiceConnected() == true

    private fun notifyLinkState() {
        val state = linkState()
        val previousTransportState = lastTransportLinkState
        lastTransportLinkState = state
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (::cameraCompanionController.isInitialized &&
            ((previousTransportState and transportBits) and state.inv()) != 0
        ) {
            cameraCompanionController.onLinkLost()
        }
        if (state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) {
            remoteImageSurfaceVersion = 0
            remoteMaxImageBytes = 0
            imageSurfaceRateLimiter.clear()
        }
        updateStatusNotification(state)
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { removeRegistration(registration, "dead callback") }
        }
        if (::pluginRegistry.isInitialized &&
            state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0
        ) {
            pluginRegistry.syncLauncherList()
            announcePhoneCapabilities()
        } else {
            lastAnnouncedPhoneCapabilities = null
        }
    }

    /** The glasses learn phone-side feature bits (camera readiness) only through this. */
    private fun announcePhoneCapabilities() {
        val announced = PhoneHubCapabilitiesContract.create(
            features = capabilities(),
            cameraConsumerName = cameraConsumerReadiness.resolveApproved()?.descriptor?.displayName,
        )
        if (announced == lastAnnouncedPhoneCapabilities) return
        val envelope = BusEnvelope(
            path = BusPaths.HUB_CAPABILITIES,
            id = UUID.randomUUID().toString(),
            payload = PhoneHubCapabilitiesContract.toJson(announced),
        )
        if (sendRemote(envelope) == null) {
            lastAnnouncedPhoneCapabilities = announced
            log("phone capabilities announced features=${announced.features}")
        }
    }

    private fun errorEnvelope(id: String, code: String): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id),
        )

    private fun capabilities(): Int {
        var capabilities = 0
        if (::cameraConsumerReadiness.isInitialized && cameraConsumerReadiness.isReady()) {
            capabilities = capabilities or BusCapabilityBits.CAMERA_CONSUMER_READY
            val consumer = cameraConsumerReadiness.resolveApproved()
            if (consumer?.descriptor?.receivePrefixes?.contains(BusPaths.CAMERA_FREEZE_IMAGE_CHUNK) == true &&
                linkState() and LinkStateBits.SPP_DATA_UP != 0
            ) {
                capabilities = capabilities or BusCapabilityBits.CAMERA_FROZEN_SPP
            }
        }
        if (remoteImageSurfaceVersion == ImageSurfaceContract.VERSION &&
            remoteMaxImageBytes >= ImageSurfaceContract.MAX_IMAGE_BYTES &&
            linkState() and LinkStateBits.SPP_DATA_UP != 0
        ) {
            capabilities = capabilities or BusCapabilityBits.IMAGE_SURFACE
        }
        return capabilities
    }

    private fun updateRemoteCapabilities(payload: JSONObject) {
        val features = payload.optInt("features", 0)
        val supported = payload.optInt("version", 0) == 1 &&
            features and BusCapabilityBits.IMAGE_SURFACE != 0 &&
            payload.optInt("imageSurfaceVersion", 0) == ImageSurfaceContract.VERSION &&
            payload.optInt("maxImageBytes", 0) >= ImageSurfaceContract.MAX_IMAGE_BYTES
        remoteImageSurfaceVersion = if (supported) ImageSurfaceContract.VERSION else 0
        remoteMaxImageBytes = if (supported) payload.optInt("maxImageBytes") else 0
        log("renderer capabilities image=$supported maxImageBytes=$remoteMaxImageBytes")
        // Link bits may be unchanged; repeat the callback so clients refresh capabilities().
        notifyLinkState()
    }

    private fun validateImageEnvelope(envelope: BusEnvelope): String? {
        if (capabilities() and BusCapabilityBits.IMAGE_SURFACE == 0) {
            return ImageSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE
        }
        val validation = ImageSurfaceContract.validate(envelope.payload, envelope.binary)
        if (validation is ImageSurfaceValidationResult.Invalid) return validation.code
        val metadata = (validation as ImageSurfaceValidationResult.Valid).metadata
        return validateDecodedImageEnvelope(envelope, metadata)
    }

    private fun validateMediaArtworkEnvelope(envelope: BusEnvelope): String? {
        if (capabilities() and BusCapabilityBits.IMAGE_SURFACE == 0) {
            return ImageSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE
        }
        val validation = MediaArtworkContract.validate(envelope.payload, envelope.binary)
        if (validation is ImageSurfaceValidationResult.Invalid) return validation.code
        val metadata = (validation as ImageSurfaceValidationResult.Valid).metadata
        return validateDecodedImageEnvelope(envelope, metadata)
    }

    private fun validateSurfaceImageEnvelope(envelope: BusEnvelope): String? =
        when (envelope.payload.optString("kind")) {
            ImageSurfaceContract.KIND -> validateImageEnvelope(envelope)
            MediaArtworkContract.KIND -> if (
                MediaArtworkContract.hasBinaryArtwork(envelope.payload) || envelope.binary != null
            ) {
                validateMediaArtworkEnvelope(envelope)
            } else {
                null
            }
            else -> null
        }

    private fun validateDecodedImageEnvelope(
        envelope: BusEnvelope,
        metadata: ImageSurfaceMetadata,
    ): String? {
        val bytes = envelope.binary ?: return ImageSurfaceContract.ERROR_INVALID_IMAGE
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth != metadata.pixelWidth || options.outHeight != metadata.pixelHeight ||
            options.outMimeType != metadata.mimeType
        ) {
            return ImageSurfaceContract.ERROR_INVALID_IMAGE
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        if (surfaceId.isBlank()) return ImageSurfaceContract.ERROR_INVALID_IMAGE
        if (!imageSurfaceRateLimiter.tryAcquire(surfaceId)) {
            return ImageSurfaceContract.ERROR_IMAGE_RATE_LIMITED
        }
        return null
    }

    private fun pushDebugImageWhenReady() {
        repeat(20) {
            if (capabilities() and BusCapabilityBits.IMAGE_SURFACE != 0) {
                pushDebugImage()
                return
            }
            sleepQuietly(250L)
        }
        log("debug image probe failed code=${ImageSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE}")
    }

    private fun pushDebugImage() {
        val resourceId = resources.getIdentifier("image_surface_sample", "raw", packageName)
        if (resourceId == 0) {
            log("debug image probe failed code=RESOURCE_MISSING")
            return
        }
        val bytes = runCatching { resources.openRawResource(resourceId).use { it.readBytes() } }
            .getOrElse {
                log("debug image probe failed code=RESOURCE_READ_FAILED")
                return
            }
        val envelope = BusEnvelope(
            path = BusPaths.SURFACE_SHOW,
            payload = JSONObject()
                .put("surfaceId", "debug:image")
                .put("seq", debugImageSeq.incrementAndGet())
                .put("kind", ImageSurfaceContract.KIND)
                .put("imageVersion", ImageSurfaceContract.VERSION)
                .put("contentKey", "debug-tree-v1")
                .put("mimeType", ImageSurfaceContract.MIME_JPEG)
                .put("pixelWidth", 480)
                .put("pixelHeight", 480)
                .put("sha256", ImageSurfaceContract.sha256(bytes))
                .put("title", "Nexus image probe")
                .put("caption", "Phone hub to glasses over SPP")
                .put("footer", "debug build"),
            binary = bytes,
        )
        val validationError = validateImageEnvelope(envelope)
        if (validationError != null) {
            log("debug image probe failed code=$validationError")
            return
        }
        val sendError = sendRemote(envelope)
        if (sendError == null) {
            log("debug image probe sent bytes=${bytes.size} surfaceId=debug:image")
        } else {
            log("debug image probe failed code=$sendError")
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isDebuggableBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun log(message: String) {
        Log.i(TAG, message)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra("line", message))
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        @Volatile private var activeInstance: BusHubService? = null

        fun onPluginAuthorizationChanged(context: android.content.Context, key: PluginGrantKey) {
            PhoneClientSupervisor.onPrincipalRevoked(context.applicationContext, key)
            activeInstance?.authorizationChanged(key)
        }

        fun pluginCatalog(context: android.content.Context): PluginCatalog =
            activeInstance?.pluginRegistry?.catalog()
                ?: context.applicationContext.let { appContext ->
                    PluginCatalog.build(
                        builtIns = emptyList(),
                        candidates = PhonePluginDiscovery(appContext.packageManager).discover(),
                        grantState = PluginGrantStore(appContext)::stateFor,
                    )
                }

        fun pluginBusJournal(): PluginBusJournal? = activeInstance?.pluginBusJournal

        fun startWithToken(context: android.content.Context, token: String) {
            val intent = Intent(context, BusHubService::class.java)
                .setAction(ACTION_SET_TOKEN)
                .putExtra(EXTRA_AUTH_TOKEN, token)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: android.content.Context) {
            val intent = Intent(context, BusHubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startDebugImage(context: android.content.Context) {
            val intent = Intent(context, BusHubService::class.java).setAction(ACTION_DEBUG_IMAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Plain startService: callers are foreground UI; the hub must not re-promote itself. */
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_STOP),
            )
        }

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, true)
    }
}
