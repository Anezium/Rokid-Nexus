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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val AUTH_REQUEST = 42
private const val PREFS = "rokidbus_phone"
private const val PREF_TOKEN = "cxrl_token"

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appendLog(intent.getStringExtra("line").orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
        status = TextView(this).apply {
            text = "RokidBus Phone Hub"
            textSize = 20f
            setTextColor(0xFFEAF6EF.toInt())
            setPadding(24, 24, 24, 12)
        }
        logView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFEAF6EF.toInt())
            setPadding(24, 12, 24, 24)
        }

        val auth = Button(this).apply {
            text = "Authorize with Hi Rokid"
            setOnClickListener {
                when (val result = CxrLAuth.requestAuthorization(this@MainActivity, AUTH_REQUEST)) {
                    null -> appendLog("Hi Rokid authorization opened")
                    is CxrLAuth.Result.Fail -> appendLog("Authorization failed: ${result.reason}")
                    CxrLAuth.Result.Cancel -> appendLog("Authorization canceled")
                    is CxrLAuth.Result.Success -> Unit
                }
            }
        }
        val start = Button(this).apply {
            text = "Start Hub"
            setOnClickListener {
                appendLog("Starting hub")
                BusHubService.start(this@MainActivity)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF030C06.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            addView(status)
            addView(auth, buttonLayout())
            addView(start, buttonLayout())
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

    private fun buttonLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(24, 4, 24, 4) }

    private fun requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 20)
        }
    }

    private fun savedToken(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TOKEN, "").orEmpty()

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        status.text = line
        logView.append(line + "\n")
    }
}
