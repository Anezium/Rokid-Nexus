package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.BusConstants

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout
    private var launcherEntries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedIndex = 0
    private var unsubscribeLauncher: (() -> Unit)? = null
    private var lastNavAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BusTheme.glassesBg
        window.navigationBarColor = BusTheme.glassesBg
        buildUi()
        requestBluetoothConnectIfNeeded()
        GlassesHub.start(applicationContext)
        unsubscribeLauncher = GlassesHub.observeLauncher { entries ->
            launcherEntries = entries
            selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            renderLauncher()
        }
        log("Launcher activity opened")
    }

    override fun onDestroy() {
        unsubscribeLauncher?.invoke()
        unsubscribeLauncher = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            183,
            -> {
                moveSelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            184,
            -> {
                moveSelection(-1)
                true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                openSelected()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun buildUi() {
        statusView = text(13f, BusTheme.muted).apply {
            text = "SPP ${BusConstants.SERVICE_NAME}\n${BusConstants.SPP_UUID_STRING}\nAccessibility keeps the hub alive."
            gravity = Gravity.CENTER_HORIZONTAL
        }
        emptyView = text(17f, BusTheme.dim).apply {
            text = "No phone plugins synced"
            gravity = Gravity.CENTER
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(22), dp(20), dp(22), dp(16))
            addView(text(12f, BusTheme.phosphor, bold = true).apply {
                text = "ROKID NEXUS"
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(gap(20))
            addView(text(24f, BusTheme.text, bold = true).apply {
                text = "Launcher"
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(gap(10))
            addView(statusView, matchWrap())
            addView(gap(26))
            addView(text(10.5f, BusTheme.dim).apply {
                text = "PLUGINS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(10))
            addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        renderLauncher()
    }

    private fun renderLauncher() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        if (launcherEntries.isEmpty()) {
            listContainer.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        launcherEntries.forEachIndexed { index, entry ->
            listContainer.addView(pluginRow(entry, selected = index == selectedIndex), matchWrap().apply {
                topMargin = if (index == 0) 0 else dp(8)
            })
        }
    }

    private fun pluginRow(
        entry: GlassesHub.LauncherEntry,
        selected: Boolean,
    ): TextView =
        text(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
            text = if (selected) "> ${entry.displayName}" else "  ${entry.displayName}"
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = outline(selected)
        }

    private fun moveSelection(delta: Int) {
        if (launcherEntries.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavAtMs < 240L) return
        lastNavAtMs = now
        selectedIndex = (selectedIndex + delta + launcherEntries.size) % launcherEntries.size
        renderLauncher()
    }

    private fun openSelected() {
        val entry = launcherEntries.getOrNull(selectedIndex) ?: return
        val result = GlassesHub.openLauncherEntry(entry.id)
        log("Launcher open result: $result")
    }

    private fun requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun gap(value: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun outline(selected: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(value: Int): Int =
        BusTheme.dp(this, value)
}
