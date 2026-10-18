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
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.ForegroundAmbientMode
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
        list.addView(NexusUi.sectionRow(this, getString(R.string.native_apps_section)), NexusUi.block())
        list.addView(BusTheme.gap(this, 6))
        list.addView(
            NexusUi.rowSub(this, getString(R.string.native_apps_patcher_hint)).apply { maxLines = 3 },
            NexusUi.block(),
        )
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
        addView(BusTheme.gap(this@NativeAppsActivity, 10))
        // Over a native app an activity is never more than its text line, whatever size the
        // display page gives it at home; the explanation must not promise the map here. The page
        // scrolls, so the note takes the lines it needs rather than an ellipsis.
        addView(
            NexusUi.rowSub(
                this@NativeAppsActivity,
                "While this app is in front: Activities keeps each as a line of text, never a map. " +
                    "Alerts only shows a brief flare on an important update. Nothing hides them.",
            ).apply {
                maxLines = Int.MAX_VALUE
                ellipsize = null
            },
        )
        addView(BusTheme.gap(this@NativeAppsActivity, 4))
        addView(ambientModeRow(app))
    }

    /**
     * What ambient islands may show while this app is in front on the glasses. An app never set
     * shows signals only, so choosing that stores nothing. The labels say what the wearer sees,
     * not the policy's names for it.
     */
    private fun ambientModeRow(app: NativeGlassesApp): View {
        val store = PhoneAmbientPreferencesStore.shared(this)
        val options = listOf(
            ForegroundAmbientMode.RESIDENTS to "Activities",
            ForegroundAmbientMode.SIGNALS to "Alerts only",
            ForegroundAmbientMode.NOTHING to "Nothing",
        )
        val current = store.modeFor(app.id) ?: AmbientPreferencesContract.defaultMode(isHome = false)
        return ambientChoiceRow(this, options, current) { mode ->
            val stored = mode.takeUnless { it == AmbientPreferencesContract.defaultMode(isHome = false) }
            if (store.setMode(app.id, stored)) BusHubService.onAmbientPolicyChanged()
        }
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
