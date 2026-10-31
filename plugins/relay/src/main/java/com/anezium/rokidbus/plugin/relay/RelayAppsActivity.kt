package com.anezium.rokidbus.plugin.relay

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.Executors

/**
 * One row per app, grouped by what it currently forwards. The row only opens a
 * chooser: no policy changes until an option in that chooser is tapped.
 */
class RelayAppsActivity : Activity() {
    private enum class LoadState { LOADING, READY, FAILED }

    private val settings by lazy { RelaySettings(this) }
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var rows: LinearLayout
    private lateinit var status: LinearLayout
    private var chooser: Dialog? = null
    private var apps = emptyList<RelaySourceApp>()
    private var query = ""
    private var loadGeneration = 0
    private var loadState = LoadState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelayHistoryForget.baseline(this)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        query = savedInstanceState?.getString("query").orEmpty()
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = NexusUi.contentColumn(this, topDp = 4).apply {
            addView(status, NexusUi.block())
            addView(rows, NexusUi.block())
        }
        // Intro and search stay put: a long app list scrolls underneath them.
        val fixed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            addView(NexusUi.cardBody(this@RelayAppsActivity,
                "Tap an app to choose what reaches your glasses."),
                NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 12))
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
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(
                this@RelayAppsActivity,
                NexusUi.iconTileImage(this@RelayAppsActivity, R.drawable.nexus_glyph_relay, 48),
                "Source apps",
                "Which apps reach the glasses",
            ) { finish() }, NexusUi.block())
            addView(fixed, NexusUi.block())
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
        chooser?.dismiss()
        chooser = null
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
                addView(BusTheme.gap(this@RelayAppsActivity, 4))
                addView(NexusUi.cardBody(this@RelayAppsActivity, "Your choices are kept, but nothing is forwarded until Relay and notification access are on."))
                if (!access) {
                    addView(BusTheme.gap(this@RelayAppsActivity, 10))
                    addView(NexusUi.outlinePillButton(this@RelayAppsActivity, "Open notification access").apply {
                        setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    }, NexusUi.block())
                }
            }, NexusUi.block())
            status.addView(BusTheme.gap(this, 16))
        }
    }

    private fun renderRows() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        if (loadState == LoadState.LOADING) {
            rows.addView(note("Loading apps…"))
            return
        }
        if (loadState == LoadState.FAILED) {
            rows.addView(note("Could not load apps. Your choices are unchanged.").apply {
                addView(BusTheme.gap(this@RelayAppsActivity, 6))
                addView(NexusUi.textButton(this@RelayAppsActivity, "Retry").apply {
                    setOnClickListener { loadApps() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            return
        }
        val needle = query.trim()
        val filtered = apps.filter { it.label.contains(needle, true) || it.packageName.contains(needle, true) }
        if (filtered.isEmpty()) {
            rows.addView(note(if (needle.isEmpty())
                "No apps yet. Apps show up once Android lists them or Relay sees one of their notifications."
                else "No app matches your search."))
            return
        }
        val (changed, unchanged) = filtered.partition { settings.appPolicy(it.packageName) != RelayAppPolicy.MESSAGES }
        if (changed.isNotEmpty()) {
            rows.addView(sectionHeader("Changed", changed.size, resetAction = true), NexusUi.block())
            rows.addView(group(changed), NexusUi.block())
            if (unchanged.isNotEmpty()) rows.addView(BusTheme.gap(this, 18))
        }
        if (unchanged.isNotEmpty()) {
            rows.addView(sectionHeader("Messages only", unchanged.size), NexusUi.block())
            rows.addView(group(unchanged), NexusUi.block())
        }
    }

    private fun note(text: String): LinearLayout =
        NexusUi.card(this).apply { addView(NexusUi.cardBody(this@RelayAppsActivity, text), NexusUi.block()) }

    private fun sectionHeader(label: String, count: Int, resetAction: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
            setPadding(dp(2), 0, dp(2), dp(6))
            addView(NexusUi.sectionLabel(this@RelayAppsActivity, label))
            addView(NexusUi.metaLabel(this@RelayAppsActivity, count.toString(), NexusUi.GREEN_DIM),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            if (resetAction) addView(NexusUi.textButton(this@RelayAppsActivity, "Reset all").apply {
                minHeight = dp(48)
                minimumHeight = dp(48)
                setOnClickListener { confirmReset() }
            })
        }

    private fun group(items: List<RelaySourceApp>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@RelayAppsActivity, NexusUi.PANEL, NexusUi.LINE2, 15)
            clipToOutline = true
            items.forEachIndexed { index, app ->
                if (index > 0) addView(View(this@RelayAppsActivity).apply {
                    setBackgroundColor(NexusUi.LINE2)
                }, NexusUi.block().apply { height = dp(1); marginStart = dp(60) })
                addView(appRow(app, settings.appPolicy(app.packageName)), NexusUi.block())
            }
        }

    private fun appRow(app: RelaySourceApp, policy: RelayAppPolicy): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), dp(9), dp(12), dp(9))
            background = NexusUi.pressed(this@RelayAppsActivity, Color.TRANSPARENT, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "${app.label}, ${policy.label}"
            setOnClickListener { showChooser(app) }
            val icon = app.icon
            addView((if (icon == null) NexusUi.iconTile(this@RelayAppsActivity, "?")
                else NexusUi.iconTileDrawable(this@RelayAppsActivity, icon)).apply {
                if (policy == RelayAppPolicy.OFF) alpha = 0.45f
            })
            addView(LinearLayout(this@RelayAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelayAppsActivity, app.label).apply {
                    maxLines = 1
                    if (policy == RelayAppPolicy.OFF) setTextColor(NexusUi.INK2)
                })
                if (!app.detailsAvailable) addView(NexusUi.rowSub(this@RelayAppsActivity, "Not installed"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(10)
            })
            addView(NexusUi.metaLabel(this@RelayAppsActivity, when (policy) {
                RelayAppPolicy.OFF -> "Off"
                RelayAppPolicy.MESSAGES -> "Messages"
                RelayAppPolicy.ALL -> "All"
            }, when (policy) {
                RelayAppPolicy.OFF -> NexusUi.INK3
                RelayAppPolicy.MESSAGES -> NexusUi.INK4
                RelayAppPolicy.ALL -> NexusUi.GREEN
            }).apply { textSize = 11f })
            addView(NexusUi.chevron(this@RelayAppsActivity),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) })
        }

    private fun showChooser(app: RelaySourceApp) {
        chooser?.dismiss()
        val selected = settings.appPolicy(app.packageName)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@RelayAppsActivity, NexusUi.PANEL, NexusUi.LINE2, 16)
            setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(LinearLayout(this@RelayAppsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val icon = app.icon
                addView(if (icon == null) NexusUi.iconTile(this@RelayAppsActivity, "?")
                    else NexusUi.iconTileDrawable(this@RelayAppsActivity, icon))
                addView(LinearLayout(this@RelayAppsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.cardTitle(this@RelayAppsActivity, app.label).apply { maxLines = 2 })
                    addView(BusTheme.gap(this@RelayAppsActivity, 3))
                    addView(NexusUi.rowSub(this@RelayAppsActivity,
                        if (app.detailsAvailable) app.packageName else "Not installed · ${app.packageName}"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                })
            }, NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 12))
            RelayAppPolicy.entries.forEach { option ->
                addView(optionRow(option, option == selected) {
                    dialog.dismiss()
                    settings.setAppPolicy(app.packageName, option)
                    NotificationControl.refreshFromSettings(this@RelayAppsActivity)
                    renderRows()
                }, NexusUi.block())
            }
            if (!app.detailsAvailable) {
                addView(BusTheme.gap(this@RelayAppsActivity, 8))
                addView(NexusUi.cardBody(this@RelayAppsActivity,
                    "This app is not on the phone. Messages only forgets it.").apply { textSize = 12f },
                    NexusUi.block())
            }
            addView(BusTheme.gap(this@RelayAppsActivity, 4))
            addView(LinearLayout(this@RelayAppsActivity).apply {
                gravity = Gravity.END
                addView(NexusUi.textButton(this@RelayAppsActivity, "Cancel").apply {
                    setOnClickListener { dialog.dismiss() }
                })
            }, NexusUi.block())
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { if (chooser === dialog) chooser = null }
        chooser = dialog
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun optionRow(option: RelayAppPolicy, selected: Boolean, onPick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = NexusUi.pressed(this@RelayAppsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            contentDescription = option.label + if (selected) ", selected" else ""
            setOnClickListener { onPick() }
            addView(LinearLayout(this@RelayAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@RelayAppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(NexusUi.rowTitle(this@RelayAppsActivity, option.label).apply {
                        if (!selected) setTextColor(NexusUi.INK2)
                    })
                    if (option == RelayAppPolicy.MESSAGES) {
                        addView(NexusUi.metaLabel(this@RelayAppsActivity, "Default", NexusUi.INK4),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                .apply { marginStart = dp(8) })
                    }
                }, NexusUi.block())
                addView(BusTheme.gap(this@RelayAppsActivity, 2))
                addView(NexusUi.cardBody(this@RelayAppsActivity, when (option) {
                    RelayAppPolicy.OFF -> "Nothing from this app reaches the glasses"
                    RelayAppPolicy.MESSAGES -> "Notifications you can reply to"
                    RelayAppPolicy.ALL -> "Also alerts you can only read. Ongoing and group summaries are skipped."
                }).apply {
                    textSize = 12f
                    setTextColor(NexusUi.INK3)
                    setLineSpacing(dp(1).toFloat(), 1f)
                }, NexusUi.block())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) })
            addView(NexusUi.dot(this@RelayAppsActivity).also {
                NexusUi.setDotColor(it, if (selected) NexusUi.GREEN else NexusUi.INK4)
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
        }

    private fun confirmReset() {
        val count = settings.configuredPackages().size
        if (count == 0) return
        chooser?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@RelayAppsActivity, NexusUi.PANEL, NexusUi.LINE2, 16)
            setPadding(dp(18), dp(18), dp(18), dp(14))
            addView(NexusUi.cardTitle(this@RelayAppsActivity, "Back to Messages only for every app?"))
            addView(BusTheme.gap(this@RelayAppsActivity, 6))
            addView(NexusUi.cardBody(this@RelayAppsActivity,
                if (count == 1) "The 1 app you changed goes back to forwarding only messages you can reply to."
                else "The $count apps you changed go back to forwarding only messages you can reply to."),
                NexusUi.block())
            addView(BusTheme.gap(this@RelayAppsActivity, 14))
            addView(LinearLayout(this@RelayAppsActivity).apply {
                gravity = Gravity.END
                addView(NexusUi.textButton(this@RelayAppsActivity, "Cancel").apply {
                    setOnClickListener { dialog.dismiss() }
                })
                addView(NexusUi.textButton(this@RelayAppsActivity, "Reset all").apply {
                    setOnClickListener {
                        dialog.dismiss()
                        settings.resetAppPolicies()
                        NotificationControl.refreshFromSettings(this@RelayAppsActivity)
                        renderRows()
                    }
                })
            }, NexusUi.block())
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { if (chooser === dialog) chooser = null }
        chooser = dialog
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = NexusUi.dp(this, value)
}
