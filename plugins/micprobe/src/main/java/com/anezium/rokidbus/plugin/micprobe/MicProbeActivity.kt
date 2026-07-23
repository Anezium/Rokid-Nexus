package com.anezium.rokidbus.plugin.micprobe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MicProbeActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(MicProbePluginService.EXTRA_STATUS)?.let(::appendStatus)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            logView.post(::requestCapture)
        }
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
        loadPersistedStatus()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildUi() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val button = Button(this).apply {
            text = "Capture 10 s"
            setOnClickListener { requestCapture() }
        }
        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13f
        }
        logScroll = ScrollView(this).apply {
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    logScroll,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            },
        )
    }

    private fun requestCapture() {
        Log.i(TAG, "Capture requested")
        appendStatus("Capture requested")
        runCatching {
            startService(
                Intent(this, MicProbePluginService::class.java)
                    .setAction(MicProbePluginService.ACTION_CAPTURE),
            )
        }.onFailure { failure ->
            val message = "Could not start probe service: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(TAG, message, failure)
            appendStatus(message)
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(MicProbePluginService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun loadPersistedStatus() {
        val file = File(filesDir, MicProbePluginService.STATUS_FILE_NAME)
        logView.text = runCatching {
            if (file.isFile) file.readText() else ""
        }.getOrElse { "Could not read status log: ${it.message}\n" }
        scrollToBottom()
    }

    private fun appendStatus(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val TAG = "MicProbe"
        const val EXTRA_AUTOSTART = "autostart"
    }
}
