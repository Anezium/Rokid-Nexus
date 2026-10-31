package com.anezium.rokidbus.phone

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.ActivityDisplayMode
import com.anezium.rokidbus.shared.ActivityDisplayPreference
import com.anezium.rokidbus.shared.plugin.PluginCapability

/** User-owned activity presentation, also opened from a plugin through the SDK. */
class ActivityDisplaySettingsActivity : Activity() {
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
    }

    // Grants change in Plugin access, which the empty state opens; the list follows them on return.
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val restoreY = scroll?.scrollY ?: 0
        val owners = owners()
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.cardBody(context,
                "Each activity keeps its card in Activities. Choose when its band also floats over the glasses."), NexusUi.block())
            if (PhoneLauncherBackendStore(context).choice() == PhoneLauncherBackendChoice.LEGACY) {
                addView(BusTheme.gap(context, 14))
                addView(noteCard("CLASSIC LAUNCHER",
                    "These choices apply with the new launcher. Classic keeps its existing display until you switch in Display."), NexusUi.block())
            }
            addView(BusTheme.gap(context, 22))
            if (owners.isEmpty()) {
                addView(NexusUi.navCard(context, "Plugin access",
                    "No plugin may show activities on your glasses yet. Allow one there first.") {
                    startActivity(Intent(this@ActivityDisplaySettingsActivity, PluginPermissionsActivity::class.java))
                }, NexusUi.block())
            }
            owners.forEachIndexed { index, owner ->
                if (index > 0) addView(BusTheme.gap(context, 10))
                addView(ownerCard(owner), NexusUi.block())
            }
        }
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll = scrollView
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(titleHeader("FLOATING ACTIVITIES"), NexusUi.block())
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        if (restoreY > 0) scrollView.post { scrollView.scrollTo(0, restoreY) }
    }

    /** Plugins the wearer approved for surfaces, narrowed to the one a plugin asked about. */
    private fun owners(): List<PhonePluginPrincipal> {
        val grants = PluginGrantStore(applicationContext)
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val pluginPackage = intent.getStringExtra(EXTRA_PLUGIN_PACKAGE)
        return PhonePluginDiscovery(packageManager).discover()
            .mapNotNull { (it as? PhonePluginCandidate.Valid)?.principal }
            .filter { owner ->
                (grants.stateFor(owner) as? PluginGrantState.Approved)?.capabilities?.contains(PluginCapability.SURFACES) == true &&
                    (pluginId?.let { it == owner.descriptor.id } ?: true) &&
                    (pluginPackage?.let { it == owner.grantKey().packageName } ?: true)
            }.distinctBy { it.grantKey() }.sortedBy { it.descriptor.displayName.lowercase() }
    }

    private fun ownerCard(owner: PhonePluginPrincipal): LinearLayout {
        val preferences = PhoneActivityPreferences.shared(this)
        val key = owner.grantKey()
        val stored = preferences.preferenceFor(key)
        val current = if (stored.floating) stored.display else ActivityDisplayPreference(ActivityDisplayMode.HIDDEN)
        val name = owner.descriptor.displayName
        fun apply(next: ActivityDisplayPreference) {
            if (preferences.setDisplay(key, next)) {
                BusHubService.onAmbientPolicyChanged()
                render()
            } else {
                Toast.makeText(this, "Too many custom activity settings. Reset another plugin to Automatic first.", Toast.LENGTH_LONG).show()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(context, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 14), NexusUi.dp(context, 15), NexusUi.dp(context, 12))
            addView(NexusUi.cardTitle(context, name))
            addView(BusTheme.gap(context, 10))
            addView(modeRow(current.mode) { showModeChooser(name, current, ::apply) }, NexusUi.block())
            if (current.mode == ActivityDisplayMode.EVENTS) {
                addView(NexusUi.divider(context))
                addView(NexusUi.rowLabel(context, "Visible for"))
                addView(BusTheme.gap(context, 2))
                val durations = ActivityDisplayPreference.DURATIONS_MS.sorted()
                addView(ambientChoiceRow(context, durations.map { it to "${it / 1_000} seconds" }, current.durationMs) { duration ->
                    apply(current.copy(durationMs = duration))
                })
                addView(note("After each change, then the band fades away. The card stays in Activities."))
            }
        }
    }

    /** The one tappable choice of the card: the mode as its value, a chevron as the promise. */
    private fun modeRow(mode: ActivityDisplayMode, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = NexusUi.pressed(context, Color.TRANSPARENT, 10)
        setPadding(NexusUi.dp(context, 4), NexusUi.dp(context, 8), NexusUi.dp(context, 4), NexusUi.dp(context, 8))
        minimumHeight = NexusUi.dp(context, 56)
        isClickable = true
        isFocusable = true
        contentDescription = "Floating band: ${label(mode)}. ${description(mode)}. Change"
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.metaLabel(context, "FLOATING BAND", NexusUi.INK3))
            addView(BusTheme.gap(context, 4))
            addView(NexusUi.rowTitle(context, label(mode)))
            addView(BusTheme.gap(context, 3))
            addView(NexusUi.cardBody(context, description(mode)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = NexusUi.dp(context, 12) })
        addView(NexusUi.chevron(context))
        setOnClickListener { onClick() }
    }

    /**
     * One option per line with its description under the label, so the whole choice reads
     * without scrolling and the last option is never hidden under the buttons.
     */
    private fun showModeChooser(name: String, current: ActivityDisplayPreference, onPick: (ActivityDisplayPreference) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(NexusUi.dp(context, 14), NexusUi.dp(context, 18), NexusUi.dp(context, 14), NexusUi.dp(context, 10))
            addView(NexusUi.cardTitle(context, name).apply {
                setPadding(NexusUi.dp(context, 6), 0, NexusUi.dp(context, 6), 0)
            })
            addView(BusTheme.gap(context, 2))
            addView(NexusUi.cardBody(context, "When its band floats over the glasses").apply {
                setPadding(NexusUi.dp(context, 6), 0, NexusUi.dp(context, 6), 0)
            })
            addView(BusTheme.gap(context, 10))
            ActivityDisplayMode.entries.forEach { mode ->
                addView(optionRow(mode, mode == current.mode) {
                    dialog.dismiss()
                    if (mode != current.mode) onPick(current.copy(mode = mode))
                }, NexusUi.block())
            }
            addView(BusTheme.gap(context, 6))
            addView(NexusUi.textButton(context, "Cancel").apply { setOnClickListener { dialog.dismiss() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                })
        }
        dialog.setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.window?.apply {
            val margin = NexusUi.dp(this@ActivityDisplaySettingsActivity, 16)
            setBackgroundDrawable(InsetDrawable(
                NexusUi.bordered(this@ActivityDisplaySettingsActivity, NexusUi.PANEL, NexusUi.LINE, 18), margin, 0, margin, 0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
        }
        dialog.show()
    }

    private fun optionRow(mode: ActivityDisplayMode, selected: Boolean, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = NexusUi.pressed(context, Color.TRANSPARENT, 10)
        setPadding(NexusUi.dp(context, 4), NexusUi.dp(context, 9), NexusUi.dp(context, 8), NexusUi.dp(context, 9))
        isClickable = true
        isFocusable = true
        contentDescription = "${label(mode)}. ${description(mode)}" + if (selected) ". Selected" else ""
        addView(RadioButton(context).apply {
            isChecked = selected
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(NexusUi.GREEN, NexusUi.INK3),
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = NexusUi.dp(context, 4)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.rowTitle(context, label(mode)).apply {
                textSize = 14f
                if (selected) setTextColor(NexusUi.GREEN)
            })
            addView(BusTheme.gap(context, 2))
            addView(NexusUi.cardBody(context, description(mode)).apply { textSize = 12f })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }

    private fun titleHeader(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexusUi.dp(context, 10), NexusUi.dp(context, 12), NexusUi.dp(context, 22), NexusUi.dp(context, 12))
            addView(NexusUi.backButton(context) { finish() })
            addView(NexusUi.metaLabel(context, title, NexusUi.INK).apply {
                textSize = 12f
                letterSpacing = 0.2f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, NexusUi.block())
        addView(View(context).apply {
            setBackgroundColor(NexusUi.LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(context, 1))
        })
    }

    private fun noteCard(caption: String, body: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(context, NexusUi.PANEL, NexusUi.LINE2, 15)
        setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 12), NexusUi.dp(context, 15), NexusUi.dp(context, 12))
        addView(NexusUi.metaLabel(context, caption, NexusUi.AMBER))
        addView(BusTheme.gap(context, 4))
        addView(NexusUi.cardBody(context, body))
    }

    private fun note(text: String): TextView = NexusUi.rowSub(this, text).apply {
        maxLines = Int.MAX_VALUE
        ellipsize = null
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_PLUGIN_PACKAGE = "pluginPackage"

        internal fun label(mode: ActivityDisplayMode): String = when (mode) {
            ActivityDisplayMode.AUTOMATIC -> "Automatic"
            ActivityDisplayMode.PERSISTENT -> "Always visible"
            ActivityDisplayMode.HOME -> "Home screen only"
            ActivityDisplayMode.EVENTS -> "On changes"
            ActivityDisplayMode.HIDDEN -> "Activities only"
        }

        private fun description(mode: ActivityDisplayMode): String = when (mode) {
            ActivityDisplayMode.AUTOMATIC -> "At home and for important events, following each app's display rules"
            ActivityDisplayMode.PERSISTENT -> "Keep its band over home and apps, except where all activities are hidden"
            ActivityDisplayMode.HOME -> "Keep its band on the home screen; hide it inside apps"
            ActivityDisplayMode.EVENTS -> "Briefly appear for a new track or another event from the plugin"
            ActivityDisplayMode.HIDDEN -> "Keep its card and player; hide its floating band"
        }
    }
}
