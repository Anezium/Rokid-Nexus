package com.anezium.rokidbus.plugin.relay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.Executors

class RelayAppsActivity : Activity() {
    private enum class LoadState { LOADING, READY, FAILED }

    private val settings by lazy { RelaySettings(this) }
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var rows: LinearLayout
    private lateinit var status: LinearLayout
    private var apps = emptyList<RelaySourceApp>()
    private var query = ""
    private var loadGeneration = 0
    private var loadState = LoadState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        query = savedInstanceState?.getString("query").orEmpty()
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = NexusUi.contentColumn(this).apply {
            addView(status, NexusUi.block())
            addView(NexusUi.card(this@RelayAppsActivity).apply {
                addView(NexusUi.cardTitle(this@RelayAppsActivity, "Choose what reaches your glasses"))
                addView(NexusUi.cardBody(this@RelayAppsActivity,
                    "Apps start on Messages only: notifications with a Reply action. Choose All notifications to include alerts you can only read."))
                addView(BusTheme.gap(this@RelayAppsActivity, 8))
                addView(NexusUi.rowSub(this@RelayAppsActivity,
                    "Extra alerts skip ongoing notifications and group summaries. Changes apply to the band and inbox; your phone notifications stay unchanged."))
            }, NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 16))
            addView(NexusUi.field(this@RelayAppsActivity, "Search apps").apply {
                setText(query)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        query = s?.toString().orEmpty()
                        renderRows()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }, NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 12))
            addView(rows, NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 12))
            addView(NexusUi.rowSub(this@RelayAppsActivity,
                "Installed apps are listed before they send anything. Other apps appear after Relay observes a notification."))
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.backButton(this@RelayAppsActivity) { finish() }, NexusUi.block())
            addView(NexusUi.pluginHeader(this@RelayAppsActivity, R.drawable.nexus_glyph_relay,
                "Source apps", "Which apps reach the glasses"), NexusUi.block())
            addView(NexusUi.screen(this@RelayAppsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration += 1
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun loadApps() {
        val generation = ++loadGeneration
        loadState = LoadState.LOADING
        renderRows()
        loader.execute {
            val result = runCatching { RelaySourceApps.list(applicationContext, settings) }
            runOnUiThread {
                if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { apps = it; loadState = LoadState.READY; renderRows() },
                    onFailure = {
                        loadState = LoadState.FAILED
                        renderRows()
                    },
                )
            }
        }
    }

    private fun renderStatus() {
        status.removeAllViews()
        val access = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').mapNotNull(ComponentName::unflattenFromString).any { it.packageName == packageName }
        if (!settings.enabled() || !access) {
            status.addView(NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@RelayAppsActivity, if (!settings.enabled()) "Relay is off" else "Notification access is off"))
                addView(NexusUi.cardBody(this@RelayAppsActivity, "You can choose apps now. Nothing is forwarded until Relay and notification access are on."))
                if (!access) addView(NexusUi.outlinePillButton(this@RelayAppsActivity, "Open notification access").apply {
                    setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                })
            }, NexusUi.block())
            status.addView(BusTheme.gap(this, 12))
        }
    }

    private fun renderRows() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        if (loadState == LoadState.LOADING) {
            rows.addView(NexusUi.cardBody(this, "Loading apps…"))
            return
        }
        if (loadState == LoadState.FAILED) {
            rows.addView(NexusUi.cardBody(this, "Could not load apps. Your choices are unchanged."))
            rows.addView(NexusUi.outlinePillButton(this, "Retry").apply { setOnClickListener { loadApps() } })
            return
        }
        val filtered = apps.filter { it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }
        if (filtered.isEmpty()) {
            rows.addView(NexusUi.cardBody(this, if (query.isBlank())
                "No apps to show yet. Android did not list any launchable apps, and Relay has not observed another source."
                else "No apps match your search. Other sources appear after Relay observes a notification."))
            return
        }
        filtered.forEach { app ->
            val policy = settings.appPolicy(app.packageName)
            rows.addView(NexusUi.card(this).apply {
                addView(LinearLayout(this@RelayAppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icon = app.icon
                    addView(if (icon == null) NexusUi.iconTile(this@RelayAppsActivity, "?")
                        else NexusUi.iconTileDrawable(this@RelayAppsActivity, icon))
                    addView(LinearLayout(this@RelayAppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(NexusUi.rowTitle(this@RelayAppsActivity, app.label))
                        if (policy != RelayAppPolicy.MESSAGES || app.label == app.packageName) {
                            addView(NexusUi.rowSub(this@RelayAppsActivity, app.packageName))
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@RelayAppsActivity, 12)
                    })
                    addView(NexusUi.metaLabel(this@RelayAppsActivity,
                        if (policy == RelayAppPolicy.MESSAGES) "DEFAULT" else policy.name,
                        when (policy) {
                            RelayAppPolicy.MESSAGES -> NexusUi.INK4
                            RelayAppPolicy.OFF -> NexusUi.INK3
                            RelayAppPolicy.ALL -> NexusUi.GREEN
                        }))
                }, NexusUi.block())
                if (policy != RelayAppPolicy.MESSAGES) {
                    addView(BusTheme.gap(this@RelayAppsActivity, 8))
                    addView(NexusUi.rowSub(this@RelayAppsActivity, when {
                        !app.detailsAvailable -> "App details unavailable · choose Messages to forget this override"
                        policy == RelayAppPolicy.OFF -> "Nothing from this app reaches the glasses"
                        else -> "Other alerts are read-only unless you can reply"
                    }))
                }
                addView(BusTheme.gap(this@RelayAppsActivity, 10))
                addView(segmentRow(app, policy), NexusUi.block())
            }, NexusUi.block())
            rows.addView(BusTheme.gap(this, 8))
        }
        if (settings.configuredPackages().isNotEmpty()) {
            rows.addView(NexusUi.textButton(this, "Use Messages only for every app").apply {
                setOnClickListener {
                    settings.resetAppPolicies()
                    NotificationControl.refreshFromSettings(this@RelayAppsActivity)
                    renderRows()
                }
            }, NexusUi.block())
        }
    }

    private fun segmentRow(app: RelaySourceApp, selected: RelayAppPolicy): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        RelayAppPolicy.entries.forEachIndexed { index, option ->
            val label = when (option) {
                RelayAppPolicy.OFF -> "Off"
                RelayAppPolicy.MESSAGES -> "Messages"
                RelayAppPolicy.ALL -> "All"
            }
            addView(NexusUi.outlinePillButton(this@RelayAppsActivity, label).apply {
                if (option == selected) NexusUi.stylePillAsPrimary(this@RelayAppsActivity, this)
                isSelected = option == selected
                contentDescription = "${app.label}: ${option.label}${if (option == selected) ", selected" else ""}"
                setOnClickListener {
                    settings.setAppPolicy(app.packageName, option)
                    NotificationControl.refreshFromSettings(this@RelayAppsActivity)
                    renderRows()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = NexusUi.dp(this@RelayAppsActivity, 6)
            })
        }
    }
}
