package com.anezium.liveocr.glasses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.LinkedHashMap
import java.util.Locale

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureTimes = LinkedHashMap<Long, Long>()

    private lateinit var stateView: TextView
    private lateinit var statsView: TextView
    private lateinit var credentialsView: TextView
    private lateinit var filesView: TextView

    private var link: GlassesLinkServer? = null
    private var streamer: CameraH264Streamer? = null
    private var latencyLogger: CsvLogger? = null
    private var telemetryLogger: CsvLogger? = null
    private var started = false
    private var lastLoggedOffer: LinkOffer? = null
    private var lastFpsFrames = 0L
    private var lastFpsNanos = 0L

    private val telemetrySample = object : Runnable {
        override fun run() {
            if (!started) return
            sampleTelemetry()
            mainHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (hasPermissions()) startSpike() else requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST)
    }

    private fun buildUi() {
        fun label(size: Float): TextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = size
            setPadding(16, 8, 16, 8)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setBackgroundColor(Color.BLACK)
            addView(label(24f).apply { text = "LIVE OCR SPIKE" })
            stateView = label(20f).also { addView(it) }
            statsView = label(18f).also { addView(it) }
            credentialsView = label(17f).also { addView(it) }
            filesView = label(14f).also { addView(it) }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        stateView.text = "Requesting permissions"
        statsView.text = "Encoder: stopped"
        credentialsView.text = "Wi-Fi Direct credentials pending"
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (hasPermissions()) startSpike() else stateView.text = "Camera + location permissions required"
    }

    private fun startSpike() {
        if (started) return
        started = true
        latencyLogger = CsvLogger(this, LATENCY_FILE, "frameId,captureNanos,ackNanos")
        telemetryLogger = CsvLogger(
            this,
            TELEMETRY_FILE,
            "sampleNanos,batteryTempC,thermalStatus,batteryLevel",
        )
        filesView.text = "CSV:\n${latencyLogger?.file}\n${telemetryLogger?.file}"

        val server = GlassesLinkServer(
            context = this,
            onOffer = ::showOffer,
            onConnected = { connected ->
                stateView.text = if (connected) "Phone linked / streaming" else "Waiting for phone"
                if (connected) streamer?.requestKeyFrame()
            },
            onAck = ::recordAck,
            onState = { state -> stateView.text = state },
        )
        link = server
        server.start()

        streamer = CameraH264Streamer(
            context = this,
            packetSender = server::enqueue,
            onFrameEnqueued = ::rememberCapture,
            onStats = ::showEncoderStats,
            onRunning = {
                runOnUiThread {
                    statsView.text = "Encoder running: ${CameraH264Streamer.WIDTH}x${CameraH264Streamer.HEIGHT} " +
                        "@ ${CameraH264Streamer.FPS} fps, ${CameraH264Streamer.DEFAULT_BITRATE / 1_000_000} Mbit/s"
                }
            },
            onError = { message, failure ->
                Log.e(TAG_CODEC, message, failure)
                runOnUiThread { stateView.text = message }
            },
        ).also { it.start() }

        mainHandler.post(telemetrySample)
    }

    private fun showOffer(offer: LinkOffer) {
        credentialsView.text = buildString {
            appendLine("SSID: ${offer.ssid}")
            appendLine("PASS: ${offer.passphrase}")
            appendLine("PORT: ${offer.port}")
            appendLine("TOKEN: ${offer.token}")
            append("GO IP: ${offer.goIp}")
        }
        if (lastLoggedOffer != offer) {
            lastLoggedOffer = offer
            // The credentials are intentionally logged once for this hardware spike's ADB workflow.
            Log.i(TAG, "credentials ssid=${offer.ssid} pass=${offer.passphrase} port=${offer.port} token=${offer.token} goIp=${offer.goIp}")
        }
    }

    private fun rememberCapture(frameId: Long, captureNanos: Long) {
        synchronized(captureTimes) {
            captureTimes[frameId] = captureNanos
            while (captureTimes.size > MAX_PENDING_CAPTURES) {
                val oldest = captureTimes.entries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    private fun recordAck(frameId: Long) {
        val captureNanos = synchronized(captureTimes) { captureTimes.remove(frameId) } ?: return
        val ackNanos = SystemClock.elapsedRealtimeNanos()
        latencyLogger?.append("$frameId,$captureNanos,$ackNanos")
    }

    private fun showEncoderStats(frames: Long, bytes: Long, dropped: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastFpsNanos == 0L) {
            lastFpsNanos = now
            lastFpsFrames = frames
            return
        }
        val interval = now - lastFpsNanos
        if (interval < 1_000_000_000L) return
        val fps = (frames - lastFpsFrames) * 1_000_000_000.0 / interval
        lastFpsFrames = frames
        lastFpsNanos = now
        runOnUiThread {
            statsView.text = String.format(
                Locale.US,
                "Encoder %.1f fps | %,d frames | %.1f MB | %,d dropped",
                fps,
                frames,
                bytes / 1_000_000.0,
                dropped,
            )
        }
    }

    private fun sampleTelemetry() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temperatureTenths = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperatureC = if (temperatureTenths >= 0) temperatureTenths / 10.0 else -1.0
        val thermalStatus = getSystemService(PowerManager::class.java).currentThermalStatus
        telemetryLogger?.append(
            "${SystemClock.elapsedRealtimeNanos()},$temperatureC,$thermalStatus,$percent",
        )
    }

    override fun onDestroy() {
        started = false
        mainHandler.removeCallbacks(telemetrySample)
        streamer?.close()
        link?.close()
        latencyLogger?.close()
        telemetryLogger?.close()
        streamer = null
        link = null
        latencyLogger = null
        telemetryLogger = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveOcrGlasses"
        private const val TAG_CODEC = "LiveOcrGlassesCodec"
        private const val PERMISSION_REQUEST = 41
        private const val TELEMETRY_INTERVAL_MS = 10_000L
        private const val MAX_PENDING_CAPTURES = 1_000
        private const val LATENCY_FILE = "live_ocr_glasses_latency.csv"
        private const val TELEMETRY_FILE = "live_ocr_glasses_telemetry.csv"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
