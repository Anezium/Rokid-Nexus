package com.anezium.rokidbus.plugin.lens

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class PhoneLensLinkOffer(
    val sessionId: String,
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
) {
    companion object {
        fun parse(payload: JSONObject): PhoneLensLinkOffer? {
            if (payload.optInt("version", 1) != 1) return null
            val sessionId = payload.optString("sessionId")
            val ssid = payload.optString("ssid")
            val passphrase = payload.optString("passphrase")
            val token = payload.optString("token")
            val goIp = payload.optString("goIp")
            val port = payload.optInt("port")
            if (sessionId.isBlank() || sessionId.length > 128 ||
                ssid.isBlank() || ssid.length > 128 ||
                passphrase.length !in 8..128 || token.length !in 16..256 ||
                goIp.isBlank() || goIp.length > 64 || port !in 1..65535
            ) return null
            return PhoneLensLinkOffer(sessionId, ssid, passphrase, port, token, goIp)
        }
    }
}

internal enum class PhoneLensLinkState { IDLE, WAITING_NETWORK, CONNECTING, CONNECTED }

private enum class GroupRemovalResult { COMPLETED, TIMED_OUT }

/** Joins the offered Wi-Fi Direct group as a P2P client; default-data routing is unchanged. */
internal class PhoneLensImageLink(
    context: Context,
    private val logger: (String) -> Unit,
    private val onPacket: (CameraLinkPacket) -> Unit = {},
    private val stageElapsedMs: () -> Long = { 0L },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "lens-phone-link").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }
    private val persistentGroupJanitor = LensPersistentGroupJanitor(::log)
    private val generation = AtomicLong(0L)
    private val tcpConnecting = AtomicBoolean(false)
    private val random = SecureRandom()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val joinRetryPolicy = PhoneLensJoinRetryPolicy(
        initialDelayMs = INITIAL_JOIN_RETRY_MS,
        delayStepMs = JOIN_RETRY_STEP_MS,
        maxDelayMs = MAX_JOIN_RETRY_MS,
        maxAttempts = MAX_JOIN_ATTEMPTS,
    )
    private val joinRecoveryPolicy = PhoneLensJoinRecoveryPolicy()
    private val discoveryPrimingPolicy = PhoneLensDiscoveryPrimingPolicy(
        discoveryWaitMs = DISCOVERY_PRIME_WAIT_MS,
        stopCallbackFallbackMs = STOP_DISCOVERY_FALLBACK_MS,
    )

    @Volatile private var closed = false
    @Volatile private var offer: PhoneLensLinkOffer? = null
    @Volatile private var p2pRunning = false
    @Volatile private var p2pConnecting = false
    @Volatile private var groupInspecting = false
    @Volatile private var probingReusedGroup = false
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var socket: Socket? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var connectionInfoPoll: Runnable? = null
    private var groupInspectionTimeout: Runnable? = null
    private var discoveryPrimeTimeout: Runnable? = null
    private var stopDiscoveryFallback: Runnable? = null
    private var joinAttemptTimeout: Runnable? = null
    private var joinRetry: Runnable? = null
    private var tcpRetry: ScheduledFuture<*>? = null
    private var conflictRecoveryAttempted = false
    private var offerStartPending = false
    private var activeJoinAttempt = 0
    private var consecutiveJoinFailures = 0
    private var groupInspectionId = 0L
    private var p2pCleanupInProgress = false
    private var discoveryPrimedForJoinCycle = false
    private var discoveryPriming = false
    private var discoveryStopPending = false
    private val p2pCleanupContinuations = mutableListOf<() -> Unit>()
    @Volatile var state: PhoneLensLinkState = PhoneLensLinkState.IDLE
        private set

    private val timeout = Runnable {
        failP2p("lensLinkP2pTimeout")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (
                        enabled && p2pRunning && !p2pConnecting && joinRetry == null &&
                        state != PhoneLensLinkState.CONNECTED && socket == null &&
                        !tcpConnecting.get()
                    ) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            inspectGroupThenConnect(expectedGeneration, currentOffer)
                        }
                    } else if (!enabled && p2pRunning) {
                        log("lensLinkP2pDisabled")
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            requestConnectionInfo(expectedGeneration, currentOffer)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    currentSession()?.let { (expectedGeneration, currentOffer) ->
                        stopDiscoveryAfterPriming(
                            expectedGeneration,
                            currentOffer,
                            cause = "peers_changed",
                        )
                    }
                }
            }
        }
    }

    fun updateOffer(payload: JSONObject) {
        if (closed) return
        val parsed = PhoneLensLinkOffer.parse(payload)
        if (parsed == null) {
            log("lensLinkOfferRejected")
            return
        }
        val nextGeneration = synchronized(this) {
            val joinActive = offerStartPending || p2pRunning || state != PhoneLensLinkState.IDLE
            if (!PhoneLensOfferUpdatePolicy.shouldStart(offer, parsed, joinActive)) return
            offer = parsed
            offerStartPending = true
            generation.incrementAndGet()
        }
        closeSocket()
        mainHandler.post {
            teardownP2p {
                if (isCurrent(nextGeneration, parsed)) {
                    synchronized(this) { offerStartPending = false }
                    startP2p(nextGeneration, parsed)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startP2p(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isCurrent(expectedGeneration, currentOffer)) return
        if (!hasWifiPermission()) {
            state = PhoneLensLinkState.IDLE
            log("lensLinkPermissionMissing")
            return
        }
        val localManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val oldChannel = channel
        channel = null
        closeP2pChannel(oldChannel, phase = "start")
        manager = localManager
        val localChannel = localManager?.initialize(appContext, Looper.getMainLooper(), null)
        channel = localChannel
        if (localManager == null || localChannel == null) {
            state = PhoneLensLinkState.IDLE
            log("lensLinkP2pUnavailable")
            return
        }
        p2pRunning = true
        acquirePerformanceLocks()
        joinRetryPolicy.reset()
        consecutiveJoinFailures = 0
        discoveryPrimedForJoinCycle = false
        discoveryPriming = false
        discoveryStopPending = false
        conflictRecoveryAttempted = false
        probingReusedGroup = false
        state = PhoneLensLinkState.WAITING_NETWORK
        registerReceiver()
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
        runPersistentGroupJanitor(localManager, localChannel)
        inspectGroupThenConnect(expectedGeneration, currentOffer)
    }

    @SuppressLint("MissingPermission")
    private fun inspectGroupThenConnect(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || isConnectedOrConnectingSocket() ||
            groupInspecting
        ) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        clearJoinRetry()
        clearConnectionInfoPoll()
        groupInspecting = true
        groupInspectionId += 1L
        val inspectionId = groupInspectionId
        scheduleGroupInspectionTimeout(expectedGeneration, currentOffer, inspectionId)
        runCatching { localManager.requestGroupInfo(localChannel) { group ->
            if (!groupInspecting || groupInspectionId != inspectionId) return@requestGroupInfo
            groupInspecting = false
            clearGroupInspectionTimeout()
            if (channel !== localChannel || !isP2pCurrent(expectedGeneration, currentOffer) ||
                isConnectedOrConnectingSocket()
            ) return@requestGroupInfo
            when {
                group == null -> connectByCredentials(expectedGeneration, currentOffer)
                group.isGroupOwner -> recoverConflictingGroup(
                    expectedGeneration,
                    currentOffer,
                    cause = "unexpected_group_owner",
                )
                group.networkName == currentOffer.ssid -> {
                    log("lensLinkGroupReuse")
                    probingReusedGroup = true
                    p2pConnecting = true
                    activeJoinAttempt = 0
                    scheduleJoinProgressTimeout(expectedGeneration, currentOffer, attempt = null)
                    requestConnectionInfo(expectedGeneration, currentOffer)
                }
                else -> recoverConflictingGroup(
                    expectedGeneration,
                    currentOffer,
                    cause = "conflicting_group",
                )
            }
        } }.onFailure {
            groupInspecting = false
            clearGroupInspectionTimeout()
            log("lensLinkGroupInfoRequestFailed type=${it.javaClass.simpleName}")
            connectByCredentials(expectedGeneration, currentOffer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun recoverConflictingGroup(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        cause: String,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || isConnectedOrConnectingSocket()) return
        if (conflictRecoveryAttempted) return failP2p("lensLinkP2pConflict")
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        conflictRecoveryAttempted = true
        p2pConnecting = false
        activeJoinAttempt = 0
        clearJoinAttemptTimeout()
        clearConnectionInfoPoll()
        groupOwnerAddress = null
        probingReusedGroup = false
        log("lensLinkRecovery step=remove_group attempt=0 cause=$cause")
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        requestGroupRemoval(localManager, localChannel, phase = "recovery") { result ->
            if (!isP2pCurrent(expectedGeneration, currentOffer)) return@requestGroupRemoval
            if (result == GroupRemovalResult.TIMED_OUT) {
                log("lensLinkRecovery step=channel_reset attempt=0 cause=remove_group_timeout")
                if (!recreateP2pChannel(expectedGeneration, currentOffer)) {
                    return@requestGroupRemoval
                }
            }
            scheduleCredentialJoinRetry(expectedGeneration, currentOffer, RESET_SETTLE_MS)
        }
    }

    /**
     * Joins the glasses' autonomous group directly by SSID + passphrase. Discovery is used only
     * to prime the scan; the autonomous owner need not appear in the peer list. A credential-only
     * WifiP2pConfig also avoids the WifiNetworkSpecifier system approval dialog.
     */
    @SuppressLint("MissingPermission")
    private fun connectByCredentials(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) ||
            isConnectedOrConnectingSocket() || p2pConnecting || discoveryPriming
        ) return
        val primingDecision = discoveryPrimingPolicy.decision(discoveryPrimedForJoinCycle)
        if (primingDecision.shouldPrime) {
            primeDiscoveryThenConnect(expectedGeneration, currentOffer, primingDecision)
            return
        }
        clearJoinRetry()
        clearConnectionInfoPoll()
        clearJoinAttemptTimeout()
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(currentOffer.ssid)
                .setPassphrase(currentOffer.passphrase)
                .enablePersistentMode(false)
                .build()
                .also { LensTemporaryP2pConfig.apply(it, ::log) }
        }.getOrElse {
            failP2p("lensLinkConfigBuildFailed")
            return
        }
        val attempt = joinRetryPolicy.startAttempt() ?: return failP2p(
            "lensLinkJoinExhausted attempts=$MAX_JOIN_ATTEMPTS",
        )
        activeJoinAttempt = attempt
        p2pConnecting = true
        state = PhoneLensLinkState.CONNECTING
        log("cameraLinkStage stage=join_attempt#$attempt elapsedMs=${stageElapsedMs().coerceAtLeast(0L)}")
        log("lensLinkJoinByCredentials attempt=$attempt")
        scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = false)
        scheduleJoinProgressTimeout(expectedGeneration, currentOffer, attempt)
        runCatching { localManager.connect(
            localChannel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isActiveJoinAttempt(expectedGeneration, currentOffer, attempt)) return
                    log("lensLinkJoinAccepted attempt=$attempt")
                    scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = true)
                }

                override fun onFailure(reason: Int) {
                    handleJoinFailure(
                        expectedGeneration = expectedGeneration,
                        currentOffer = currentOffer,
                        attempt = attempt,
                        cause = "callback_reason_$reason",
                    )
                }
            },
        ) }.onFailure {
            handleJoinFailure(
                expectedGeneration = expectedGeneration,
                currentOffer = currentOffer,
                attempt = attempt,
                cause = "connect_exception_${it.javaClass.simpleName}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun primeDiscoveryThenConnect(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        decision: PhoneLensDiscoveryPrimingDecision,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || discoveryPriming ||
            discoveryPrimedForJoinCycle
        ) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        discoveryPrimedForJoinCycle = true
        discoveryPriming = true
        discoveryStopPending = false
        clearDiscoveryPrimingCallbacks()
        log("lensLinkDiscoveryPrimeStarted waitMs=${decision.discoveryWaitMs}")
        discoveryPrimeTimeout = Runnable {
            discoveryPrimeTimeout = null
            stopDiscoveryAfterPriming(
                expectedGeneration,
                currentOffer,
                cause = "timeout",
            )
        }.also { mainHandler.postDelayed(it, decision.discoveryWaitMs) }
        runCatching {
            localManager.discoverPeers(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isDiscoveryPrimingCurrent(expectedGeneration, currentOffer, localChannel)) {
                        return
                    }
                    log("lensLinkDiscoveryPrimeAccepted")
                }

                override fun onFailure(reason: Int) {
                    if (!isDiscoveryPrimingCurrent(expectedGeneration, currentOffer, localChannel)) {
                        return
                    }
                    log("lensLinkDiscoveryPrimeFailed reason=$reason")
                    stopDiscoveryAfterPriming(
                        expectedGeneration,
                        currentOffer,
                        cause = "callback_reason_$reason",
                    )
                }
            })
        }.onFailure {
            if (isDiscoveryPrimingCurrent(expectedGeneration, currentOffer, localChannel)) {
                log("lensLinkDiscoveryPrimeException type=${it.javaClass.simpleName}")
                stopDiscoveryAfterPriming(
                    expectedGeneration,
                    currentOffer,
                    cause = "exception",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscoveryAfterPriming(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        cause: String,
    ) {
        if (!discoveryPriming || discoveryStopPending ||
            !isP2pCurrent(expectedGeneration, currentOffer)
        ) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        discoveryStopPending = true
        discoveryPrimeTimeout?.let(mainHandler::removeCallbacks)
        discoveryPrimeTimeout = null
        val fallbackMs = discoveryPrimingPolicy
            .decision(alreadyPrimedForJoinCycle = true)
            .stopCallbackFallbackMs
        log("lensLinkDiscoveryPrimeStopping cause=$cause fallbackMs=$fallbackMs")
        stopDiscoveryFallback = Runnable {
            stopDiscoveryFallback = null
            completeDiscoveryPriming(
                expectedGeneration,
                currentOffer,
                localChannel,
                cause = "stop_timeout",
            )
        }.also { mainHandler.postDelayed(it, fallbackMs) }
        runCatching {
            localManager.stopPeerDiscovery(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = completeDiscoveryPriming(
                    expectedGeneration,
                    currentOffer,
                    localChannel,
                    cause = "stop_succeeded",
                )

                override fun onFailure(reason: Int) = completeDiscoveryPriming(
                    expectedGeneration,
                    currentOffer,
                    localChannel,
                    cause = "stop_failed_$reason",
                )
            })
        }.onFailure {
            completeDiscoveryPriming(
                expectedGeneration,
                currentOffer,
                localChannel,
                cause = "stop_exception_${it.javaClass.simpleName}",
            )
        }
    }

    private fun completeDiscoveryPriming(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        expectedChannel: WifiP2pManager.Channel,
        cause: String,
    ) {
        if (!isDiscoveryPrimingCurrent(expectedGeneration, currentOffer, expectedChannel) ||
            !discoveryStopPending
        ) return
        clearDiscoveryPrimingCallbacks()
        discoveryPriming = false
        discoveryStopPending = false
        log("lensLinkDiscoveryPrimeCompleted cause=$cause")
        connectByCredentials(expectedGeneration, currentOffer)
    }

    private fun isDiscoveryPrimingCurrent(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        expectedChannel: WifiP2pManager.Channel,
    ): Boolean = discoveryPriming && channel === expectedChannel &&
        isP2pCurrent(expectedGeneration, currentOffer)

    private fun clearDiscoveryPrimingCallbacks() {
        discoveryPrimeTimeout?.let(mainHandler::removeCallbacks)
        discoveryPrimeTimeout = null
        stopDiscoveryFallback?.let(mainHandler::removeCallbacks)
        stopDiscoveryFallback = null
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        runCatching { localManager.requestConnectionInfo(localChannel) { info ->
            if (channel !== localChannel) return@requestConnectionInfo
            handleConnectionInfo(expectedGeneration, currentOffer, info)
        } }.onFailure {
            handleConnectionInfoRequestFailure(expectedGeneration, currentOffer, it)
        }
    }

    private fun handleConnectionInfo(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        info: WifiP2pInfo?,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        if (info?.groupFormed != true) {
            scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = false)
            return
        }
        if (info.isGroupOwner) {
            log("lensLinkUnexpectedGroupOwner")
            p2pConnecting = false
            activeJoinAttempt = 0
            clearJoinAttemptTimeout()
            recoverConflictingGroup(
                expectedGeneration,
                currentOffer,
                cause = "unexpected_group_owner",
            )
            return
        }

        val callbackAddress = info.groupOwnerAddress?.hostAddress.orEmpty()
        val ownerAddress = callbackAddress.ifBlank {
            log("lensLinkOwnerAddressFallback")
            currentOffer.goIp
        }
        p2pConnecting = false
        activeJoinAttempt = 0
        clearJoinAttemptTimeout()
        groupOwnerAddress = ownerAddress
        consecutiveJoinFailures = 0
        clearJoinRetry()
        clearConnectionInfoPoll()
        mainHandler.removeCallbacks(timeout)
        state = PhoneLensLinkState.CONNECTING
        val reuseProbe = probingReusedGroup
        executor.execute {
            connectToGroupOwner(
                expectedGeneration,
                currentOffer,
                ownerAddress,
                reuseProbeAttempt = if (reuseProbe) 1 else 0,
            )
        }
    }

    private fun scheduleConnectionInfoPoll(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        immediate: Boolean,
        delayMs: Long = CONNECTION_INFO_POLL_MS,
    ) {
        clearConnectionInfoPoll()
        connectionInfoPoll = Runnable {
            connectionInfoPoll = null
            if (isP2pCurrent(expectedGeneration, currentOffer) && p2pConnecting) {
                requestConnectionInfo(expectedGeneration, currentOffer)
            }
        }.also { mainHandler.postDelayed(it, if (immediate) 0L else delayMs) }
    }

    private fun scheduleGroupInspectionTimeout(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        inspectionId: Long,
    ) {
        clearGroupInspectionTimeout()
        groupInspectionTimeout = Runnable {
            groupInspectionTimeout = null
            if (!isP2pCurrent(expectedGeneration, currentOffer) || !groupInspecting ||
                groupInspectionId != inspectionId
            ) return@Runnable
            groupInspecting = false
            log("lensLinkGroupInfoTimeout")
            connectByCredentials(expectedGeneration, currentOffer)
        }.also { mainHandler.postDelayed(it, GROUP_INFO_TIMEOUT_MS) }
    }

    private fun clearGroupInspectionTimeout() {
        groupInspectionTimeout?.let(mainHandler::removeCallbacks)
        groupInspectionTimeout = null
    }

    private fun scheduleJoinProgressTimeout(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        attempt: Int?,
    ) {
        clearJoinAttemptTimeout()
        joinAttemptTimeout = Runnable {
            joinAttemptTimeout = null
            if (!isP2pCurrent(expectedGeneration, currentOffer) || !p2pConnecting) return@Runnable
            if (attempt != null) {
                handleJoinFailure(
                    expectedGeneration = expectedGeneration,
                    currentOffer = currentOffer,
                    attempt = attempt,
                    cause = "progress_timeout",
                )
            } else if (activeJoinAttempt == 0) {
                log("lensLinkGroupReuseTimeout")
                p2pConnecting = false
                clearConnectionInfoPoll()
                recoverConflictingGroup(
                    expectedGeneration,
                    currentOffer,
                    cause = "group_reuse_timeout",
                )
            }
        }.also { mainHandler.postDelayed(it, joinProgressTimeoutMs(attempt)) }
    }

    /**
     * Measured on RG glasses + S23: the very first connect against a freshly created GO is
     * accepted by the framework but never reaches groupFormed, regardless of how long the GO
     * has settled — while the retry consistently connects in ~2.4s. Cut the doomed first
     * attempt short and give real attempts room to finish.
     */
    private fun joinProgressTimeoutMs(attempt: Int?): Long =
        if (attempt == 1) FIRST_JOIN_PROGRESS_TIMEOUT_MS else JOIN_PROGRESS_TIMEOUT_MS

    private fun clearJoinAttemptTimeout() {
        joinAttemptTimeout?.let(mainHandler::removeCallbacks)
        joinAttemptTimeout = null
    }

    private fun handleJoinFailure(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        attempt: Int,
        cause: String,
    ) {
        if (!isActiveJoinAttempt(expectedGeneration, currentOffer, attempt)) return
        p2pConnecting = false
        activeJoinAttempt = 0
        clearJoinAttemptTimeout()
        clearConnectionInfoPoll()
        log("lensLinkJoinFailed attempt=$attempt cause=$cause")
        consecutiveJoinFailures += 1
        val delayMs = joinRetryPolicy.retryDelayAfter(attempt)
            ?: return failP2p("lensLinkJoinExhausted attempts=$attempt cause=$cause")
        val recoveryAction = joinRecoveryPolicy.actionAfter(consecutiveJoinFailures)
        when (recoveryAction) {
            PhoneLensJoinRecoveryAction.NONE -> {
                cancelPendingConnect(attempt)
                logJoinRetryScheduled(attempt, delayMs, cause)
                scheduleJoinRetry(expectedGeneration, currentOffer, delayMs)
            }
            PhoneLensJoinRecoveryAction.REMOVE_GROUP -> {
                log("lensLinkRecovery step=remove_group attempt=$attempt")
                val retryDelayMs = delayMs.coerceAtLeast(RESET_SETTLE_MS)
                logJoinRetryScheduled(attempt, retryDelayMs, cause)
                // removeGroup while our own cancelConnect is still in flight returns BUSY;
                // sequence it behind the cancel callback.
                cancelPendingConnect(attempt) {
                    if (!isP2pCurrent(expectedGeneration, currentOffer)) return@cancelPendingConnect
                    removeGroupForJoinRecovery(
                        expectedGeneration,
                        currentOffer,
                        attempt,
                        retryDelayMs,
                    )
                }
            }
            PhoneLensJoinRecoveryAction.RESET_CHANNEL -> {
                cancelPendingConnect(attempt)
                log("lensLinkRecovery step=channel_reset attempt=$attempt")
                if (!recreateP2pChannel(expectedGeneration, currentOffer)) return
                val retryDelayMs = delayMs.coerceAtLeast(RESET_SETTLE_MS)
                logJoinRetryScheduled(attempt, retryDelayMs, cause)
                scheduleCredentialJoinRetry(expectedGeneration, currentOffer, retryDelayMs)
            }
        }
    }

    private fun logJoinRetryScheduled(attempt: Int, delayMs: Long, cause: String) {
        log(
            "lensLinkJoinRetryScheduled nextAttempt=${attempt + 1} " +
                "delayMs=$delayMs cause=$cause",
        )
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupForJoinRecovery(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        attempt: Int,
        retryDelayMs: Long,
    ) {
        val localManager = manager
        val localChannel = channel
        if (localManager == null || localChannel == null) {
            failP2p("lensLinkRecoveryRemoveGroupUnavailable")
            return
        }
        val retryNotBefore = SystemClock.uptimeMillis() + retryDelayMs
        probingReusedGroup = false
        groupOwnerAddress = null
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        requestGroupRemoval(localManager, localChannel, phase = "recovery") { result ->
            if (!isP2pCurrent(expectedGeneration, currentOffer)) return@requestGroupRemoval
            if (result == GroupRemovalResult.TIMED_OUT) {
                log("lensLinkRecovery step=channel_reset attempt=$attempt cause=remove_group_timeout")
                if (!recreateP2pChannel(expectedGeneration, currentOffer)) {
                    return@requestGroupRemoval
                }
            }
            val remainingBackoffMs = (retryNotBefore - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            scheduleCredentialJoinRetry(
                expectedGeneration,
                currentOffer,
                remainingBackoffMs.coerceAtLeast(RESET_SETTLE_MS),
            )
        }
    }

    private fun recreateP2pChannel(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ): Boolean {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return false
        val localManager = manager
        if (localManager == null) {
            failP2p("lensLinkRecoveryChannelResetUnavailable")
            return false
        }
        groupInspecting = false
        groupInspectionId += 1L
        probingReusedGroup = false
        groupOwnerAddress = null
        clearGroupInspectionTimeout()
        clearConnectionInfoPoll()
        val oldChannel = channel
        channel = null
        closeP2pChannel(oldChannel, phase = "recovery")
        val replacement = runCatching {
            localManager.initialize(appContext, Looper.getMainLooper(), null)
        }.onFailure {
            log("lensLinkChannelInitializeFailed phase=recovery type=${it.javaClass.simpleName}")
        }.getOrNull()
        if (replacement == null) {
            failP2p("lensLinkRecoveryChannelResetFailed")
            return false
        }
        channel = replacement
        state = PhoneLensLinkState.WAITING_NETWORK
        // The receiver is process-scoped rather than channel-scoped; this is a no-op when active.
        registerReceiver()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun cancelPendingConnect(attempt: Int, onDone: (() -> Unit)? = null) {
        val localManager = manager
        val localChannel = channel
        if (localManager == null || localChannel == null) {
            log("lensLinkCancelSkipped attempt=$attempt reason=manager_or_channel_lost")
            onDone?.invoke()
            return
        }
        runCatching {
            localManager.cancelConnect(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("lensLinkCancelSucceeded attempt=$attempt")
                    onDone?.invoke()
                }

                override fun onFailure(reason: Int) {
                    log("lensLinkCancelFailed attempt=$attempt reason=$reason")
                    onDone?.invoke()
                }
            })
        }.onFailure {
            log("lensLinkCancelException attempt=$attempt type=${it.javaClass.simpleName}")
            onDone?.invoke()
        }
    }

    private fun handleConnectionInfoRequestFailure(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        failure: Throwable,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val attempt = activeJoinAttempt
        if (attempt > 0) {
            handleJoinFailure(
                expectedGeneration,
                currentOffer,
                attempt,
                "connection_info_exception_${failure.javaClass.simpleName}",
            )
        } else {
            log("lensLinkConnectionInfoFailed type=${failure.javaClass.simpleName}")
            p2pConnecting = false
            clearJoinAttemptTimeout()
            recoverConflictingGroup(
                expectedGeneration,
                currentOffer,
                cause = "group_reuse_connection_info_exception",
            )
        }
    }

    private fun clearConnectionInfoPoll() {
        connectionInfoPoll?.let(mainHandler::removeCallbacks)
        connectionInfoPoll = null
    }

    private fun scheduleJoinRetry(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        delayMs: Long,
    ) {
        clearJoinRetry()
        joinRetry = Runnable {
            joinRetry = null
            inspectGroupThenConnect(expectedGeneration, currentOffer)
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun scheduleCredentialJoinRetry(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        delayMs: Long,
    ) {
        clearJoinRetry()
        joinRetry = Runnable {
            joinRetry = null
            connectByCredentials(expectedGeneration, currentOffer)
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun clearJoinRetry() {
        joinRetry?.let(mainHandler::removeCallbacks)
        joinRetry = null
    }

    private fun clearTcpRetry() {
        tcpRetry?.cancel(false)
        tcpRetry = null
    }

    private fun isConnectedOrConnectingSocket(): Boolean =
        state == PhoneLensLinkState.CONNECTED || socket != null || tcpConnecting.get()

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun connectToGroupOwner(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        ownerAddress: String,
        reuseProbeAttempt: Int = 0,
    ) {
        if (!isSocketCurrent(expectedGeneration, currentOffer, ownerAddress) ||
            !tcpConnecting.compareAndSet(false, true)
        ) return
        state = PhoneLensLinkState.CONNECTING
        var currentSocket: Socket? = null
        var reuseAuthenticated = false
        val reuseProbe = reuseProbeAttempt > 0
        try {
            currentSocket = Socket()
            currentSocket.tcpNoDelay = true
            currentSocket.keepAlive = true
            val connectTimeoutMs = if (reuseProbe) REUSED_GROUP_TCP_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            currentSocket.connect(InetSocketAddress(ownerAddress, currentOffer.port), connectTimeoutMs)
            if (!isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) return
            if (reuseProbe) currentSocket.soTimeout = REUSED_GROUP_AUTH_TIMEOUT_MS
            socket = currentSocket
            val output = currentSocket.getOutputStream()
            val input = currentSocket.getInputStream()
            val requestId = random.nextLong()
            CameraLinkProtocol.write(
                output,
                CameraLinkPacket(
                    type = CameraLinkPacketType.HELLO,
                    requestId = requestId,
                    meta = JSONObject().put("token", currentOffer.token).toString(),
                ),
            )
            if (!reuseProbe) markSocketConnected()

            val probe = ByteArray(PROBE_BYTES).also(random::nextBytes)
            val probeStartedAt = SystemClock.elapsedRealtime()
            CameraLinkProtocol.write(
                output,
                CameraLinkPacket(CameraLinkPacketType.PROBE, requestId, payload = probe),
            )
            while (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress) &&
                !currentSocket.isClosed
            ) {
                val packet = CameraLinkProtocol.read(input) ?: break
                if (reuseProbe && !reuseAuthenticated) {
                    reuseAuthenticated = true
                    probingReusedGroup = false
                    currentSocket.soTimeout = 0
                    log("lensLinkGroupReuseAuthenticated")
                    markSocketConnected()
                }
                if (packet.type == CameraLinkPacketType.PROBE_ACK && packet.requestId == requestId) {
                    val elapsedMs = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                    val bytes = runCatching { JSONObject(packet.meta).optInt("bytes", PROBE_BYTES) }
                        .getOrDefault(PROBE_BYTES)
                    log("lensLinkProbe bytes=$bytes ms=$elapsedMs mbps=${"%.1f".format(bytes * 8.0 / elapsedMs / 1000.0)}")
                } else {
                    onPacket(packet)
                }
            }
        } catch (failure: Throwable) {
            if (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) {
                log("lensLinkSocketFailure type=${failure.javaClass.simpleName}")
            }
        } finally {
            val recoverReusedGroup = reuseProbe && !reuseAuthenticated &&
                reuseProbeAttempt >= MAX_REUSED_GROUP_TCP_PROBES &&
                isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)
            if (socket === currentSocket) socket = null
            runCatching { currentSocket?.close() }
            tcpConnecting.set(false)
            if (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) {
                state = PhoneLensLinkState.WAITING_NETWORK
                clearTcpRetry()
                if (recoverReusedGroup) {
                    mainHandler.post {
                        recoverReusedGroupAfterSocketFailure(
                            expectedGeneration,
                            currentOffer,
                            ownerAddress,
                        )
                    }
                } else if (reuseProbe && !reuseAuthenticated) {
                    tcpRetry = executor.schedule(
                        {
                            connectToGroupOwner(
                                expectedGeneration,
                                currentOffer,
                                ownerAddress,
                                reuseProbeAttempt + 1,
                            )
                        },
                        TCP_RETRY_MS,
                        TimeUnit.MILLISECONDS,
                    )
                } else {
                    tcpRetry = executor.schedule(
                        { connectToGroupOwner(expectedGeneration, currentOffer, ownerAddress) },
                        TCP_RETRY_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
    }

    private fun markSocketConnected() {
        state = PhoneLensLinkState.CONNECTED
        clearJoinRetry()
        clearConnectionInfoPoll()
        clearTcpRetry()
        mainHandler.removeCallbacks(timeout)
        log("cameraLinkStage stage=connected elapsedMs=${stageElapsedMs().coerceAtLeast(0L)}")
        log("lensLinkConnected")
    }

    private fun recoverReusedGroupAfterSocketFailure(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        ownerAddress: String,
    ) {
        if (!probingReusedGroup ||
            !isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)
        ) return
        log("lensLinkGroupReuseStale")
        recoverConflictingGroup(
            expectedGeneration,
            currentOffer,
            cause = "group_reuse_socket_failure",
        )
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentSession(): Pair<Long, PhoneLensLinkOffer>? {
        val currentOffer = offer ?: return null
        return generation.get() to currentOffer
    }

    private fun isCurrent(expectedGeneration: Long, expectedOffer: PhoneLensLinkOffer): Boolean =
        !closed && generation.get() == expectedGeneration && offer == expectedOffer

    private fun isP2pCurrent(
        expectedGeneration: Long,
        expectedOffer: PhoneLensLinkOffer,
    ): Boolean = p2pRunning && isCurrent(expectedGeneration, expectedOffer)

    private fun isActiveJoinAttempt(
        expectedGeneration: Long,
        expectedOffer: PhoneLensLinkOffer,
        attempt: Int,
    ): Boolean = isP2pCurrent(expectedGeneration, expectedOffer) &&
        p2pConnecting && activeJoinAttempt == attempt

    private fun isSocketCurrent(
        expectedGeneration: Long,
        expectedOffer: PhoneLensLinkOffer,
        expectedOwnerAddress: String,
    ): Boolean = isP2pCurrent(expectedGeneration, expectedOffer) &&
        groupOwnerAddress == expectedOwnerAddress

    private fun log(message: String) {
        Log.i(TAG, message)
        logger(message)
    }

    private fun failP2p(message: String) {
        if (!p2pRunning) return
        log(message)
        state = PhoneLensLinkState.IDLE
        closeSocket()
        teardownP2p()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        tcpConnecting.set(false)
    }

    private fun acquirePerformanceLocks() {
        val cpuAcquired = runCatching {
            if (wakeLock?.isHeld != true) {
                wakeLock = appContext.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${appContext.packageName}:lens-link")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            wakeLock?.isHeld == true
        }.getOrDefault(false)
        val wifiAcquired = runCatching {
            if (wifiLock?.isHeld != true) {
                wifiLock = appContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "${appContext.packageName}:lens-link-wifi",
                    )
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            wifiLock?.isHeld == true
        }.getOrDefault(false)
        log("lensLinkPerformanceLocks cpu=$cpuAcquired wifiLowLatency=$wifiAcquired")
    }

    private fun releasePerformanceLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupRemoval(
        localManager: WifiP2pManager,
        localChannel: WifiP2pManager.Channel,
        phase: String,
        onComplete: (GroupRemovalResult) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        var watchdog: Runnable? = null

        fun complete(result: GroupRemovalResult, message: String) {
            if (!completed.compareAndSet(false, true)) return
            watchdog?.let(mainHandler::removeCallbacks)
            log(message)
            onComplete(result)
        }

        watchdog = Runnable {
            complete(
                GroupRemovalResult.TIMED_OUT,
                "lensLinkRemoveGroupTimeout phase=$phase",
            )
        }.also { mainHandler.postDelayed(it, GROUP_REMOVE_CALLBACK_TIMEOUT_MS) }
        runCatching {
            localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = complete(
                    GroupRemovalResult.COMPLETED,
                    "lensLinkRemoveGroupSucceeded phase=$phase",
                )

                override fun onFailure(reason: Int) = complete(
                    GroupRemovalResult.COMPLETED,
                    "lensLinkRemoveGroupFailed phase=$phase reason=$reason",
                )
            })
        }.onFailure {
            complete(
                GroupRemovalResult.COMPLETED,
                "lensLinkRemoveGroupException phase=$phase type=${it.javaClass.simpleName}",
            )
        }
    }

    private fun closeP2pChannel(localChannel: WifiP2pManager.Channel?, phase: String) {
        if (localChannel == null) return
        runCatching { localChannel.close() }.onFailure {
            log("lensLinkChannelCloseFailed phase=$phase type=${it.javaClass.simpleName}")
        }
    }

    private fun runPersistentGroupJanitor(
        localManager: WifiP2pManager,
        localChannel: WifiP2pManager.Channel,
    ) {
        runCatching {
            executor.execute {
                persistentGroupJanitor.clean(localManager, localChannel)
            }
        }.onFailure(persistentGroupJanitor::reportFailure)
    }

    @SuppressLint("MissingPermission")
    private fun runPostTeardownPersistentGroupJanitor(localManager: WifiP2pManager) {
        mainHandler.post {
            val janitorChannel = runCatching {
                localManager.initialize(appContext, Looper.getMainLooper(), null)
            }.onFailure(persistentGroupJanitor::reportFailure).getOrNull()
            if (janitorChannel == null) {
                persistentGroupJanitor.reportFailure(
                    IllegalStateException("janitor channel unavailable"),
                )
                return@post
            }

            val completed = AtomicBoolean(false)
            var watchdog: Runnable? = null

            fun finish() {
                if (!completed.compareAndSet(false, true)) return
                watchdog?.let(mainHandler::removeCallbacks)
                closeP2pChannel(janitorChannel, phase = "janitor")
            }

            watchdog = Runnable {
                persistentGroupJanitor.reportFailure(TimeoutException("janitor callback"))
                finish()
            }.also { mainHandler.postDelayed(it, JANITOR_CALLBACK_TIMEOUT_MS) }
            persistentGroupJanitor.clean(localManager, janitorChannel) {
                mainHandler.post(::finish)
            }
        }
    }

    private fun teardownP2p(onComplete: (() -> Unit)? = null) {
        p2pRunning = false
        p2pConnecting = false
        groupInspecting = false
        probingReusedGroup = false
        discoveryPrimedForJoinCycle = false
        discoveryPriming = false
        discoveryStopPending = false
        groupInspectionId += 1L
        activeJoinAttempt = 0
        consecutiveJoinFailures = 0
        groupOwnerAddress = null
        mainHandler.removeCallbacks(timeout)
        clearJoinRetry()
        clearConnectionInfoPoll()
        clearGroupInspectionTimeout()
        clearDiscoveryPrimingCallbacks()
        clearJoinAttemptTimeout()
        clearTcpRetry()
        onComplete?.let(p2pCleanupContinuations::add)
        if (p2pCleanupInProgress) {
            releasePerformanceLocks()
            return
        }
        val localManager = manager
        val localChannel = channel
        manager = null
        channel = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        releasePerformanceLocks()
        if (localManager != null && localChannel != null) {
            p2pCleanupInProgress = true
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            requestGroupRemoval(localManager, localChannel, phase = "teardown") { result ->
                // A timed-out request belongs to the old channel. Close it before allowing a
                // new generation to initialize, then quarantine the global P2P service briefly.
                closeP2pChannel(localChannel, phase = "teardown")
                if (result == GroupRemovalResult.TIMED_OUT) {
                    log("lensLinkTeardownCleanupQuarantine delayMs=$RESET_SETTLE_MS")
                    mainHandler.postDelayed(
                        {
                            try {
                                finishP2pCleanup()
                            } finally {
                                runPostTeardownPersistentGroupJanitor(localManager)
                            }
                        },
                        RESET_SETTLE_MS,
                    )
                } else {
                    try {
                        finishP2pCleanup()
                    } finally {
                        runPostTeardownPersistentGroupJanitor(localManager)
                    }
                }
            }
        } else {
            closeP2pChannel(localChannel, phase = "teardown")
            finishP2pCleanup()
        }
    }

    private fun finishP2pCleanup() {
        p2pCleanupInProgress = false
        val continuations = p2pCleanupContinuations.toList()
        p2pCleanupContinuations.clear()
        continuations.forEach { it() }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        state = PhoneLensLinkState.IDLE
        offer = null
        synchronized(this) { offerStartPending = false }
        closeSocket()
        teardownP2p()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "RokidBusLens"
        private const val PROBE_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val REUSED_GROUP_TCP_TIMEOUT_MS = 1_500
        private const val REUSED_GROUP_AUTH_TIMEOUT_MS = 4_500
        private const val MAX_REUSED_GROUP_TCP_PROBES = 2
        private const val GROUP_REMOVE_CALLBACK_TIMEOUT_MS = 1_000L
        private const val JANITOR_CALLBACK_TIMEOUT_MS = 2_000L
        private const val TIMEOUT_MS = 60_000L
        private const val TCP_RETRY_MS = 1_000L
        private const val CONNECTION_INFO_POLL_MS = 500L
        private const val GROUP_INFO_TIMEOUT_MS = 1_000L
        private const val DISCOVERY_PRIME_WAIT_MS = 2_000L
        private const val STOP_DISCOVERY_FALLBACK_MS = 400L
        private const val FIRST_JOIN_PROGRESS_TIMEOUT_MS = 1_500L
        private const val JOIN_PROGRESS_TIMEOUT_MS = 4_500L
        private const val INITIAL_JOIN_RETRY_MS = 300L
        private const val JOIN_RETRY_STEP_MS = 1_000L
        private const val MAX_JOIN_RETRY_MS = 3_000L
        private const val MAX_JOIN_ATTEMPTS = 6
        private const val RESET_SETTLE_MS = 500L
    }
}
