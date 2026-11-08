package com.anezium.rokidbus.phone

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.HeadTiltContract
import java.util.UUID
import kotlin.math.roundToInt

class HeadTiltSettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { PhoneHeadTiltStore(this) }
    private lateinit var preview: HeadTiltPreviewView
    private lateinit var angle: TextView
    private lateinit var status: TextView
    private lateinit var target: TextView
    private lateinit var enabled: Switch
    private var unsubscribe: (() -> Unit)? = null
    private var sessionId = ""
    private var lastResponse = 0L
    private var lastRenew = 0L
    private var ready = false
    private var testCount = 0
    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRenew >= 15_000) {
                lastRenew = now
                if (!BusHubService.sendHeadTiltRequest("preview", sessionId)) showUnavailable()
            }
            if (now - lastResponse > 3_000) showUnavailable()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this)
        fun gap() { content.addView(BusTheme.gap(this, 16)) }
        content.addView(NexusUi.cardBody(this,
            "Look up to open Nexus or a chosen plugin. Works from the Rokid or Nexus home screen while the glasses display is on."))
        gap()
        content.addView(NexusUi.sectionLabel(this, "1 / PREPARE"))
        content.addView(NexusUi.cardBody(this,
            "Turn off Head-up display in Hi Rokid first, so both apps do not react to the same movement."))
        content.addView(NexusUi.outlinePillButton(this, "Open Hi Rokid").apply {
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(CxrLAuth.GLOBAL_AI_APP_PACKAGE)?.let { startActivity(it) }
            }
        })
        content.addView(NexusUi.switch(this).apply {
            text = "I turned off Head-up display in Hi Rokid"
            setTextColor(NexusUi.INK)
            isChecked = store.stockDisabled()
            setOnCheckedChangeListener { _, checked ->
                store.setStockDisabled(checked)
                if (!checked) enabled.isChecked = false
                updateEnableGate()
            }
        }, NexusUi.block())
        gap()
        content.addView(NexusUi.sectionLabel(this, "2 / FIND YOUR NEUTRAL POSITION"))
        content.addView(NexusUi.cardBody(this,
            "Wear the glasses, wake their display and look straight ahead comfortably. Keep still for one second after tapping Calibrate."))
        preview = HeadTiltPreviewView(this).apply { threshold = store.settings().angleDegrees }
        content.addView(preview, NexusUi.block())
        angle = NexusUi.hero(this, 32f).apply { text = "—°"; gravity = Gravity.CENTER }
        content.addView(angle, NexusUi.block())
        status = NexusUi.cardBody(this, "Connecting to glasses…")
        content.addView(status, NexusUi.block())
        content.addView(NexusUi.pillButton(this, "Calibrate neutral position").apply {
            setOnClickListener {
                ready = false
                updateEnableGate()
                if (BusHubService.sendHeadTiltRequest("calibrate", sessionId)) {
                    status.text = "Look straight ahead and hold still…"
                } else showUnavailable()
            }
        })
        gap()
        content.addView(NexusUi.sectionLabel(this, "3 / ADJUST AND TRY"))
        val thresholdLabel = NexusUi.rowTitle(this, "Raise your head by ${store.settings().angleDegrees}°")
        content.addView(thresholdLabel)
        content.addView(SeekBar(this).apply {
            max = HeadTiltContract.MAX_ANGLE - HeadTiltContract.MIN_ANGLE
            progress = store.settings().angleDegrees - HeadTiltContract.MIN_ANGLE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val degrees = value + HeadTiltContract.MIN_ANGLE
                    thresholdLabel.text = "Raise your head by $degrees°"
                    preview.threshold = degrees
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    store.save(store.settings().copy(angleDegrees = bar.progress + HeadTiltContract.MIN_ANGLE))
                }
            })
        }, NexusUi.block())
        content.addView(NexusUi.outlinePillButton(this, holdLabel()).apply {
            setOnClickListener {
                val values = listOf(300, 500, 800, 1_000)
                AlertDialog.Builder(this@HeadTiltSettingsActivity).setTitle("Hold the gesture")
                    .setSingleChoiceItems(values.map { "$it ms" }.toTypedArray(), values.indexOf(store.settings().holdMillis)) { dialog, index ->
                        store.save(store.settings().copy(holdMillis = values[index]))
                        text = holdLabel()
                        dialog.dismiss()
                    }.show()
            }
        })
        content.addView(NexusUi.cardBody(this,
            "Look straight ahead for half a second, then raise your head and hold. This screen tests the gesture without opening anything. Return to neutral before each try."))
        gap()
        content.addView(NexusUi.sectionLabel(this, "4 / CHOOSE WHAT OPENS"))
        target = NexusUi.rowTitle(this, "")
        content.addView(target)
        content.addView(NexusUi.outlinePillButton(this, "Choose destination").apply { setOnClickListener { chooseTarget() } })
        enabled = NexusUi.switch(this).apply {
            text = "Enable head tilt"
            setTextColor(NexusUi.INK)
            isChecked = store.settings().enabled
            setOnCheckedChangeListener { _, value -> store.save(store.settings().copy(enabled = value)) }
        }
        content.addView(enabled, NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Head tilt pauses while you use another app, read a Nexus surface or notification, or run setup. Triple tap and Back keep working as usual."))
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(NexusUi.backButton(this@HeadTiltSettingsActivity) { finish() })
            addView(NexusUi.wordmark(this@HeadTiltSettingsActivity, "HEAD TILT"))
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(header, NexusUi.block())
            addView(NexusUi.screen(this@HeadTiltSettingsActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        refreshTarget()
        updateEnableGate()
    }

    override fun onResume() {
        super.onResume()
        sessionId = UUID.randomUUID().toString()
        lastResponse = 0L
        lastRenew = -15_000L
        ready = false
        unsubscribe = PhoneHeadTiltPreview.observe { payload -> main.post {
            if (payload.optString("sessionId") != sessionId) return@post
            lastResponse = SystemClock.elapsedRealtime()
            val state = payload.optString("state")
            ready = state == "ready"
            val degrees = payload.optDouble("upDegrees", Double.NaN)
            if (degrees.isFinite()) {
                preview.upDegrees = degrees.toFloat()
                angle.text = "${degrees.roundToInt()}° / ${store.settings().angleDegrees}°"
            }
            if (payload.optBoolean("detected")) testCount++
            status.text = when (state) {
                "ready" -> if (testCount > 0) "Gesture detected · $testCount successful tries" else "Calibrated. Try raising your head."
                "calibrating" -> "Look straight ahead and hold still…"
                "needs_calibration" -> "Ready to calibrate your neutral position."
                "screen_off" -> "Wake the glasses display to continue."
                "accessibility_required" -> "Enable Nexus accessibility on the glasses."
                "sensor_unavailable" -> "The glasses motion sensor is unavailable."
                else -> "Waiting for motion readings. Keep the glasses display awake."
            }
            updateEnableGate()
        } }
        refreshTarget()
        main.post(heartbeat)
    }

    override fun onPause() {
        main.removeCallbacks(heartbeat)
        unsubscribe?.invoke()
        unsubscribe = null
        BusHubService.sendHeadTiltRequest("stop", sessionId)
        sessionId = ""
        ready = false
        super.onPause()
    }

    private fun showUnavailable() {
        ready = false
        status.text = "Connect Nexus on both devices and wake the glasses display."
        angle.text = "—°"
        updateEnableGate()
    }

    private fun updateEnableGate() { enabled.isEnabled = enabled.isChecked || (ready && store.stockDisabled()) }
    private fun holdLabel(): String = "Hold for ${store.settings().holdMillis} ms"

    private fun refreshTarget() {
        val id = store.settings().pluginId
        val entry = BusHubService.pluginCatalog(this).launchableEntries.firstOrNull { it.id == id }
        target.text = if (id.isBlank()) "Nexus launcher" else entry?.displayName ?: "Plugin unavailable · opens Nexus launcher"
    }

    private fun chooseTarget() {
        val entries = BusHubService.pluginCatalog(this).launchableEntries.filter { !it.id.isNullOrBlank() }
        val ids = listOf("") + entries.map { it.id.orEmpty() }
        val labels = listOf("Nexus launcher") + entries.map { it.displayName }
        AlertDialog.Builder(this).setTitle("Open with head tilt")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(store.settings().pluginId)) { dialog, index ->
                store.save(store.settings().copy(pluginId = ids[index]))
                refreshTarget()
                dialog.dismiss()
            }.show()
    }
}
