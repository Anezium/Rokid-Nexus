package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.LinkStateBits

private const val SETTINGS_TAG = "RokidNexusSettings"

class SettingsActivity : Activity() {
    private val developerModeStore by lazy { DeveloperModeStore(this) }
    private lateinit var cxrValue: TextView
    private lateinit var sppValue: TextView
    private lateinit var bondValue: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var hubUiClient: BusClient? = null
    private var lastLinkState = 0

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
            clientId = "settings-ui",
            pathPrefixes = emptyList(),
        ) { event -> handleHubEvent(event) }.also { it.connect() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NexusPhoneState.ACTION_LOG)
        ContextCompat.registerReceiver(
            this,
            logReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        if (requestCode != NexusPhoneState.AUTH_REQUEST) return
        when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(NexusPhoneState.PREF_TOKEN, result.token)
                    .apply()
                logLine("Hi Rokid authorization succeeded")
                BusHubService.startWithToken(this, result.token)
            }
            is CxrLAuth.Result.Fail -> logLine("Authorization failed: ${result.reason}")
            CxrLAuth.Result.Cancel -> logLine("Authorization canceled")
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        cxrValue = NexusUi.rowValue(this)
        sppValue = NexusUi.rowValue(this)
        bondValue = NexusUi.rowValue(this)
        logView = TextView(this)
        logScroll = NexusUi.console(this, logView)

        val content = NexusUi.contentColumn(this).apply {
            if (NexusPhoneState.updateAvailable) {
                addView(
                    NexusUi.updateBanner(this@SettingsActivity, NexusPhoneState.UPDATE_VERSION_LABEL) {
                        Toast.makeText(this@SettingsActivity, "Coming soon", Toast.LENGTH_SHORT).show()
                    },
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@SettingsActivity, 22))
            }
            addView(NexusUi.sectionRow(this@SettingsActivity, "Connection"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(connectionCard(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(authorizeRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(
                actionRow(
                    title = "Plugin access",
                    value = "Manage",
                    danger = false,
                ) {
                    startActivity(Intent(this@SettingsActivity, PluginPermissionsActivity::class.java))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SettingsActivity, "Advanced"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(developerModeRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            if (developerModeStore.isEnabled()) {
                addView(
                    actionRow(
                        title = "Bus inspector",
                        value = "Open",
                        danger = false,
                    ) {
                        startActivity(Intent(this@SettingsActivity, BusInspectorActivity::class.java))
                    },
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@SettingsActivity, 10))
            }
            addView(
                logScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    NexusUi.dp(this@SettingsActivity, 190),
                ),
            )
            addView(BusTheme.gap(this@SettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SettingsActivity, "About"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(aboutCard(), NexusUi.block())
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(titleHeader("Settings"), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        renderLinkState()
        setContentView(root)
    }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@SettingsActivity, 10),
                        NexusUi.dp(this@SettingsActivity, 12),
                        NexusUi.dp(this@SettingsActivity, 22),
                        NexusUi.dp(this@SettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@SettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(line())
        }

    private fun backButton(): TextView =
        TextView(this).apply {
            text = "\u2039"
            textSize = 26f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            background = NexusUi.pressed(this@SettingsActivity, android.graphics.Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SettingsActivity, 44),
                NexusUi.dp(this@SettingsActivity, 44),
            )
        }

    private fun line(): View =
        View(this).apply {
            setBackgroundColor(NexusUi.LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NexusUi.dp(this@SettingsActivity, 1),
            )
        }

    private fun connectionCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(detailRow("CXR-L control", cxrValue), NexusUi.block())
            addView(NexusUi.divider(this@SettingsActivity))
            addView(detailRow("SPP data", sppValue), NexusUi.block())
            addView(NexusUi.divider(this@SettingsActivity))
            addView(detailRow("Hi Rokid bond", bondValue), NexusUi.block())
        }

    private fun authorizeRow(): LinearLayout =
        actionRow(
            title = "Authorize Hi Rokid",
            value = "Open",
            danger = false,
        ) { startAuthorization() }

    private fun aboutCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(detailRow("Rokid Nexus", NexusUi.rowValue(this@SettingsActivity).apply {
                text = versionName()
                setTextColor(NexusUi.INK3)
            }), NexusUi.block())
            addView(NexusUi.divider(this@SettingsActivity))
            addView(
                plainActionRow(
                    title = "Check for updates",
                    value = if (NexusPhoneState.updateAvailable) "Install" else "Check",
                    danger = false,
                ) {
                    Toast.makeText(this@SettingsActivity, "Coming soon", Toast.LENGTH_SHORT).show()
                },
                NexusUi.block(),
            )
        }

    private fun detailRow(label: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.rowLabel(this@SettingsActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(value)
        }

    private fun plainActionRow(
        title: String,
        value: String,
        danger: Boolean,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@SettingsActivity, android.graphics.Color.TRANSPARENT, 12)
            setOnClickListener { onClick() }
            addView(
                NexusUi.rowLabel(this@SettingsActivity, title).apply {
                    setTextColor(if (danger) NexusUi.DANGER else NexusUi.INK2)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.metaLabel(
                    this@SettingsActivity,
                    "$value \u203A",
                    if (danger) NexusUi.DANGER else NexusUi.GREEN,
                ),
            )
        }

    private fun developerModeRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.bordered(this@SettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 10),
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 10),
            )
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@SettingsActivity, "Developer mode"))
                    addView(BusTheme.gap(this@SettingsActivity, 3))
                    addView(
                        NexusUi.rowSub(this@SettingsActivity, "Sideload alerts, DEV badges, bus inspector"),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                Switch(this@SettingsActivity).apply {
                    isChecked = developerModeStore.isEnabled()
                    thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                    )
                    trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(NexusUi.GREEN_DIM, NexusUi.LINE),
                    )
                    setOnCheckedChangeListener { _, enabled ->
                        developerModeStore.setEnabled(enabled)
                        buildUi()
                    }
                },
            )
        }

    private fun actionRow(
        title: String,
        value: String,
        danger: Boolean,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.pressedBordered(this@SettingsActivity, NexusUi.PANEL, 15)
            setPadding(
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                NexusUi.rowTitle(this@SettingsActivity, title).apply {
                    setTextColor(if (danger) NexusUi.DANGER else NexusUi.INK)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.metaLabel(
                    this@SettingsActivity,
                    "$value \u203A",
                    if (danger) NexusUi.DANGER else NexusUi.GREEN,
                ),
            )
        }

    private fun startAuthorization() {
        when (val result = CxrLAuth.requestAuthorization(this, NexusPhoneState.AUTH_REQUEST)) {
            null -> logLine("Hi Rokid authorization opened")
            is CxrLAuth.Result.Fail -> logLine("Authorization failed: ${result.reason}")
            CxrLAuth.Result.Cancel -> logLine("Authorization canceled")
            is CxrLAuth.Result.Success -> Unit
        }
    }

    private fun handleHubEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> {
                lastLinkState = event.state
                renderLinkState()
            }
            is BusEvent.Error -> logLine("settings-ui: ${event.message}")
            is BusEvent.Message -> Unit
            is BusEvent.Binary -> Unit
        }
    }

    private fun renderLinkState() {
        val cxrUp = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
        val sppUp = lastLinkState and LinkStateBits.SPP_DATA_UP != 0
        val bonded = lastLinkState and LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED != 0
        setDetail(cxrValue, cxrUp, if (cxrUp) "Up" else "Down")
        setDetail(sppValue, sppUp, if (sppUp) "Up" else "Down")
        setDetail(bondValue, bonded, if (bonded) "Bonded" else "Not bonded")
    }

    private fun setDetail(view: TextView, up: Boolean, label: String) {
        view.text = label
        view.setTextColor(if (up) NexusUi.GREEN else NexusUi.INK3)
    }

    private fun versionName(): String =
        runCatching {
            val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")

    private fun logLine(line: String) {
        if (line.isBlank()) return
        Log.i(SETTINGS_TAG, line)
        appendLog(line)
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
