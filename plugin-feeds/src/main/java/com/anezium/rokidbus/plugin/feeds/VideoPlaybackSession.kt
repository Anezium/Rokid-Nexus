package com.anezium.rokidbus.plugin.feeds

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaLinkPacket
import com.anezium.rokidbus.shared.MediaLinkPacketFlags
import com.anezium.rokidbus.shared.MediaLinkPacketType
import com.anezium.rokidbus.shared.MediaLinkProtocol
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-side, one-shot AVC sender. It deliberately accepts only the hub-owned P2P offer. */
internal class VideoPlaybackSession(
    context: Context,
    private val sendBus: (String, JSONObject) -> Boolean,
    private val onState: (String) -> Unit,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefetcher = FeedVideoPrefetcher(appContext.cacheDir)
    private var executor = newExecutor()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var socket: Socket? = null
    private var offer: Offer? = null
    private var source: Source? = null
    private var requestedSessionId: String? = null
    @Volatile private var remoteOpened = false
    private var epoch = 0L
    private var seq = 0
    private var connectionPolls = 0
    private val offerTimeout = Runnable {
        if (running.get() && offer == null) fail("VIDEO OPEN TIMEOUT")
    }

    private data class Source(
        val media: FeedMedia,
        val variant: FeedMediaVariant,
        val loop: Boolean,
        val localFile: File,
    )
    private data class Offer(val sessionId: String, val ssid: String, val passphrase: String, val goIp: String, val port: Int, val token: String, val epoch: Long)

    fun open(media: FeedMedia): Boolean {
        val variant = media.selectPlaybackVariant()
        if (variant == null) {
            onState("VIDEO FORMAT NOT AVAILABLE")
            return false
        }
        if (!hasWifiPermission()) {
            onState("VIDEO WI-FI PERMISSION REQUIRED")
            return false
        }
        close()
        if (executor.isShutdown) executor = newExecutor()
        val sessionId = UUID.randomUUID().toString()
        requestedSessionId = sessionId
        running.set(true)
        paused.set(false)
        onState("DOWNLOADING VIDEO")
        executor.execute {
            val prepared = runCatching { prefetcher.prepare(variant, running::get) }
                .getOrElse {
                    if (running.get()) fail("VIDEO DOWNLOAD FAILED")
                    return@execute
                }
            if (!running.get()) {
                prepared.delete()
                return@execute
            }
            source = Source(media, variant, media.type == FeedMediaType.GIF, prepared)
            if (!runCatching { validatePrepared(prepared) }.getOrDefault(false)) {
                fail("VIDEO FORMAT NOT SUPPORTED")
                return@execute
            }
            onState("PREPARING VIDEO")
            remoteOpened = true
            val requestSent = runCatching {
                sendBus(
                    BusPaths.VIDEO_SESSION_OPEN,
                    JSONObject()
                        .put("sessionId", sessionId)
                        .put("surfaceId", "feeds")
                        .put("mediaType", media.type.name.lowercase())
                        .put("loop", media.type == FeedMediaType.GIF)
                        .put("muted", media.type == FeedMediaType.GIF),
                )
            }.getOrDefault(false)
            if (!requestSent) {
                remoteOpened = false
                fail("VIDEO HUB UNAVAILABLE")
            } else {
                mainHandler.postDelayed(offerTimeout, OFFER_TIMEOUT_MS)
            }
        }
        return true
    }

    fun handleState(payload: JSONObject) {
        if (!running.get() || payload.optString("sessionId") != requestedSessionId) return
        when (payload.optString("state")) {
            "paused" -> { paused.set(true); onState("VIDEO PAUSED") }
            "playing" -> { paused.set(false); onState("VIDEO PLAYING") }
            "ended" -> onState("VIDEO ENDED")
            "closed", "error", "busy" -> {
                remoteOpened = false
                onState("VIDEO ${payload.optString("state").uppercase()}")
                close()
            }
        }
    }

    fun handleOffer(payload: JSONObject) {
        if (!running.get() || payload.optString("sessionId") != requestedSessionId ||
            payload.optString("mode", "p2p") != "p2p"
        ) return
        mainHandler.removeCallbacks(offerTimeout)
        val decoded = Offer(
            sessionId = payload.optString("sessionId"), ssid = payload.optString("ssid"),
            passphrase = payload.optString("passphrase"), goIp = payload.optString("goIp"),
            port = payload.optInt("port"), token = payload.optString("token"), epoch = payload.optLong("epoch"),
        )
        if (decoded.sessionId.isBlank() || decoded.ssid.isBlank() || decoded.passphrase.length !in 8..128 ||
            !isPrivateIpv4(decoded.goIp) || decoded.port != VIDEO_PORT ||
            decoded.token.length !in 16..256 || decoded.epoch == 0L
        ) return fail("VIDEO LINK OFFER INVALID")
        offer = decoded
        epoch = decoded.epoch
        joinP2p(decoded)
    }

    fun control(action: String) {
        val sessionId = requestedSessionId ?: return
        when (action) {
            "pause" -> paused.set(true)
            "resume" -> paused.set(false)
        }
        runCatching {
            sendBus(
                BusPaths.VIDEO_SESSION_CONTROL,
                JSONObject().put("sessionId", sessionId).put("action", action),
            )
        }
        if (action == "stop") {
            remoteOpened = false
            close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinP2p(next: Offer) {
        if (!running.get()) return
        p2p = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = p2p?.initialize(appContext, Looper.getMainLooper(), null)
        val manager = p2p ?: return fail("VIDEO WI-FI DIRECT UNAVAILABLE")
        val activeChannel = channel ?: return fail("VIDEO WI-FI DIRECT UNAVAILABLE")
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val config = runCatching {
            WifiP2pConfig.Builder().setNetworkName(next.ssid).setPassphrase(next.passphrase)
                .enablePersistentMode(false).build()
        }.getOrElse { return fail("VIDEO WI-FI CONFIG FAILED") }
        onState("CONNECTING VIDEO")
        connectionPolls = 0
        manager.connect(activeChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = requestConnectionInfo()
            override fun onFailure(reason: Int) = fail("VIDEO WI-FI DIRECT FAILED")
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val manager = p2p ?: return
        val activeChannel = channel ?: return
        manager.requestConnectionInfo(activeChannel) { info ->
            if (info?.groupFormed == true && !info.isGroupOwner) {
                connectSocket()
            } else if (running.get() && connectionPolls++ < MAX_CONNECTION_POLLS) {
                mainHandler.postDelayed(::requestConnectionInfo, CONNECTION_POLL_MS)
            } else if (running.get()) {
                fail("VIDEO WI-FI TIMEOUT")
            }
        }
    }

    private fun connectSocket() {
        val currentOffer = offer ?: return
        if (!running.get() || socket != null) return
        executor.execute {
            try {
                Socket().also { candidate ->
                    candidate.tcpNoDelay = true
                    candidate.keepAlive = true
                    candidate.connect(InetSocketAddress(currentOffer.goIp, currentOffer.port), 12_000)
                    socket = candidate
                    val output = candidate.getOutputStream()
                    MediaLinkProtocol.write(output, MediaLinkPacket(
                        type = MediaLinkPacketType.HELLO, epoch = epoch,
                        meta = JSONObject().put("sessionId", currentOffer.sessionId).put("token", currentOffer.token).toString(),
                    ))
                    onState("VIDEO PLAYING")
                    stream(output)
                }
            } catch (failure: Throwable) {
                log("video sender failed: ${failure.javaClass.simpleName}")
                fail("VIDEO LINK FAILED")
            }
        }
    }

    private fun stream(output: java.io.OutputStream) {
        val activeSource = source ?: return
        do {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(activeSource.localFile.absolutePath)
                val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "video/avc"
                } ?: return fail("VIDEO AVC NOT FOUND")
                val format = extractor.getTrackFormat(videoTrack)
                val audioTrack = if (activeSource.media.type == FeedMediaType.GIF) null else {
                    (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
                    }
                }
                extractor.selectTrack(videoTrack)
                audioTrack?.let(extractor::selectTrack)
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val csd = format.getByteBuffer("csd-0")?.copyBytes().orEmpty()
                val config = JSONObject().put("mimeType", "video/avc").put("width", width).put("height", height)
                format.getByteBuffer("csd-1")?.copyBytes()?.takeIf { it.isNotEmpty() }?.let {
                    config.put("csd1Base64", Base64.encodeToString(it, Base64.NO_WRAP))
                }
                MediaLinkProtocol.write(output, MediaLinkPacket(
                    MediaLinkPacketType.VIDEO_CONFIG, epoch, seq++, meta = config.toString(), payload = csd,
                ))
                audioTrack?.let { track ->
                    val audio = extractor.getTrackFormat(track)
                    MediaLinkProtocol.write(output, MediaLinkPacket(
                        MediaLinkPacketType.AUDIO_CONFIG,
                        epoch,
                        seq++,
                        meta = JSONObject()
                            .put("mimeType", "audio/mp4a-latm")
                            .put("sampleRate", audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                            .put("channelCount", audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            .toString(),
                        payload = audio.getByteBuffer("csd-0")?.copyBytes().orEmpty(),
                    ))
                }
                val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
                var firstPtsUs = Long.MIN_VALUE
                var playbackStartNs = 0L
                var pauseStartedNs = 0L
                while (running.get()) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > MAX_SAMPLE_BYTES) return fail("VIDEO SAMPLE TOO LARGE")
                    val bytes = ByteArray(size).also { buffer.position(0); buffer.get(it) }
                    val type = if (extractor.sampleTrackIndex == videoTrack) {
                        MediaLinkPacketType.VIDEO_SAMPLE
                    } else {
                        MediaLinkPacketType.AUDIO_SAMPLE
                    }
                    val flags = if (type == MediaLinkPacketType.VIDEO_SAMPLE &&
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    ) MediaLinkPacketFlags.KEY_FRAME else 0
                    val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                    if (firstPtsUs == Long.MIN_VALUE) {
                        firstPtsUs = ptsUs
                        playbackStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                    }
                    while (running.get() && paused.get()) {
                        if (pauseStartedNs == 0L) {
                            pauseStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                        Thread.sleep(PAUSE_POLL_MS)
                    }
                    if (pauseStartedNs != 0L) {
                        playbackStartNs += android.os.SystemClock.elapsedRealtimeNanos() - pauseStartedNs
                        pauseStartedNs = 0L
                    }
                    paceUntil(playbackStartNs + (ptsUs - firstPtsUs).coerceAtLeast(0L) * 1_000L)
                    if (!running.get()) break
                    MediaLinkProtocol.write(output, MediaLinkPacket(type, epoch, seq++, ptsUs, flags, payload = bytes))
                    extractor.advance()
                }
                if (!activeSource.loop) {
                    MediaLinkProtocol.write(output, MediaLinkPacket(MediaLinkPacketType.EOS, epoch, seq++))
                }
            } finally { extractor.release() }
        } while (running.get() && activeSource.loop)
        if (!activeSource.loop && running.get()) {
            onState("VIDEO ENDED")
            control("stop")
        }
    }

    private fun ByteBuffer.copyBytes(): ByteArray = duplicate().let { copy -> ByteArray(copy.remaining()).also(copy::get) }

    private fun validatePrepared(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
                ?: return false
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (video.containsKey(MediaFormat.KEY_DURATION)) {
                video.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            width in 1..MAX_VIDEO_WIDTH && height in 1..MAX_VIDEO_HEIGHT &&
                (durationUs <= 0L || durationUs <= MAX_DURATION_US)
        } finally {
            extractor.release()
        }
    }

    private fun paceUntil(targetNs: Long) {
        while (running.get()) {
            val remainingNs = targetNs - android.os.SystemClock.elapsedRealtimeNanos()
            if (remainingNs <= 0L) return
            Thread.sleep((remainingNs / 1_000_000L).coerceIn(1L, MAX_PACE_SLEEP_MS))
        }
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPrivateIpv4(value: String): Boolean {
        if (!IPV4.matches(value)) return false
        return runCatching { InetAddress.getByName(value) }
            .getOrNull()
            ?.let { it is Inet4Address && it.isSiteLocalAddress } == true
    }
    private fun fail(message: String) {
        if (running.get()) onState(message)
        val sessionId = requestedSessionId
        if (remoteOpened && sessionId != null) {
            remoteOpened = false
            runCatching {
                sendBus(
                    BusPaths.VIDEO_SESSION_CONTROL,
                    JSONObject().put("sessionId", sessionId).put("action", "stop"),
                )
            }
        }
        close()
    }
    override fun close() {
        running.set(false); paused.set(false); runCatching { socket?.close() }; socket = null
        mainHandler.removeCallbacks(offerTimeout)
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }; receiverRegistered = false
        runCatching { p2p?.removeGroup(channel, null) }; channel = null; p2p = null; offer = null
        source?.localFile?.delete(); source = null
        requestedSessionId = null; remoteOpened = false; executor.shutdownNow()
    }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) requestConnectionInfo()
    } }
    private companion object { 
        const val MAX_SAMPLE_BYTES = 1_048_576
        const val MAX_VIDEO_WIDTH = 1280
        const val MAX_VIDEO_HEIGHT = 720
        const val VIDEO_PORT = 38402
        const val MAX_DURATION_US = 10L * 60L * 1_000_000L
        const val CONNECTION_POLL_MS = 500L
        const val MAX_CONNECTION_POLLS = 30
        const val OFFER_TIMEOUT_MS = 20_000L
        const val PAUSE_POLL_MS = 25L
        const val MAX_PACE_SLEEP_MS = 25L
        val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        fun newExecutor() = Executors.newSingleThreadExecutor {
            Thread(it, "feeds-video").apply { isDaemon = true }
        }
    }
}
