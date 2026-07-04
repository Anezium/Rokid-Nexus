package com.anezium.rokidbus.phoneprobe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import org.json.JSONObject

private const val PATH_ECHO = "/probe/echo"
private const val PATH_ECHO_REPLY = "/probe/echo/reply"
private const val PATH_HTTP_TRIGGER = "/probe/http"
private const val PATH_HTTP_TRIGGER_REPLY = "/probe/http/reply"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val DEFAULT_HTTP_URL = "https://api.transitous.org/api/v1/geocode?text=Paris"

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var client: BusClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        client = BusClient(
            context = applicationContext,
            clientId = "phone-client-probe",
            pathPrefixes = listOf(PATH_ECHO_REPLY, PATH_HTTP_TRIGGER_REPLY, PATH_HTTP_REPLY),
        ) { event -> handleEvent(event) }
        client.connect()
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    private fun buildUi() {
        status = TextView(this).apply {
            text = "RokidBus Phone Probe"
            textSize = 20f
            setTextColor(0xFF11231A.toInt())
            setPadding(24, 24, 24, 12)
        }
        logView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF202020.toInt())
            setPadding(24, 12, 24, 24)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F8F4.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            addView(status)
            addView(button("Echo") { echoSmall() }, buttonLayout())
            addView(button("Echo-big 64 KB") { echoBig() }, buttonLayout())
            addView(button("HTTP via bus") { httpViaBus() }, buttonLayout())
            addView(
                ScrollView(this@MainActivity).apply { addView(logView) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener {
                appendLog("Button: $label")
                action()
            }
        }

    private fun buttonLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(24, 4, 24, 4) }

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

    private fun handleEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> appendLog("linkState=${event.state}")
            is BusEvent.Error -> appendLog("client error: ${event.message}")
            is BusEvent.Message -> appendLog(
                "RX ${event.path} id=${event.id.take(8)} bytes=${event.payload.toString().length}",
            )
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        status.text = line
        logView.append(line + "\n")
    }
}
