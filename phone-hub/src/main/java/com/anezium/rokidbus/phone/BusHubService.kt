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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.lyrics.LyricsPlugin
import com.anezium.rokidbus.media.MediaDeckPlugin
import com.anezium.rokidbus.phone.lens.LensTranslationPlugin
import com.anezium.rokidbus.plugin.transit.TransitPlugin
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.LinkStateBits
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ROKIDBUS-PHONE"
private const val CHANNEL_ID = "rokidbus_phone"
private const val NOTIFICATION_ID = 1
private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val ACTION_SET_TOKEN = "com.anezium.rokidbus.phone.SET_TOKEN"
private const val ACTION_STOP = "com.anezium.rokidbus.phone.STOP"
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
        val trusted: Boolean,
        val callbackBinder: IBinder,
        val callback: IBusCallback,
        val deathRecipient: IBinder.DeathRecipient,
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
    private lateinit var lensTranslationPlugin: LensTranslationPlugin
    @Volatile private var cxrConnected = false
    @Volatile private var glassBtConnected = false
    @Volatile private var lastNotifiedStatus: String? = null

    private val binder = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            unregister(cb)
            val callbackBinder = cb.asBinder()
            val deathRecipient = IBinder.DeathRecipient {
                removeRegistrationsByBinder(callbackBinder, "binderDied")
            }
            val registration = Registration(
                clientId = clientId,
                prefixes = pathPrefixes.filter { it.isNotBlank() },
                uid = Binder.getCallingUid(),
                trusted = isTrustedUid(Binder.getCallingUid()),
                callbackBinder = callbackBinder,
                callback = cb,
                deathRecipient = deathRecipient,
            )
            runCatching { callbackBinder.linkToDeath(deathRecipient, 0) }
            registrations += registration
            log("client registered id=$clientId prefixes=${pathPrefixes.joinToString()}")
            runCatching { cb.onLinkState(linkState()) }
            PhoneClientSupervisor.onClientRegistered(applicationContext, registration.prefixes)
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

        override fun capabilities(): Int = BusCapabilityBits.PROTECTED_LENS_LINK
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
        PhoneClientSupervisor.attach(this)
        lensTranslationPlugin = LensTranslationPlugin()
        pluginRegistry = PhonePluginRegistry(
            context = applicationContext,
            plugins = listOf(LyricsPlugin(), MediaDeckPlugin(), TransitPlugin(), lensTranslationPlugin),
            sendEnvelope = { envelope -> sendRemote(envelope) },
            logger = { message -> log(message) },
        )
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
        if (::pluginRegistry.isInitialized) pluginRegistry.close()
        if (::lensTranslationPlugin.isInitialized) lensTranslationPlugin.close()
        registrations.clear()
        super.onDestroy()
    }

    fun deliverQueued(envelope: BusEnvelope): Boolean =
        deliverLocal(envelope)

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        if (BusPaths.isProtectedLensPath(envelope.path) && !isTrustedUid(senderUid)) {
            log("blocked untrusted protected lens send uid=$senderUid")
            return
        }
        if (handleHubPath(envelope, replyRemote = false, senderUid = senderUid)) return
        if (deliverLocal(envelope, excludeUid = senderUid)) return
        if (envelope.path != BusPaths.ERROR &&
            PhoneClientSupervisor.enqueue(applicationContext, envelope, excludeUid = senderUid)
        ) return
        val errorCode = sendRemote(envelope)
        if (errorCode != null) {
            deliverLocal(errorEnvelope(envelope.id, errorCode))
        }
    }

    private fun routeRemote(envelope: BusEnvelope) {
        if (envelope.path == "/hub/probe") {
            log("hub probe received from glasses")
            return
        }
        if (::pluginRegistry.isInitialized && pluginRegistry.handleRemote(envelope)) return
        if (handleHubPath(envelope, replyRemote = true)) return
        if (deliverLocal(envelope)) return
        if (envelope.path == BusPaths.ERROR) {
            log("dropping undeliverable remote error id=${envelope.id}")
            return
        }
        if (envelope.binary != null) {
            log("dropping undeliverable binary ${envelope.path} id=${envelope.id}; no live registration")
            return
        }
        if (PhoneClientSupervisor.enqueue(applicationContext, envelope)) return
        sendRemote(errorEnvelope(envelope.id, "NO_LOCAL_CLIENT"))
    }

    private fun handleHubPath(envelope: BusEnvelope, replyRemote: Boolean, senderUid: Int? = null): Boolean {
        when (envelope.path) {
            BusPaths.HTTP_REQUEST -> executor.execute { fetchAndStream(envelope, replyRemote) }
            AUDIO_LEASE_ACQUIRE -> executor.execute { acquireAudioLease(envelope, replyRemote, senderUid) }
            AUDIO_LEASE_RELEASE -> executor.execute { releaseAudioLease(envelope, replyRemote) }
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
            if (BusPaths.isProtectedLensPath(envelope.path) && !registration.trusted) return@forEach
            if (registration.prefixes.any { envelope.path.startsWith(it) }) {
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
                    PhoneClientSupervisor.touch()
                }.onFailure {
                    removeRegistration(registration, "dead callback")
                }
            }
        }
        return delivered
    }

    private fun removeRegistrationsByBinder(callbackBinder: IBinder, reason: String) {
        registrations.filter { it.callbackBinder == callbackBinder }.forEach { registration ->
            removeRegistration(registration, reason)
        }
    }

    private fun removeRegistration(registration: Registration, reason: String) {
        if (!registrations.remove(registration)) return
        runCatching { registration.callbackBinder.unlinkToDeath(registration.deathRecipient, 0) }
        releaseAudioLeaseForLocalBinder(registration.callbackBinder, reason)
    }

    private fun acquireAudioLease(envelope: BusEnvelope, replyRemote: Boolean, senderUid: Int?) {
        val link = cxrLink
        if (audioLease != null) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"))
            return
        }
        if (link == null || !isCxrUp()) {
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "NO_CXR"))
            return
        }

        val holderBinder = if (replyRemote) null else findLocalAudioHolder(senderUid)
        val lease = AudioLease(
            leaseId = UUID.randomUUID().toString(),
            side = if (replyRemote) AudioLeaseSide.REMOTE else AudioLeaseSide.LOCAL,
            localCallbackBinder = holderBinder,
        )
        synchronized(audioLeaseLock) {
            if (audioLease != null) {
                replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "BUSY"))
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
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", "START_FAILED"))
            return
        }
        if (synchronized(audioLeaseLock) { audioLease?.leaseId != lease.leaseId }) {
            // A concurrent revoke may have run stopAudioStreamQuietly() before our
            // startAudioStream() landed; stop again so no orphan stream survives.
            stopAudioStreamQuietly()
            val reason = if (isCxrUp()) "START_FAILED" else "NO_CXR"
            replyToAudioRequest(envelope, replyRemote, JSONObject().put("granted", false).put("reason", reason))
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
        )
    }

    private fun releaseAudioLease(envelope: BusEnvelope, replyRemote: Boolean) {
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
        replyToAudioRequest(envelope, replyRemote, JSONObject().put("released", true))
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

    private fun replyToAudioRequest(envelope: BusEnvelope, replyRemote: Boolean, payload: JSONObject) {
        val response = BusEnvelope(path = envelope.path + "/reply", id = envelope.id, payload = payload)
        if (replyRemote) sendRemote(response) else deliverLocal(response)
    }

    private fun findLocalAudioHolder(senderUid: Int?): IBinder? {
        if (senderUid == null) return null
        val audioRegistration = registrations.firstOrNull { registration ->
            registration.uid == senderUid && registration.prefixes.any { prefix ->
                AUDIO_FRAMES.startsWith(prefix) || AUDIO_LEASE_REVOKED.startsWith(prefix)
            }
        }
        return audioRegistration?.callbackBinder ?: registrations.firstOrNull { it.uid == senderUid }?.callbackBinder
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        if (envelope.binary != null) {
            if (output == null) return "NO_DATA_PLANE"
            return if (writeSpp(envelope)) null else "NO_DATA_PLANE"
        }
        val bytes = FrameProtocol.toJsonBytes(envelope)
        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp()) {
            if (sendCxr(envelope)) return null
        }
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && output == null) {
            return "NO_DATA_PLANE"
        }
        if (writeSpp(envelope)) return null
        return if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES) "NO_DATA_PLANE" else "NO_LINK"
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

    private fun fetchAndStream(envelope: BusEnvelope, replyRemote: Boolean) {
        val request = envelope.payload
        val urlText = request.optString("url")
        val reply = { meta: JSONObject, data: ByteArray ->
            val response = BusEnvelope(path = BusPaths.HTTP_REPLY, id = envelope.id, payload = meta, binary = data)
            if (replyRemote) sendRemote(response) else deliverLocal(response)
        }
        val url = runCatching { URL(urlText) }.getOrNull()
        if (url == null || url.host != "api.transitous.org") {
            reply(
                JSONObject()
                    .put("status", 0)
                    .put("bytes", 0)
                    .put("error", "HTTP host not allowed")
                    .put("done", true)
                    .put("totalBytes", 0),
                ByteArray(0),
            )
            return
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = request.optString("method", "GET").ifBlank { "GET" }
                request.optJSONObject("headers")?.let { headers ->
                    headers.keys().forEach { key -> setRequestProperty(key, headers.optString(key)) }
                }
                val body = request.optString("body")
                if (body.isNotBlank()) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    reply(
                        JSONObject()
                            .put("status", status)
                            .put("bytes", read)
                            .put("done", false),
                        buffer.copyOf(read),
                    )
                }
            }
            reply(
                JSONObject()
                    .put("status", status)
                    .put("bytes", 0)
                    .put("done", true)
                    .put("totalBytes", total),
                ByteArray(0),
            )
            log("HTTP proxy complete status=$status totalBytes=$total")
        } catch (t: Throwable) {
            reply(
                JSONObject()
                    .put("status", 0)
                    .put("bytes", 0)
                    .put("error", t.javaClass.simpleName + ": " + (t.message ?: "no message"))
                    .put("done", true)
                    .put("totalBytes", 0),
                ByteArray(0),
            )
        } finally {
            connection?.disconnect()
        }
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
                    log("SPP connecting to bonded glasses name=${device.name ?: "unknown"}")
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
        startForeground(NOTIFICATION_ID, buildStatusNotification(state), ServiceInfoCompat.hubTypes(this))
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
        state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0 ->
            "Connected to glasses"
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
        updateStatusNotification(state)
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { removeRegistration(registration, "dead callback") }
        }
        if (::pluginRegistry.isInitialized &&
            state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0
        ) {
            pluginRegistry.syncLauncherList()
        }
    }

    private fun errorEnvelope(id: String, code: String): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id),
        )

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isTrustedUid(uid: Int): Boolean =
        packageManager.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH

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
