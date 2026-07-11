package com.anezium.rokidbus.lens.debug

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Debug-only end-to-end smoke for the glasses -> phone -> ML Kit -> glasses path. */
class LensE2eSmokeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var metricsView: TextView

    private var busClient: BusClient? = null
    private var requestId: String? = null
    private var requestStartedAtMs = 0L
    private var requestSent = false
    private var terminal = false

    private val totalTimeout = Runnable {
        complete(
            passed = false,
            count = 0,
            fallbackCount = 0,
            uiStatus = "FAIL - TIMEOUT",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        handler.postDelayed(totalTimeout, TOTAL_TIMEOUT_MS)
        busClient = BusClient(
            context = applicationContext,
            clientId = CLIENT_ID,
            pathPrefixes = listOf(BusPaths.LENS_TRANSLATE_REPLY),
            hubTarget = HubTarget.GLASSES,
            listener = ::handleBusEvent,
        ).also(BusClient::connect)
    }

    override fun onDestroy() {
        closeRuntime()
        super.onDestroy()
    }

    private fun handleBusEvent(event: BusEvent) {
        if (terminal) return
        when (event) {
            is BusEvent.LinkState -> {
                if (event.state and LinkStateBits.SPP_DATA_UP != 0) sendRequestOnce()
            }
            is BusEvent.Message -> handleMessage(event)
            is BusEvent.Error -> updateUi("WAITING FOR HUB / DATA LINK")
            is BusEvent.Binary -> Unit
        }
    }

    private fun sendRequestOnce() {
        if (requestSent || terminal) return
        requestSent = true
        requestStartedAtMs = SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        requestId = id

        val payload = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("id", id)
            .put("targetLang", TARGET_LANGUAGE)
            .put("mode", RECOGNIZER_MODE)
            .put("strings", JSONArray(SOURCE_PHRASES))

        updateUi("REQUEST SENT")
        busClient?.send(BusPaths.LENS_TRANSLATE_REQUEST, id, payload)
    }

    private fun handleMessage(event: BusEvent.Message) {
        val expectedId = requestId ?: return
        if (event.path == BusPaths.ERROR) {
            val failedId = event.payload.optString("forId", event.id)
            if (failedId == expectedId) {
                complete(false, 0, 0, "FAIL - BUS ERROR")
            }
            return
        }
        if (event.path != BusPaths.LENS_TRANSLATE_REPLY) return

        val responseId = event.payload.optString("id", event.id)
        if (event.id != expectedId && responseId != expectedId) return

        when (event.payload.optString("status")) {
            "downloading" -> updateUi("MODEL DOWNLOAD IN PROGRESS")
            "error" -> complete(false, 0, 0, "FAIL - TRANSLATION ERROR")
            else -> validateTerminalReply(event.payload)
        }
    }

    private fun validateTerminalReply(payload: JSONObject) {
        val translations = payload.optJSONArray("translations")
        if (translations == null) {
            complete(false, 0, 0, "FAIL - INVALID REPLY")
            return
        }

        var validCount = 0
        var fallbackCount = 0
        for (index in 0 until translations.length()) {
            val item = translations.optJSONObject(index) ?: continue
            if (item.optString("dst").isNotBlank()) validCount += 1
            if (item.optBoolean("fallback", false)) fallbackCount += 1
        }
        val passed = validCount == SOURCE_PHRASES.size &&
            translations.length() == SOURCE_PHRASES.size &&
            fallbackCount == 0
        complete(
            passed = passed,
            count = validCount,
            fallbackCount = fallbackCount,
            uiStatus = if (passed) "PASS" else "FAIL - INVALID RESULTS",
        )
    }

    private fun complete(
        passed: Boolean,
        count: Int,
        fallbackCount: Int,
        uiStatus: String,
    ) {
        if (terminal) return
        terminal = true
        val latencyMs = requestStartedAtMs
            .takeIf { it > 0L }
            ?.let { SystemClock.elapsedRealtime() - it }
            ?: 0L
        val outcome = if (passed) "PASS" else "FAIL"
        Log.i(LOG_TAG, "$outcome count=$count latencyMs=$latencyMs fallbackCount=$fallbackCount")
        statusView.text = uiStatus
        statusView.setTextColor(if (passed) COLOR_PASS else COLOR_FAIL)
        metricsView.text = "count=$count\nlatencyMs=$latencyMs\nfallbackCount=$fallbackCount"
        closeRuntime()
    }

    private fun closeRuntime() {
        handler.removeCallbacksAndMessages(null)
        busClient?.close()
        busClient = null
    }

    private fun updateUi(status: String) {
        statusView.text = status
    }

    private fun buildContentView(): LinearLayout {
        val sidePadding = 32.dp
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(sidePadding, 24.dp, sidePadding, 24.dp)
            setBackgroundColor(Color.BLACK)

            addView(textView("LENS E2E SMOKE", 28f, Color.WHITE))
            statusView = textView("WAITING FOR SPP DATA LINK", 24f, COLOR_WAITING).also {
                it.gravity = Gravity.CENTER
                addView(it, linearLayoutParams(topMargin = 48.dp))
            }
            metricsView = textView("count=-\nlatencyMs=-\nfallbackCount=-", 20f, Color.WHITE).also {
                it.gravity = Gravity.CENTER
                addView(it, linearLayoutParams(topMargin = 40.dp))
            }
            addView(
                textView("Debug only - auto run", 18f, COLOR_HINT),
                linearLayoutParams(topMargin = 56.dp),
            )
        }
    }

    private fun textView(text: String, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun linearLayoutParams(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        private const val LOG_TAG = "LensE2eSmoke"
        private const val CLIENT_ID = "lens-e2e-smoke"
        private const val PROTOCOL_VERSION = 1
        private const val TARGET_LANGUAGE = "fr"
        private const val RECOGNIZER_MODE = "LATIN"
        private const val TOTAL_TIMEOUT_MS = 145_000L
        private val SOURCE_PHRASES = listOf(
            "Hello, how are you?",
            "Where is the train station?",
            "This menu is easy to read.",
        )
        private val COLOR_WAITING = Color.rgb(255, 210, 64)
        private val COLOR_PASS = Color.rgb(80, 255, 130)
        private val COLOR_FAIL = Color.rgb(255, 90, 90)
        private val COLOR_HINT = Color.rgb(170, 170, 170)
    }
}
