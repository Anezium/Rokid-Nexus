package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.anezium.rokidbus.shared.HeadTiltContract
import com.anezium.rokidbus.shared.HeadTiltSettings
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.sqrt

/** Main-thread owner of the non-wakeup IMU listener and bounded phone calibration preview. */
internal object HeadTiltController : SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var settings = HeadTiltSettings()
    private var manager: SensorManager? = null
    private var listening = false
    private var failure: String? = null
    private var baseline: Pair<Float, Float>? = null
    private var detector = HeadTiltDetector(20f, 500)
    private var calibration: HeadTiltCalibration? = null
    private var previewId: String? = null
    private var previewDeadline = 0L
    private var lastStatus = 0L
    private var lastSample = 0L
    private var gateDirty = true
    private var allowed = false
    private var up: Float? = null
    private val rotation = FloatArray(9)
    private val expirePreview = Runnable { endPreview(); refresh() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) endPreview()
            refresh()
        }
    }

    fun start(current: AccessibilityService) = onMain {
        if (service != null) stop()
        service = current
        val prefs = current.getSharedPreferences("head_tilt", Context.MODE_PRIVATE)
        settings = runCatching { HeadTiltContract.parse(JSONObject(prefs.getString("config", "{}").orEmpty())) }
            .getOrNull() ?: HeadTiltSettings()
        baseline = if (prefs.contains("pitch") && prefs.contains("side")) {
            (prefs.getFloat("pitch", 0f) to prefs.getFloat("side", 0f)).takeIf { it.first.isFinite() && it.second.isFinite() }
        } else null
        detector = HeadTiltDetector(settings.angleDegrees.toFloat(), settings.holdMillis.toLong())
        manager = current.getSystemService(SensorManager::class.java)
        current.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        refresh()
    }

    fun stop() = onMain {
        endPreview()
        manager?.unregisterListener(this)
        service?.let { runCatching { it.unregisterReceiver(receiver) } }
        listening = false
        service = null
        manager = null
        detector.reset()
    }

    fun configure(context: Context, payload: JSONObject) = onMain {
        val next = HeadTiltContract.parse(payload) ?: HeadTiltSettings()
        context.getSharedPreferences("head_tilt", Context.MODE_PRIVATE).edit()
            .putString("config", HeadTiltContract.toJson(next).toString()).apply()
        if (settings != next) {
            settings = next
            detector = HeadTiltDetector(next.angleDegrees.toFloat(), next.holdMillis.toLong())
        }
        refresh()
    }

    fun request(payload: JSONObject) = onMain {
        if (payload.optInt("v") != HeadTiltContract.VERSION) return@onMain
        val id = payload.optString("sessionId").takeIf { it.length in 1..64 } ?: return@onMain
        when (payload.optString("action")) {
            "preview" -> {
                if (previewId != id) { calibration = null; detector.reset() }
                previewId = id
                previewDeadline = SystemClock.elapsedRealtime() + HeadTiltContract.PREVIEW_MILLIS
                main.removeCallbacks(expirePreview)
                main.postDelayed(expirePreview, HeadTiltContract.PREVIEW_MILLIS)
                refresh()
                status()
            }
            "calibrate" -> if (previewId == id && listening) {
                calibration = HeadTiltCalibration()
                status()
            }
            "stop" -> if (previewId == id) { endPreview(); refresh() }
        }
    }

    fun onLinkLost() = onMain { endPreview(); refresh() }

    fun onWindowsChanged() { gateDirty = true }

    private fun endPreview() {
        previewId = null
        calibration = null
        previewDeadline = 0L
        main.removeCallbacks(expirePreview)
        detector.reset()
        gateDirty = true
    }

    private fun refresh() {
        val current = service
        val awake = current?.getSystemService(PowerManager::class.java)?.isInteractive == true
        val need = current != null && awake && ((settings.enabled && baseline != null) || previewId != null)
        if (!need) {
            manager?.unregisterListener(this)
            listening = false
            lastSample = 0L
            up = null
            detector.reset()
            return
        }
        if (listening) return
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        listening = sensor != null && runCatching { manager?.registerListener(this, sensor, 50_000, main) == true }.getOrDefault(false)
        failure = if (listening) null else "sensor_unavailable"
        detector.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val current = service ?: return
        if (!listening || current.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        val now = SystemClock.elapsedRealtime()
        if (now - event.timestamp / 1_000_000 !in 0..250) return
        if (previewId != null && now >= previewDeadline) { endPreview(); refresh() }
        if (event.values.size < 3 || event.values.any { !it.isFinite() }) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Rokid's glasses axes: looking up decreases atan2(R[8], R[7]), unlike phone pitch.
        val pitch = Math.toDegrees(atan2(rotation[8].toDouble(), rotation[7].toDouble())).toFloat()
        val side = Math.toDegrees(atan2(rotation[6].toDouble(), sqrt((rotation[7] * rotation[7] + rotation[8] * rotation[8]).toDouble()))).toFloat()
        lastSample = now
        val calibrating = calibration
        calibrating?.sample(pitch, side, now)?.let { pose ->
            baseline = pose
            current.getSharedPreferences("head_tilt", Context.MODE_PRIVATE).edit()
                .putFloat("pitch", pose.first).putFloat("side", pose.second).apply()
            calibration = null
            detector.reset()
        }
        val neutral = baseline
        up = neutral?.let { -headTiltDelta(it.first, pitch) }
        val sideways = neutral?.let { headTiltDelta(it.second, side) } ?: 0f
        if (gateDirty && previewId == null) {
            allowed = NexusInput.canOpenFromHeadTilt()
            gateDirty = false
        }
        val detected = detector.sample(up ?: Float.NaN, sideways, now,
            neutral != null && calibration == null && (previewId != null || (settings.enabled && allowed)))
        if (detected && previewId == null && NexusInput.canOpenFromHeadTilt()) {
            if (NexusInput.openFromHeadTilt(settings.pluginId)) DisplayWakePolicy.noteUserInteraction()
        }
        if (previewId != null && (detected || now - lastStatus >= 200)) status(detected)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun status(detected: Boolean = false) {
        val id = previewId ?: return
        lastStatus = SystemClock.elapsedRealtime()
        val state = when {
            service == null -> "accessibility_required"
            service?.getSystemService(PowerManager::class.java)?.isInteractive != true -> "screen_off"
            !listening -> failure ?: "sensor_unavailable"
            calibration != null -> "calibrating"
            baseline == null -> "needs_calibration"
            lastSample == 0L || lastStatus - lastSample > 1_000 -> "waiting_for_sensor"
            else -> "ready"
        }
        GlassesHub.sendToPhone(HeadTiltContract.STATUS_PATH, JSONObject().put("v", HeadTiltContract.VERSION)
            .put("sessionId", id).put("state", state).put("detected", detected)
            .apply { up?.takeIf { it.isFinite() }?.let { put("upDegrees", it.toDouble()) } })
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
