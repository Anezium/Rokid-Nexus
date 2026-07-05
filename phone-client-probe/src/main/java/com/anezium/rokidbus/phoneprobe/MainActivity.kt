package com.anezium.rokidbus.phoneprobe

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme
import org.json.JSONObject

private const val PATH_ECHO = "/probe/echo"
private const val PATH_ECHO_REPLY = "/probe/echo/reply"
private const val PATH_HTTP_TRIGGER = "/probe/http"
private const val PATH_HTTP_TRIGGER_REPLY = "/probe/http/reply"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val DEFAULT_HTTP_URL = "https://api.transitous.org/api/v1/geocode?text=Paris"

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var heroView: TextView
    private lateinit var heroSub: TextView
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
            is BusEvent.Message -> appendLog(
                "RX ${event.path} id=${event.id.take(8)} bytes=${event.payload.toString().length}",
            )
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
