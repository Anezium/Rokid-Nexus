package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme

private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val AUTH_REQUEST = 42
private const val PREFS = "rokidbus_phone"
private const val PREF_TOKEN = "cxrl_token"
private const val LINK_CXR_CONTROL_UP = 1
private const val LINK_SPP_DATA_UP = 2
private const val LINK_GLASS_BONDED = 4

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var heroView: TextView
    private lateinit var cxrTile: BusTheme.Tile
    private lateinit var sppTile: BusTheme.Tile
    private lateinit var hiRokidTile: BusTheme.Tile
    private var hubUiClient: BusClient? = null

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appendLog(intent.getStringExtra("line").orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        hubUiClient = BusClient(
            context = applicationContext,
            clientId = "hub-ui",
            pathPrefixes = emptyList(),
        ) { event -> handleHubEvent(event) }.also { it.connect() }
        requestBluetoothConnectIfNeeded()
        if (savedToken().isNotBlank()) {
            appendLog("Saved Hi Rokid token present")
            BusHubService.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(logReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        hubUiClient?.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST) return
        when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_TOKEN, result.token)
                    .apply()
                appendLog("Hi Rokid authorization succeeded")
                BusHubService.startWithToken(this, result.token)
            }
            is CxrLAuth.Result.Fail -> appendLog("Authorization failed: ${result.reason}")
            CxrLAuth.Result.Cancel -> appendLog("Authorization canceled")
        }
    }

    private fun buildUi() {
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg

        logView = TextView(this)
        logScroll = BusTheme.console(this, logView)
        heroView = BusTheme.hero(this)
        cxrTile = BusTheme.Tile(this, "CXR-L")
        sppTile = BusTheme.Tile(this, "SPP")
        hiRokidTile = BusTheme.Tile(this, "HI ROKID")
        updateLinkState(0)

        val auth = BusTheme.pill(this, "Authorize").apply {
            setOnClickListener {
                when (val result = CxrLAuth.requestAuthorization(this@MainActivity, AUTH_REQUEST)) {
                    null -> appendLog("Hi Rokid authorization opened")
                    is CxrLAuth.Result.Fail -> appendLog("Authorization failed: ${result.reason}")
                    CxrLAuth.Result.Cancel -> appendLog("Authorization canceled")
                    is CxrLAuth.Result.Success -> Unit
                }
            }
        }
        val start = BusTheme.pill(this, "Start hub").apply {
            setOnClickListener {
                appendLog("Starting hub")
                BusHubService.start(this@MainActivity)
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(auth, actionButtonLayout(isFirst = true))
            addView(start, actionButtonLayout(isFirst = false))
        }

        val root = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@MainActivity, "Rokidbus"))
            addView(BusTheme.gap(this@MainActivity, 30))
            addView(heroView)
            addView(BusTheme.gap(this@MainActivity, 6))
            addView(BusTheme.heroSub(this@MainActivity, "glasses bus · cxr-l + spp"))
            addView(BusTheme.gap(this@MainActivity, 28))
            addView(
                BusTheme.tileRow(this@MainActivity, listOf(cxrTile, sppTile, hiRokidTile)),
                blockLayout(),
            )
            addView(BusTheme.gap(this@MainActivity, 26))
            addView(actions, blockLayout())
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

    private fun blockLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun actionButtonLayout(isFirst: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            if (isFirst) {
                marginEnd = BusTheme.dp(this@MainActivity, 5)
            } else {
                marginStart = BusTheme.dp(this@MainActivity, 5)
            }
        }

    private fun requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 20)
        }
    }

    private fun savedToken(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TOKEN, "").orEmpty()

    private fun handleHubEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> updateLinkState(event.state)
            is BusEvent.Error -> appendLog("hub-ui: ${event.message}")
            is BusEvent.Message -> Unit
        }
    }

    private fun updateLinkState(state: Int) {
        val cxrUp = state and LINK_CXR_CONTROL_UP != 0
        val sppUp = state and LINK_SPP_DATA_UP != 0
        val bonded = state and LINK_GLASS_BONDED != 0
        cxrTile.set(cxrUp, if (cxrUp) "up" else "down")
        sppTile.set(sppUp, if (sppUp) "up" else "down")
        hiRokidTile.set(bonded, if (bonded) "bonded" else "away")
        when {
            cxrUp && sppUp -> {
                heroView.text = "Online"
                heroView.setTextColor(BusTheme.phosphor)
            }
            cxrUp || sppUp -> {
                heroView.text = "Partial"
                heroView.setTextColor(BusTheme.text)
            }
            else -> {
                heroView.text = "Offline"
                heroView.setTextColor(BusTheme.muted)
            }
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
