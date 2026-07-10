package com.anezium.rokidbus.phoneprobe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme
import org.json.JSONObject

private const val PATH_ECHO = "/probe/echo"
private const val PATH_ECHO_REPLY = "/probe/echo/reply"
private const val PATH_HTTP_TRIGGER = "/probe/http"
private const val PATH_HTTP_TRIGGER_REPLY = "/probe/http/reply"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val PATH_AUDIO = "/audio"
private const val PATH_AUDIO_LEASE_ACQUIRE = "/audio/lease/acquire"
private const val PATH_AUDIO_LEASE_RELEASE = "/audio/lease/release"
private const val PATH_AUDIO_LEASE_REVOKED = "/audio/lease/revoked"
private const val PATH_AUDIO_FRAMES = "/audio/frames"
private const val DEFAULT_HTTP_URL = "https://api.transitous.org/api/v1/geocode?text=Paris"

class MainActivity : Activity() {
    private data class MicCapture(
        val leaseId: String,
        var frames: Int = 0,
        var bytes: Long = 0L,
        var gaps: Long = 0L,
        var nextSeq: Long = 0L,
    )

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var heroView: TextView
    private lateinit var heroSub: TextView
    private lateinit var client: BusClient
    private val main = Handler(Looper.getMainLooper())
    private var micCapture: MicCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        client = BusClient(
            context = applicationContext,
            clientId = "phone-client-probe",
            pathPrefixes = listOf(PATH_ECHO_REPLY, PATH_HTTP_TRIGGER_REPLY, PATH_HTTP_REPLY, PATH_AUDIO),
            hubTarget = HubTarget.PHONE,
        ) { event -> handleEvent(event) }
        client.connect()
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg

        logView = TextView(this)
        logScroll = BusTheme.console(this, logView)
        heroView = BusTheme.hero(this).apply {
            textSize = 32f
            text = "No link"
            setTextColor(BusTheme.muted)
        }
        heroSub = BusTheme.heroSub(this, "client probe · waiting for hub")

        val root = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@MainActivity, "Rokidbus · Probe"))
            addView(BusTheme.gap(this@MainActivity, 30))
            addView(heroView)
            addView(BusTheme.gap(this@MainActivity, 6))
            addView(heroSub)
            addView(BusTheme.gap(this@MainActivity, 28))
            addView(button("Echo", "Echo") { echoSmall() }, blockLayout())
            addView(BusTheme.gap(this@MainActivity, 10))
            addView(button("Echo 64K", "Echo-big 64 KB") { echoBig() }, blockLayout())
            addView(BusTheme.gap(this@MainActivity, 10))
            addView(button("HTTP via bus", "HTTP via bus") { httpViaBus() }, blockLayout())
            addView(BusTheme.gap(this@MainActivity, 10))
            addView(button("Mic 5 s", "Mic 5 s") { micFiveSeconds() }, blockLayout())
            addView(BusTheme.gap(this@MainActivity, 30))
            addView(BusTheme.tinyLabel(this@MainActivity, "Console"))
            addView(BusTheme.gap(this@MainActivity, 10))
            addView(
                logScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    private fun button(label: String, logLabel: String, action: () -> Unit): Button =
        BusTheme.pill(this, label).apply {
            setOnClickListener {
                appendLog("Button: $logLabel")
                action()
            }
        }

    private fun blockLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun echoSmall() {
        client.request(
            PATH_ECHO,
            JSONObject().put("message", "hello from phone probe"),
        ) { result ->
            appendLog(result.fold(
                onSuccess = { "Echo reply bytes=${it.toString().length} side=${it.optString("side")}" },
                onFailure = { "Echo failed: ${it.message}" },
            ))
        }
    }

    private fun echoBig() {
        client.request(
            PATH_ECHO,
            JSONObject()
                .put("message", "big echo from phone probe")
                .put("bin", "B".repeat(64 * 1024)),
            timeoutMs = 30_000L,
        ) { result ->
            appendLog(result.fold(
                onSuccess = { "Big echo reply bytes=${it.toString().length} side=${it.optString("side")}" },
                onFailure = { "Big echo failed: ${it.message}" },
            ))
        }
    }

    private fun httpViaBus() {
        client.request(
            PATH_HTTP_TRIGGER,
            JSONObject().put("url", DEFAULT_HTTP_URL),
            timeoutMs = 45_000L,
        ) { result ->
            appendLog(result.fold(
                onSuccess = { "HTTP via bus status=${it.optInt("status")} totalBytes=${it.optLong("totalBytes")}" },
                onFailure = { "HTTP via bus failed: ${it.message}" },
            ))
        }
    }

    private fun micFiveSeconds() {
        if (micCapture != null) {
            client.request(
                PATH_AUDIO_LEASE_ACQUIRE,
                JSONObject(),
                timeoutMs = 10_000L,
            ) { result ->
                appendLog(result.fold(
                    onSuccess = {
                        if (it.optBoolean("granted", false)) {
                            val extraLeaseId = it.optString("leaseId")
                            client.send(PATH_AUDIO_LEASE_RELEASE, JSONObject().put("leaseId", extraLeaseId))
                            "Mic second acquire unexpectedly granted"
                        } else {
                            "Mic lease denied reason=${it.optString("reason")}"
                        }
                    },
                    onFailure = { "Mic second acquire failed: ${it.message}" },
                ))
            }
            return
        }

        client.request(
            PATH_AUDIO_LEASE_ACQUIRE,
            JSONObject(),
            timeoutMs = 10_000L,
        ) { result ->
            result.fold(
                onSuccess = { payload ->
                    if (!payload.optBoolean("granted", false)) {
                        appendLog("Mic lease denied reason=${payload.optString("reason")}")
                        return@fold
                    }
                    val leaseId = payload.optString("leaseId")
                    micCapture = MicCapture(leaseId)
                    appendLog(
                        "Mic lease granted id=${leaseId.take(8)} rate=${payload.optInt("sampleRate")} channels=${payload.optInt("channels")}",
                    )
                    main.postDelayed({ finishMicCapture(leaseId) }, 5_000L)
                },
                onFailure = { appendLog("Mic lease failed: ${it.message}") },
            )
        }
    }

    private fun finishMicCapture(leaseId: String) {
        val capture = micCapture ?: return
        if (capture.leaseId != leaseId) return
        micCapture = null
        client.request(
            PATH_AUDIO_LEASE_RELEASE,
            JSONObject().put("leaseId", leaseId),
            timeoutMs = 10_000L,
        ) { result ->
            appendLog(result.fold(
                onSuccess = { "Mic frames=${capture.frames} bytes=${capture.bytes} gaps=${capture.gaps}" },
                onFailure = { "Mic release failed: ${it.message}; frames=${capture.frames} bytes=${capture.bytes} gaps=${capture.gaps}" },
            ))
        }
    }

    private fun handleEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> {
                if (event.state != 0) {
                    heroView.text = "Linked"
                    heroView.setTextColor(BusTheme.phosphor)
                } else {
                    heroView.text = "No link"
                    heroView.setTextColor(BusTheme.muted)
                }
                heroSub.text = "client probe · bus state ${event.state}"
                appendLog("linkState=${event.state}")
            }
            is BusEvent.Error -> appendLog("client error: ${event.message}")
            is BusEvent.Message -> handleMessage(event)
            is BusEvent.Binary -> handleBinary(event)
        }
    }

    private fun handleMessage(event: BusEvent.Message) {
        if (event.path == PATH_AUDIO_LEASE_REVOKED) {
            val leaseId = event.payload.optString("leaseId")
            val capture = micCapture
            if (capture != null && capture.leaseId == leaseId) micCapture = null
            appendLog("Mic lease revoked id=${leaseId.take(8)} reason=${event.payload.optString("reason")}")
            return
        }
        appendLog(
            "RX ${event.path} id=${event.id.take(8)} bytes=${event.payload.toString().length}",
        )
    }

    private fun handleBinary(event: BusEvent.Binary) {
        when (event.path) {
            PATH_HTTP_REPLY -> {
                val bytes = event.meta.optLong("bytes", event.data.size.toLong())
                if (event.meta.optBoolean("done", false)) {
                    appendLog(
                        "HTTP done id=${event.id.take(8)} status=${event.meta.optInt("status")} totalBytes=${event.meta.optLong("totalBytes")}",
                    )
                } else {
                    appendLog("HTTP chunk id=${event.id.take(8)} bytes=$bytes data=${event.data.size}")
                }
            }
            PATH_AUDIO_FRAMES -> handleMicFrame(event)
            else -> {
                appendLog(
                    "RX binary ${event.path} id=${event.id.take(8)} metaBytes=${event.meta.toString().length} data=${event.data.size}",
                )
            }
        }
    }

    private fun handleMicFrame(event: BusEvent.Binary) {
        val capture = micCapture ?: return
        if (event.meta.optString("leaseId") != capture.leaseId) return
        val seq = event.meta.optLong("seq", -1L)
        if (seq != capture.nextSeq) {
            capture.gaps += if (seq > capture.nextSeq) seq - capture.nextSeq else 1L
        }
        capture.nextSeq = seq + 1
        capture.frames += 1
        capture.bytes += event.data.size.toLong()
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
