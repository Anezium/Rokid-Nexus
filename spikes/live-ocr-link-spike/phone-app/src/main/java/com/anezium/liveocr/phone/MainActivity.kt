package com.anezium.liveocr.phone

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ssidInput: EditText
    private lateinit var passphraseInput: EditText
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var goIpInput: EditText
    private lateinit var stateView: TextView
    private lateinit var metricsView: TextView
    private lateinit var previewView: TextView

    private lateinit var logger: CsvLogger
    private lateinit var holder: LatestFrameHolder
    private lateinit var decoder: LatestFrameDecoder
    private lateinit var ocr: LiveOcrRunner
    private lateinit var link: PhoneLinkClient
    private var pendingCredentials: LinkCredentials? = null
    private var decodeSummary = "Decode: waiting"
    private var ocrSummary = "OCR: waiting"

    private val configUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ConfigReceiver.ACTION_CONFIG_UPDATED) return
            ConfigReceiver.loadCredentials(this@MainActivity)?.let {
                populateCredentials(it)
                requestConnect(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        registerConfigUpdates()

        logger = CsvLogger(
            this,
            PHONE_CSV,
            "frameId,recvNanos,decodeDoneNanos,ocrDoneNanos,blockCount,charCount",
        )
        holder = LatestFrameHolder()
        ocr = LiveOcrRunner(
            holder = holder,
            onComplete = { frameId, recvNanos, decodeDoneNanos, ocrDoneNanos, blocks, chars, text ->
                logger.append("$frameId,$recvNanos,$decodeDoneNanos,$ocrDoneNanos,$blocks,$chars")
                link.sendAck(frameId)
                runOnUiThread { previewView.text = text.take(PREVIEW_CHARS).ifBlank { "(no text)" } }
            },
            onCadence = { hz, total ->
                ocrSummary = String.format(Locale.US, "OCR: %.2f Hz | %,d completed", hz, total)
                updateMetrics()
            },
            onError = { error -> Log.w(TAG_OCR, "Recognition failed: $error") },
        )
        decoder = LatestFrameDecoder(
            holder = holder,
            onFrameReady = ocr::kick,
            onDecodeFps = { fps, total, dropped ->
                decodeSummary = String.format(
                    Locale.US,
                    "Decode: %.1f fps | %,d frames | %,d dropped",
                    fps,
                    total,
                    dropped,
                )
                updateMetrics()
            },
            onError = { message, failure ->
                Log.e(TAG_DECODER, message, failure)
                runOnUiThread { stateView.text = message }
            },
        )
        link = PhoneLinkClient(
            context = this,
            onState = { state, detail -> stateView.text = "$state\n$detail" },
            onPacket = { packet, recvNanos ->
                when (packet.type) {
                    PacketType.VIDEO_CONFIG -> decoder.configure(packet.payload)
                    PacketType.VIDEO_FRAME -> decoder.queueFrame(packet, recvNanos)
                    else -> Unit
                }
            },
        )

        findViewById<Button>(CONNECT_BUTTON_ID).setOnClickListener {
            val credentials = credentialsFromFields()
            if (credentials == null) {
                stateView.text = "Enter valid SSID, passphrase, port, token, and GO IP"
            } else {
                ConfigReceiver.saveCredentials(this, credentials)
                requestConnect(credentials)
            }
        }

        stateView.text = "Enter credentials or use the ADB configuration broadcast"
        metricsView.text = "$decodeSummary\n$ocrSummary"
        previewView.text = "(OCR preview)"
        ConfigReceiver.loadCredentials(this)?.let {
            populateCredentials(it)
            requestConnect(it)
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 32)
        }
        content.addView(TextView(this).apply {
            text = "Live OCR link spike"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        stateView = TextView(this).apply { textSize = 17f; setPadding(0, 12, 0, 12) }
        content.addView(stateView)
        ssidInput = addField(content, "SSID")
        passphraseInput = addField(content, "Passphrase", visiblePassword = true)
        portInput = addField(content, "Port", numeric = true).apply {
            setText(ConfigReceiver.DEFAULT_PORT.toString())
        }
        tokenInput = addField(content, "Token", visiblePassword = true)
        goIpInput = addField(content, "GO IP").apply { setText(ConfigReceiver.DEFAULT_GO_IP) }
        content.addView(Button(this).apply {
            id = CONNECT_BUTTON_ID
            text = "Join and run"
        })
        metricsView = TextView(this).apply { textSize = 17f; setPadding(0, 18, 0, 8) }
        content.addView(metricsView)
        content.addView(TextView(this).apply { text = "Latest OCR text"; setTypeface(typeface, Typeface.BOLD) })
        previewView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 16)
            setTextIsSelectable(true)
        }
        content.addView(previewView)
        content.addView(TextView(this).apply {
            text = "Phone CSV:\n/sdcard/Android/data/$packageName/files/$PHONE_CSV"
            textSize = 13f
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun addField(
        parent: LinearLayout,
        hint: String,
        numeric: Boolean = false,
        visiblePassword: Boolean = false,
    ): EditText = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            visiblePassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        parent.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun populateCredentials(credentials: LinkCredentials) {
        ssidInput.setText(credentials.ssid)
        passphraseInput.setText(credentials.passphrase)
        portInput.setText(credentials.port.toString())
        tokenInput.setText(credentials.token)
        goIpInput.setText(credentials.goIp)
    }

    private fun credentialsFromFields(): LinkCredentials? = LinkCredentials(
        ssid = ssidInput.text.toString().trim(),
        passphrase = passphraseInput.text.toString(),
        port = portInput.text.toString().toIntOrNull() ?: -1,
        token = tokenInput.text.toString().trim(),
        goIp = goIpInput.text.toString().trim(),
    ).takeIf(LinkCredentials::isValid)

    private fun requestConnect(credentials: LinkCredentials) {
        if (hasWifiPermission()) {
            pendingCredentials = null
            link.connect(credentials)
        } else {
            pendingCredentials = credentials
            requestPermissions(arrayOf(requiredWifiPermission()), PERMISSION_REQUEST)
        }
    }

    private fun requiredWifiPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    private fun hasWifiPermission(): Boolean =
        checkSelfPermission(requiredWifiPermission()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        val pending = pendingCredentials
        if (hasWifiPermission() && pending != null) {
            pendingCredentials = null
            link.connect(pending)
        } else {
            stateView.text = "Nearby Wi-Fi permission is required"
        }
    }

    private fun updateMetrics() {
        runOnUiThread { metricsView.text = "$decodeSummary\n$ocrSummary" }
    }

    private fun registerConfigUpdates() {
        val filter = IntentFilter(ConfigReceiver.ACTION_CONFIG_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(configUpdateReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configUpdateReceiver) }
        if (::link.isInitialized) link.close()
        if (::decoder.isInitialized) decoder.close()
        if (::ocr.isInitialized) ocr.close()
        if (::logger.isInitialized) logger.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG_DECODER = "LiveOcrPhoneDecoder"
        private const val TAG_OCR = "LiveOcrPhoneOcr"
        private const val PHONE_CSV = "live_ocr_phone.csv"
        private const val PREVIEW_CHARS = 1_000
        private const val PERMISSION_REQUEST = 52
        private const val CONNECT_BUTTON_ID = 0x4f4352
    }
}
