package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.phone.speech.HubSecretStore
import com.anezium.rokidbus.phone.speech.SpeechReadiness
import com.anezium.rokidbus.phone.speech.SpeechSettingsStore
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesAccessibilityCheckContract
import com.anezium.rokidbus.shared.GlassesRepairContract
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONObject

private const val SETTINGS_TAG = "RokidNexusSettings"

/**
 * A repair with the radio down spends up to 45 s driving Settings, then arms over the network it
 * just gained, then hands the radio back before answering. The budget covers that whole worst
 * case, so a timeout genuinely means the glasses went silent.
 */
private const val REPAIR_REQUEST_TIMEOUT_MS = 150_000L
private const val REPAIR_STATUS_LINGER_MS = 12_000L

class SettingsActivity : Activity() {
    private val developerModeStore by lazy { DeveloperModeStore(this) }
    private val glassesControlsVisibility by lazy { GlassesControlsVisibilityStore(this) }
    private lateinit var updateSection: LinearLayout
    private lateinit var updateCheckValue: TextView
    private lateinit var cxrValue: TextView
    private lateinit var sppValue: TextView
    private lateinit var bondValue: TextView
    private var speechValue: TextView? = null
    private var voiceValue: TextView? = null
    private var displayValue: TextView? = null
    private var repairValue: TextView? = null
    private var repairStatus: TextView? = null
    private var repairInFlight = false
    private val repairStatusClear = Runnable { repairStatus?.visibility = View.GONE }
    private var accessibilityCheckValue: TextView? = null
    private var accessibilityCheckStatus: TextView? = null
    private var accessibilityCheckInFlight = false
    private val accessibilityCheckStatusClear =
        Runnable { accessibilityCheckStatus?.visibility = View.GONE }
    private var hubUiClient: BusClient? = null
    private var lastLinkState = 0
    // State updates can land on a Binder thread, and touching the hierarchy from there leaves the
    // screen half-torn-down with the exception swallowed by the binder stub. Same reason the home
    // screen renders through a dispatcher.
    private val renderDispatcher by lazy {
        PhoneHomeRenderDispatcher(
            isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            postToMain = { action -> runOnUiThread { action() } },
            render = { if (!isDestroyed && !isFinishing) renderUpdateUi() },
        )
    }
    private val updateStateListener: () -> Unit = renderDispatcher::requestRender

    // The console moved to its own screen, but the same broadcast still carries the glasses
    // app install state this screen renders in its update section.
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            NexusPhoneState.updateGlassesAppInstallState(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NexusPhoneState.restore(this)
        buildUi()
        hubUiClient = BusClient(
            context = applicationContext,
            clientId = "settings-ui",
            // The repair reply is consumed by the request/reply correlation, but delivery still
            // requires the prefix to be registered.
            pathPrefixes = listOf(
                BusPaths.GLASSES_REPAIR_REPLY,
                BusPaths.GLASSES_ACCESSIBILITY_CHECK_REPLY,
            ),
        ) { event -> handleHubEvent(event) }.also { it.connect() }
    }

    override fun onStart() {
        super.onStart()
        NexusPhoneState.addUpdateListener(updateStateListener)
        updateStateListener()
        val filter = IntentFilter(NexusPhoneState.ACTION_LOG)
        ContextCompat.registerReceiver(
            this,
            logReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        NexusPhoneState.removeUpdateListener(updateStateListener)
        runCatching { unregisterReceiver(logReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // These rows summarize state owned by sub-screens, so they can go stale while
        // this activity sits behind one.
        renderSpeechRow()
        renderVoiceRow()
        renderDisplayRow()
        resumeRecoveredNexusUpdateInstall()
        if (lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0) {
            BusHubService.queryGlassesApp(this)
        }
        renderUpdateUi()
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
        updateSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(updateSection, NexusUi.block())
            addView(NexusUi.sectionRow(this@SettingsActivity, "Connection"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(connectionCard(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(authorizeRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SettingsActivity, "Glasses"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(displayRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(speechRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(voiceRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(
                controlVisibilityRow(
                    title = "Keyboard & remote",
                    subtitle = "Show it on the home screen",
                    checked = glassesControlsVisibility.isRemoteVisible(),
                    onChanged = glassesControlsVisibility::setRemoteVisible,
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(
                controlVisibilityRow(
                    title = "Glasses apps",
                    subtitle = "Show it on the home screen",
                    checked = glassesControlsVisibility.isNativeAppsVisible(),
                    onChanged = glassesControlsVisibility::setNativeAppsVisible,
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SettingsActivity, "Plugins"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
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
            addView(NexusUi.sectionRow(this@SettingsActivity, "Maintenance"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(glassesRepairToggleRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(glassesRepairNowRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(accessibilityCheckRow(), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 10))
            addView(
                actionRow(
                    title = "Console",
                    value = "Open",
                    danger = false,
                ) {
                    startActivity(Intent(this@SettingsActivity, ConsoleActivity::class.java))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SettingsActivity, "Advanced"), NexusUi.block())
            addView(BusTheme.gap(this@SettingsActivity, 12))
            addView(developerModeRow(), NexusUi.block())
            if (developerModeStore.isEnabled()) {
                addView(BusTheme.gap(this@SettingsActivity, 10))
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
                addView(
                    actionRow(
                        title = "Manual glasses setup",
                        value = "Open",
                        danger = false,
                    ) {
                        startActivity(
                            Intent(this@SettingsActivity, GlassesManualSetupActivity::class.java),
                        )
                    },
                    NexusUi.block(),
                )
            }
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
        renderUpdateUi()
        setContentView(root)
    }

    private fun renderUpdateUi() {
        if (::updateSection.isInitialized) {
            updateSection.removeAllViews()
            var hasContent = false

            fun addBlock(view: View) {
                if (hasContent) updateSection.addView(BusTheme.gap(this, 10))
                updateSection.addView(view, NexusUi.block())
                hasContent = true
            }

            if (NexusPhoneState.updateAvailable) {
                addBlock(
                    NexusUi.updateBanner(
                        context = this,
                        versionLabel = NexusPhoneState.updateVersionLabel,
                        actionLabel = NexusPhoneState.updateActionLabel(),
                        actionEnabled = NexusPhoneState.updateActionEnabled(),
                        onDetails = { startActivity(WhatsNewActivity.intent(this)) },
                        preview = NexusPhoneState.availableRelease?.notes?.let {
                            ReleaseNotesRenderer.render(this, it)
                        },
                    ) { NexusUpdateManager.performUpdateAction(applicationContext) },
                )
            }
            val glassesUpdateLabel = NexusPhoneState.glassesUpdateVersionLabel()
            if (glassesUpdateLabel != null) {
                val cxrReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
                addBlock(
                    NexusUi.updateBanner(
                        context = this,
                        versionLabel = glassesUpdateLabel,
                        actionLabel = NexusPhoneState.glassesUpdateActionLabel(),
                        actionEnabled = cxrReady && NexusPhoneState.glassesUpdateActionEnabled(),
                    ) { BusHubService.installGlassesApp(applicationContext) },
                )
            } else {
                NexusPhoneState.glassesInstalledStatusLabel()?.let { status ->
                    addBlock(NexusUi.sectionRow(this, "Glasses app", status))
                }
            }
            if (hasContent) updateSection.addView(BusTheme.gap(this, 22))
        }
        if (::updateCheckValue.isInitialized) {
            val label = when {
                NexusPhoneState.updateAvailable -> NexusPhoneState.updateActionLabel()
                NexusPhoneState.checkingForUpdate -> "Checking"
                else -> "Check"
            }
            updateCheckValue.text = "$label \u203A"
        }
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
            updateCheckValue = NexusUi.metaLabel(
                this@SettingsActivity,
                "Check \u203A",
                NexusUi.GREEN,
            )
            addView(
                plainActionRow(
                    title = "Check for updates",
                    danger = false,
                    valueView = updateCheckValue,
                    onClick = ::onUpdateRowClicked,
                ),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@SettingsActivity))
            addView(
                plainActionRow(
                    title = "What's new",
                    danger = false,
                    valueView = NexusUi.metaLabel(this@SettingsActivity, "›", NexusUi.INK3),
                    onClick = { startActivity(WhatsNewActivity.intent(this@SettingsActivity)) },
                ),
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
        danger: Boolean,
        valueView: TextView,
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
            valueView.setTextColor(if (danger) NexusUi.DANGER else NexusUi.GREEN)
            addView(valueView)
        }

    private fun onUpdateRowClicked() {
        if (NexusPhoneState.updateAvailable) {
            NexusUpdateManager.performUpdateAction(applicationContext)
            return
        }
        if (NexusPhoneState.checkingForUpdate) return
        NexusUpdateManager.checkForUpdates(applicationContext, force = true) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdates
            val message = when (result) {
                is NexusUpdateCheckResult.Available -> "${result.release.versionLabel} is available"
                is NexusUpdateCheckResult.Current -> "Rokid Nexus is up to date"
                is NexusUpdateCheckResult.Failure ->
                    "Could not check for updates: ${result.error.message ?: "Unknown error"}"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /** A built-in glasses control, and whether the home screen offers it. */
    private fun controlVisibilityRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout =
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
                    addView(NexusUi.rowTitle(this@SettingsActivity, title))
                    addView(BusTheme.gap(this@SettingsActivity, 3))
                    addView(NexusUi.rowSub(this@SettingsActivity, subtitle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.switch(this@SettingsActivity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, value -> onChanged(value) }
                },
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
                        NexusUi.rowSub(
                            this@SettingsActivity,
                            "Sideload alerts, manual setup, bus inspector",
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.switch(this@SettingsActivity).apply {
                    isChecked = developerModeStore.isEnabled()
                    setOnCheckedChangeListener { _, enabled ->
                        developerModeStore.setEnabled(enabled)
                        buildUi()
                    }
                },
            )
        }

    /** Navigation into the display sub-screen; the label mirrors the position mode. */
    private fun displayRow(): LinearLayout =
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
            setOnClickListener {
                startActivity(
                    Intent(this@SettingsActivity, GlassesDisplaySettingsActivity::class.java),
                )
            }
            addView(
                NexusUi.rowTitle(this@SettingsActivity, "Display"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            displayValue = NexusUi.metaLabel(this@SettingsActivity, "", NexusUi.GREEN)
            addView(displayValue)
            renderDisplayRow()
        }

    private fun renderDisplayRow() {
        val value = displayValue ?: return
        val auto = PhoneHudPositionStore(this).hudPositionAuto()
        value.text = if (auto) "AUTO ›" else "MANUAL ›"
    }

    private fun glassesRepairToggleRow(): LinearLayout =
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
            val store = GlassesRepairSettingsStore(this@SettingsActivity)
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@SettingsActivity, "Auto-repair at boot"))
                    addView(BusTheme.gap(this@SettingsActivity, 3))
                    addView(
                        NexusUi.rowSub(
                            this@SettingsActivity,
                            "Settings may open briefly on the glasses after a restart",
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.switch(this@SettingsActivity).apply {
                    isChecked = store.isAutoRepairEnabled()
                    setOnCheckedChangeListener { _, enabled ->
                        store.setAutoRepairEnabled(enabled)
                        BusHubService.onGlassesRepairSettingChanged()
                    }
                },
            )
        }

    private fun glassesRepairNowRow(): LinearLayout {
        val value = NexusUi.metaLabel(this, "Repair ›", NexusUi.GREEN)
        repairValue = value
        val status = NexusUi.rowSub(this, "").apply {
            visibility = View.GONE
            setPadding(0, NexusUi.dp(this@SettingsActivity, 6), 0, 0)
        }
        repairStatus = status
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.pressedBordered(this@SettingsActivity, NexusUi.PANEL, 15)
            setPadding(
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { startGlassesRepair() }
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(this@SettingsActivity, "Repair now"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(value)
                },
                NexusUi.block(),
            )
            addView(status, NexusUi.block())
        }
    }

    private fun startGlassesRepair() {
        if (repairInFlight) return
        val client = hubUiClient ?: return
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (lastLinkState and transportBits == 0) {
            showRepairStatus("The glasses are not connected.")
            return
        }
        repairInFlight = true
        repairValue?.text = "Working…"
        showRepairStatus("Asking the glasses to check their helper…", autoClear = false)
        client.request(
            BusPaths.GLASSES_REPAIR_REQUEST,
            GlassesRepairContract.requestToJson(),
            timeoutMs = REPAIR_REQUEST_TIMEOUT_MS,
        ) { result ->
            if (isDestroyed || isFinishing) return@request
            repairInFlight = false
            repairValue?.text = "Repair ›"
            val message = result.fold(
                onSuccess = { payload ->
                    repairStatusMessage(GlassesRepairContract.resultFromReply(payload))
                },
                onFailure = { "The glasses did not answer. Check the connection and try again." },
            )
            showRepairStatus(message)
        }
    }

    private fun repairStatusMessage(result: String?): String = when (result) {
        GlassesRepairContract.RESULT_REPAIRED ->
            "Repaired. The glasses have their helper back."
        GlassesRepairContract.RESULT_ALREADY_HEALTHY ->
            "Nothing to repair — the helper is already in place."
        GlassesRepairContract.RESULT_WIFI_UNAVAILABLE ->
            "Could not turn on the glasses' Wi-Fi. Wake the glasses and try again."
        GlassesRepairContract.RESULT_ARM_FAILED ->
            "The glasses could not restore the helper. Try again, or redo the glasses setup."
        GlassesRepairContract.RESULT_BUSY ->
            "The glasses are busy with another repair or with setup. Try again in a moment."
        else -> "The glasses sent an answer this version does not understand."
    }

    private fun showRepairStatus(message: String, autoClear: Boolean = true) {
        val status = repairStatus ?: return
        status.removeCallbacks(repairStatusClear)
        status.text = message
        status.visibility = View.VISIBLE
        if (autoClear) status.postDelayed(repairStatusClear, REPAIR_STATUS_LINGER_MS)
    }

    private fun accessibilityCheckRow(): LinearLayout {
        val value = NexusUi.metaLabel(this, "Check ›", NexusUi.GREEN)
        accessibilityCheckValue = value
        val status = NexusUi.rowSub(this, "").apply {
            visibility = View.GONE
            setPadding(0, NexusUi.dp(this@SettingsActivity, 6), 0, 0)
        }
        accessibilityCheckStatus = status
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.pressedBordered(this@SettingsActivity, NexusUi.PANEL, 15)
            setPadding(
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
                NexusUi.dp(this@SettingsActivity, 15),
                NexusUi.dp(this@SettingsActivity, 14),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { startAccessibilityCheck() }
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(this@SettingsActivity, "Check accessibility services"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(value)
                },
                NexusUi.block(),
            )
            addView(
                NexusUi.rowSub(
                    this@SettingsActivity,
                    "Warn if another app's accessibility service is interfering with Nexus",
                ),
                NexusUi.block(),
            )
            addView(status, NexusUi.block())
        }
    }

    private fun startAccessibilityCheck() {
        if (accessibilityCheckInFlight) return
        val client = hubUiClient ?: return
        val transportBits = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        if (lastLinkState and transportBits == 0) {
            showAccessibilityCheckStatus("The glasses are not connected.")
            return
        }
        accessibilityCheckInFlight = true
        accessibilityCheckValue?.text = "Working…"
        showAccessibilityCheckStatus("Asking the glasses which services are enabled…", autoClear = false)
        client.request(
            BusPaths.GLASSES_ACCESSIBILITY_CHECK_REQUEST,
            GlassesAccessibilityCheckContract.requestToJson(),
            timeoutMs = REPAIR_REQUEST_TIMEOUT_MS,
        ) { result ->
            if (isDestroyed || isFinishing) return@request
            accessibilityCheckInFlight = false
            accessibilityCheckValue?.text = "Check ›"
            val foreign = result.fold(
                onSuccess = { payload -> GlassesAccessibilityCheckContract.foreignServicesFromReply(payload) },
                onFailure = { null },
            )
            showAccessibilityCheckStatus(accessibilityCheckMessage(result.isSuccess, foreign))
            if (!foreign.isNullOrEmpty()) offerOpenGlassesAccessibilitySettings()
        }
    }

    private fun accessibilityCheckMessage(succeeded: Boolean, foreign: List<String>?): String = when {
        !succeeded -> "The glasses did not answer. Check the connection and try again."
        foreign == null -> "The glasses sent an answer this version does not understand."
        foreign.isEmpty() -> "Nothing else is enabled — only Nexus's own service."
        else -> "Found ${foreign.size} other accessibility service(s) enabled, which can interfere " +
            "with Nexus's input handling:\n" + foreign.joinToString("\n") { "• $it" }
    }

    private fun offerOpenGlassesAccessibilitySettings() {
        val status = accessibilityCheckStatus ?: return
        status.setOnClickListener {
            hubUiClient?.send(
                BusPaths.GLASSES_SELFARM_MANUAL,
                JSONObject().put("version", 1).put("action", "open_accessibility_settings"),
            )
        }
        status.append("\n\nTap here to open Accessibility settings on the glasses.")
    }

    private fun showAccessibilityCheckStatus(message: String, autoClear: Boolean = true) {
        val status = accessibilityCheckStatus ?: return
        status.removeCallbacks(accessibilityCheckStatusClear)
        status.setOnClickListener(null)
        status.text = message
        status.visibility = View.VISIBLE
        if (autoClear) status.postDelayed(accessibilityCheckStatusClear, REPAIR_STATUS_LINGER_MS)
    }

    private fun speechRow(): LinearLayout =
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
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, SpeechSettingsActivity::class.java))
            }
            addView(
                NexusUi.rowTitle(this@SettingsActivity, "Speech"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            speechValue = NexusUi.metaLabel(this@SettingsActivity, "", NexusUi.GREEN)
            addView(speechValue)
            renderSpeechRow()
        }

    /** Speech is how the glasses listen; this is how they answer. */
    private fun voiceRow(): LinearLayout =
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
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, VoiceSettingsActivity::class.java))
            }
            addView(
                NexusUi.rowTitle(this@SettingsActivity, "Voice"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            voiceValue = NexusUi.metaLabel(this@SettingsActivity, "", NexusUi.GREEN)
            addView(voiceValue)
            renderVoiceRow()
        }

    private fun renderVoiceRow() {
        val value = voiceValue ?: return
        val rate = PhoneTtsSettingsStore(this).speechRate()
        val label = if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"
        value.text = "$label ›"
        value.setTextColor(NexusUi.GREEN)
    }

    private fun renderSpeechRow() {
        val value = speechValue ?: return
        val ready = SpeechSettingsStore(this).readiness(HubSecretStore(this)) == SpeechReadiness.READY
        value.text = if (ready) "READY ›" else "SET UP ›"
        // Every other row here opens in the same bright green, so dimming this one made a
        // working screen look disabled. Amber is what the rest of the app uses to mean
        // "this still wants you".
        value.setTextColor(if (ready) NexusUi.GREEN else NexusUi.AMBER)
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
                val cxrWasReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
                lastLinkState = event.state
                renderLinkState()
                renderUpdateUi()
                val cxrIsReady = lastLinkState and LinkStateBits.CXR_CONTROL_UP != 0
                if (cxrIsReady && !cxrWasReady) BusHubService.queryGlassesApp(this)
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
        NexusPhoneState.recordLogLine(line)
        sendBroadcast(
            Intent(NexusPhoneState.ACTION_LOG)
                .setPackage(packageName)
                .putExtra("line", line),
        )
    }
}
