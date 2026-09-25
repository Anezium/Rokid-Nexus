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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.AssistantTakeoverAction
import com.anezium.rokidbus.shared.AssistantTakeoverContract
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.ForegroundSurfacePathPolicy
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.GlassesRepairContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.SetupNoteContract
import com.anezium.rokidbus.shared.ImageSurfaceMetadata
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.PhoneHubCapabilities
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteNavigationContract
import com.anezium.rokidbus.shared.RemotePointerContract
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppHandshakeDeadline
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SetupPairingFailureReason
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsValidationResult
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbContract
import com.anezium.rokidbus.shared.WirelessAdbReply
import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import com.anezium.rokidbus.shared.plugin.PluginCapability.Companion.serialize
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.phone.speech.HubSecretStore
import com.anezium.rokidbus.phone.speech.InternalAudioAccess
import com.anezium.rokidbus.phone.speech.InternalAudioAcquireResult
import com.anezium.rokidbus.phone.speech.InternalAudioConsumer
import com.anezium.rokidbus.phone.speech.InternalAudioStopReason
import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SpeechProvider
import com.anezium.rokidbus.phone.speech.SpeechSessionManager
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechSettingsStore
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SpeechUtteranceListener
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.SttErrorKind
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.GlassInfo
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import com.rokid.cxr.Caps
import com.anezium.rokidbus.phone.mediasync.MediaSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ROKIDBUS-PHONE"
private const val CHANNEL_ID = "rokidbus_phone"
private const val DEVELOPER_CHANNEL_ID = "developer"
private const val UPDATES_CHANNEL_ID = "updates"
private const val NOTIFICATION_ID = 1
private const val DEVELOPER_NOTIFICATION_ID = 2
private const val APP_UPDATE_NOTIFICATION_ID = 3
private const val PLUGIN_UPDATE_NOTIFICATION_ID = 4
private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val ACTION_SET_TOKEN = "com.anezium.rokidbus.phone.SET_TOKEN"
private const val ACTION_STOP = "com.anezium.rokidbus.phone.STOP"
private const val ACTION_DEBUG_IMAGE = "com.anezium.rokidbus.phone.DEBUG_IMAGE_SURFACE"
private const val ACTION_DEBUG_MANUAL_PAIRING = "com.anezium.rokidbus.phone.DEBUG_MANUAL_PAIRING"
private const val ACTION_INSTALL_GLASSES_APP = "com.anezium.rokidbus.phone.INSTALL_GLASSES_APP"
private const val ACTION_QUERY_GLASSES_APP = "com.anezium.rokidbus.phone.QUERY_GLASSES_APP"
private const val ACTION_OPEN_GLASSES_APP = "com.anezium.rokidbus.phone.OPEN_GLASSES_APP"
private const val ACTION_START_GLASSES_SETUP = "com.anezium.rokidbus.phone.START_GLASSES_SETUP"
private const val EXTRA_AUTH_TOKEN = "auth_token"
private const val EXTRA_MANUAL_OPERATION = "manual_operation"
private const val EXTRA_MANUAL_HOST = "manual_host"
private const val EXTRA_MANUAL_PAIR_PORT = "manual_pair_port"
private const val EXTRA_MANUAL_CODE = "manual_code"
private const val PREF_ENABLED = "hub_enabled"
private const val PREF_LAST_GLASSES_ADDRESS = "last_glasses_address"
private const val GLASSES_HUB_PACKAGE = "com.anezium.rokidbus.glasses"
private const val PREFS = "rokidbus_phone"
private const val PREF_TOKEN = "cxrl_token"
private const val UPDATE_NOTIFICATION_PREFERENCES = "update-notification-state"
private const val PREF_LAST_NOTIFIED_APP_VERSION = "last-notified-app-version"
private const val PREF_LAST_NOTIFIED_PLUGIN_UPDATES = "last-notified-plugin-updates"
private const val LOCAL_BINARY_MAX_BYTES = 512 * 1024
private const val GLASSES_RELEASE_CHECK_INTERVAL_MILLIS = 4L * 60L * 60L * 1000L
private const val BACKGROUND_UPDATE_CHECK_INTERVAL_MILLIS = 60L * 60L * 1000L
private const val AUDIO_LEASE_ACQUIRE = "/audio/lease/acquire"
private const val AUDIO_LEASE_RELEASE = "/audio/lease/release"
private const val AUDIO_FRAMES = "/audio/frames"
private const val AUDIO_LEASE_REVOKED = "/audio/lease/revoked"
private const val PLUGIN_AI_ASSIST_PATH = "/system/plugin/ai-assist"
private const val PLUGIN_AI_ASSIST_OPEN_TYPE = PluginOpenTypes.AI_ASSIST
private val NATIVE_ASSISTANT_EXIT_BURST_DELAYS_MILLIS = longArrayOf(0L, 50L, 150L, 300L)
private const val SNAPSHOT_JPEG_QUALITY = 80
private const val SNAPSHOT_ERROR_BUSY = "BUSY"
private const val SNAPSHOT_ERROR_LINK_DOWN = "LINK_DOWN"
private const val SNAPSHOT_ERROR_CAPTURE_FAILED = "CAPTURE_FAILED"
private const val SNAPSHOT_ERROR_TIMEOUT = "TIMEOUT"
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

    private enum class AudioLeaseSide { LOCAL, REMOTE, INTERNAL }

    private data class AudioLease(
        val leaseId: String,
        val side: AudioLeaseSide,
        val localCallbackBinder: IBinder?,
        val holderPluginId: String?,
        val internalTag: String? = null,
        val internalConsumer: InternalAudioConsumer? = null,
        @Volatile var streamStarted: Boolean = false,
        var seq: Long = 0L,
    )

    private class SpeechBusSession(
        val sessionId: String,
        val callbackBinder: IBinder,
        val pluginId: String,
        val grantKey: PluginGrantKey,
        val stateSeq: AtomicLong = AtomicLong(),
        val partialSeq: AtomicLong = AtomicLong(),
        val accepted: AtomicBoolean = AtomicBoolean(),
        val ended: AtomicBoolean = AtomicBoolean(),
    ) {
        lateinit var listener: SpeechUtteranceListener
    }

    private val executor = Executors.newCachedThreadPool()
    private val speechBusExecutor = SerialExecutor(executor)
    private val audioHandler = Handler(Looper.getMainLooper())
    private val pinHandler = Handler(Looper.getMainLooper())
    private val inkResultHandler = Handler(Looper.getMainLooper())
    private val pinExpiryTick: Runnable = Runnable { pinRouter.expireCanonical() }
    private val activityHandler = Handler(Looper.getMainLooper())
    private val activityExpiryTick: Runnable = Runnable { activityRouter.expireCanonical() }
    private val assistantExitHandler = Handler(Looper.getMainLooper())
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    @Volatile private var updateCheckLoopStopped = true
    private val updateCheckTick = object : Runnable {
        override fun run() {
            if (updateCheckLoopStopped) return
            runBackgroundUpdateChecks()
            if (!updateCheckLoopStopped) {
                updateCheckHandler.postDelayed(this, BACKGROUND_UPDATE_CHECK_INTERVAL_MILLIS)
            }
        }
    }
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val glassAiAssistActive = AtomicBoolean(false)
    private val snapshotCaptureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotCapture = CxrSnapshotCapture()
    private val snapshotInFlight = AtomicBoolean(false)
    @Volatile private var snapshotCaptureJob: Job? = null
    private val hudRouteSink: PhoneHudRouteSink = object : PhoneHudRouteSink {
        override fun capabilities(): Int = this@BusHubService.capabilities()
        override fun pinLinkUp(): Boolean = this@BusHubService.pinLinkUp()
        override fun log(message: String) = this@BusHubService.log(message)
        override fun sendRemote(envelope: BusEnvelope): String? = this@BusHubService.sendRemote(envelope)
        override fun deliverLocal(envelope: BusEnvelope, targetBinder: IBinder?): Boolean =
            this@BusHubService.deliverLocal(envelope, targetBinder = targetBinder)
        override fun deliverError(targetBinder: IBinder?, id: String, code: String) =
            this@BusHubService.deliverError(targetBinder, id, code)
        override fun recordLocalRoute(
            envelope: BusEnvelope,
            senderUid: Int,
            pluginId: String?,
            verdict: PluginBusJournal.Verdict,
            reason: String?,
        ) = this@BusHubService.recordLocalRoute(envelope, senderUid, pluginId, verdict, reason)
        override fun recordRemoteRoute(
            envelope: BusEnvelope,
            verdict: PluginBusJournal.Verdict,
            reason: String?,
        ) = this@BusHubService.recordRemoteRoute(envelope, verdict, reason)
    }
    private val noticeRouter = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { SystemClock.elapsedRealtime() }),
        sink = hudRouteSink,
    )
    private val pinRouter: PhonePinRouter = PhonePinRouter(
        state = PhonePinState(nowMs = { SystemClock.elapsedRealtime() }),
        sink = hudRouteSink,
        nowMs = SystemClock::elapsedRealtime,
        postExpiry = { delay -> pinHandler.postDelayed(pinExpiryTick, delay) },
        cancelExpiry = { pinHandler.removeCallbacks(pinExpiryTick) },
    )
    private val activityRouter: PhoneActivityRouter = PhoneActivityRouter(
        state = PhoneActivityState(nowMs = { SystemClock.elapsedRealtime() }),
        sink = hudRouteSink,
        nowMs = SystemClock::elapsedRealtime,
        postExpiry = { delay -> activityHandler.postDelayed(activityExpiryTick, delay) },
        cancelExpiry = { activityHandler.removeCallbacks(activityExpiryTick) },
    )
    private val surfaceRouter = PhoneSurfaceRouter(
        sink = hudRouteSink,
        onPluginSelfHid = ::onExternalSurfaceReleased,
    )
    private val inkRouter = PhoneInkRouter(
        sink = hudRouteSink,
        coordinator = PhoneInkSurfaceCoordinator(
            postResult = { action -> inkResultHandler.post { action() } },
        ),
        surfaceRouter = surfaceRouter,
    )
    private val debugImageSeq = AtomicLong(System.currentTimeMillis())
    private val imageSurfaceRateLimiter = ImageSurfaceRateLimiter()
    private val ttsRequestGate = PhoneTtsRequestGate()
    private lateinit var phoneTtsDispatcher: PhoneTtsDispatcher
    private val pluginBusJournal = busJournal
    private val sppLoopStarted = AtomicBoolean(false)
    private val audioLeaseArbitrator = SingleAudioLeaseArbitrator<AudioLease>()
    private val speechBusLock = Any()
    private var activeSpeechBusSession: SpeechBusSession? = null
    private val glassesAppOperationLock = Any()
    private val glassesAppStateLock = Any()
    private val glassesAppReleaseLock = Any()
    @Volatile private var sppLoopStop = false
    @Volatile private var hubEnabled = true
    @Volatile private var startupBlockedByBluetoothPermission = false
    private val sppLock = Any()
    private val sppDeadlines = Executors.newSingleThreadScheduledExecutor()
    private val sppNonces = SppAuthProtocol.RecentNonces()
    private val sppProvisioning = Executors.newSingleThreadExecutor()
    private val sppBackoff = SppReconnectBackoff()
    @Volatile private var sppKeyStatus: PhoneSppPairing.KeyStatus? = null
    private val sppCxrIdentity = SppCxrIdentity(
        schedule = { delay, task ->
            val future = sppDeadlines.schedule(task, delay, TimeUnit.MILLISECONDS)
            ({ future.cancel(false); Unit })
        },
        onReady = { revision ->
            sppBackoff.reset()
            offerSppKeyForCurrentCxr(revision)
        },
    )
    private val sppPairing by lazy {
        PhoneSppPairing(
            PhoneSppPairingStorage(applicationContext),
            hasCxrConnection = ::isCxrUp,
            onKeyStatus = ::updateSppKeyStatus,
        ) {
            sppCxrIdentity.current().takeIf { hubEnabled && !sppLoopStop && isCxrUp() }
        }
    }
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var sppSession: SppAuthProtocol.Session? = null
    private var cxrLink: CXRLink? = null
    private lateinit var pluginRegistry: PhonePluginRegistry
    private lateinit var pluginDiscovery: PhonePluginDiscovery
    private lateinit var pluginGrantStore: PluginGrantStore
    private lateinit var assistantTakeoverStore: AssistantTakeoverStore
    private lateinit var pluginGrantReconciler: PluginGrantReconciler
    private lateinit var registryClient: RegistryClient
    private lateinit var developerModeStore: DeveloperModeStore
    private var developerModeJournalSubscription: DeveloperModeStore.Subscription? = null
    private lateinit var externalPluginController: ExternalPluginController
    private lateinit var cameraConsumerReadiness: CameraConsumerReadiness
    private lateinit var cameraCompanionController: CameraCompanionController
    private lateinit var pluginGuardianCoordinator: PluginGuardianCoordinator
    private lateinit var mediaSyncCoordinator: MediaSyncCoordinator
    private lateinit var coreRemoteBridge: PhoneCoreRemoteBridge
    private lateinit var manualPairingEngine: GlassesManualPairingEngine
    private var manualPairingEngineSubscription: Closeable? = null
    private val phoneAssistedSetupOfferPolicy = PhoneAssistedSetupOfferPolicy()
    private val phoneAssistedPairingLock = Any()
    private var activePhoneAssistedPairing: ActivePhoneAssistedPairing? = null
    private lateinit var transitLegacyStateExporter: TransitLegacyStateExporter
    private lateinit var speechSettingsStore: SpeechSettingsStore
    private lateinit var speechSessionManager: SpeechSessionManager
    @Volatile private var speechMicrophoneForegroundRequested = false
    @Volatile private var speechMicrophoneForegroundActive = false
    @Volatile private var speechMicrophoneForegroundFailure = ""
    @Volatile private var cxrConnected = false
    @Volatile private var glassBtConnected = false
    @Volatile private var glassesWorn = false
    @Volatile private var glassesAppInstallState: GlassesAppInstallState = GlassesAppInstallState.Unknown
    private var glassesAppOperationSequence = 0L
    private var activeGlassesAppOperationId: Long? = null
    @Volatile private var lastAnnouncedPhoneCapabilities: PhoneHubCapabilities? = null
    private var phoneBatteryReporter: PhoneBatteryReporter? = null
    private var phoneBatteryBadgeSubscription: PhoneBatteryBadgeStore.Subscription? = null
    @Volatile private var lastNotifiedStatus: String? = null
    @Volatile private var remoteImageSurfaceVersion = 0
    @Volatile private var remotePinSurfaceVersion = 0
    @Volatile private var remoteNoticeSurfaceVersion = 0
    @Volatile private var remoteActivitySurfaceVersion = 0
    @Volatile private var remoteInkSurfaceVersion = 0
    @Volatile private var remoteEditableSurfaceVersion = 0
    @Volatile private var remoteMaxImageBytes = 0
    @Volatile private var remoteGlassesVersionName: String? = null
    @Volatile private var remoteGlassesSetupComplete = false
    @Volatile private var remoteGlassesSetupFailureState = ""
    @Volatile private var remoteGlassesSetupFailureDiagnostic = ""
    @Volatile private var remoteGlassesSetupSessionId = ""
    @Volatile private var remoteGlassesSetupStage = ""
    @Volatile private var remoteGlassesSetupRunning = false
    @Volatile private var remoteGlassesSetupRequiresUserAction = false
    @Volatile private var remoteGlassesSetupSupportCode = ""
    @Volatile private var remoteGlassesSetupCompletionMode = ""
    @Volatile private var remoteGlassesCoreReady = false
    @Volatile private var remoteGlassesMaintenanceReady = false
    @Volatile private var latestGlassesAppRelease: NexusReleaseAsset? = null
    @Volatile private var glassesAppUpdateState: GlassesAppUpdateState = GlassesAppUpdateState.Unknown
    @Volatile private var glassesReleaseCheckedAtMillis = 0L
    private var glassesReleaseCheckInFlight = false
    @Volatile private var lastTransportLinkState = 0
    private var pluginPackageReceiverRegistered = false
    private var wifiStateReceiverRegistered = false
    private val notifiedDeveloperPackages = ConcurrentHashMap.newKeySet<String>()

    private data class ActivePhoneAssistedPairing(
        val sessionId: String,
        val offerId: String,
        var lastState: GlassesManualPairingState = GlassesManualPairingState.IDLE,
    )

    private val pluginPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val replacing = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true
            if (!PluginPackageChangePolicy.shouldReconcile(
                    action = action,
                    replacing = replacing,
                )
            ) return
            val packageName = intent?.data?.schemeSpecificPart.orEmpty()
            if (packageName.isNotBlank()) reconcilePluginPackage(packageName, action, replacing)
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                WifiManager.WIFI_STATE_ENABLED,
                WifiManager.WIFI_STATE_DISABLED,
                -> announcePhoneCapabilities()
            }
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

        /**
         * Answered from the registration this UID already holds, so it can only ever
         * describe the caller: an unknown plugin id, another app's id, or a caller with
         * no live registration all get "" rather than somebody else's grants.
         */
        override fun approvedCapabilities(pluginId: String): String {
            val callingUid = Binder.getCallingUid()
            val registration = registrations.firstOrNull { registration ->
                registration.uid == callingUid &&
                    registration.principal?.descriptor?.id == pluginId
            } ?: return ""
            return PluginCapability.serialize(registration.grantedCapabilities)
        }

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
            sppCxrIdentity.onConnectionChanged(isCxrUp())
            if (!connected) glassesWorn = false
            log("CXR-L connected=$connected")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassBtConnected = connected
            sppCxrIdentity.onConnectionChanged(isCxrUp())
            if (!connected) glassesWorn = false
            log("Hi Rokid glass BT connected=$connected")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }

        override fun onGlassDeviceInfo(info: GlassInfo) {
            sppCxrIdentity.onDeviceInfo(info.sn, info.deviceName)
            notifyGlassesDeviceInfo(info)
        }

        override fun onGlassWearingStatus(wearing: Boolean) {
            glassesWorn = wearing
            notifyLinkState()
        }

        override fun onGlassAiAssistStart() {
            if (!glassAiAssistActive.compareAndSet(false, true)) return
            // Paused from the glasses or the phone: the native scene the ROM just opened is
            // exactly what the wearer asked for, so there is nothing to dismiss and nobody to wake.
            if (::assistantTakeoverStore.isInitialized && !assistantTakeoverStore.isEnabled()) {
                log("assistant gesture left to Rokid: takeover paused")
                return
            }
            val assistant = approvedAssistantPrincipal() ?: return
            val gestureId = UUID.randomUUID().toString()
            val alreadyActive = externalPluginController.activeId() == assistant.descriptor.id
            val captureSignaled = if (alreadyActive) {
                notifyGlassesAiButton(active = true)
                true
            } else {
                externalPluginController.open(
                    assistant,
                    ExternalPluginOpenRequest(
                        type = PLUGIN_AI_ASSIST_OPEN_TYPE,
                        followUp = ExternalPluginOpenFollowUp(
                            path = PLUGIN_AI_ASSIST_PATH,
                            type = PLUGIN_AI_ASSIST_OPEN_TYPE,
                            extra = {
                                JSONObject()
                                    .put("gestureId", gestureId)
                                    .put("buttonActive", glassAiAssistActive.get())
                                    .put("source", "glass_ai_assist_start")
                            },
                        ),
                    ),
                )
            }
            if (!captureSignaled) {
                log("assistant gesture wake failed plugin=${assistant.descriptor.id}")
                return
            }
            sendNativeAssistantExitBurst(gestureId)
            val error = sendRemote(
                BusEnvelope(
                    path = BusPaths.GLASSES_ASSISTANT_DISMISS,
                    payload = JSONObject()
                        .put("version", 1)
                        .put("gestureId", gestureId)
                        .put("source", "glass_ai_assist_start"),
                ),
            )
            log(
                "assistant gesture plugin=${assistant.descriptor.id} active=$alreadyActive " +
                    "gestureId=$gestureId; native dismiss " +
                    "sent=${error == null} result=${error ?: "OK"}",
            )
        }

        override fun onGlassAiAssistStop() {
            // Also reached as the echo of our own sendExit burst (the native
            // scene closing reports onAiExit). Must stay side-effect-free
            // beyond flag/notify: capture stop belongs to the plugin's VAD,
            // and anything stronger here would let the burst cancel it.
            glassAiAssistActive.set(false)
            notifyGlassesAiButton(active = false)
        }
    }

    // Phone-side kill of the native assistant popup, complementing the
    // glasses-side BACK burst: Hi Rokid's exported service forwards sendExit
    // to the scene the gesture just opened. A single shot can lose the race
    // against the scene's opening animation, hence the spaced repeats.
    private fun sendNativeAssistantExitBurst(gestureId: String) {
        val link = cxrLink ?: return
        for (delayMs in NATIVE_ASSISTANT_EXIT_BURST_DELAYS_MILLIS) {
            assistantExitHandler.postDelayed({
                val sent = link.sendExit(false)
                log("assistant native exit burst delay=${delayMs}ms sent=$sent gestureId=$gestureId")
            }, delayMs)
        }
    }

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
            runCatching {
                forwardAudioFrame(data, offset, length)
            }.onFailure {
                log("CXR audio frame failed ${it.javaClass.simpleName}: ${it.message}")
            }
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
            // Every way out of this callback says so. A frame that reaches the phone's Rokid app
            // and then vanishes here used to leave no trace at all, which made "the callback was
            // never invoked" and "the callback ran and dropped the frame" impossible to tell apart
            // from a log - the two have completely different causes.
            if (key != BusConstants.CXR_KEY) {
                log("CXR RX ignored: key=$key")
                return
            }
            val envelope = decodeCxrPayload(payload)
            if (envelope == null) {
                log("CXR RX undecodable: ${payload.size} bytes")
                return
            }
            if (SppKeyProvisioning.isReserved(envelope.path)) return
            log("CXR RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NexusPhoneState.restore(applicationContext)
        activeInstance = this
        coreRemoteBridge = PhoneCoreRemoteBridge(
            context = applicationContext,
            sendRemote = ::sendRemote,
            sendNativePointer = ::sendNativePointer,
            isConnected = {
                linkState() and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0
            },
            isNativePointerAvailable = ::isCxrUp,
        ).also(PhoneCoreRemoteBridge::start)
        val phoneSpeakerRouteProbe = PhoneSpeakerRouteProbe(applicationContext) {
            prefs().getString(PREF_LAST_GLASSES_ADDRESS, null)
        }
        phoneTtsDispatcher = PhoneTtsDispatcher(
            playback = PhoneTtsPlayback(
                output = PhoneTtsEngine(applicationContext, ::log),
                emitStarted = ::emitPhoneTtsStarted,
                emitDone = ::emitPhoneTtsDone,
            ),
            emitDone = ::emitPhoneTtsDone,
            phoneRoute = phoneSpeakerRouteProbe::classifyRoute,
            logger = ::log,
        )
        phoneTtsDispatcher.initialize()
        manualPairingEngine = GlassesManualPairingEngine.create(
            context = applicationContext,
            control = GlassesManualControlSender(::sendManualSelfArmControl),
            logger = ::log,
        )
        manualPairingEngineSubscription =
            manualPairingEngine.observe(::onPhoneAssistedPairingStateChanged)
        // The glasses only re-advertise their setup state once CXR is back up. Without seeding
        // these from the last persisted values, a hub restart (e.g. after an app update kills the
        // process) would broadcast setupComplete=false and versionName="" before the glasses
        // reconnect, which resets the phone's finished onboarding and strands the setup step on
        // "Connect your glasses". Start from what we last knew; a live advertisement still wins.
        prefs().let { stored ->
            remoteGlassesSetupComplete = stored.getBoolean(
                NexusPhoneState.PREF_GLASSES_SETUP_COMPLETE,
                false,
            )
            remoteGlassesVersionName = stored.getString(
                NexusPhoneState.PREF_INSTALLED_GLASSES_VERSION_NAME,
                null,
            )?.trim()?.takeIf { it.isNotEmpty() }
            remoteGlassesSetupFailureState = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_FAILURE_STATE,
                "",
            ).orEmpty()
            remoteGlassesSetupFailureDiagnostic = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_FAILURE_DIAGNOSTIC,
                "",
            ).orEmpty()
            remoteGlassesSetupSessionId = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_SESSION_ID,
                "",
            ).orEmpty()
            remoteGlassesSetupStage = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_STAGE,
                "",
            ).orEmpty()
            remoteGlassesSetupRunning = stored.getBoolean(
                NexusPhoneState.PREF_GLASSES_SETUP_RUNNING,
                false,
            )
            remoteGlassesSetupRequiresUserAction = stored.getBoolean(
                NexusPhoneState.PREF_GLASSES_SETUP_REQUIRES_USER_ACTION,
                false,
            )
            remoteGlassesSetupSupportCode = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_SUPPORT_CODE,
                "",
            ).orEmpty()
            remoteGlassesSetupCompletionMode = stored.getString(
                NexusPhoneState.PREF_GLASSES_SETUP_COMPLETION_MODE,
                "",
            ).orEmpty()
            remoteGlassesCoreReady = stored.getBoolean(
                NexusPhoneState.PREF_GLASSES_CORE_READY,
                false,
            )
            remoteGlassesMaintenanceReady = stored.getBoolean(
                NexusPhoneState.PREF_GLASSES_MAINTENANCE_READY,
                false,
            )
        }
        developerModeStore = DeveloperModeStore(applicationContext)
        developerModeJournalSubscription = bindDeveloperModeToJournal(developerModeStore, pluginBusJournal)
        PhoneClientSupervisor.attach(this)
        pluginDiscovery = PhonePluginDiscovery(packageManager)
        pluginGrantStore = PluginGrantStore(applicationContext)
        assistantTakeoverStore = AssistantTakeoverStore(applicationContext)
        registryClient = RegistryClient.create(applicationContext)
        pluginGrantReconciler = PluginGrantReconciler(
            discoverCandidates = pluginDiscovery::discover,
            reconcileGrants = pluginGrantStore::reconcile,
        )
        executor.execute { pluginGrantReconciler.reconcile() }
        pluginGuardianCoordinator = PluginGuardianCoordinator(
            context = applicationContext,
            targetProvider = ::approvedGuardianTargets,
            logger = ::log,
        )
        cameraConsumerReadiness = CameraConsumerReadiness(
            installedPrincipals = ::installedPluginPrincipals,
            grantState = pluginGrantStore::stateFor,
        ).also { it.recompute() }
        transitLegacyStateExporter = TransitLegacyStateExporter(
            AndroidTransitLegacyStateStorage(applicationContext),
        )
        speechSettingsStore = SpeechSettingsStore(applicationContext)
        speechSessionManager = SpeechSessionManager(
            context = applicationContext,
            settings = speechSettingsStore,
            secrets = HubSecretStore(applicationContext),
            internalAudio = object : InternalAudioAccess {
                override fun acquireInternalAudio(
                    tag: String,
                    consumer: InternalAudioConsumer,
                ): InternalAudioAcquireResult =
                    this@BusHubService.acquireInternalAudio(tag, consumer)

                override fun releaseInternalAudio(tag: String) {
                    this@BusHubService.releaseInternalAudio(tag)
                }
            },
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
            onBackgroundChanged = NexusPhoneState::setBackgroundAudioPluginId,
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
                    registryFeed = registryClient.cachedSnapshot()?.feed
                        ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList()),
                    grantState = pluginGrantStore::stateFor,
                )
            },
            externalController = externalPluginController,
            journal = pluginBusJournal,
        )
        mediaSyncCoordinator = MediaSyncCoordinator(
            context = applicationContext,
            sendToGlasses = { path, payload ->
                sendRemote(BusEnvelope(path = path, payload = payload)) == null
            },
            // Nothing photo sync sends from the phone is bulky; only the glasses stream bytes.
            publishStatus = ::publishMediaSyncStatus,
            logger = ::log,
        )
        refreshMediaSyncConsent()
        registerPluginPackageReceiver()
        registerWifiStateReceiver()
        phoneBatteryReporter = PhoneBatteryReporter(
            context = this,
            send = ::sendRemote,
            log = ::log,
            initiallyEnabled = PhoneBatteryBadgeStore(this).isEnabled(),
        ).also { it.start() }
        phoneBatteryBadgeSubscription = PhoneBatteryBadgeStore(this).addChangeListener { enabled ->
            phoneBatteryReporter?.setEnabled(enabled)
        }
        hubEnabled = prefs().getBoolean(PREF_ENABLED, true)
        if (hubEnabled) {
            if (!canRunHub(this)) {
                startupBlockedByBluetoothPermission = true
                log("BusHubService start deferred: BLUETOOTH_CONNECT permission not granted; stopping service")
                stopSelf()
                return
            }
            startForegroundWithType()
            startCxrIfTokenAvailable()
        }
        connectSpp()
        startPeriodicUpdateChecks()
        log("BusHubService created enabled=$hubEnabled")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupBlockedByBluetoothPermission && !canRunHub(this)) {
            if (intent?.action == ACTION_STOP) {
                prefs().edit().putBoolean(PREF_ENABLED, false).apply()
                hubEnabled = false
            }
            log("BusHubService command skipped: BLUETOOTH_CONNECT permission not granted")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startupBlockedByBluetoothPermission = false
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
            ACTION_DEBUG_MANUAL_PAIRING -> {
                if (isDebuggableBuild()) {
                    enableHub()
                    startCxrIfTokenAvailable()
                    when (intent.getStringExtra(EXTRA_MANUAL_OPERATION)) {
                        "start" -> manualPairingEngine.start()
                        "submit" -> {
                            val host = intent.getStringExtra(EXTRA_MANUAL_HOST).orEmpty()
                            val pairPort = intent.getIntExtra(EXTRA_MANUAL_PAIR_PORT, 0)
                            val code = intent.getStringExtra(EXTRA_MANUAL_CODE).orEmpty()
                            intent.removeExtra(EXTRA_MANUAL_CODE)
                            manualPairingEngine.submit(host, pairPort, code)
                        }
                        "cancel" -> manualPairingEngine.cancel()
                    }
                } else {
                    log("debug manual pairing rejected status=release_build")
                }
            }
            ACTION_INSTALL_GLASSES_APP -> installGlassesApp()
            ACTION_QUERY_GLASSES_APP -> queryGlassesApp()
            ACTION_OPEN_GLASSES_APP -> openGlassesAppOnLens()
            ACTION_START_GLASSES_SETUP -> startGlassesSetupOnLens()
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
        if (canRunHub(this)) {
            startForegroundWithType()
        } else {
            log("Hub enabled; foreground start deferred until BLUETOOTH_CONNECT is granted")
        }
    }

    /** Release every radio resource: SPP socket, CXR-L session, foreground state. */
    private fun stopHub() {
        prefs().edit().putBoolean(PREF_ENABLED, false).apply()
        hubEnabled = false
        if (::speechSessionManager.isInitialized) speechSessionManager.cancel()
        stopAudioLease(InternalAudioStopReason.HUB_STOPPED)
        activityRouter.clearAllForHubStop()
        snapshotCaptureJob?.cancel()
        if (::coreRemoteBridge.isInitialized) coreRemoteBridge.close()
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        cxrConnected = false
        glassBtConnected = false
        sppCxrIdentity.onConnectionChanged(false)
        glassesWorn = false
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
        stopPeriodicUpdateChecks()
        pinHandler.removeCallbacks(pinExpiryTick)
        inkResultHandler.removeCallbacksAndMessages(null)
        activityHandler.removeCallbacks(activityExpiryTick)
        activityRouter.clearAllForHubStop()
        sppLoopStop = true
        sppBackoff.reset()
        sppCxrIdentity.onConnectionChanged(false)
        sppDeadlines.shutdownNow()
        sppProvisioning.shutdownNow()
        if (::speechSessionManager.isInitialized) speechSessionManager.close()
        stopAudioLease(InternalAudioStopReason.HUB_STOPPED)
        snapshotCaptureJob?.cancel()
        snapshotCaptureScope.cancel()
        if (::phoneTtsDispatcher.isInitialized) phoneTtsDispatcher.shutdown()
        if (::coreRemoteBridge.isInitialized) coreRemoteBridge.close()
        runCatching { cxrLink?.disconnect() }
        closeSocket()
        PhoneClientSupervisor.detach(applicationContext, this)
        if (pluginPackageReceiverRegistered) {
            runCatching { unregisterReceiver(pluginPackageReceiver) }
            pluginPackageReceiverRegistered = false
        }
        if (wifiStateReceiverRegistered) {
            runCatching { unregisterReceiver(wifiStateReceiver) }
            wifiStateReceiverRegistered = false
        }
        phoneBatteryReporter?.stop()
        phoneBatteryReporter = null
        phoneBatteryBadgeSubscription?.close()
        phoneBatteryBadgeSubscription = null
        developerModeJournalSubscription?.close()
        developerModeJournalSubscription = null
        if (::pluginGuardianCoordinator.isInitialized) pluginGuardianCoordinator.close()
        if (::pluginRegistry.isInitialized) pluginRegistry.close()
        inkRouter.close()
        if (::cameraCompanionController.isInitialized) cameraCompanionController.close()
        if (::mediaSyncCoordinator.isInitialized) mediaSyncCoordinator.close()
        synchronized(phoneAssistedPairingLock) { activePhoneAssistedPairing = null }
        manualPairingEngineSubscription?.close()
        manualPairingEngineSubscription = null
        if (::manualPairingEngine.isInitialized) manualPairingEngine.close()
        registrations.clear()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    fun deliverQueued(envelope: BusEnvelope): Boolean =
        deliverLocal(envelope)

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        if (SppKeyProvisioning.isReserved(envelope.path)) return
        val sender = resolveSender(senderUid)
        if (isGlassesControlRequest(envelope.path)) {
            val strictlyHubOwned = isStrictlyHubOwnedGlassesPath(envelope.path)
            if (senderUid != Process.myUid() && (strictlyHubOwned || !isDebuggableBuild())) {
                recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, "TRUSTED_LOCAL_ONLY")
                deliverError(sender.replyBinder, envelope.id, "TRUSTED_LOCAL_ONLY")
                return
            }
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.OK)
            executor.execute { handleGlassesControlRequest(envelope, sender.replyBinder) }
            return
        }
        if (!protectedPathAllowed(envelope.path, senderUid, sender.principal)) {
            val code = if (BusPaths.isProtectedMediaSyncPath(envelope.path)) {
                "PROTECTED_MEDIASYNC_PATH"
            } else {
                "PROTECTED_CAMERA_PATH"
            }
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, code)
            deliverError(sender.replyBinder, envelope.id, code)
            return
        }
        val decision = PluginRoutePolicy.authorize(sender.caller, envelope.path)
        if (decision is PluginRouteDecision.Denied) {
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, decision.code)
            deliverError(sender.replyBinder, envelope.id, decision.code)
            return
        }
        if (envelope.path == BusPaths.TTS_SPEAK || envelope.path == BusPaths.TTS_STOP) {
            handleLocalTts(envelope, senderUid, sender)
            return
        }
        val invalidHud = when {
            pinRouter.matches(envelope.path) -> pinRouter.invalidLocalReason(envelope)
            noticeRouter.matches(envelope.path) -> noticeRouter.invalidLocalReason(envelope)
            activityRouter.matches(envelope.path) -> activityRouter.invalidLocalReason(envelope)
            else -> null
        }
        if (invalidHud != null) {
            recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, invalidHud)
            deliverError(sender.replyBinder, envelope.id, invalidHud)
            return
        }
        val ownedEnvelope = if (
            sender.principal != null &&
            PathRules.requiredCapability(envelope.path) in setOf(
                PluginCapability.SURFACES,
                PluginCapability.INK_SURFACE,
            )
        ) {
            val payload = PluginRoutePolicy.injectSurfaceOwner(sender.principal.descriptor.id, envelope.payload)
            if (payload == null) {
                val error = if (
                    envelope.path == BusPaths.PIN_SHOW || envelope.path == BusPaths.PIN_HIDE
                ) {
                    PinSurfaceContract.ERROR_INVALID_PIN
                } else if (noticeRouter.matches(envelope.path)) {
                    NoticeSurfaceContract.ERROR_INVALID_NOTICE
                } else if (activityRouter.matches(envelope.path)) {
                    ActivitySurfaceContract.ERROR_INVALID_ACTIVITY
                } else {
                    "INVALID_SURFACE_ID"
                }
                recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, error)
                deliverError(sender.replyBinder, envelope.id, error)
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
            // A plugin that answers its PLUGIN_OPEN on any owner-drawn tier is
            // alive; a notice-only assistant must not be rebound as unresponsive.
            // This must run before the tier dispatches below return -- including
            // the notice image check, which rejects and returns.
            ownedEnvelope.path in setOf(
                BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE, BusPaths.SURFACE_HIDE,
                BusPaths.NOTICE_SHOW, BusPaths.NOTICE_UPDATE, BusPaths.NOTICE_HIDE,
                BusPaths.PIN_SHOW, BusPaths.PIN_HIDE,
                BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE, BusPaths.ACTIVITY_END,
                BusPaths.INK_SHOW, BusPaths.INK_UPDATE, BusPaths.INK_HIDE,
            ) &&
            ::externalPluginController.isInitialized
        ) {
            externalPluginController.onPluginActivity(sender.principal.descriptor.id)
        }
        if (ownedEnvelope.path == BusPaths.NOTICE_SHOW && ownedEnvelope.binary != null) {
            val validation = NoticeSurfaceContract.validateShow(
                ownedEnvelope.payload,
                ownedEnvelope.binary,
            )
            val metadata = (validation as? NoticeSurfaceValidationResult.Valid)
                ?.content
                ?.image
            val imageError = if (
                metadata == null || validateDecodedImageEnvelope(ownedEnvelope, metadata) != null
            ) {
                NoticeSurfaceContract.ERROR_INVALID_NOTICE
            } else {
                null
            }
            if (imageError != null) {
                recordLocalRoute(
                    ownedEnvelope,
                    senderUid,
                    sender,
                    PluginBusJournal.Verdict.REJECTED,
                    imageError,
                )
                deliverError(sender.replyBinder, ownedEnvelope.id, imageError)
                return
            }
        }
        if (pinRouter.matches(ownedEnvelope.path)) {
            pinRouter.handleLocal(ownedEnvelope, sender.toHudSender(senderUid))
            return
        }
        if (noticeRouter.matches(ownedEnvelope.path)) {
            noticeRouter.handleLocal(ownedEnvelope, sender.toHudSender(senderUid))
            return
        }
        if (activityRouter.matches(ownedEnvelope.path)) {
            activityRouter.handleLocal(ownedEnvelope, sender.toHudSender(senderUid))
            return
        }
        if (
            sender.principal != null &&
            ForegroundSurfacePathPolicy.isShowOrUpdate(ownedEnvelope.path) &&
            ::pluginRegistry.isInitialized &&
            !pluginRegistry.allowExternalSurface(sender.principal, ownedEnvelope.path)
        ) {
            recordLocalRoute(ownedEnvelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, "SURFACE_BUSY")
            if (PathRules.requiredCapability(ownedEnvelope.path) == PluginCapability.INK_SURFACE) {
                inkRouter.deliverProblems(
                    inkRouter.ownerFrom(ownedEnvelope),
                    ownedEnvelope.id,
                    sender.replyBinder,
                    listOf(InkProblem("SURFACE_BUSY", "Another plugin owns the foreground surface")),
                )
            } else {
                deliverError(sender.replyBinder, ownedEnvelope.id, "SURFACE_BUSY")
            }
            log("surface rejected path=${ownedEnvelope.path} plugin=${sender.principal.descriptor.id} reason=foreground_busy")
            return
        }
        if (inkRouter.matches(ownedEnvelope.path)) {
            inkRouter.handleLocal(ownedEnvelope, sender.toHudSender(senderUid))
            return
        }
        val authorizedEnvelope = if (
            sender.principal != null &&
            ownedEnvelope.path in setOf(
                BusPaths.SURFACE_SHOW,
                BusPaths.SURFACE_UPDATE,
                BusPaths.SURFACE_HIDE,
            )
        ) {
            surfaceRouter.withMetadata(
                ownedEnvelope,
                sender.principal.descriptor.id,
                closeOnHide = true,
            )
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
                principal = sender.principal,
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
        if (SppKeyProvisioning.isReserved(envelope.path)) return
        if (envelope.path == "/hub/probe") {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            log("hub probe received from glasses")
            return
        }
        if (envelope.path == BusPaths.HUB_CAPABILITIES) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            updateRemoteCapabilities(envelope.payload)
            // The glasses re-announce on every transport-up, including after a hub restart that
            // wiped their photo-sync consent. Re-push it on the same edge.
            if (::mediaSyncCoordinator.isInitialized) {
                executor.execute { mediaSyncCoordinator.onLinkUp() }
            }
            // The glasses persist the boot-repair switch, but a reinstall or a toggle flipped
            // while the link was down leaves them stale; ride the same edge the consent does.
            executor.execute { pushGlassesRepairConfig() }
            return
        }
        if (envelope.path == RemoteInputContract.SESSION_PATH ||
            envelope.path == RemoteInputContract.STATUS_PATH ||
            envelope.path == RemoteNavigationContract.RESULT_PATH ||
            envelope.path == RemotePointerContract.RESULT_PATH ||
            envelope.path == NativeAppContract.RESULT_PATH
        ) {
            val handled = ::coreRemoteBridge.isInitialized && coreRemoteBridge.handleRemote(envelope)
            recordRemoteRoute(
                envelope,
                if (handled) PluginBusJournal.Verdict.OK else PluginBusJournal.Verdict.REJECTED,
                if (handled) null else "INVALID_CORE_RESULT",
            )
            if (!handled) sendRemote(errorEnvelope(envelope.id, "INVALID_CORE_RESULT"))
            return
        }
        if (envelope.path == BusPaths.GLASSES_SETUP_NOTE) {
            // Diagnostics only: file it and move on. Nothing about setup waits on a note.
            SetupNoteContract.fromJson(envelope.payload)?.let { note ->
                SetupJournal.record(
                    context = applicationContext,
                    fromGlasses = true,
                    code = note.code,
                    detail = listOfNotNull(
                        note.stage.takeIf { it.isNotBlank() },
                        note.detail.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
            return
        }
        if (envelope.path == BusPaths.GLASSES_SETUP_PAIRING_OFFER) {
            val arrivedAtMillis = SystemClock.elapsedRealtime()
            executor.execute { handlePhoneAssistedSetupOffer(envelope, arrivedAtMillis) }
            return
        }
        if (handleManualSelfArmResponse(envelope)) return
        if (::mediaSyncCoordinator.isInitialized && handleMediaSyncRemote(envelope)) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (::cameraCompanionController.isInitialized &&
            cameraCompanionController.onRemoteEnvelope(envelope)
        ) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (envelope.path == BusPaths.INK_EVENT) {
            inkRouter.handleGlassesEvent(envelope)
            return
        }
        if (::pluginRegistry.isInitialized && pluginRegistry.handleRemote(envelope)) return
        if (handleHubPath(envelope, replyRemote = true)) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            return
        }
        if (envelope.path == BusPaths.NOTICE_INPUT) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            noticeRouter.handleGlassesInput(envelope)
            return
        }
        if (envelope.path == BusPaths.NOTICE_ACTION) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            noticeRouter.handleGlassesAction(envelope)
            return
        }
        if (envelope.path == BusPaths.NOTICE_CLOSED) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            noticeRouter.handleGlassesClosed(envelope)
            return
        }
        if (envelope.path == BusPaths.ACTIVITY_ACTION) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            activityRouter.handleGlassesAction(envelope)
            return
        }
        if (envelope.path == BusPaths.ACTIVITY_CLOSED) {
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            activityRouter.handleGlassesClosed(envelope)
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
    ) = recordLocalRoute(envelope, senderUid, sender.principal?.descriptor?.id, verdict, reason)

    private fun AuthorizedSender.toHudSender(uid: Int) = PhoneHudRouteSender(
        pluginId = principal?.descriptor?.id,
        replyBinder = replyBinder,
        uid = uid,
    )

    private fun recordLocalRoute(
        envelope: BusEnvelope,
        senderUid: Int,
        pluginId: String?,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        if (senderUid == Process.myUid() || !pluginBusJournal.enabled.get()) return
        try {
            pluginBusJournal.record(
                pluginId = pluginId,
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
            .ifBlank { envelope.payload.optString("activityId") }
        return surfaceId.substringBefore(':').takeIf { ':' in surfaceId && it.isNotBlank() }
    }

    private fun journalCategory(path: String, hasBinary: Boolean): PluginBusJournal.Category = when (path) {
        BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE, BusPaths.SURFACE_HIDE,
        BusPaths.PIN_SHOW, BusPaths.PIN_HIDE,
        BusPaths.NOTICE_SHOW, BusPaths.NOTICE_UPDATE, BusPaths.NOTICE_HIDE,
        BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE, BusPaths.ACTIVITY_END,
        -> PluginBusJournal.Category.SURFACE
        BusPaths.SURFACE_INPUT, BusPaths.PLUGIN_INPUT,
        BusPaths.NOTICE_INPUT, BusPaths.NOTICE_ACTION, BusPaths.NOTICE_CLOSED,
        BusPaths.ACTIVITY_ACTION, BusPaths.ACTIVITY_CLOSED,
        -> PluginBusJournal.Category.INPUT
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
        principal: PhonePluginPrincipal? = null,
    ): Boolean {
        when (envelope.path) {
            BusPaths.HTTP_REQUEST -> executor.execute { fetchAndStream(envelope, replyRemote, replyBinder) }
            AUDIO_LEASE_ACQUIRE -> executor.execute {
                acquireAudioLease(envelope, replyRemote, senderUid, replyBinder)
            }
            AUDIO_LEASE_RELEASE -> executor.execute { releaseAudioLease(envelope, replyRemote, replyBinder) }
            BusPaths.CAMERA_SNAPSHOT_REQUEST -> {
                if (replyRemote || principal == null) return false
                requestCameraSnapshot(envelope, principal)
            }
            BusPaths.MEDIA_SYNC_SETTINGS -> executor.execute {
                if (::mediaSyncCoordinator.isInitialized) {
                    mediaSyncCoordinator.applySettings(envelope.payload)
                }
            }
            BusPaths.MEDIA_SYNC_NOW -> executor.execute {
                if (::mediaSyncCoordinator.isInitialized) mediaSyncCoordinator.requestSyncNow()
            }
            BusPaths.WIRELESS_ADB_REQUEST -> {
                if (replyRemote || principal == null) return false
                handleWirelessAdbRequest(envelope, principal, replyBinder)
            }
            BusPaths.ASSISTANT_TAKEOVER_REQUEST -> {
                if (replyRemote || principal == null) return false
                handleAssistantTakeoverRequest(envelope, principal, replyBinder)
            }
            SttWireProtocol.SESSION_START_PATH -> speechBusExecutor.execute {
                handleSpeechSessionStart(envelope, replyRemote, replyBinder, principal)
            }
            SttWireProtocol.SESSION_STOP_PATH -> speechBusExecutor.execute {
                handleSpeechSessionStop(envelope, replyRemote, replyBinder, principal)
            }
            else -> return false
        }
        return true
    }

    private fun handleWirelessAdbRequest(
        envelope: BusEnvelope,
        principal: PhonePluginPrincipal,
        replyBinder: IBinder?,
    ) {
        val action = WirelessAdbContract.requestAction(envelope.payload)
        val stamped = action?.let {
            WirelessAdbContract.stampedRequest(envelope.payload, principal.descriptor.id)
        }
        if (envelope.binary != null || action == null || stamped == null) {
            deliverWirelessAdbFailure(
                principal = principal,
                replyBinder = replyBinder,
                requestId = envelope.id,
                action = action ?: WirelessAdbAction.STATUS,
                code = "INVALID_REQUEST",
                message = "The wireless debugging request was invalid.",
            )
            return
        }
        val error = sendRemote(envelope.copy(payload = stamped)) ?: return
        deliverWirelessAdbFailure(
            principal = principal,
            replyBinder = replyBinder,
            requestId = envelope.id,
            action = action,
            code = error,
            message = "The glasses are not connected.",
        )
    }

    private fun deliverWirelessAdbFailure(
        principal: PhonePluginPrincipal,
        replyBinder: IBinder?,
        requestId: String,
        action: WirelessAdbAction,
        code: String,
        message: String,
    ) {
        deliverLocal(
            BusEnvelope(
                path = BusPaths.WIRELESS_ADB_REPLY,
                id = requestId,
                payload = WirelessAdbContract.reply(
                    principal.descriptor.id,
                    WirelessAdbReply(
                        action = action,
                        success = false,
                        wifiConnected = false,
                        enabled = false,
                        pairingActive = false,
                        errorCode = code,
                        message = message,
                    ),
                ),
            ),
            targetBinder = replyBinder,
        )
    }

    /**
     * A plugin holding the `assistant` grant reading or moving the assist-button switch. The
     * grant was already checked by the route policy; the switch is global on purpose — there is
     * one button — and the reply always reports where it ended up, so a `set` doubles as a read.
     */
    private fun handleAssistantTakeoverRequest(
        envelope: BusEnvelope,
        principal: PhonePluginPrincipal,
        replyBinder: IBinder?,
    ) {
        val request = AssistantTakeoverContract.parseRequest(envelope.payload)
        if (envelope.binary != null || request == null) {
            deliverError(replyBinder, envelope.id, AssistantTakeoverContract.ERROR_INVALID_REQUEST)
            return
        }
        if (request.action == AssistantTakeoverAction.SET) {
            val enabled = request.enabled == true
            assistantTakeoverStore.setEnabled(enabled)
            log("assistant takeover set=$enabled by plugin=${principal.descriptor.id}")
        }
        deliverLocal(
            BusEnvelope(
                path = BusPaths.ASSISTANT_TAKEOVER_REPLY,
                id = envelope.id,
                payload = AssistantTakeoverContract.reply(
                    principal.descriptor.id,
                    assistantTakeoverStore.isEnabled(),
                ),
            ),
            targetBinder = replyBinder,
        )
    }

    private fun requestCameraSnapshot(
        request: BusEnvelope,
        principal: PhonePluginPrincipal,
    ) {
        if (::cameraCompanionController.isInitialized &&
            cameraCompanionController.activeSessionId() != null
        ) {
            deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_BUSY)
            return
        }
        if (!isCxrUp() || cxrLink == null) {
            deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_LINK_DOWN)
            return
        }
        if (!snapshotInFlight.compareAndSet(false, true)) {
            deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_BUSY)
            return
        }

        val job = snapshotCaptureScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (::cameraCompanionController.isInitialized &&
                    cameraCompanionController.activeSessionId() != null
                ) {
                    deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_BUSY)
                    return@launch
                }
                val link = cxrLink
                if (link == null || !isCxrUp()) {
                    deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_LINK_DOWN)
                    return@launch
                }

                val raw = snapshotCapture.capture(CxrLinkSnapshotAdapter(link))
                val normalized = SnapshotJpegEncoder.normalize(
                    encoded = raw,
                    maxBytes = LOCAL_BINARY_MAX_BYTES,
                    jpegQuality = SNAPSHOT_JPEG_QUALITY,
                )
                val payload = JSONObject()
                    .put("version", 1)
                    .put("type", "snapshot")
                    .put("requestId", request.id)
                    .put("pluginId", principal.descriptor.id)
                    .put("mimeType", "image/jpeg")
                    .put("sizeBytes", normalized.jpeg.size)
                    .put("width", normalized.width)
                    .put("height", normalized.height)
                    .put("quality", normalized.quality)
                val delivered = deliverExternalBinary(
                    principal = principal,
                    path = BusPaths.CAMERA_SNAPSHOT_RESULT,
                    id = request.id,
                    payload = payload,
                    data = normalized.jpeg,
                )
                log(
                    "snapshot result plugin=${principal.descriptor.id} request=${request.id} " +
                        "bytes=${normalized.jpeg.size} dimensions=${normalized.width}x${normalized.height} " +
                        "delivered=$delivered",
                )
            } catch (_: SnapshotLinkDownCancellationException) {
                deliverSnapshotError(principal, request.id, SNAPSHOT_ERROR_LINK_DOWN)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: SnapshotCaptureTimeoutException) {
                deliverSnapshotError(
                    principal,
                    request.id,
                    if (isCxrUp()) SNAPSHOT_ERROR_TIMEOUT else SNAPSHOT_ERROR_LINK_DOWN,
                )
            } catch (failure: Throwable) {
                log(
                    "snapshot capture failed plugin=${principal.descriptor.id} " +
                        "request=${request.id} type=${failure.javaClass.simpleName}",
                )
                deliverSnapshotError(
                    principal,
                    request.id,
                    if (isCxrUp()) SNAPSHOT_ERROR_CAPTURE_FAILED else SNAPSHOT_ERROR_LINK_DOWN,
                )
            }
        }
        snapshotCaptureJob = job
        job.invokeOnCompletion {
            if (snapshotCaptureJob === job) snapshotCaptureJob = null
            snapshotInFlight.set(false)
        }
        job.start()
    }

    private fun deliverSnapshotError(
        principal: PhonePluginPrincipal,
        requestId: String,
        code: String,
    ) {
        val payload = JSONObject()
            .put("version", 1)
            .put("type", "error")
            .put("requestId", requestId)
            .put("pluginId", principal.descriptor.id)
            .put("code", code)
            .put("message", snapshotErrorMessage(code))
        deliverExternalLifecycle(
            principal = principal,
            path = BusPaths.CAMERA_SNAPSHOT_ERROR,
            id = requestId,
            payload = payload,
        )
        log("snapshot error plugin=${principal.descriptor.id} request=$requestId code=$code")
    }

    private fun snapshotErrorMessage(code: String): String = when (code) {
        SNAPSHOT_ERROR_BUSY -> "Camera is busy."
        SNAPSHOT_ERROR_LINK_DOWN -> "Glasses link is down."
        SNAPSHOT_ERROR_TIMEOUT -> "Camera capture timed out."
        else -> "Camera capture failed."
    }

    private fun cancelSnapshotForLinkDown() {
        snapshotCaptureJob?.cancel(SnapshotLinkDownCancellationException())
    }

    private fun isGlassesControlRequest(path: String): Boolean =
        path == BusPaths.GLASSES_BRIGHTNESS_REQUEST ||
            path == BusPaths.GLASSES_VOLUME_REQUEST ||
            isStrictlyHubOwnedGlassesPath(path)

    private fun isStrictlyHubOwnedGlassesPath(path: String): Boolean =
        path == BusPaths.GLASSES_SELFARM_MANUAL ||
            path == BusPaths.GLASSES_SETUP_NOTE ||
            path == BusPaths.GLASSES_SETUP_PAIRING_OFFER ||
            path == BusPaths.GLASSES_SETUP_PAIRING_RESULT ||
            path == BusPaths.GLASSES_REPAIR_CONFIG ||
            path == BusPaths.GLASSES_REPAIR_REQUEST ||
            path == BusPaths.GLASSES_ACCESSIBILITY_CHECK_REQUEST

    private fun handleGlassesControlRequest(envelope: BusEnvelope, replyBinder: IBinder?) {
        if (envelope.path == BusPaths.GLASSES_REPAIR_CONFIG ||
            envelope.path == BusPaths.GLASSES_REPAIR_REQUEST ||
            envelope.path == BusPaths.GLASSES_ACCESSIBILITY_CHECK_REQUEST
        ) {
            // Pure forwards: the glasses answer on GLASSES_REPAIR_REPLY / GLASSES_ACCESSIBILITY_
            // CHECK_REPLY with the request's id, which the settings screen correlates itself. Only
            // a transport failure is answered here, so "not connected" fails fast instead of eating
            // the full timeout.
            val error = sendRemote(envelope)
            if (error != null) {
                deliverError(replyBinder, envelope.id, error)
            }
            return
        }
        if (envelope.path == BusPaths.GLASSES_SELFARM_MANUAL) {
            val action = envelope.payload.optString("action")
            if (GlassesManualControlAction.entries.none { it.wireValue == action }) {
                deliverError(replyBinder, envelope.id, "INVALID_ACTION")
                return
            }
            val error = sendRemote(envelope)
            if (error != null) {
                deliverError(replyBinder, envelope.id, error)
            }
            return
        }
        val level = GlassesControlLevelPolicy.parseAndClamp(envelope.payload.opt("level"))
        if (level == null) {
            deliverError(replyBinder, envelope.id, "INVALID_LEVEL")
            return
        }
        val link = cxrLink
        if (link == null || !isCxrUp()) {
            deliverError(replyBinder, envelope.id, "NO_CXR")
            return
        }
        val applied = runCatching {
            when (envelope.path) {
                BusPaths.GLASSES_BRIGHTNESS_REQUEST -> link.setBrightness(level)
                BusPaths.GLASSES_VOLUME_REQUEST -> link.setVolume(level)
                else -> false
            }
        }.getOrElse {
            log("CXR glasses control failed ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        when (envelope.path) {
            BusPaths.GLASSES_BRIGHTNESS_REQUEST -> log("glassesBrightnessRequest level=$level applied=$applied")
            BusPaths.GLASSES_VOLUME_REQUEST -> log("glassesVolumeRequest level=$level applied=$applied")
        }
        if (!applied) {
            deliverError(replyBinder, envelope.id, "APPLY_FAILED")
            return
        }
        val response = BusEnvelope(
            path = envelope.path + "/reply",
            id = envelope.id,
            payload = JSONObject().put("applied", true).put("level", level),
        )
        deliverLocal(response, targetBinder = replyBinder)
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
        if (!protectedPathAllowed(
                envelope.path,
                registration.uid,
                registration.principal,
                ProtectedPathDirection.RECEIVE,
            )
        ) return false
        val addressed = registration.principal
        if (addressed != null && PathRules.isDirectReply(envelope.path)) {
            return envelope.payload.optString("pluginId") == addressed.descriptor.id
        }
        if (registration.prefixes.none { PathRules.matchesPrefix(envelope.path, it) }) return false
        val principal = registration.principal ?: return true
        if (PathRules.isPluginPrivate(envelope.path, principal.descriptor.id)) return true
        if (PathRules.isOwnerScoped(envelope.path)) {
            return envelope.payload.optString("pluginId") == principal.descriptor.id
        }
        return true
    }

    private fun protectedPathAllowed(
        path: String,
        uid: Int,
        principal: PhonePluginPrincipal?,
        direction: ProtectedPathDirection = ProtectedPathDirection.SEND,
    ): Boolean = ProtectedPathAccessPolicy.isAllowed(
        path = path,
        isHubUid = uid == Process.myUid(),
        principal = principal,
        grantState = principal?.let(pluginGrantStore::stateFor),
        direction = direction,
    )

    /**
     * Photo-sync status, delivered one registration at a time.
     *
     * The plugin SDK drops any message whose payload lacks its own pluginId, so a single broadcast
     * copy was swallowed before it ever reached the settings screen — the screen sat on
     * "Connecting to Rokid Nexus" forever while the hub synced perfectly. Stamping each recipient's
     * id in is what makes status visible; [registrationMatches] still decides who may receive it.
     */
    private fun publishMediaSyncStatus(payload: JSONObject) {
        registrations.forEach { registration ->
            val pluginId = registration.principal?.descriptor?.id ?: return@forEach
            val stamped = runCatching { JSONObject(payload.toString()).put("pluginId", pluginId) }
                .getOrNull() ?: return@forEach
            deliverLocal(
                BusEnvelope(path = BusPaths.MEDIA_SYNC_STATUS, payload = stamped),
                targetBinder = registration.callbackBinder,
            )
        }
    }

    /** Hub-to-hub photo-sync traffic: engine state, the config request, and the data plane. */
    private fun handleMediaSyncRemote(envelope: BusEnvelope): Boolean = when {
        envelope.path == BusPaths.MEDIA_SYNC_STATE -> {
            executor.execute { mediaSyncCoordinator.onGlassesState(envelope.payload) }
            true
        }
        envelope.path == BusPaths.MEDIA_SYNC_CONFIG_REQUEST -> {
            // A restarted glasses hub has lost its consent and the transport never bounced, so
            // nothing would have re-pushed it. Answer instead of leaving it dormant.
            executor.execute { mediaSyncCoordinator.onLinkUp() }
            true
        }
        BusPaths.isMediaSyncTransferPath(envelope.path) -> {
            // Deliberately NOT `executor`: that is a cached thread pool, and dispatching an
            // ordered byte stream onto it lets chunks race each other into the staging file.
            // Every chunk still lands exactly once, so lengths stay right while content is
            // scrambled - which is precisely how this failed on device. The coordinator owns a
            // single-threaded data plane; handing it envelopes from this one reader thread is
            // what preserves wire order.
            mediaSyncCoordinator.onTransferEnvelope(
                envelope.path,
                envelope.payload,
                envelope.binary,
            )
            true
        }
        else -> false
    }

    /**
     * Photo sync stays dormant until the wearer approves a `mediasync` plugin: the capability
     * grant is the consent, so the hub never moves private captures on its own initiative.
     */
    private fun refreshMediaSyncConsent() {
        if (!::mediaSyncCoordinator.isInitialized || !::pluginGrantReconciler.isInitialized) return
        val consented = runCatching {
            pluginGrantReconciler.reconcile().validPrincipals.any { principal ->
                val state = pluginGrantStore.stateFor(principal)
                state is PluginGrantState.Approved &&
                    PluginCapability.MEDIA_SYNC in state.capabilities
            }
        }.getOrDefault(false)
        mediaSyncCoordinator.onConsentChanged(consented)
    }

    private fun handleLocalTts(
        envelope: BusEnvelope,
        senderUid: Int,
        sender: AuthorizedSender,
    ) {
        val principal = sender.principal
        if (principal == null) {
            rejectTts(envelope, senderUid, sender, TtsContract.ERROR_INVALID_TTS)
            return
        }
        if (capabilities() and BusCapabilityBits.TTS == 0) {
            rejectTts(
                envelope,
                senderUid,
                sender,
                TtsContract.ERROR_CAPABILITY_NOT_AVAILABLE,
            )
            return
        }
        when (
            val result = ttsRequestGate.evaluate(
                ownerPluginId = principal.descriptor.id,
                path = envelope.path,
                payload = envelope.payload,
                hasBinary = envelope.binary != null,
            )
        ) {
            is PhoneTtsGateResult.Rejected ->
                rejectTts(envelope, senderUid, sender, result.code)
            is PhoneTtsGateResult.Accepted -> {
                val forwarded = envelope.copy(payload = result.payload)
                when (val dispatch = phoneTtsDispatcher.dispatch(forwarded)) {
                    PhoneTtsDispatchResult.PhoneHandled -> recordLocalRoute(
                        forwarded,
                        senderUid,
                        sender,
                        PluginBusJournal.Verdict.OK,
                    )
                    is PhoneTtsDispatchResult.Invalid ->
                        rejectTts(envelope, senderUid, sender, dispatch.error)
                }
            }
        }
    }

    private fun rejectTts(
        envelope: BusEnvelope,
        senderUid: Int,
        sender: AuthorizedSender,
        code: String,
    ) {
        recordLocalRoute(envelope, senderUid, sender, PluginBusJournal.Verdict.REJECTED, code)
        deliverError(sender.replyBinder, envelope.id, code)
    }

    private fun handleTtsEvent(envelope: BusEnvelope) {
        if (envelope.binary != null) {
            log("tts event ignored path=${envelope.path} reason=${TtsContract.ERROR_INVALID_TTS}")
            return
        }
        val routed = when (envelope.path) {
            BusPaths.TTS_STARTED -> when (val result = TtsContract.validateStarted(envelope.payload)) {
                is TtsValidationResult.Valid -> result.value to null
                is TtsValidationResult.Invalid -> null
            }
            BusPaths.TTS_DONE -> when (val result = TtsContract.validateDone(envelope.payload)) {
                is TtsValidationResult.Valid -> result.value
                is TtsValidationResult.Invalid -> null
            }
            else -> null
        }
        if (routed == null) {
            log("tts event ignored path=${envelope.path} reason=${TtsContract.ERROR_INVALID_TTS}")
            return
        }
        val (request, reason) = routed
        val ownerPluginId = request.ownerPluginId ?: return
        val registration = registrations.firstOrNull { registration ->
            registration.principal?.descriptor?.id == ownerPluginId &&
                PluginCapability.TTS in registration.grantedCapabilities
        }
        val principal = registration?.principal
        if (principal == null) {
            log("tts event undelivered owner=$ownerPluginId reason=no_live_registration")
            return
        }
        val payload = JSONObject()
            .put("utteranceId", request.utteranceId)
        reason?.let { payload.put("reason", it.name) }
        if (!deliverExternalLifecycle(principal, envelope.path, envelope.id, payload)) {
            log("tts event undelivered owner=$ownerPluginId path=${envelope.path}")
        }
    }

    private fun emitPhoneTtsStarted(ownerPluginId: String, utteranceId: String) {
        handleTtsEvent(
            BusEnvelope(
                path = BusPaths.TTS_STARTED,
                payload = TtsContract.startedPayload(ownerPluginId, utteranceId),
            ),
        )
    }

    private fun emitPhoneTtsDone(event: TtsDoneEvent) {
        handleTtsEvent(
            BusEnvelope(
                path = BusPaths.TTS_DONE,
                payload = TtsContract.donePayload(
                    event.ownerPluginId,
                    event.utteranceId,
                    event.reason,
                ),
            ),
        )
    }

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
        // pluginId matches NexusPluginClient's own filter on every inbound message
        // (onMessage returns early when it doesn't); omitting it, as this used to,
        // meant every error this function ever delivered was silently dropped
        // before a plugin's code ever saw it — including SURFACE_BUSY.
        val envelope = errorEnvelope(id, code, target.clientId)
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
            .put("noticeInteractionVersion", NoticeSurfaceContract.INTERACTION_VERSION)
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
        releaseSpeechSessionForLocalBinder(registration, reason)
        registration.principal?.descriptor?.id?.let { pluginId ->
            val ownerStillConnected = registrations.any {
                it.principal?.descriptor?.id == pluginId
            }
            if (!ownerStillConnected &&
                reason != "replace" &&
                reason != "authorizationChanged"
            ) {
                activityRouter.clearForDisconnectedOwner(pluginId, reason)
            }
        }
        // A registration going away is normal: the hub unbinds dormant plugins, and a
        // background plugin is expected to push its pin and disconnect. The pin outlives
        // the connection — only losing the grant, a TTL, a replacement, or an explicit
        // hide clears it. See pinRouter.clearForRevokedOwner. Activities intentionally differ:
        // the final live registration disappearing cleared that owner's activity above.
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
        pinRouter.clearForRevokedOwner(key.pluginId, "authorizationChanged")
        noticeRouter.clearForRevokedOwner(key.pluginId, "authorizationChanged")
        activityRouter.clearForRevokedOwner(key.pluginId, "authorizationChanged")
        registrations.filter { it.principal?.grantKey() == key }.forEach { registration ->
            removeRegistration(registration, "authorizationChanged")
        }
    }

    private fun authorizationChanged(key: PluginGrantKey) {
        revokePrincipal(key)
        cameraConsumerReadiness.recompute()
        refreshMediaSyncConsent()
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

    private fun registerWifiStateReceiver() {
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wifiStateReceiver, filter)
        }
        wifiStateReceiverRegistered = true
    }

    private fun reconcilePluginPackage(packageName: String, action: String?, replacing: Boolean) {
        val reconciliation = pluginGrantReconciler.reconcile()
        val validPrincipals = reconciliation.validPrincipals
        handleSideloadNotification(packageName, action, replacing, reconciliation.candidates)
        cameraConsumerReadiness.recompute()
        refreshMediaSyncConsent()
        // Keyed by owner id, not by registration: the pin's owner is usually dormant by
        // the time it is uninstalled, so there is no binder left to notice it going away.
        pinRouter.ownerPluginId()?.let { owner ->
            val stillGranted = validPrincipals.any { principal ->
                principal.descriptor.id == owner &&
                    pluginGrantStore.stateFor(principal) is PluginGrantState.Approved
            }
            if (!stillGranted) pinRouter.clearForRevokedOwner(owner, "ownerUnavailable")
            if (!stillGranted) noticeRouter.clearForRevokedOwner(owner, "ownerUnavailable")
        }
        activityRouter.ownerPluginIds().forEach { owner ->
            val stillGranted = validPrincipals.any { principal ->
                principal.descriptor.id == owner &&
                    (pluginGrantStore.stateFor(principal) as? PluginGrantState.Approved)
                        ?.capabilities
                        ?.contains(PluginCapability.SURFACES) == true
            }
            if (!stillGranted) activityRouter.clearForRevokedOwner(owner, "ownerUnavailable")
        }
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

    private fun handleSideloadNotification(
        packageName: String,
        action: String?,
        replacing: Boolean,
        candidates: List<PhonePluginCandidate>,
    ) {
        if (action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
            notifiedDeveloperPackages.remove(packageName)
            getSystemService(NotificationManager::class.java)
                .cancel(packageName, DEVELOPER_NOTIFICATION_ID)
            return
        }
        val candidate = candidates.singleOrNull { it.packageName == packageName }
        val principal = (candidate as? PhonePluginCandidate.Valid)?.principal
        if (!PluginSideloadNotificationPolicy.shouldNotify(
                developerModeEnabled = developerModeStore.isEnabled(),
                action = action,
                replacing = replacing,
                candidate = candidate,
                hasExistingGrant = principal?.let(pluginGrantStore::hasGrantFor) == true,
            ) || principal == null || !notifiedDeveloperPackages.add(packageName)
        ) return
        postSideloadNotification(principal)
    }

    private fun postSideloadNotification(principal: PhonePluginPrincipal) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DEVELOPER_CHANNEL_ID,
                "Developer",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val target = PluginGrantTarget(principal.packageName, principal.descriptor.id)
        val review = PendingIntent.getActivity(
            this,
            principal.packageName.hashCode(),
            PluginPermissionsActivity.intent(this, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayName = principal.descriptor.displayName
        val notification = Notification.Builder(this, DEVELOPER_CHANNEL_ID)
            .setContentTitle("New plugin detected: $displayName")
            .setContentText("Tap to review access")
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(review)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(principal.packageName, DEVELOPER_NOTIFICATION_ID, notification)
    }

    private fun installedPluginPrincipals(): List<PhonePluginPrincipal> =
        pluginDiscovery.discover().mapNotNull { candidate ->
            (candidate as? PhonePluginCandidate.Valid)?.principal
        }

    private fun approvedGuardianTargets(): List<PluginGuardianTarget> =
        selectApprovedGuardianTargets(installedPluginPrincipals(), pluginGrantStore::stateFor)

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
        val surfaceIds = surfaceRouter.hidePlugin(pluginId)
        inkRouter.clearOwner(pluginId) { owners ->
            // A show may still be compiling when its plugin disconnects. The
            // callback also closes surfaces absent from the initial snapshot.
            owners.filterNot { it.wireSurfaceId in surfaceIds }.forEach { owner ->
                surfaceRouter.forget(owner.pluginId, owner.wireSurfaceId)
                surfaceRouter.sendHide(owner.pluginId, owner.wireSurfaceId)
            }
        }
    }

    private fun onExternalSurfaceReleased(pluginId: String, detach: Boolean) {
        if (::externalPluginController.isInitialized) {
            val hasActiveAudioLease = pluginHoldsActiveAudioLease(pluginId)
            externalPluginController.onPluginSelfHid(
                pluginId = pluginId,
                detach = detach,
                hasActiveAudioLease = hasActiveAudioLease,
            )
            // Close the only race where the lease can end after the grant check but before the
            // controller has installed its background slot. A later lease end sees the slot itself.
            if (detach && hasActiveAudioLease && !pluginHoldsActiveAudioLease(pluginId)) {
                externalPluginController.onAudioLeaseEnded(pluginId, "LEASE_ENDED")
            }
        }
    }

    private fun pluginHoldsActiveAudioLease(pluginId: String): Boolean =
        audioLeaseArbitrator.snapshot()?.let { lease ->
            lease.side == AudioLeaseSide.LOCAL &&
                lease.streamStarted &&
                lease.holderPluginId == pluginId
        } == true

    private fun handleSpeechSessionStart(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        replyBinder: IBinder?,
        principal: PhonePluginPrincipal?,
    ) {
        if (replyRemote) {
            sendRemote(errorEnvelope(envelope.id, "STT_LOCAL_ONLY"))
            return
        }
        val registration = speechRegistration(replyBinder, principal) ?: return
        val request = SttWireProtocol.parseStart(envelope.payload)
        if (request == null) {
            replyToSpeechRequest(
                envelope = envelope,
                payload = JSONObject()
                    .put("accepted", false)
                    .put("reason", "INVALID_REQUEST"),
                registration = registration,
            )
            return
        }

        val session = SpeechBusSession(
            sessionId = UUID.randomUUID().toString(),
            callbackBinder = registration.callbackBinder,
            pluginId = registration.principal!!.descriptor.id,
            grantKey = registration.principal.grantKey(),
        )
        session.listener = SpeechBusListener(session)
        val reserved = synchronized(speechBusLock) {
            val current = activeSpeechBusSession
            if (current != null && !current.ended.get()) {
                false
            } else {
                activeSpeechBusSession = session
                true
            }
        }
        if (!reserved) {
            replyToSpeechRequest(
                envelope = envelope,
                payload = JSONObject()
                    .put("accepted", false)
                    .put("reason", "BUSY"),
                registration = registration,
            )
            return
        }

        val startResult = runCatching {
            speechSessionManager.startUtterance(
                listener = session.listener,
                language = request.language,
            )
        }.getOrDefault(SpeechStartResult.START_FAILED)
        if (startResult != SpeechStartResult.OK) {
            discardSpeechBusSession(session)
            replyToSpeechRequest(
                envelope = envelope,
                payload = JSONObject()
                    .put("accepted", false)
                    .put("reason", SttWireProtocol.startDenialReason(startResult)),
                registration = registration,
            )
            return
        }

        if (!isCurrentSpeechBusSession(session) ||
            speechRegistration(registration.callbackBinder, registration.principal) == null
        ) {
            speechSessionManager.cancel(session.listener)
            return
        }
        val realtime = speechSessionManager.activeRealtime
            ?: speechSettingsStore.selectedEngine()?.usesRealtime
            ?: false
        session.accepted.set(true)
        replyToSpeechRequest(
            envelope = envelope,
            payload = JSONObject()
                .put("accepted", true)
                .put("sessionId", session.sessionId)
                .put("realtime", realtime),
            registration = registration,
        )
    }

    private fun handleSpeechSessionStop(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        replyBinder: IBinder?,
        principal: PhonePluginPrincipal?,
    ) {
        if (replyRemote) {
            sendRemote(errorEnvelope(envelope.id, "STT_LOCAL_ONLY"))
            return
        }
        val registration = speechRegistration(replyBinder, principal) ?: return
        val requestedSessionId = envelope.payload.optString("sessionId")
        val ownedSession = synchronized(speechBusLock) {
            activeSpeechBusSession?.let { current ->
                current.takeIf {
                    !current.ended.get() &&
                    current.sessionId == requestedSessionId &&
                    current.callbackBinder == registration.callbackBinder &&
                    current.grantKey == registration.principal!!.grantKey()
                }
            }
        }
        ownedSession?.let { speechSessionManager.cancel(it.listener) }
        replyToSpeechRequest(
            envelope = envelope,
            payload = JSONObject().put("stopped", true),
            registration = registration,
        )
    }

    private inner class SpeechBusListener(
        private val session: SpeechBusSession,
    ) : SpeechUtteranceListener {
        override fun onState(state: SpeechSessionState) {
            speechBusExecutor.execute {
                if (!isCurrentSpeechBusSession(session)) return@execute
                val sequence = session.stateSeq.getAndIncrement()
                deliverSpeechBusEnvelope(
                    session,
                    BusEnvelope(
                        path = SttWireProtocol.STATE_PATH,
                        id = SttWireProtocol.stateId(session.sessionId, sequence),
                        payload = SttWireProtocol.statePayload(
                            session.pluginId,
                            session.sessionId,
                            state,
                        ),
                    ),
                )
            }
        }

        override fun onPartial(text: String) {
            speechBusExecutor.execute {
                if (!isCurrentSpeechBusSession(session)) return@execute
                val sequence = session.partialSeq.getAndIncrement()
                deliverSpeechBusEnvelope(
                    session,
                    BusEnvelope(
                        path = SttWireProtocol.PARTIAL_PATH,
                        id = SttWireProtocol.partialId(session.sessionId, sequence),
                        payload = SttWireProtocol.partialPayload(
                            session.pluginId,
                            session.sessionId,
                            text,
                            sequence,
                        ),
                    ),
                )
            }
        }

        override fun onFinal(text: String) {
            speechBusExecutor.execute {
                if (!isCurrentSpeechBusSession(session)) return@execute
                deliverSpeechBusEnvelope(
                    session,
                    BusEnvelope(
                        path = SttWireProtocol.FINAL_PATH,
                        id = SttWireProtocol.finalId(session.sessionId),
                        payload = SttWireProtocol.finalPayload(
                            session.pluginId,
                            session.sessionId,
                            text,
                        ),
                    ),
                )
            }
        }

        override fun onEnded(reason: SpeechEndReason, error: SttError?) {
            speechBusExecutor.execute {
                if (!completeSpeechBusSession(session)) return@execute
                deliverSpeechBusEnvelope(
                    session,
                    BusEnvelope(
                        path = SttWireProtocol.SESSION_ENDED_PATH,
                        id = SttWireProtocol.endedId(session.sessionId),
                        payload = SttWireProtocol.endedPayload(
                            session.pluginId,
                            session.sessionId,
                            reason,
                            error,
                        ),
                    ),
                )
            }
        }
    }

    private fun speechRegistration(
        callbackBinder: IBinder?,
        principal: PhonePluginPrincipal?,
    ): Registration? {
        if (callbackBinder == null || principal == null) return null
        return registrations.firstOrNull { registration ->
            registration.callbackBinder == callbackBinder &&
                registration.principal?.grantKey() == principal.grantKey() &&
                PluginCapability.STT in registration.grantedCapabilities
        }
    }

    private fun replyToSpeechRequest(
        envelope: BusEnvelope,
        payload: JSONObject,
        registration: Registration,
    ) {
        payload.put("pluginId", registration.principal!!.descriptor.id)
        deliverLocal(
            BusEnvelope(
                path = envelope.path + "/reply",
                id = envelope.id,
                payload = payload,
            ),
            targetBinder = registration.callbackBinder,
        )
    }

    private fun deliverSpeechBusEnvelope(session: SpeechBusSession, envelope: BusEnvelope) {
        deliverLocal(envelope, targetBinder = session.callbackBinder)
    }

    private fun isCurrentSpeechBusSession(session: SpeechBusSession): Boolean =
        synchronized(speechBusLock) {
            activeSpeechBusSession === session && !session.ended.get()
        }

    private fun discardSpeechBusSession(session: SpeechBusSession) {
        synchronized(speechBusLock) {
            if (activeSpeechBusSession === session) activeSpeechBusSession = null
            session.ended.set(true)
        }
    }

    private fun completeSpeechBusSession(session: SpeechBusSession): Boolean =
        synchronized(speechBusLock) {
            if (activeSpeechBusSession !== session ||
                !session.ended.compareAndSet(false, true)
            ) {
                false
            } else {
                activeSpeechBusSession = null
                true
            }
        }

    private fun releaseSpeechSessionForLocalBinder(registration: Registration, reason: String) {
        val session = synchronized(speechBusLock) {
            val current = activeSpeechBusSession
            if (current?.callbackBinder == registration.callbackBinder &&
                current.ended.compareAndSet(false, true)
            ) {
                activeSpeechBusSession = null
                current
            } else {
                null
            }
        } ?: return
        speechSessionManager.cancel(session.listener)
        if (reason != "authorizationChanged" || !session.accepted.get()) return

        val revoked = BusEnvelope(
            path = SttWireProtocol.SESSION_ENDED_PATH,
            id = SttWireProtocol.endedId(session.sessionId),
            payload = SttWireProtocol.revokedPayload(session.pluginId, session.sessionId),
        )
        speechBusExecutor.execute {
            val payload = revoked.payload.toString().toByteArray(Charsets.UTF_8)
            runCatching {
                registration.callback.onMessage(revoked.path, revoked.id, payload)
            }.onSuccess {
                recordLocalDelivery(registration, revoked, PluginBusJournal.Verdict.OK, "LOCAL")
            }.onFailure {
                recordLocalDelivery(
                    registration,
                    revoked,
                    PluginBusJournal.Verdict.REJECTED,
                    "DEAD_CALLBACK",
                )
            }
        }
    }

    /** Whether a pin envelope can go out right now. State is kept either way. */
    private fun pinLinkUp(): Boolean =
        linkState() and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0

    private fun acquireAudioLease(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        senderUid: Int?,
        replyBinder: IBinder?,
    ) {
        val holderPluginId = if (replyRemote) {
            null
        } else {
            registrations.firstOrNull { it.uid == senderUid && it.principal != null }
                ?.principal
                ?.descriptor
                ?.id
        }
        val link = cxrLink
        if (audioLeaseArbitrator.snapshot() != null) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"), replyBinder, holderPluginId)
            return
        }
        if (link == null || !isCxrUp()) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "NO_CXR"), replyBinder, holderPluginId)
            return
        }

        val holderBinder = if (replyRemote) null else replyBinder ?: findLocalAudioHolder(senderUid)
        val lease = AudioLease(
            leaseId = UUID.randomUUID().toString(),
            side = if (replyRemote) AudioLeaseSide.REMOTE else AudioLeaseSide.LOCAL,
            localCallbackBinder = holderBinder,
            holderPluginId = holderPluginId,
        )
        if (!audioLeaseArbitrator.tryAcquire(lease)) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"), replyBinder, holderPluginId)
            return
        }

        audioHandler.post {
            val started = runCatching {
                link.setInterruptAiWake(true)
                link.setCXRAudioCbk(audioCallback)
                val streamStarted = link.startAudioStream(CXR_AUDIO_PCM)
                log("CXR audio start streamStarted=$streamStarted")
                streamStarted
            }.getOrElse {
                log("CXR audio start failed ${it.javaClass.simpleName}: ${it.message}")
                false
            }
            if (!started) {
                audioLeaseArbitrator.clearIf { it.leaseId == lease.leaseId }
                stopAudioStreamQuietly()
                replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "START_FAILED"), replyBinder, holderPluginId)
                return@post
            }
            val activated = audioLeaseArbitrator.withActive { current ->
                if (current.leaseId == lease.leaseId) {
                    current.streamStarted = true
                    true
                } else {
                    false
                }
            } == true
            if (!activated) {
                // A concurrent revoke may have run stopAudioStreamQuietly() before our
                // startAudioStream() landed; stop again so no orphan stream survives.
                stopAudioStreamQuietly()
                val reason = if (isCxrUp()) "START_FAILED" else "NO_CXR"
                replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", reason), replyBinder, holderPluginId)
                return@post
            }

            silenceSpeechForMicrophone()
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
                holderPluginId,
            )
        }
    }

    private fun acquireInternalAudio(
        tag: String,
        consumer: InternalAudioConsumer,
    ): InternalAudioAcquireResult {
        val link = cxrLink
        if (audioLeaseArbitrator.snapshot() != null) return InternalAudioAcquireResult.BUSY
        if (link == null || !isCxrUp()) return InternalAudioAcquireResult.NO_LINK
        val lease = AudioLease(
            leaseId = UUID.randomUUID().toString(),
            side = AudioLeaseSide.INTERNAL,
            localCallbackBinder = null,
            holderPluginId = null,
            internalTag = tag,
            internalConsumer = consumer,
        )
        if (!audioLeaseArbitrator.tryAcquire(lease)) return InternalAudioAcquireResult.BUSY

        val result = runOnAudioMain {
            val started = runCatching {
                link.setInterruptAiWake(true)
                link.setCXRAudioCbk(audioCallback)
                link.startAudioStream(CXR_AUDIO_PCM)
            }.getOrDefault(false)
            if (!started) {
                audioLeaseArbitrator.clearIf { it.leaseId == lease.leaseId }
                stopAudioStreamQuietly()
                InternalAudioAcquireResult.START_FAILED
            } else if (audioLeaseArbitrator.withActive { current ->
                    if (current.leaseId == lease.leaseId) {
                        current.streamStarted = true
                        true
                    } else {
                        false
                    }
                } != true
            ) {
                // Match plugin acquisition's post-start double check so a concurrent link
                // revoke cannot leave an orphan stream running.
                stopAudioStreamQuietly()
                if (isCxrUp()) {
                    InternalAudioAcquireResult.START_FAILED
                } else {
                    InternalAudioAcquireResult.NO_LINK
                }
            } else {
                silenceSpeechForMicrophone()
                InternalAudioAcquireResult.OK
            }
        }
        if (result != null) return result

        audioLeaseArbitrator.clearIf { it.leaseId == lease.leaseId }
        stopAudioStreamQuietly()
        return InternalAudioAcquireResult.START_FAILED
    }

    /**
     * Silences TTS the moment the microphone opens, whoever opened it.
     *
     * Speech and dictation share one pair of ears: left running, the glasses
     * record the phone voice into what the wearer is saying. Every path that
     * grants the microphone calls this — the plugin-facing lease and the hub's
     * own internal one, which is the one dictation actually uses. Cancelled
     * speech never resumes; a plugin that still wants it said, says it again.
     */
    private fun silenceSpeechForMicrophone() {
        if (::phoneTtsDispatcher.isInitialized) phoneTtsDispatcher.cancelForMicrophone()
        if (::phoneTtsDispatcher.isInitialized) phoneTtsDispatcher.prewarm()
    }

    private fun releaseInternalAudio(tag: String) {
        val leaseToStop = audioLeaseArbitrator.clearIf {
            it.side == AudioLeaseSide.INTERNAL && it.internalTag == tag
        }
        if (leaseToStop != null) stopAudioStreamQuietly()
    }

    private fun releaseAudioLease(envelope: BusEnvelope, replyRemote: Boolean, replyBinder: IBinder?) {
        val leaseId = envelope.payload.optString("leaseId")
        val leaseToStop = audioLeaseArbitrator.clearIf { it.leaseId == leaseId }
        if (leaseToStop != null) stopAudioStreamQuietly()
        replyToAudioRequest(envelope, replyRemote, JSONObject().put("released", true), replyBinder, leaseToStop?.holderPluginId)
        leaseToStop?.let { finalizeBackgroundPluginForLease(it, "RELEASED") }
    }

    private fun releaseAudioLeaseForLocalBinder(callbackBinder: IBinder, reason: String) {
        val leaseToStop = audioLeaseArbitrator.clearIf { current ->
            current.side == AudioLeaseSide.LOCAL && current.localCallbackBinder == callbackBinder
        }
        if (leaseToStop != null) {
            log("Audio lease ${leaseToStop.leaseId} released after $reason")
            stopAudioStreamQuietly()
            finalizeBackgroundPluginForLease(leaseToStop, reason)
        }
    }

    private fun revokeAudioLease(reason: String) {
        val leaseToRevoke = audioLeaseArbitrator.clear() ?: return
        finishRevokedAudioLease(leaseToRevoke, reason)
    }

    private fun revokeBackgroundAudioLease(pluginId: String, reason: String): Boolean {
        if (!::externalPluginController.isInitialized ||
            externalPluginController.backgroundId() != pluginId
        ) {
            return false
        }
        val leaseToRevoke = audioLeaseArbitrator.clearIf { lease ->
            lease.side == AudioLeaseSide.LOCAL &&
                lease.streamStarted &&
                lease.holderPluginId == pluginId
        } ?: return false
        finishRevokedAudioLease(leaseToRevoke, reason)
        return true
    }

    private fun finishRevokedAudioLease(leaseToRevoke: AudioLease, reason: String) {
        stopAudioStreamQuietly()
        if (leaseToRevoke.side == AudioLeaseSide.INTERNAL) {
            leaseToRevoke.internalConsumer?.onStopped(InternalAudioStopReason.LINK_LOST)
            return
        }
        val revoked = BusEnvelope(
            path = AUDIO_LEASE_REVOKED,
            id = leaseToRevoke.leaseId,
            payload = JSONObject()
                .put("leaseId", leaseToRevoke.leaseId)
                .put("reason", reason)
                .apply {
                    if (leaseToRevoke.side == AudioLeaseSide.LOCAL) {
                        leaseToRevoke.holderPluginId?.let { put("pluginId", it) }
                    }
                },
        )
        deliverAudioToHolder(leaseToRevoke, revoked)
        finalizeBackgroundPluginForLease(leaseToRevoke, reason)
    }

    private fun stopAudioLease(internalReason: InternalAudioStopReason) {
        val leaseToStop = audioLeaseArbitrator.clear()
        if (leaseToStop != null) {
            stopAudioStreamQuietly()
            if (leaseToStop.side == AudioLeaseSide.INTERNAL) {
                leaseToStop.internalConsumer?.onStopped(internalReason)
            }
            finalizeBackgroundPluginForLease(leaseToStop, internalReason.name)
        }
    }

    private fun finalizeBackgroundPluginForLease(lease: AudioLease, reason: String) {
        val pluginId = lease.holderPluginId ?: return
        if (::externalPluginController.isInitialized) {
            externalPluginController.onAudioLeaseEnded(pluginId, reason)
        }
    }

    private fun <T> runOnAudioMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val task = FutureTask<T> { block() }
        if (!audioHandler.post(task)) return null
        return runCatching {
            task.get(INTERNAL_AUDIO_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure {
            task.cancel(false)
        }.getOrNull()
    }

    private fun stopAudioStreamQuietly() {
        val stopAudio = Runnable {
            runCatching { cxrLink?.stopAudioStream() }
            runCatching { cxrLink?.setCXRAudioCbk(null) }
            runCatching { cxrLink?.setInterruptAiWake(false) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopAudio.run()
        } else {
            audioHandler.post(stopAudio)
        }
    }

    private fun forwardAudioFrame(data: ByteArray, offset: Int, length: Int) {
        val safeOffset = offset.coerceIn(0, data.size)
        val safeLength = length.coerceAtMost(data.size - safeOffset)
        if (safeLength <= 0) return
        val leaseSnapshot = audioLeaseArbitrator.withActive { current ->
            val seq = current.seq
            current.seq += 1
            current.copy(seq = seq)
        } ?: return
        if (leaseSnapshot.side == AudioLeaseSide.INTERNAL) {
            leaseSnapshot.internalConsumer?.onPcm(
                data = data,
                offset = safeOffset,
                length = safeLength,
                seq = leaseSnapshot.seq,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            return
        }
        val chunk = data.copyOfRange(safeOffset, safeOffset + safeLength)
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val frame = BusEnvelope(
            path = AUDIO_FRAMES,
            id = "${leaseSnapshot.leaseId}:${leaseSnapshot.seq}",
            payload = JSONObject()
                .put("leaseId", leaseSnapshot.leaseId)
                .put("seq", leaseSnapshot.seq)
                .put("elapsedRealtime", elapsedRealtime)
                .apply {
                    if (leaseSnapshot.side == AudioLeaseSide.LOCAL) {
                        leaseSnapshot.holderPluginId?.let { put("pluginId", it) }
                    }
                },
            binary = chunk,
        )
        deliverAudioToHolder(leaseSnapshot, frame)
    }

    private fun deliverAudioToHolder(lease: AudioLease, envelope: BusEnvelope) {
        when (lease.side) {
            AudioLeaseSide.LOCAL -> lease.localCallbackBinder?.let { deliverLocal(envelope, targetBinder = it) }
            AudioLeaseSide.REMOTE -> sendRemote(envelope)
            AudioLeaseSide.INTERNAL -> Unit
        }
    }

    private fun replyToAudioRequest(
        envelope: BusEnvelope,
        replyRemote: Boolean,
        payload: JSONObject,
        replyBinder: IBinder?,
        pluginId: String?,
    ) {
        val response = BusEnvelope(path = envelope.path + "/reply", id = envelope.id, payload = payload)
        if (!replyRemote && pluginId != null) response.payload.put("pluginId", pluginId)
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
        if (SppKeyProvisioning.isReserved(envelope.path)) return "INVALID_PATH"
        if (envelope.binary != null) {
            if (sppSession == null) {
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
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && sppSession == null) {
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

    private fun sendNativePointer(command: RokidNativePointerCommand): Boolean {
        val link = cxrLink ?: return false
        if (!isCxrUp()) return false
        return runCatching {
            val message = RokidNativePointerProtocol.encode(command)
            val result = link.sendCustomCmd(
                RokidNativePointerProtocol.MODULE,
                Caps().apply {
                    write(message.capsCommand)
                    write(message.payload.toString())
                }.serialize(),
            )
            result != null && result >= 0
        }.getOrElse {
            log("Native pointer TX failed ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun offerSppKeyForCurrentCxr(revision: Long) {
        if (!hubEnabled || sppLoopStop || !isCxrUp()) return
        runCatching {
            sppProvisioning.execute {
                if (!sppCxrIdentity.isCurrentOffer(revision)) return@execute
                runCatching { sppPairing.offerCurrent(::sendSppProvisioning) }
                    .onFailure { log("SPP provisioning unavailable; retrying on connection attempt") }
            }
        }
    }

    private fun sendSppProvisioning(envelope: BusEnvelope): Boolean = runCatching {
        if (!hubEnabled || sppLoopStop || !isCxrUp()) return false
        val result = cxrLink?.sendCustomCmd(
            BusConstants.CXR_KEY,
            Caps().apply { write(FrameProtocol.toJson(envelope).toString()) }.serialize(),
        )
        (result != null && result >= 0).also { offered ->
            if (offered) log("SPP pairing key offered over CXR")
        }
    }.getOrDefault(false)

    private fun updateSppKeyStatus(status: PhoneSppPairing.KeyStatus) {
        if (sppKeyStatus == status) return
        sppKeyStatus = status
        log(when (status) {
            PhoneSppPairing.KeyStatus.UNAVAILABLE -> "SPP pairing key unavailable"
            PhoneSppPairing.KeyStatus.RECOVERING -> "SPP pairing key recovery over CXR"
            PhoneSppPairing.KeyStatus.RECOVERED -> "SPP pairing key recovered; awaiting authentication"
        })
        updateStatusNotification(linkState())
    }

    private fun writeSpp(envelope: BusEnvelope): Boolean {
        val (activeSocket, session) = synchronized(sppLock) {
            (socket ?: return false) to (sppSession ?: return false)
        }
        return runCatching {
            session.write(activeSocket.outputStream, envelope)
            log("SPP TX ${envelope.path} id=${envelope.id}")
            true
        }.getOrElse {
            log("SPP TX failed ${it.javaClass.simpleName}")
            closeSocket(activeSocket)
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
        val callbackBinder = cb.asBinder()
        val replacedRegistrations = registrations.filter {
            it.callbackBinder == callbackBinder
        }
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
        // Keep the old registration live until the replacement callback has a
        // death link and is registered. A failed re-registration is not a
        // connection drop and must not strand or spuriously clear activity.
        replacedRegistrations.forEach { removeRegistration(it, "replace") }
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
            while (!sppLoopStop) {
                val attempt = sppBackoff.beginAttempt()
                if (!hubEnabled) {
                    sppBackoff.await(attempt, 750L)
                    continue
                }
                if (!canRunHub(this)) {
                    log("Missing BLUETOOTH_CONNECT; SPP loop waiting")
                    sppBackoff.await(attempt, 5_000L)
                    continue
                }
                val device = pickBondedDevice()
                if (device == null) {
                    log("No bonded glasses device found; SPP loop waiting")
                    sppBackoff.await(attempt, 10_000L)
                    continue
                }
                var current: BluetoothSocket? = null
                try {
                    val pairing = sppPairing.prepare(device.address, ::sendSppProvisioning)
                        ?: throw IOException("SPP pairing unavailable")
                    log("SPP connecting to bonded glasses")
                    val candidate = device.createInsecureRfcommSocketToServiceRecord(BusConstants.SPP_UUID)
                    current = candidate
                    synchronized(sppLock) {
                        if (!hubEnabled || sppLoopStop) throw IOException("Hub stopped")
                        socket = candidate
                    }
                    candidate.connect()
                    val deadline = SppHandshakeDeadline(
                        schedule = { delay, task ->
                            val future = sppDeadlines.schedule(task, delay, TimeUnit.MILLISECONDS)
                            ({ future.cancel(false); Unit })
                        },
                        close = { runCatching { candidate.close() }; Unit },
                    )
                    val session = try {
                        sppPairing.authenticate(device.address, pairing, candidate.inputStream, candidate.outputStream, sppNonces)
                    } catch (failure: Exception) {
                        deadline.complete()
                        throw failure
                    }
                    if (!deadline.complete()) throw IOException("SPP handshake timed out")
                    synchronized(sppLock) {
                        if (socket !== candidate || !hubEnabled || sppLoopStop) {
                            throw IOException("SPP connection retired")
                        }
                        sppSession = session
                    }
                    sppBackoff.connected()
                    sppKeyStatus = null
                    log("SPP authenticated")
                    notifyLinkState()
                    readSppLoop(candidate, session)
                    log("SPP link closed")
                } catch (t: Throwable) {
                    log("SPP connect failed: ${t.javaClass.simpleName}; retrying in ${sppBackoff.retryDelayMs()}ms")
                } finally {
                    runCatching { current?.close() }
                    current?.let { closeSocket(it) }
                    notifyLinkState()
                }
                sppBackoff.awaitRetry(attempt)
            }
        }, "rokidbus-spp").apply { isDaemon = true }.start()
    }

    private fun readSppLoop(activeSocket: BluetoothSocket, session: SppAuthProtocol.Session) {
        val input = activeSocket.inputStream
        while (true) {
            val envelope = session.read(input) ?: return
            log("SPP RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickBondedDevice(): BluetoothDevice? {
        val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        val rememberedAddress = prefs().getString(PREF_LAST_GLASSES_ADDRESS, null)
        val selectedIndex = GlassesBondedDeviceSelector.pickIndex(
            bonded.map { device ->
                BondedBluetoothDevice(
                    address = device.address,
                    name = device.name,
                    alias = device.alias,
                    serviceUuids = device.uuids.orEmpty().map { it.uuid.toString() }.toSet(),
                )
            },
            preferredAddress = rememberedAddress,
        ) ?: return null
        return bonded[selectedIndex].also { selected ->
            if (!selected.address.equals(rememberedAddress, ignoreCase = true)) {
                prefs().edit().putString(PREF_LAST_GLASSES_ADDRESS, selected.address).apply()
            }
        }
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
            sppCxrIdentity.onConnectionChanged(false)
            glassesWorn = false
            notifyLinkState()
        }
    }

    /**
     * One tap on the phone, one switch on the lens. Starts the setup entry point on the glasses so
     * the wearer lands on the accessibility switch directly instead of hunting for it inside the
     * glasses app.
     */
    private fun startGlassesSetupOnLens() {
        val link = cxrLink
        if (!isCxrUp() || link == null) {
            log("glasses setup start skipped: CXR link down")
            NexusPhoneState.setGlassesSetupHandoff(NexusPhoneState.SetupHandoff.FAILED)
            return
        }
        NexusPhoneState.setGlassesSetupHandoff(NexusPhoneState.SetupHandoff.SENDING)
        SetupJournal.record(applicationContext, fromGlasses = false, code = "start_requested")
        val started = runCatching {
            link.appStart(
                "$GLASSES_HUB_PACKAGE.SetupEntryActivity",
                object : IGlassAppCbk {
                    override fun onOpenAppResult(success: Boolean) {
                        log("glasses setup start result=$success")
                        SetupJournal.record(
                            context = applicationContext,
                            fromGlasses = false,
                            code = if (success) "start_delivered" else "start_not_confirmed",
                            detail = if (success) {
                                ""
                            } else {
                                "CXR did not confirm the entry point stayed on screen"
                            },
                        )
                        NexusPhoneState.setGlassesSetupHandoff(
                            if (success) {
                                NexusPhoneState.SetupHandoff.IDLE
                            } else {
                                NexusPhoneState.SetupHandoff.FAILED
                            },
                        )
                    }
                },
            )
        }.isSuccess
        if (!started) {
            log("glasses setup start failed to dispatch")
            NexusPhoneState.setGlassesSetupHandoff(NexusPhoneState.SetupHandoff.FAILED)
        }
    }

    private fun openGlassesAppOnLens() {
        val link = cxrLink
        if (!isCxrUp() || link == null) {
            log("glasses app open skipped: CXR link down")
            return
        }
        runCatching {
            link.appStart(
                "$GLASSES_HUB_PACKAGE.MainActivity",
                object : IGlassAppCbk {
                    override fun onOpenAppResult(success: Boolean) {
                        log("glasses app open result=$success")
                    }
                },
            )
        }.onFailure { log("glasses app open failed: ${it.message}") }
    }

    private fun queryGlassesApp(installIfMissing: Boolean = false) {
        val link = cxrLink
        if (!isCxrUp() || link == null) {
            transitionGlassesAppState(
                GlassesAppInstallEvent.Failed(
                    "Glasses aren't connected — reconnect them first, then retry.",
                    GlassesAppRetry.QUERY,
                ),
            )
            return
        }
        val operationId = beginGlassesAppOperation()
        if (operationId == null) {
            broadcastGlassesAppState(glassesAppInstallState)
            return
        }
        transitionGlassesAppState(GlassesAppInstallEvent.QueryRequested)
        requestGlassesAppQuery(link, operationId, installIfMissing)
    }

    private fun requestGlassesAppQuery(
        link: CXRLink,
        operationId: Long,
        installIfMissing: Boolean = false,
    ) {
        runCatching {
            link.appIsInstalled(
                object : IGlassAppCbk {
                    override fun onQueryAppResult(installed: Boolean) {
                        if (!isGlassesAppOperationActive(operationId)) return
                        transitionGlassesAppState(GlassesAppInstallEvent.QueryCompleted(installed))
                        finishGlassesAppOperation(operationId)
                        if (!installed && installIfMissing) installGlassesApp()
                    }
                },
            )
        }.onFailure { failure ->
            failGlassesAppOperation(
                operationId,
                "Could not check whether Nexus is installed on the glasses.",
                GlassesAppRetry.QUERY,
                failure,
            )
        }
    }

    private fun installGlassesApp() {
        val state = glassesAppInstallState
        val canInstall = state == GlassesAppInstallState.NotInstalled ||
            (state == GlassesAppInstallState.Installed &&
                (glassesAppUpdateState is GlassesAppUpdateState.UpdateAvailable ||
                    glassesAppUpdateState == GlassesAppUpdateState.Unknown)) ||
            state is GlassesAppInstallState.Error && state.retry == GlassesAppRetry.INSTALL
        if (!canInstall) {
            if (state == GlassesAppInstallState.Unknown) {
                queryGlassesApp(installIfMissing = true)
            } else if (state != GlassesAppInstallState.Resolving &&
                state !is GlassesAppInstallState.Downloading &&
                state != GlassesAppInstallState.Installing &&
                state != GlassesAppInstallState.Querying
            ) {
                queryGlassesApp()
            } else {
                broadcastGlassesAppState(state)
            }
            return
        }
        if (!isPhoneWifiEnabled()) {
            transitionGlassesAppState(
                GlassesAppInstallEvent.Failed(
                    "Turn on Wi-Fi first — the update travels to the glasses over Wi-Fi.",
                    GlassesAppRetry.INSTALL,
                ),
            )
            return
        }
        if (!isCxrUp() || cxrLink == null) {
            transitionGlassesAppState(
                GlassesAppInstallEvent.Failed(
                    "Glasses aren't connected — reconnect them first, then retry.",
                    GlassesAppRetry.INSTALL,
                ),
            )
            return
        }
        val operationId = beginGlassesAppOperation()
        if (operationId == null) {
            broadcastGlassesAppState(glassesAppInstallState)
            return
        }
        transitionGlassesAppState(GlassesAppInstallEvent.InstallRequested)
        executor.execute { downloadAndInstallGlassesApp(operationId) }
    }

    private fun pushGlassesRepairConfig() {
        val enabled = GlassesRepairSettingsStore(applicationContext).isAutoRepairEnabled()
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.GLASSES_REPAIR_CONFIG,
                payload = GlassesRepairContract.configToJson(enabled),
            ),
        )
        log("glassesRepairConfig push autoRepair=$enabled error=${error ?: "none"}")
    }

    private fun sendManualSelfArmControl(
        requestId: String,
        action: GlassesManualControlAction,
        armed: Boolean,
    ): String? = sendRemote(
        BusEnvelope(
            path = BusPaths.GLASSES_SELFARM_MANUAL,
            id = requestId,
            payload = JSONObject()
                .put("version", 1)
                .put("action", action.wireValue)
                .apply {
                    if (action == GlassesManualControlAction.CLOSE) put("armed", armed)
                },
        ),
    )

    private fun handlePhoneAssistedSetupOffer(
        envelope: BusEnvelope,
        arrivedAtMillis: Long,
    ) {
        val validation = SetupPairingOfferContract.validateOffer(envelope.payload)
        if (validation !is SetupPairingOfferContract.OfferValidationResult.Valid) {
            log("phone-assisted pairing offer rejected reason=UNSUPPORTED")
            sendInvalidPhoneAssistedSetupResult(envelope)
            return
        }
        val offer = validation.offer
        val decision = phoneAssistedSetupOfferPolicy.evaluate(
            offer = offer,
            currentSessionId = NexusPhoneState.glassesSetupSessionId,
            lastUserIntentAtMillis = lastGlassesSetupUserIntentAtMillis.get(),
            arrivedAtMillis = arrivedAtMillis,
            nowMillis = SystemClock.elapsedRealtime(),
        )
        if (decision is PhoneAssistedSetupOfferPolicy.Decision.Rejected) {
            log("phone-assisted pairing offer rejected reason=${decision.reason}")
            sendPhoneAssistedSetupResult(
                sessionId = offer.sessionId,
                offerId = offer.offerId,
                ok = false,
                reason = decision.reason,
            )
            return
        }

        val accepted = synchronized(phoneAssistedPairingLock) {
            if (activePhoneAssistedPairing != null) {
                false
            } else {
                activePhoneAssistedPairing = ActivePhoneAssistedPairing(
                    sessionId = offer.sessionId,
                    offerId = offer.offerId,
                )
                true
            }
        }
        if (!accepted) {
            log("phone-assisted pairing offer rejected reason=PAIR_REFUSED")
            sendPhoneAssistedSetupResult(
                sessionId = offer.sessionId,
                offerId = offer.offerId,
                ok = false,
                reason = SetupPairingFailureReason.PAIR_REFUSED,
            )
            return
        }

        log("phone-assisted pairing offer accepted")
        manualPairingEngine.start(awaitGlassesConfirmation = false)
        manualPairingEngine.onPhoneAssistedConnectPort(offer.connectPort)
        if (!manualPairingEngine.submit(offer.host, offer.pairingPort, offer.pairingCode)) {
            synchronized(phoneAssistedPairingLock) {
                activePhoneAssistedPairing
                    ?.takeIf { it.sessionId == offer.sessionId && it.offerId == offer.offerId }
                    ?.let { activePhoneAssistedPairing = null }
            }
            sendPhoneAssistedSetupResult(
                sessionId = offer.sessionId,
                offerId = offer.offerId,
                ok = false,
                reason = SetupPairingFailureReason.PAIR_REFUSED,
            )
        }
    }

    private fun sendInvalidPhoneAssistedSetupResult(envelope: BusEnvelope) {
        val sessionId = envelope.payload.optString("sessionId")
        val offerId = envelope.payload.optString("offerId")
        if (!SetupPairingOfferContract.validSessionId(sessionId) ||
            !SetupPairingOfferContract.validOfferId(offerId)
        ) {
            return
        }
        sendPhoneAssistedSetupResult(
            sessionId = sessionId,
            offerId = offerId,
            ok = false,
            reason = SetupPairingFailureReason.UNSUPPORTED,
        )
    }

    private fun onPhoneAssistedPairingStateChanged(state: GlassesManualPairingState) {
        val completion = synchronized(phoneAssistedPairingLock) {
            val active = activePhoneAssistedPairing ?: return@synchronized null
            when (state) {
                GlassesManualPairingState.DONE -> {
                    activePhoneAssistedPairing = null
                    Triple(active, true, "")
                }
                is GlassesManualPairingState.ERROR -> {
                    val reason =
                        if (active.lastState == GlassesManualPairingState.PAIRING) {
                            SetupPairingFailureReason.PAIR_REFUSED
                        } else {
                            SetupPairingFailureReason.ARM_FAILED
                        }
                    activePhoneAssistedPairing = null
                    Triple(active, false, reason)
                }
                GlassesManualPairingState.IDLE -> {
                    if (active.lastState == GlassesManualPairingState.IDLE) {
                        null
                    } else {
                        activePhoneAssistedPairing = null
                        Triple(active, false, SetupPairingFailureReason.PAIR_REFUSED)
                    }
                }
                else -> {
                    active.lastState = state
                    null
                }
            }
        } ?: return

        sendPhoneAssistedSetupResult(
            sessionId = completion.first.sessionId,
            offerId = completion.first.offerId,
            ok = completion.second,
            reason = completion.third,
        )
    }

    private fun sendPhoneAssistedSetupResult(
        sessionId: String,
        offerId: String,
        ok: Boolean,
        reason: String,
    ) {
        val result = SetupPairingOfferContract.createResult(
            sessionId = sessionId,
            offerId = offerId,
            ok = ok,
            reason = reason,
        ) ?: return
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.GLASSES_SETUP_PAIRING_RESULT,
                payload = SetupPairingOfferContract.resultToJson(result),
            ),
        )
        log(
            if (error == null) {
                "phone-assisted pairing result sent ok=$ok"
            } else {
                "phone-assisted pairing result send failed reason=$error"
            },
        )
    }

    private fun handleManualSelfArmResponse(envelope: BusEnvelope): Boolean {
        if (!::manualPairingEngine.isInitialized) return false
        val requestId = envelope.payload.optString("forId", envelope.id).ifBlank { envelope.id }
        if (envelope.path == BusPaths.GLASSES_SELFARM_MANUAL_REPLY &&
            envelope.payload.optBoolean("connectPortUpdate", false)
        ) {
            val port = envelope.payload.optInt("connectPort")
            val sessionId = envelope.payload.optString("sessionId")
            val offerId = envelope.payload.optString("offerId")
            val accepted = if (sessionId.isNotBlank() || offerId.isNotBlank()) {
                synchronized(phoneAssistedPairingLock) {
                    val active = activePhoneAssistedPairing
                    if (port !in 1..65535 || active == null ||
                        active.sessionId != sessionId || active.offerId != offerId
                    ) {
                        false
                    } else {
                        manualPairingEngine.onPhoneAssistedConnectPort(port)
                        true
                    }
                }
            } else {
                manualPairingEngine.onGlassesConnectPort(requestId, port)
            }
            if (!accepted) {
                return false
            }
            recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
            log("glasses connect port updated")
            return true
        }
        val errorCode = when (envelope.path) {
            BusPaths.GLASSES_SELFARM_MANUAL_REPLY -> if (
                envelope.payload.optBoolean("accepted", false)
            ) {
                null
            } else {
                envelope.payload.optString("code", "REJECTED")
            }
            BusPaths.ERROR -> envelope.payload.optString("code", "REMOTE_ERROR")
            else -> return false
        }
        if (!manualPairingEngine.onManualControlResponse(requestId, errorCode)) return false
        // Only a reply that matched the live request may teach the engine a connect port. A stale
        // ack straggling in after a cancel used to re-plant its (possibly obsolete) port, and a
        // later typed pairing would then skip discovery and dial the wrong door.
        manualPairingEngine.onGlassesConnectPort(
            requestId,
            envelope.payload.optInt("connectPort"),
        )
        recordRemoteRoute(
            envelope,
            if (errorCode == null) PluginBusJournal.Verdict.OK else PluginBusJournal.Verdict.REJECTED,
            errorCode,
        )
        log(
            if (errorCode == null) {
                "manual self-arm control acknowledged"
            } else {
                "manual self-arm control rejected code=$errorCode"
            },
        )
        return true
    }

    private fun isPhoneWifiEnabled(): Boolean =
        runCatching {
            getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(true)

    private fun downloadAndInstallGlassesApp(operationId: Long) {
        val release = runCatching { resolveLatestGlassesAppRelease() }.getOrElse { failure ->
            failGlassesAppOperation(
                operationId,
                "Could not find the latest glasses APK release.",
                GlassesAppRetry.INSTALL,
                failure,
            )
            return
        }
        if (!isGlassesAppOperationActive(operationId)) return

        val cacheDirectory = File(cacheDir, "glasses-app-installs").apply { mkdirs() }
        val apk = File(cacheDirectory, "nexus-glasses-${release.version}.apk")
        apk.delete()
        transitionGlassesAppState(GlassesAppInstallEvent.DownloadStarted)
        runCatching {
            HttpsArtifactDownloader().download(
                url = release.apkUrl,
                destination = apk,
                isCancelled = {
                    !isGlassesAppOperationActive(operationId) || !isCxrUp()
                },
                onProgress = { downloaded, total ->
                    if (isGlassesAppOperationActive(operationId)) {
                        transitionGlassesAppState(
                            GlassesAppInstallEvent.DownloadProgress(downloaded, total),
                        )
                    }
                },
            )
            if (release.sha256 != null && !PluginInstaller.sha256Matches(apk, release.sha256)) {
                throw IOException("GitHub release digest did not match")
            }
            val archive = AndroidArtifactPackageInspector(packageManager).inspect(apk)
            val verdict = GlassesApkVerificationPolicy.verdict(
                parsedPackageName = archive?.packageName,
                expectedPackageName = GLASSES_HUB_PACKAGE,
                phoneSdkInt = Build.VERSION.SDK_INT,
                digestVerified = release.sha256 != null,
            )
            if (verdict is GlassesApkVerdict.Reject) {
                throw IOException(verdict.reason)
            }
            if (archive == null) {
                log(
                    "glasses apk accepted unparsed: phone API ${Build.VERSION.SDK_INT} < " +
                        "${GlassesApkVerificationPolicy.GLASSES_APK_MIN_SDK}, release digest verified",
                )
            }
        }.onFailure { failure ->
            apk.delete()
            val message = if (!isCxrUp()) {
                "Connection to the glasses was lost."
            } else {
                "Could not download or verify the glasses APK."
            }
            failGlassesAppOperation(operationId, message, GlassesAppRetry.INSTALL, failure)
            return
        }

        if (!isGlassesAppOperationActive(operationId)) {
            apk.delete()
            return
        }
        val link = cxrLink
        if (!isCxrUp() || link == null) {
            apk.delete()
            failGlassesAppOperation(
                operationId,
                "Connection to the glasses was lost.",
                GlassesAppRetry.INSTALL,
            )
            return
        }
        transitionGlassesAppState(GlassesAppInstallEvent.UploadStarted)
        runCatching {
            link.appUploadAndInstall(
                apk.absolutePath,
                object : IGlassAppCbk {
                    override fun onInstallAppResult(success: Boolean) {
                        apk.delete()
                        if (!isGlassesAppOperationActive(operationId)) return
                        if (success) {
                            updateRemoteGlassesAppState(
                                null,
                                setupComplete = false,
                                setupFailureState = "",
                                setupFailureDiagnostic = "",
                            )
                            transitionGlassesAppState(GlassesAppInstallEvent.InstallCompleted(true))
                            requestGlassesAppQuery(link, operationId)
                        } else {
                            transitionGlassesAppState(GlassesAppInstallEvent.InstallCompleted(false))
                            finishGlassesAppOperation(operationId)
                        }
                    }
                },
            )
        }.onFailure { failure ->
            apk.delete()
            failGlassesAppOperation(
                operationId,
                "Could not send the glasses APK over CXR.",
                GlassesAppRetry.INSTALL,
                failure,
            )
        }
    }

    private fun resolveLatestGlassesAppRelease(): NexusReleaseAsset {
        val response = HttpsNexusUpdateTransport(URL(NexusUpdateChecker.RELEASES_URL)).fetch(null)
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub releases request failed with HTTP ${response.statusCode}")
        }
        val body = response.body ?: throw IOException("GitHub releases response was empty")
        return NexusReleaseAssetResolver.parseLatest(body, NexusReleaseArtifact.GLASSES)
            ?: throw IOException("No stable glasses APK release was found")
    }

    private fun updateRemoteGlassesAppState(
        versionName: String?,
        setupComplete: Boolean,
        setupFailureState: String = remoteGlassesSetupFailureState,
        setupFailureDiagnostic: String = remoteGlassesSetupFailureDiagnostic,
        setupSessionId: String = remoteGlassesSetupSessionId,
        setupStage: String = remoteGlassesSetupStage,
        setupRunning: Boolean = remoteGlassesSetupRunning,
        setupRequiresUserAction: Boolean = remoteGlassesSetupRequiresUserAction,
        setupSupportCode: String = remoteGlassesSetupSupportCode,
        setupCompletionMode: String = remoteGlassesSetupCompletionMode,
        coreReady: Boolean = remoteGlassesCoreReady,
        maintenanceReady: Boolean = remoteGlassesMaintenanceReady,
    ) {
        val stateChanged = synchronized(glassesAppReleaseLock) {
            val versionChanged = remoteGlassesVersionName != versionName
            val setupChanged = remoteGlassesSetupComplete != setupComplete
            val failureChanged = remoteGlassesSetupFailureState != setupFailureState ||
                remoteGlassesSetupFailureDiagnostic != setupFailureDiagnostic
            val progressChanged = remoteGlassesSetupSessionId != setupSessionId ||
                remoteGlassesSetupStage != setupStage ||
                remoteGlassesSetupRunning != setupRunning ||
                remoteGlassesSetupRequiresUserAction != setupRequiresUserAction ||
                remoteGlassesSetupSupportCode != setupSupportCode ||
                remoteGlassesSetupCompletionMode != setupCompletionMode ||
                remoteGlassesCoreReady != coreReady ||
                remoteGlassesMaintenanceReady != maintenanceReady
            remoteGlassesVersionName = versionName
            remoteGlassesSetupComplete = setupComplete
            remoteGlassesSetupFailureState = setupFailureState
            remoteGlassesSetupFailureDiagnostic = setupFailureDiagnostic
            remoteGlassesSetupSessionId = setupSessionId
            remoteGlassesSetupStage = setupStage
            remoteGlassesSetupRunning = setupRunning
            remoteGlassesSetupRequiresUserAction = setupRequiresUserAction
            remoteGlassesSetupSupportCode = setupSupportCode
            remoteGlassesSetupCompletionMode = setupCompletionMode
            remoteGlassesCoreReady = coreReady
            remoteGlassesMaintenanceReady = maintenanceReady
            val updateStateChanged = recomputeGlassesAppUpdateStateLocked()
            versionChanged || setupChanged || failureChanged || progressChanged || updateStateChanged
        }
        if (stateChanged) {
            broadcastGlassesAppState(glassesAppInstallState)
        }
        if (versionName != null) refreshLatestGlassesAppRelease()
    }

    private fun refreshLatestGlassesAppRelease() {
        val now = System.currentTimeMillis()
        val shouldCheck = synchronized(glassesAppReleaseLock) {
            val elapsed = now - glassesReleaseCheckedAtMillis
            val checkIsDue = glassesReleaseCheckedAtMillis == 0L ||
                elapsed < 0L || elapsed >= GLASSES_RELEASE_CHECK_INTERVAL_MILLIS
            if (glassesReleaseCheckInFlight || !checkIsDue) {
                false
            } else {
                glassesReleaseCheckInFlight = true
                true
            }
        }
        if (!shouldCheck) return
        executor.execute {
            val result = runCatching(::resolveLatestGlassesAppRelease)
            synchronized(glassesAppReleaseLock) {
                glassesReleaseCheckInFlight = false
                glassesReleaseCheckedAtMillis = System.currentTimeMillis()
                result.getOrNull()?.let { latestGlassesAppRelease = it }
            }
            result.exceptionOrNull()?.let { failure ->
                log(
                    "glasses release check failed type=${failure.javaClass.simpleName} " +
                        "msg=${failure.message.orEmpty()}",
                )
            }
            if (recomputeGlassesAppUpdateState()) {
                broadcastGlassesAppState(glassesAppInstallState)
            }
        }
    }

    private fun recomputeGlassesAppUpdateState(): Boolean {
        return synchronized(glassesAppReleaseLock) { recomputeGlassesAppUpdateStateLocked() }
    }

    private fun recomputeGlassesAppUpdateStateLocked(): Boolean {
        val next = GlassesAppUpdatePolicy.compare(
            installedVersionName = remoteGlassesVersionName,
            latestRelease = latestGlassesAppRelease,
        )
        return if (glassesAppUpdateState == next) {
            false
        } else {
            glassesAppUpdateState = next
            true
        }
    }

    private fun beginGlassesAppOperation(): Long? = synchronized(glassesAppOperationLock) {
        if (activeGlassesAppOperationId != null) return@synchronized null
        glassesAppOperationSequence += 1L
        glassesAppOperationSequence.also { activeGlassesAppOperationId = it }
    }

    private fun isGlassesAppOperationActive(operationId: Long): Boolean =
        synchronized(glassesAppOperationLock) { activeGlassesAppOperationId == operationId }

    private fun finishGlassesAppOperation(operationId: Long): Boolean =
        synchronized(glassesAppOperationLock) {
            if (activeGlassesAppOperationId != operationId) return@synchronized false
            activeGlassesAppOperationId = null
            true
        }

    private fun failActiveGlassesAppOperation(message: String) {
        val retry = synchronized(glassesAppOperationLock) {
            if (activeGlassesAppOperationId == null) return
            activeGlassesAppOperationId = null
            if (glassesAppInstallState == GlassesAppInstallState.Querying) {
                GlassesAppRetry.QUERY
            } else {
                GlassesAppRetry.INSTALL
            }
        }
        transitionGlassesAppState(GlassesAppInstallEvent.Failed(message, retry))
    }

    private fun failGlassesAppOperation(
        operationId: Long,
        message: String,
        retry: GlassesAppRetry,
        failure: Throwable? = null,
    ) {
        if (!finishGlassesAppOperation(operationId)) return
        if (failure != null) {
            log("glasses app operation failed type=${failure.javaClass.simpleName} msg=${failure.message.orEmpty()}")
        }
        transitionGlassesAppState(GlassesAppInstallEvent.Failed(message, retry))
    }

    private fun transitionGlassesAppState(event: GlassesAppInstallEvent) {
        val state = synchronized(glassesAppStateLock) {
            GlassesAppInstallStateMachine.reduce(glassesAppInstallState, event)
                .also { glassesAppInstallState = it }
        }
        broadcastGlassesAppState(state)
    }

    private fun broadcastGlassesAppState(state: GlassesAppInstallState) {
        val updateState = glassesAppUpdateState
        NexusPhoneState.recordLogLine(glassesAppStatusLine(state))
        val intent = Intent(ACTION_LOG)
            .setPackage(packageName)
            .putExtra("line", glassesAppStatusLine(state))
            .putExtra(NexusPhoneState.EXTRA_GLASSES_APP_STATE, state.broadcastValue())
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_APP_VERSION_NAME,
                remoteGlassesVersionName.orEmpty(),
            )
            .putExtra(NexusPhoneState.EXTRA_GLASSES_SETUP_COMPLETE, remoteGlassesSetupComplete)
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_FAILURE_STATE,
                remoteGlassesSetupFailureState,
            )
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_FAILURE_DIAGNOSTIC,
                remoteGlassesSetupFailureDiagnostic,
            )
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_SESSION_ID,
                remoteGlassesSetupSessionId,
            )
            .putExtra(NexusPhoneState.EXTRA_GLASSES_SETUP_STAGE, remoteGlassesSetupStage)
            .putExtra(NexusPhoneState.EXTRA_GLASSES_SETUP_RUNNING, remoteGlassesSetupRunning)
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_REQUIRES_USER_ACTION,
                remoteGlassesSetupRequiresUserAction,
            )
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_SUPPORT_CODE,
                remoteGlassesSetupSupportCode,
            )
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_SETUP_COMPLETION_MODE,
                remoteGlassesSetupCompletionMode,
            )
            .putExtra(NexusPhoneState.EXTRA_GLASSES_CORE_READY, remoteGlassesCoreReady)
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_MAINTENANCE_READY,
                remoteGlassesMaintenanceReady,
            )
            .putExtra(
                NexusPhoneState.EXTRA_GLASSES_APP_UPDATE_STATE,
                updateState.broadcastValue(),
            )
            .apply {
                updateState.latestVersion()?.let { latest ->
                    putExtra(NexusPhoneState.EXTRA_GLASSES_APP_LATEST_VERSION_NAME, latest.toString())
                }
            }
        when (state) {
            is GlassesAppInstallState.Downloading -> intent
                .putExtra(NexusPhoneState.EXTRA_GLASSES_APP_DOWNLOADED, state.downloadedBytes)
                .apply {
                    state.totalBytes?.let { putExtra(NexusPhoneState.EXTRA_GLASSES_APP_TOTAL, it) }
                }
            is GlassesAppInstallState.Error -> intent
                .putExtra(NexusPhoneState.EXTRA_GLASSES_APP_MESSAGE, state.message)
                .putExtra(NexusPhoneState.EXTRA_GLASSES_APP_RETRY, state.retry.name.lowercase())
            else -> Unit
        }
        sendBroadcast(intent)
    }

    private fun GlassesAppInstallState.broadcastValue(): String = when (this) {
        GlassesAppInstallState.Unknown -> "unknown"
        GlassesAppInstallState.Querying -> "querying"
        GlassesAppInstallState.NotInstalled -> "not_installed"
        GlassesAppInstallState.Resolving -> "resolving"
        is GlassesAppInstallState.Downloading -> "downloading"
        GlassesAppInstallState.Installing -> "installing"
        GlassesAppInstallState.Installed -> "installed"
        is GlassesAppInstallState.Error -> "error"
    }

    private fun GlassesAppUpdateState.broadcastValue(): String = when (this) {
        GlassesAppUpdateState.Unknown -> "unknown"
        is GlassesAppUpdateState.UpToDate -> "up_to_date"
        is GlassesAppUpdateState.UpdateAvailable -> "update_available"
    }

    private fun GlassesAppUpdateState.latestVersion(): NexusSemVersion? = when (this) {
        GlassesAppUpdateState.Unknown -> null
        is GlassesAppUpdateState.UpToDate -> latest
        is GlassesAppUpdateState.UpdateAvailable -> latest
    }

    private fun glassesAppStatusLine(state: GlassesAppInstallState): String = when (state) {
        GlassesAppInstallState.Unknown -> "Glasses app status is unknown"
        GlassesAppInstallState.Querying -> "Checking glasses app installation"
        GlassesAppInstallState.NotInstalled -> "Glasses app is not installed"
        GlassesAppInstallState.Resolving -> "Resolving glasses app release"
        is GlassesAppInstallState.Downloading -> state.totalBytes?.takeIf { it > 0L }?.let { total ->
            "Downloading glasses app ${(state.downloadedBytes * 100L / total).coerceIn(0L, 100L)}%"
        } ?: "Downloading glasses app"
        GlassesAppInstallState.Installing -> "Installing glasses app over CXR"
        GlassesAppInstallState.Installed -> "Glasses app installation confirmed"
        is GlassesAppInstallState.Error -> "Glasses app install error: ${state.message}"
    }

    private fun decodeCxrPayload(payload: ByteArray): BusEnvelope? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            FrameProtocol.fromJson(JSONObject(caps.at(0).string))
        }.onFailure {
            log("CXR decode failed")
        }.getOrNull()

    private fun startPeriodicUpdateChecks() {
        updateCheckLoopStopped = false
        createUpdateNotificationChannel()
        updateCheckHandler.post(updateCheckTick)
    }

    private fun stopPeriodicUpdateChecks() {
        updateCheckLoopStopped = true
        updateCheckHandler.removeCallbacks(updateCheckTick)
    }

    private fun runBackgroundUpdateChecks() {
        runCatching {
            NexusUpdateManager.checkForUpdates(applicationContext) { result ->
                if (!updateCheckLoopStopped && result is NexusUpdateCheckResult.Available) {
                    maybeNotifyAppUpdate(result.release)
                }
            }
        }.onFailure { failure ->
            Log.w(TAG, "Could not start app update check", failure)
        }
        runCatching {
            PluginUpdateChecker.refreshIfStale(applicationContext) { updates ->
                if (!updateCheckLoopStopped) maybeNotifyPluginUpdates(updates)
            }
        }.onFailure { failure ->
            Log.w(TAG, "Could not start plugin update check", failure)
        }
    }

    private fun maybeNotifyAppUpdate(release: NexusAppRelease) {
        val preferences = updateNotificationPreferences()
        val version = release.versionName
        val lastNotifiedVersion = preferences.getString(PREF_LAST_NOTIFIED_APP_VERSION, null)
        if (!UpdateNotificationPolicy.shouldNotifyAppUpdate(version, lastNotifiedVersion)) return

        if (postUpdateNotification(
                notificationId = APP_UPDATE_NOTIFICATION_ID,
                title = "Rokid Nexus update available",
                text = "Version $version is available.",
            )
        ) {
            preferences.edit().putString(PREF_LAST_NOTIFIED_APP_VERSION, version).apply()
        }
    }

    private fun maybeNotifyPluginUpdates(updates: List<PluginUpdateInfo>) {
        val updateSet = UpdateNotificationPolicy.pluginUpdateSet(updates)
        val preferences = updateNotificationPreferences()
        val lastNotifiedSet = preferences.getStringSet(PREF_LAST_NOTIFIED_PLUGIN_UPDATES, null)?.toSet()
        if (!UpdateNotificationPolicy.shouldNotifyPluginUpdates(updateSet, lastNotifiedSet)) return

        val count = updateSet.size
        val title = if (count == 1) {
            "1 plugin update available"
        } else {
            "$count plugin updates available"
        }
        if (postUpdateNotification(
                notificationId = PLUGIN_UPDATE_NOTIFICATION_ID,
                title = title,
                text = "Open Rokid Nexus to review and install.",
            )
        ) {
            preferences.edit().putStringSet(PREF_LAST_NOTIFIED_PLUGIN_UPDATES, updateSet).apply()
        }
    }

    private fun createUpdateNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                UPDATES_CHANNEL_ID,
                "Updates available",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun postUpdateNotification(
        notificationId: Int,
        title: String,
        text: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Update notification skipped: POST_NOTIFICATIONS permission not granted")
            return false
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, UPDATES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        return runCatching {
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            true
        }.onFailure { failure ->
            Log.w(TAG, "Could not post update notification", failure)
        }.getOrDefault(false)
    }

    private fun updateNotificationPreferences() =
        getSharedPreferences(UPDATE_NOTIFICATION_PREFERENCES, MODE_PRIVATE)

    private fun startForegroundWithType(): SttError? {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW),
        )
        val state = linkState()
        lastNotifiedStatus = statusText(state)
        val requestMicrophone = speechMicrophoneForegroundRequested
        val result = runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildStatusNotification(state),
                ServiceInfoCompat.hubTypes(includeMicrophone = requestMicrophone),
            )
        }
        if (result.isSuccess) {
            speechMicrophoneForegroundActive = requestMicrophone
            speechMicrophoneForegroundFailure = ""
            return null
        }

        val failure = result.exceptionOrNull()
        speechMicrophoneForegroundActive = false
        speechMicrophoneForegroundFailure = foregroundFailureDetail(failure)
        Log.w(
            TAG,
            "Hub foreground promotion failed microphone=$requestMicrophone " +
                "type=${failure?.javaClass?.simpleName.orEmpty()}",
            failure,
        )
        if (requestMicrophone) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    buildStatusNotification(state),
                    ServiceInfoCompat.hubTypes(),
                )
            }.onFailure { fallbackFailure ->
                Log.w(
                    TAG,
                    "Connected-device foreground fallback failed " +
                        "type=${fallbackFailure.javaClass.simpleName}",
                    fallbackFailure,
                )
            }
            return SttError(
                SttErrorKind.SOURCE_UNAVAILABLE,
                SpeechProvider.ANDROID.displayName,
                "Microphone foreground service is unavailable " +
                    "(${speechMicrophoneForegroundFailure})",
            )
        }
        return null
    }

    private fun requestSpeechMicrophoneForegroundOnMain(): SttError? {
        speechMicrophoneForegroundRequested = true
        val error = startForegroundWithType()
        if (error != null) speechMicrophoneForegroundRequested = false
        return error
    }

    private fun releaseSpeechMicrophoneForegroundOnMain() {
        if (!speechMicrophoneForegroundRequested && !speechMicrophoneForegroundActive) return
        speechMicrophoneForegroundRequested = false
        startForegroundWithType()
    }

    private fun foregroundFailureDetail(failure: Throwable?): String {
        if (failure == null) return "ForegroundServiceException"
        val type = failure.javaClass.simpleName.ifBlank { "ForegroundServiceException" }
        val message = failure.message?.trim().orEmpty()
        return if (message.isBlank()) type else "$type: $message"
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
        sppKeyStatus == PhoneSppPairing.KeyStatus.UNAVAILABLE ->
            "Glasses pairing key unavailable; reconnect Hi Rokid to recover"
        sppKeyStatus == PhoneSppPairing.KeyStatus.RECOVERING ->
            "Recovering glasses pairing key"
        sppKeyStatus == PhoneSppPairing.KeyStatus.RECOVERED ->
            "Glasses pairing key recovered; reconnecting"
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

    private fun linkState(): Int = PhoneLinkState.compose(
        cxrControlUp = isCxrUp(),
        sppDataUp = sppSession != null && socket?.isConnected == true,
        glassesBondedOrPhoneConnected = isGlassesBonded(),
        glassesWorn = glassesWorn,
    )

    @SuppressLint("MissingPermission")
    private fun isGlassesBonded(): Boolean =
        canRunHub(this) &&
            pickBondedDevice() != null

    private fun isCxrUp(): Boolean =
        cxrConnected && glassBtConnected && cxrLink?.isServiceConnected() == true

    private fun notifyGlassesAiButton(active: Boolean) {
        registrations.forEach { registration ->
            runCatching { registration.callback.onGlassesAiButton(active) }
                .onFailure { removeRegistration(registration, "dead callback") }
        }
    }

    /**
     * Resolves the assistant from installed principals as well as live registrations because the
     * normal plugin state is dormant and therefore has no binder registration. The current grant
     * remains the authority; a live registration, when present, must agree with it.
     */
    private fun approvedAssistantPrincipal(): PhonePluginPrincipal? {
        if (!::externalPluginController.isInitialized ||
            !::pluginDiscovery.isInitialized ||
            !::pluginGrantStore.isInitialized
        ) {
            return null
        }
        val activePluginId = externalPluginController.activeId()
        val installed = runCatching(::installedPluginPrincipals).getOrDefault(emptyList())
        val live = registrations.mapNotNull(Registration::principal)
        return (installed + live)
            .distinctBy(PhonePluginPrincipal::grantKey)
            .filter { principal ->
                if (PluginCapability.ASSISTANT !in principal.descriptor.requestedCapabilities) {
                    return@filter false
                }
                val currentGrant = pluginGrantStore.stateFor(principal) as? PluginGrantState.Approved
                    ?: return@filter false
                if (PluginCapability.ASSISTANT !in currentGrant.capabilities) return@filter false
                val registration = registrations.firstOrNull {
                    it.principal?.grantKey() == principal.grantKey()
                }
                registration == null ||
                    PluginCapability.ASSISTANT in registration.grantedCapabilities
            }
            .sortedWith(
                compareBy<PhonePluginPrincipal>(
                    { it.descriptor.id != activePluginId },
                    { it.descriptor.id },
                    { it.packageName },
                ),
            )
            .firstOrNull()
    }

    private fun notifyGlassesDeviceInfo(info: GlassInfo) {
        val eventId = UUID.randomUUID().toString()
        registrations.mapNotNull { it.principal }
            .distinctBy { it.grantKey() }
            .forEach { principal ->
                deliverExternalLifecycle(
                    principal = principal,
                    path = BusPaths.GLASSES_DEVICE_INFO,
                    id = eventId,
                    payload = GlassesDeviceInfoPayload.create(
                        info = info,
                        pluginId = principal.descriptor.id,
                        eventId = eventId,
                    ),
                )
            }
    }

    private fun notifyLinkState() {
        val state = linkState()
        val previousTransportState = lastTransportLinkState
        lastTransportLinkState = state
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (::coreRemoteBridge.isInitialized) {
            coreRemoteBridge.onLinkStateChanged(
                connected = state and transportBits != 0,
                nativePointerAvailable = state and LinkStateBits.CXR_CONTROL_UP != 0,
            )
        }
        if (::pluginGuardianCoordinator.isInitialized) {
            pluginGuardianCoordinator.onLinkStateChanged(state and transportBits != 0)
        }
        if (previousTransportState and transportBits != 0 && state and transportBits == 0) {
            inkRouter.clearForLinkLoss()
        }
        if (::cameraCompanionController.isInitialized &&
            ((previousTransportState and transportBits) and state.inv()) != 0
        ) {
            cameraCompanionController.onLinkLost()
        }
        if (state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) {
            if (::mediaSyncCoordinator.isInitialized) mediaSyncCoordinator.onLinkLost()
            remoteImageSurfaceVersion = 0
            remoteInkSurfaceVersion = 0
            remoteMaxImageBytes = 0
            // remotePinSurfaceVersion and remoteActivitySurfaceVersion deliberately survive,
            // for the same reason as the setup state below: support is a property of the
            // glasses, not of the link, and both tiers have canonical state plus reconnect
            // resends. The next announce overwrites them, so swapping in older glasses
            // corrects itself. Image support is not kept — an image has no canonical state
            // to resend, so it must refuse while the link is down.
            // Keep the last-known setup state across link drops: powered-off glasses must
            // not re-open the setup step. Only a live announcement may report false.
            // The installed version is kept for the same reason — glasses that went quiet
            // did not uninstall their app, and forgetting the version turns the update
            // check into "Reinstall" for something we know is current. The next announce
            // overwrites it, so swapping in another unit still corrects itself.
            updateRemoteGlassesAppState(
                remoteGlassesVersionName,
                setupComplete = remoteGlassesSetupComplete,
            )
            imageSurfaceRateLimiter.clear()
        }
        if (::mediaSyncCoordinator.isInitialized &&
            state and LinkStateBits.CXR_CONTROL_UP != 0 &&
            previousTransportState and LinkStateBits.CXR_CONTROL_UP == 0
        ) {
            mediaSyncCoordinator.onLinkUp()
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
            phoneBatteryReporter?.resend("link_up")
        } else {
            lastAnnouncedPhoneCapabilities = null
        }
    }

    /** The glasses learn phone-side feature bits (camera readiness) only through this. */
    private fun announcePhoneCapabilities() {
        val hudPosition = PhoneHudPositionStore(this)
        val announced = PhoneHubCapabilitiesContract.create(
            features = PhoneAssistedSetupCapabilityPolicy.advertised(phoneCameraCapabilities()),
            cameraConsumerName = cameraConsumerReadiness.resolveApproved()?.descriptor?.displayName,
            activityAlwaysExpanded =
                PhoneActivityPresentationSettings(this).isAlwaysExpanded(),
            hudTopInsetDp = hudPosition.hudTopInsetDp(),
            hudPositionAuto = hudPosition.hudPositionAuto(),
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

    private fun errorEnvelope(id: String, code: String, pluginId: String? = null): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id).apply {
                if (pluginId != null) put("pluginId", pluginId)
            },
        )

    private fun capabilities(): Int {
        var capabilities =
            PhoneAssistedSetupCapabilityPolicy.advertised(baseCameraCapabilities())
        if (remoteImageSurfaceVersion == ImageSurfaceContract.VERSION &&
            remoteMaxImageBytes >= ImageSurfaceContract.MAX_IMAGE_BYTES &&
            linkState() and LinkStateBits.SPP_DATA_UP != 0
        ) {
            capabilities = capabilities or BusCapabilityBits.IMAGE_SURFACE
        }
        if (PhoneInkCapabilityPolicy.isAvailable(remoteInkSurfaceVersion, linkState())) {
            capabilities = capabilities or BusCapabilityBits.INK_SURFACE
        }
        // Deliberately not gated on the link, unlike IMAGE_SURFACE above: the bit means
        // "these glasses can show a pin", not "a pin would go out this instant". A pin has
        // canonical phone-side state and a resend path, so one pushed while the glasses are
        // asleep is held and delivered on the next announce. An image has neither, so it
        // still has to refuse when the link is down.
        if (remotePinSurfaceVersion == PinSurfaceContract.VERSION) {
            capabilities = capabilities or BusCapabilityBits.PIN_SURFACE
        }
        // Gated on the link, unlike the pin above and for the mirror-image reason:
        // a notice has no resend path because it is never worth delivering late,
        // so a plugin must learn the glasses are unreachable before it composes a
        // banner, not after.
        if (remoteNoticeSurfaceVersion == NoticeSurfaceContract.VERSION &&
            linkState() and LinkStateBits.SPP_DATA_UP != 0
        ) {
            capabilities = capabilities or BusCapabilityBits.NOTICE_SURFACE
        }
        // Like pins, activities have canonical phone-side state and an announce-time
        // resend. Their owner must stay connected, but a transient glasses-link loss does
        // not make an otherwise valid start or update disappear.
        if (remoteActivitySurfaceVersion == ActivitySurfaceContract.VERSION) {
            capabilities = capabilities or BusCapabilityBits.ACTIVITY_SURFACE
        }
        // Same reasoning as the notice gate above: a card opened to type into is a
        // live interactive moment, not state a plugin can just resend once the
        // glasses come back — so the bit means "reachable right now", not merely
        // "this hub build understands the field".
        if (remoteEditableSurfaceVersion == EditableSurfaceContract.VERSION &&
            linkState() and LinkStateBits.SPP_DATA_UP != 0
        ) {
            capabilities = capabilities or BusCapabilityBits.EDITABLE_SURFACE
        }
        capabilities = capabilities or BusCapabilityBits.TTS
        // Unconditional: this build can always take a pairing offer off the glasses. Gating it on
        // link or session state would make the glasses read "no phone help available" during the
        // exact window where the offer is about to be sent.
        return PhoneAssistedSetupCapabilityPolicy.advertised(capabilities)
    }

    private fun baseCameraCapabilities(): Int {
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
        return capabilities
    }

    private fun phoneCameraCapabilities(): Int {
        val wifiEnabled = runCatching {
            getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()
        return PhoneHubCameraCapabilityPolicy.applyLohsRequirement(baseCameraCapabilities(), wifiEnabled)
    }

    private fun updateRemoteCapabilities(payload: JSONObject) {
        val advertised = GlassesHubCapabilitiesContract.parse(payload)
        val imageSupported = advertised.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            advertised.features and BusCapabilityBits.IMAGE_SURFACE != 0 &&
            advertised.imageSurfaceVersion == ImageSurfaceContract.VERSION &&
            advertised.maxImageBytes >= ImageSurfaceContract.MAX_IMAGE_BYTES
        val pinSupported = advertised.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            advertised.features and BusCapabilityBits.PIN_SURFACE != 0 &&
            advertised.pinSurfaceVersion == PinSurfaceContract.VERSION
        remoteImageSurfaceVersion = if (imageSupported) ImageSurfaceContract.VERSION else 0
        val noticeSupported = advertised.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            advertised.features and BusCapabilityBits.NOTICE_SURFACE != 0 &&
            advertised.noticeSurfaceVersion == NoticeSurfaceContract.VERSION
        val activitySupported = advertised.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            advertised.features and BusCapabilityBits.ACTIVITY_SURFACE != 0 &&
            advertised.activitySurfaceVersion == ActivitySurfaceContract.VERSION
        val acceptedInkVersion = PhoneInkCapabilityPolicy.acceptedVersion(advertised)
        val editableSupported = GlassesHubCapabilitiesContract.supportsEditableSurface(advertised)
        remotePinSurfaceVersion = if (pinSupported) PinSurfaceContract.VERSION else 0
        remoteNoticeSurfaceVersion = if (noticeSupported) NoticeSurfaceContract.VERSION else 0
        remoteActivitySurfaceVersion = if (activitySupported) ActivitySurfaceContract.VERSION else 0
        remoteInkSurfaceVersion = acceptedInkVersion
        remoteEditableSurfaceVersion = if (editableSupported) EditableSurfaceContract.VERSION else 0
        remoteMaxImageBytes = if (imageSupported) advertised.maxImageBytes else 0
        updateRemoteGlassesAppState(
            advertised.versionName,
            advertised.setupComplete,
            advertised.setupFailureState,
            advertised.setupFailureDiagnostic,
            advertised.setupSessionId,
            GlassesHubCapabilitiesContract.effectiveStage(advertised),
            advertised.setupRunning,
            advertised.setupRequiresUserAction,
            advertised.setupSupportCode,
            advertised.setupCompletionMode,
            advertised.coreReady,
            advertised.maintenanceReady,
        )
        NexusPhoneState.setGlassesSetupProgress(
            sessionId = advertised.setupSessionId,
            stage = GlassesHubCapabilitiesContract.effectiveStage(advertised),
            running = advertised.setupRunning,
            requiresUserAction = advertised.setupRequiresUserAction,
            supportCode = advertised.setupSupportCode,
            completionMode = advertised.setupCompletionMode,
            coreReady = advertised.coreReady,
            maintenanceReady = advertised.maintenanceReady,
        )
        if (::manualPairingEngine.isInitialized) {
            manualPairingEngine.onGlassesSetupReported(advertised.setupComplete)
        }
        // The glasses announce on every hub start, which is the one restart the
        // phone cannot see as a link change. Without this the badge stays blank
        // until the phone next crosses a percent.
        phoneBatteryReporter?.resend("glasses_announced")
        log(
            "renderer capabilities image=$imageSupported pin=$pinSupported " +
                "notice=${advertised.features and BusCapabilityBits.NOTICE_SURFACE != 0} " +
                "activity=$activitySupported " +
                "maxImageBytes=$remoteMaxImageBytes",
        )
        // Link bits may be unchanged; repeat the callback so clients refresh capabilities().
        notifyLinkState()
        if (pinSupported) pinRouter.resendIfAvailable()
        if (activitySupported) activityRouter.resendIfAvailable()
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

    private fun closeSocket(expected: BluetoothSocket? = null) {
        val retired = synchronized(sppLock) {
            if (expected != null && socket !== expected) return
            val current = socket
            socket = null
            sppSession = null
            current
        }
        runCatching { retired?.close() }
    }

    private fun isDebuggableBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun log(message: String) {
        Log.i(TAG, message)
        NexusPhoneState.recordLogLine(message)
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
        private val lastGlassesSetupUserIntentAtMillis = AtomicLong(0L)

        // Process-wide so the inspector can read events across service restarts.
        val busJournal = PluginBusJournal()

        fun onPluginAuthorizationChanged(context: android.content.Context, key: PluginGrantKey) {
            PhoneClientSupervisor.onPrincipalRevoked(context.applicationContext, key)
            activeInstance?.authorizationChanged(key)
        }

        internal fun onActivityPresentationPreferenceChanged() {
            activeInstance?.announcePhoneCapabilities()
        }

        internal fun onHudPositionPreferenceChanged() {
            activeInstance?.announcePhoneCapabilities()
        }

        internal fun onGlassesRepairSettingChanged() {
            activeInstance?.let { service ->
                service.executor.execute { service.pushGlassesRepairConfig() }
            }
        }

        internal fun availablePhoneTtsVoices(locale: Locale): List<PhoneTtsVoiceOption> =
            activeInstance
                ?.takeIf { it::phoneTtsDispatcher.isInitialized }
                ?.phoneTtsDispatcher
                ?.availableVoices(locale)
                ?: emptyList()

        internal fun speakPhoneTtsSample(text: String, locale: Locale): Boolean =
            activeInstance
                ?.takeIf { it::phoneTtsDispatcher.isInitialized }
                ?.phoneTtsDispatcher
                ?.speakSample(text, locale) == true

        fun pluginCatalog(context: android.content.Context): PluginCatalog =
            activeInstance?.pluginRegistry?.catalog()
                ?: context.applicationContext.let { appContext ->
                    PluginCatalog.build(
                        builtIns = emptyList(),
                        candidates = PhonePluginDiscovery(appContext.packageManager).discover(),
                        registryFeed = RegistryClient.create(appContext).cachedSnapshot()?.feed
                            ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList()),
                        grantState = PluginGrantStore(appContext)::stateFor,
                    )
                }

        fun pluginBusJournal(): PluginBusJournal? = activeInstance?.pluginBusJournal

        internal fun openPlugin(pluginId: String): Boolean =
            activeInstance?.pluginRegistry?.openFromPhone(pluginId) == true

        internal fun stopBackgroundAudio(pluginId: String): Boolean =
            activeInstance?.revokeBackgroundAudioLease(pluginId, "USER_STOPPED") == true

        internal fun manualPairingEngine(): GlassesManualPairingEngine? =
            activeInstance?.manualPairingEngine

        internal fun noteGlassesSetupUserIntent(
            nowMillis: Long = SystemClock.elapsedRealtime(),
        ) {
            if (nowMillis > 0L) lastGlassesSetupUserIntentAtMillis.set(nowMillis)
        }

        /**
         * Same-process hook for the future non-exported Speech settings activity. Transcript
         * callbacks are delivered directly and never enter the app's log broadcast.
         */
        internal fun startSpeechDictationTest(
            listener: SpeechUtteranceListener,
        ): SpeechStartResult =
            activeInstance
                ?.takeIf { it::speechSessionManager.isInitialized }
                ?.speechSessionManager
                ?.startUtterance(listener)
                ?: SpeechStartResult.NO_LINK

        internal fun cancelSpeechDictationTest() {
            activeInstance
                ?.takeIf { it::speechSessionManager.isInitialized }
                ?.speechSessionManager
                ?.cancel()
        }

        internal fun isSpeechDictationTestActive(): Boolean =
            activeInstance
                ?.takeIf { it::speechSessionManager.isInitialized }
                ?.speechSessionManager
                ?.isActive == true

        internal fun requestSpeechMicrophoneForeground(): SttError? {
            val service = activeInstance
                ?: return SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    SpeechProvider.ANDROID.displayName,
                    "Phone hub service is not running",
                )
            return service.requestSpeechMicrophoneForegroundOnMain()
        }

        internal fun releaseSpeechMicrophoneForeground() {
            activeInstance?.releaseSpeechMicrophoneForegroundOnMain()
        }

        fun startWithToken(context: android.content.Context, token: String) {
            if (!canRunHub(context)) {
                Log.i(TAG, "startWithToken skipped: BLUETOOTH_CONNECT permission not granted")
                return
            }
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
            if (!canRunHub(context)) {
                Log.i(TAG, "start skipped: BLUETOOTH_CONNECT permission not granted")
                return
            }
            val intent = Intent(context, BusHubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startDebugImage(context: android.content.Context) {
            if (!canRunHub(context)) {
                Log.i(TAG, "startDebugImage skipped: BLUETOOTH_CONNECT permission not granted")
                return
            }
            val intent = Intent(context, BusHubService::class.java).setAction(ACTION_DEBUG_IMAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun installGlassesApp(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_INSTALL_GLASSES_APP),
            )
        }

        fun queryGlassesApp(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_QUERY_GLASSES_APP),
            )
        }

        fun openGlassesApp(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_OPEN_GLASSES_APP),
            )
        }

        fun startGlassesSetup(context: android.content.Context) {
            noteGlassesSetupUserIntent()
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_START_GLASSES_SETUP),
            )
        }

        /** Plain startService: callers are foreground UI; the hub must not re-promote itself. */
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_STOP),
            )
        }

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        fun hasSavedAuthorization(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_TOKEN, "")
                .orEmpty()
                .isNotBlank()

        fun canRunHub(context: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        private const val INTERNAL_AUDIO_START_TIMEOUT_SECONDS = 10L
    }
}
