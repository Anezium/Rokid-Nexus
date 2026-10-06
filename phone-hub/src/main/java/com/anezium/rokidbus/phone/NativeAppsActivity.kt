package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.UUID

/** Phone-side shell for discovering and controlling ordinary Android apps on the glasses. */
class NativeAppsActivity : Activity() {
    private lateinit var list: LinearLayout
    private var state: NativeAppsUiState = NativeAppsUiState.Loading
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val next = intent?.let(NativeAppsPhoneContract::parseState) ?: return
            state = next
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        render()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        registerStateReceiver()
        requestApps()
    }

    override fun onStop() {
        unregisterStateReceiver()
        super.onStop()
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(NativeAppsPhoneContract.ACTION_STATE)
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            INTERNAL_CORE_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        list = NexusUi.contentColumn(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        list.addView(
            NexusUi.cardBody(this, getString(R.string.native_apps_intro)),
            NexusUi.block(),
        )
        list.addView(BusTheme.gap(this, 22))
        addStateCard("YouTube on glasses", "Install MicroG and patched YouTube, then connect your account using the phone keyboard.",
            "Set up YouTube" to { startActivity(Intent(this, YoutubeSetupActivity::class.java)) })
        list.addView(BusTheme.gap(this, 22))
        list.addView(NexusUi.sectionRow(this, getString(R.string.native_apps_section)), NexusUi.block())
        list.addView(BusTheme.gap(this, 10))
        when (val current = state) {
            NativeAppsUiState.Loading -> addStateCard(
                getString(R.string.native_apps_loading),
                getString(R.string.native_apps_loading_body),
            )
            NativeAppsUiState.Empty -> addStateCard(
                getString(R.string.native_apps_empty),
                getString(R.string.native_apps_empty_body),
                getString(R.string.native_apps_refresh) to ::requestApps,
            )
            is NativeAppsUiState.Error -> addStateCard(
                getString(R.string.native_apps_error),
                current.message,
                getString(R.string.native_apps_retry) to ::requestApps,
            )
            is NativeAppsUiState.Content -> current.apps.forEachIndexed { index, app ->
                if (index > 0) list.addView(BusTheme.gap(this, 8))
                list.addView(appRow(app), NexusUi.block())
            }
        }
    }

    private fun addStateCard(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
        list.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@NativeAppsActivity, title), NexusUi.block())
                addView(BusTheme.gap(this@NativeAppsActivity, 7))
                addView(NexusUi.cardBody(this@NativeAppsActivity, body), NexusUi.block())
                action?.let { (label, callback) ->
                    addView(BusTheme.gap(this@NativeAppsActivity, 12))
                    addView(
                        NexusUi.outlinePillButton(this@NativeAppsActivity, label).apply {
                            setOnClickListener { callback() }
                        },
                        NexusUi.block(),
                    )
                }
            },
            NexusUi.block(),
        )
    }

    private fun appRow(app: NativeGlassesApp): LinearLayout = NexusUi.card(this).apply {
        addView(
            LinearLayout(this@NativeAppsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(NexusUi.iconTile(this@NativeAppsActivity, app.name.take(1).uppercase()))
                addView(
                    LinearLayout(this@NativeAppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(NexusUi.rowTitle(this@NativeAppsActivity, app.name))
                        if (app.detail.isNotBlank()) {
                            addView(BusTheme.gap(this@NativeAppsActivity, 4))
                            addView(
                                NexusUi.rowSub(this@NativeAppsActivity, app.detail).apply {
                                    maxLines = 2
                                },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@NativeAppsActivity, 12)
                    },
                )
                actionButton(app)?.let { addView(it) }
            },
            NexusUi.block(),
        )
    }

    private fun actionButton(app: NativeGlassesApp) = when (app.action) {
        NativeAppAction.OPEN -> NexusUi.textButton(this, getString(R.string.native_apps_open)).apply {
            setOnClickListener {
                sendBroadcast(
                    NativeAppsPhoneContract.open(
                        this@NativeAppsActivity,
                        UUID.randomUUID().toString(),
                        app.id,
                    ),
                )
            }
        }
        NativeAppAction.INSTALL -> NexusUi.textButton(this, getString(R.string.native_apps_install)).apply {
            setOnClickListener {
                sendBroadcast(
                    NativeAppsPhoneContract.install(
                        this@NativeAppsActivity,
                        UUID.randomUUID().toString(),
                        app.id,
                    ),
                )
            }
        }
        NativeAppAction.NONE -> null
    }

    private fun requestApps() {
        state = NativeAppsUiState.Loading
        render()
        sendBroadcast(
            NativeAppsPhoneContract.requestList(this, UUID.randomUUID().toString()),
        )
    }

    private fun titleHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(this@NativeAppsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    NexusUi.dp(this@NativeAppsActivity, 10),
                    NexusUi.dp(this@NativeAppsActivity, 12),
                    NexusUi.dp(this@NativeAppsActivity, 22),
                    NexusUi.dp(this@NativeAppsActivity, 12),
                )
                addView(backButton())
                addView(
                    NexusUi.metaLabel(
                        this@NativeAppsActivity,
                        getString(R.string.native_apps_title),
                        NexusUi.INK,
                    ).apply {
                        textSize = 12f
                        letterSpacing = 0.2f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(NexusUi.wordmark(this@NativeAppsActivity, "NEXUS"))
            },
            NexusUi.block(),
        )
        addView(NexusUi.divider(this@NativeAppsActivity))
    }

    private fun backButton(): TextView = TextView(this).apply {
        text = "\u2039"
        textSize = 26f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(NexusUi.INK)
        background = NexusUi.pressed(this@NativeAppsActivity, Color.TRANSPARENT, 22)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.native_apps_back)
        setOnClickListener { finish() }
        layoutParams = LinearLayout.LayoutParams(
            NexusUi.dp(this@NativeAppsActivity, 44),
            NexusUi.dp(this@NativeAppsActivity, 44),
        )
    }
}
