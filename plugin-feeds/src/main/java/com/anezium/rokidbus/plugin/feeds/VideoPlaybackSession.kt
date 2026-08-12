package com.anezium.rokidbus.plugin.feeds

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.anezium.rokidbus.client.plugin.NexusBulkChannel
import com.anezium.rokidbus.shared.BulkLinkPurpose
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaLinkPacket
import com.anezium.rokidbus.shared.MediaLinkPacketFlags
import com.anezium.rokidbus.shared.MediaLinkPacketType
import com.anezium.rokidbus.shared.MediaLinkProtocol
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-side compressed-media sender. The Nexus hub owns P2P and exposes only a byte channel. */
internal class VideoPlaybackSession(
    context: android.content.Context,
    private val openBulkChannel: (String, BulkLinkPurpose) -> NexusBulkChannel?,
    private val sendBus: (String, JSONObject) -> Boolean,
    private val onState: (String) -> Unit,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefetcher = FeedVideoPrefetcher(context.applicationContext.cacheDir)
    private var executor = newExecutor()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var channel: NexusBulkChannel? = null
    private var source: Source? = null
    private var requestedSessionId: String? = null
    @Volatile private var remoteOpened = false
    @Volatile private var channelOpening = false
    private var epoch = 0L
    private var seq = 0
    private val linkTimeout = Runnable {
        if (running.get() && channel == null) fail("VIDEO OPEN TIMEOUT")
    }

    private data class Source(
        val media: FeedMedia,
        val variant: FeedMediaVariant,
        val loop: Boolean,
        val localFile: File,
    )

    fun open(media: FeedMedia): Boolean {
        val variant = media.selectPlaybackVariant()
        if (variant == null) {
            onState("VIDEO FORMAT NOT AVAILABLE")
            return false
        }
        close()
        if (executor.isShutdown) executor = newExecutor()
        val sessionId = UUID.randomUUID().toString()
        requestedSessionId = sessionId
        running.set(true)
        paused.set(false)
        channelOpening = false
        epoch = 0L
        seq = 0
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
                mainHandler.postDelayed(linkTimeout, LINK_TIMEOUT_MS)
            }
        }
        return true
    }

    fun handleState(payload: JSONObject) {
        if (!running.get() || payload.optString("sessionId") != requestedSessionId) return
        when (payload.optString("state")) {
            "link_ready" -> {
                val offeredEpoch = payload.optLong("epoch")
                if (offeredEpoch <= 0L || channel != null || channelOpening) return
                epoch = offeredEpoch
                channelOpening = true
                mainHandler.removeCallbacks(linkTimeout)
                onState("CONNECTING VIDEO")
                executor.execute(::startStream)
            }
            "paused" -> {
                paused.set(true)
                onState("VIDEO PAUSED")
            }
            "playing" -> {
                paused.set(false)
                onState("VIDEO PLAYING")
            }
            "ended" -> onState("VIDEO ENDED")
            "closed", "error", "busy" -> {
                remoteOpened = false
                onState("VIDEO ${payload.optString("state").uppercase()}")
                close()
            }
        }
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

    private fun startStream() {
        val sessionId = requestedSessionId ?: return
        val activeSource = source ?: return fail("VIDEO SOURCE UNAVAILABLE")
        val activeChannel = openBulkChannel(sessionId, BulkLinkPurpose.VIDEO)
            ?: return fail("VIDEO LINK UNAVAILABLE")
        if (!running.get() || requestedSessionId != sessionId) {
            activeChannel.close()
            return
        }
        channel = activeChannel
        channelOpening = false
        try {
            MediaLinkProtocol.write(
                activeChannel.output,
                MediaLinkPacket(
                    type = MediaLinkPacketType.HELLO,
                    epoch = epoch,
                    meta = JSONObject().put("sessionId", sessionId).toString(),
                ),
            )
            onState("VIDEO PLAYING")
            stream(activeSource, activeChannel.output)
        } catch (failure: Throwable) {
            if (running.get()) {
                log("video sender failed: ${failure.javaClass.simpleName}")
                fail("VIDEO LINK FAILED")
            }
        }
    }

    private fun stream(activeSource: Source, output: java.io.OutputStream) {
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
                val config = JSONObject()
                    .put("mimeType", "video/avc")
                    .put("width", width)
                    .put("height", height)
                format.getByteBuffer("csd-1")?.copyBytes()?.takeIf { it.isNotEmpty() }?.let {
                    config.put("csd1Base64", Base64.encodeToString(it, Base64.NO_WRAP))
                }
                MediaLinkProtocol.write(
                    output,
                    MediaLinkPacket(
                        MediaLinkPacketType.VIDEO_CONFIG,
                        epoch,
                        seq++,
                        meta = config.toString(),
                        payload = csd,
                    ),
                )
                audioTrack?.let { track ->
                    val audio = extractor.getTrackFormat(track)
                    MediaLinkProtocol.write(
                        output,
                        MediaLinkPacket(
                            MediaLinkPacketType.AUDIO_CONFIG,
                            epoch,
                            seq++,
                            meta = JSONObject()
                                .put("mimeType", "audio/mp4a-latm")
                                .put("sampleRate", audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                                .put("channelCount", audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                                .toString(),
                            payload = audio.getByteBuffer("csd-0")?.copyBytes().orEmpty(),
                        ),
                    )
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
                    val bytes = ByteArray(size).also {
                        buffer.position(0)
                        buffer.get(it)
                    }
                    val type = if (extractor.sampleTrackIndex == videoTrack) {
                        MediaLinkPacketType.VIDEO_SAMPLE
                    } else {
                        MediaLinkPacketType.AUDIO_SAMPLE
                    }
                    val flags = if (type == MediaLinkPacketType.VIDEO_SAMPLE &&
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    ) {
                        MediaLinkPacketFlags.KEY_FRAME
                    } else {
                        0
                    }
                    val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                    if (firstPtsUs == Long.MIN_VALUE) {
                        firstPtsUs = ptsUs
                        playbackStartNs = SystemClock.elapsedRealtimeNanos()
                    }
                    while (running.get() && paused.get()) {
                        if (pauseStartedNs == 0L) pauseStartedNs = SystemClock.elapsedRealtimeNanos()
                        Thread.sleep(PAUSE_POLL_MS)
                    }
                    if (pauseStartedNs != 0L) {
                        playbackStartNs += SystemClock.elapsedRealtimeNanos() - pauseStartedNs
                        pauseStartedNs = 0L
                    }
                    paceUntil(playbackStartNs + (ptsUs - firstPtsUs).coerceAtLeast(0L) * 1_000L)
                    if (!running.get()) break
                    MediaLinkProtocol.write(
                        output,
                        MediaLinkPacket(type, epoch, seq++, ptsUs, flags, payload = bytes),
                    )
                    extractor.advance()
                }
                if (!activeSource.loop) {
                    MediaLinkProtocol.write(
                        output,
                        MediaLinkPacket(MediaLinkPacketType.EOS, epoch, seq++),
                    )
                }
            } finally {
                extractor.release()
            }
        } while (running.get() && activeSource.loop)
        if (!activeSource.loop && running.get()) {
            onState("VIDEO ENDED")
            control("stop")
        }
    }

    private fun ByteBuffer.copyBytes(): ByteArray = duplicate().let { copy ->
        ByteArray(copy.remaining()).also(copy::get)
    }

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
            val remainingNs = targetNs - SystemClock.elapsedRealtimeNanos()
            if (remainingNs <= 0L) return
            Thread.sleep((remainingNs / 1_000_000L).coerceIn(1L, MAX_PACE_SLEEP_MS))
        }
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
        running.set(false)
        paused.set(false)
        channelOpening = false
        mainHandler.removeCallbacks(linkTimeout)
        runCatching { channel?.close() }
        channel = null
        source?.localFile?.delete()
        source = null
        requestedSessionId = null
        remoteOpened = false
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_SAMPLE_BYTES = 1_048_576
        const val MAX_VIDEO_WIDTH = 1280
        const val MAX_VIDEO_HEIGHT = 720
        const val MAX_DURATION_US = 10L * 60L * 1_000_000L
        const val LINK_TIMEOUT_MS = 20_000L
        const val PAUSE_POLL_MS = 25L
        const val MAX_PACE_SLEEP_MS = 25L

        fun newExecutor() = Executors.newSingleThreadExecutor {
            Thread(it, "feeds-video").apply { isDaemon = true }
        }
    }
}
