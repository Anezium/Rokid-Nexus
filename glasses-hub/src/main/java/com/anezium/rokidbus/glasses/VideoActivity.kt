package com.anezium.rokidbus.glasses

import android.app.Activity
import android.util.Base64
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.BulkLinkPurpose
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.MediaLinkPacket
import com.anezium.rokidbus.shared.MediaLinkPacketType
import com.anezium.rokidbus.shared.MediaLinkProtocol
import org.json.JSONObject
import java.util.UUID
import java.io.FileInputStream
import java.util.concurrent.Executors

/** Full-screen, foreground video consumer. It is intentionally independent of SurfaceController. */
class VideoActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var videoSurface: SurfaceView
    private lateinit var status: TextView
    private var busClient: BusClient? = null
    private var bulkChannel: ParcelFileDescriptor? = null
    private val bulkReader = Executors.newSingleThreadExecutor { Thread(it, "video-bulk-reader").apply { isDaemon = true } }
    private var decoder: VideoH264Decoder? = null
    private var audioDecoder: VideoAacDecoder? = null
    private var surfaceReady = false
    @Volatile private var paused = false
    private var sessionId: String = ""
    private var ownerPluginId: String = ""
    private var epoch = Long.MIN_VALUE
    private var helloAccepted = false
    private var hadPhoneLink = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().ifBlank { UUID.randomUUID().toString() }
        ownerPluginId = intent.getStringExtra(EXTRA_OWNER_PLUGIN_ID).orEmpty()
        buildUi()
        startBus()
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        startLinkIfReady()
    }

    override fun onPause() {
        super.onPause()
        // A background decoder would keep the ROM Wi-Fi app-op alive without a visible user session.
        stopSession("closed")
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        stopSession("closed")
        busClient?.close()
        busClient = null
        bulkReader.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val requestedOwner = intent.getStringExtra(EXTRA_OWNER_PLUGIN_ID).orEmpty()
        if (requested.isNotBlank() && (requested != sessionId || requestedOwner != ownerPluginId)) {
            stopSession("replaced")
            sessionId = requested
            ownerPluginId = requestedOwner
            epoch = Long.MIN_VALUE
            helloAccepted = false
            startLinkIfReady()
        }
    }

    override fun onBackPressed() {
        stopSession("closed")
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { onBackPressed(); true }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
            paused = !paused
            publishState(if (paused) "paused" else "playing")
            showStatus(if (paused) "PAUSED" else "PLAYING")
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startLinkIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        decoder?.close(); decoder = null
        audioDecoder?.close(); audioDecoder = null
    }

    private fun buildUi() {
        videoSurface = SurfaceView(this)
        videoSurface.holder.addCallback(this)
        status = TextView(this).apply {
            setTextColor(Color.rgb(113, 255, 151))
            textSize = 18f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
            text = "PREPARING VIDEO"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoSurface, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(status, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun startBus() {
        busClient = BusClient(
            context = applicationContext,
            clientId = GlassesHub.VIDEO_CLIENT_ID,
            pathPrefixes = listOf(BusPaths.VIDEO_SESSION_CONTROL),
            hubTarget = HubTarget.GLASSES,
        ) { event ->
            when (event) {
                is BusEvent.Message -> if (event.path == BusPaths.VIDEO_SESSION_CONTROL) {
                    runOnUiThread { handleControl(event.payload) }
                }
                is BusEvent.LinkState -> {
                    val linked = event.state and
                        (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0
                    if (linked) {
                        hadPhoneLink = true
                    } else if (hadPhoneLink) {
                        runOnUiThread {
                            stopSession("closed")
                            finish()
                        }
                    }
                }
                else -> Unit
            }
        }.also { it.connect() }
    }

    private fun startLinkIfReady() {
        if (!surfaceReady || bulkChannel != null || isFinishing) return
        decoder = VideoH264Decoder(videoSurface.holder.surface)
        val channel = busClient?.openBulkChannel(sessionId, BulkLinkPurpose.VIDEO) ?: run {
            showStatus("VIDEO LINK UNAVAILABLE")
            publishState("error")
            return
        }
        bulkChannel = channel
        bulkReader.execute {
            runCatching {
                FileInputStream(channel.fileDescriptor).use { input ->
                    while (bulkChannel === channel) {
                        val packet = MediaLinkProtocol.read(input) ?: break
                        handlePacket(packet)
                    }
                }
            }.onFailure {
                if (bulkChannel === channel) runOnUiThread { showStatus("VIDEO LINK LOST") }
            }
        }
        publishState("opened")
    }

    private fun handlePacket(packet: MediaLinkPacket) {
        if (packet.type == MediaLinkPacketType.HELLO) {
            val helloSession = runCatching { JSONObject(packet.meta).optString("sessionId") }.getOrNull()
            if (helloAccepted || packet.epoch <= 0L || helloSession != sessionId || packet.payload.isNotEmpty()) {
                rejectStream("VIDEO LINK REJECTED")
                return
            }
            epoch = packet.epoch
            helloAccepted = true
            return
        }
        if (!helloAccepted || packet.epoch != epoch) return
        when (packet.type) {
            MediaLinkPacketType.VIDEO_CONFIG -> {
                val metadata = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
                val width = metadata.optInt("width")
                val height = metadata.optInt("height")
                val csd1 = metadata.optString("csd1Base64").takeIf { it.isNotBlank() }?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
                val activeDecoder = decoder ?: return
                runCatching { activeDecoder.configure(width, height, packet.payload, csd1) }
                    .onSuccess {
                        runOnUiThread {
                            showStatus("PLAYING")
                            publishState("playing")
                        }
                    }
                    .onFailure {
                        runOnUiThread {
                            showStatus("VIDEO FORMAT UNSUPPORTED")
                            publishState("error")
                            finish()
                        }
                    }
            }
            MediaLinkPacketType.VIDEO_SAMPLE -> if (!paused) decoder?.queue(packet.payload, packet.ptsUs, packet.flags)
            MediaLinkPacketType.AUDIO_CONFIG -> {
                val metadata = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
                val sampleRate = metadata.optInt("sampleRate")
                val channelCount = metadata.optInt("channelCount")
                runCatching {
                    (audioDecoder ?: VideoAacDecoder().also { audioDecoder = it })
                        .configure(sampleRate, channelCount, packet.payload)
                }
            }
            MediaLinkPacketType.AUDIO_SAMPLE -> if (!paused) audioDecoder?.queue(packet.payload, packet.ptsUs)
            MediaLinkPacketType.EOS -> runOnUiThread {
                showStatus("VIDEO ENDED")
                stopSession("closed")
                finish()
            }
            MediaLinkPacketType.ERROR -> runOnUiThread { showStatus("VIDEO LINK ERROR") }
            else -> Unit
        }
    }

    private fun rejectStream(message: String) {
        runOnUiThread {
            showStatus(message)
            stopSession("error")
            finish()
        }
    }

    private fun handleControl(payload: JSONObject) {
        val owner = payload.optString("ownerPluginId", payload.optString("pluginId"))
        if (payload.optString("sessionId") != sessionId || owner != ownerPluginId) return
        when (payload.optString("action")) {
            "pause" -> { paused = true; showStatus("PAUSED"); publishState("paused") }
            "resume" -> { paused = false; showStatus("PLAYING"); publishState("playing") }
            "stop" -> { stopSession("closed"); finish() }
        }
    }

    private fun stopSession(state: String) {
        if (bulkChannel == null && decoder == null && audioDecoder == null) return
        val active = bulkChannel
        bulkChannel = null
        runCatching { active?.close() }
        decoder?.close(); decoder = null
        audioDecoder?.close(); audioDecoder = null
        epoch = Long.MIN_VALUE
        helloAccepted = false
        publishState(state)
    }

    private fun publishState(state: String) {
        if (sessionId.isBlank()) return
        busClient?.send(
            BusPaths.VIDEO_SESSION_STATE,
            JSONObject().put("sessionId", sessionId).put("state", state).put("pluginId", ownerPluginId),
        )
    }

    private fun showStatus(message: String) { status.text = message }

    private fun hideSystemUi() {
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val EXTRA_SESSION_ID = "videoSessionId"
        const val EXTRA_OWNER_PLUGIN_ID = "videoOwnerPluginId"
    }
}
