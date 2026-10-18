package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.plugin.PluginCapability

/**
 * Everything about what the glasses screen shows and where: the Nexus surface position, the
 * battery chip in the status row, and whether the activity panel stays open. These lived on the
 * main settings page until the position preview outgrew it.
 */
class GlassesDisplaySettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@GlassesDisplaySettingsActivity,
                    "Where Nexus sits on the glasses screen, and what stays visible around it.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 22))
            addView(positionCard(), NexusUi.block())
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 10))
            addView(
                switchRow(
                    title = "Phone battery on glasses",
                    subtitle = "Charge chip beside the clock in the status row",
                    checked = PhoneBatteryBadgeStore(this@GlassesDisplaySettingsActivity).isEnabled(),
                ) { enabled ->
                    PhoneBatteryBadgeStore(this@GlassesDisplaySettingsActivity).setEnabled(enabled)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 10))
            addView(
                switchRow(
                    title = "Keep activities expanded",
                    subtitle = "Keep the primary activity panel open on glasses",
                    checked = PhoneActivityPresentationSettings(this@GlassesDisplaySettingsActivity)
                        .isAlwaysExpanded(),
                ) { enabled ->
                    PhoneActivityPresentationSettings(this@GlassesDisplaySettingsActivity)
                        .setAlwaysExpanded(enabled)
                    BusHubService.onActivityPresentationPreferenceChanged()
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 10))
            addView(launcherCard(), NexusUi.block())
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 10))
            addView(islandSizeCard(), NexusUi.block())
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

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader("DISPLAY"), NexusUi.block())
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun positionCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@GlassesDisplaySettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
            )
            val store = PhoneHudPositionStore(this@GlassesDisplaySettingsActivity)
            val followGlasses = store.hudPositionAuto()
            val preview = HudPositionPreviewView(this@GlassesDisplaySettingsActivity).apply {
                insetDp = store.hudTopInsetDp()
                dragEnabled = !followGlasses
                onInsetCommitted = { value ->
                    store.setHudTopInsetDp(value)
                    BusHubService.onHudPositionPreferenceChanged()
                }
            }
            addView(NexusUi.rowTitle(this@GlassesDisplaySettingsActivity, "Position"))
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 3))
            addView(
                NexusUi.rowSub(
                    this@GlassesDisplaySettingsActivity,
                    "Follow the Hi Rokid screen or set a manual position",
                ),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 8))
            addView(
                LinearLayout(this@GlassesDisplaySettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(this@GlassesDisplaySettingsActivity, "Follow glasses position"),
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        styledSwitch(followGlasses) { enabled ->
                            store.setHudPositionAuto(enabled)
                            preview.dragEnabled = !enabled
                            BusHubService.onHudPositionPreferenceChanged()
                        },
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 8))
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /**
     * Which launcher the glasses run. Default leaves it to the glasses' own setting, so nobody's
     * existing choice is reset; Classic stays the rollback when the new launcher misbehaves.
     */
    private fun launcherCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@GlassesDisplaySettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
            )
            addView(NexusUi.rowTitle(this@GlassesDisplaySettingsActivity, "Glasses launcher"))
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 3))
            addView(
                NexusUi.rowSub(
                    this@GlassesDisplaySettingsActivity,
                    "Default keeps what the glasses chose. Classic is always there to go back to.",
                ),
            )
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 6))
            val store = PhoneLauncherBackendStore(this@GlassesDisplaySettingsActivity)
            val current = store.choice()
            val options = listOf(
                PhoneLauncherBackendChoice.DEFAULT to "Default",
                PhoneLauncherBackendChoice.SESSION to "New launcher",
                PhoneLauncherBackendChoice.LEGACY to "Classic launcher",
            )
            addView(
                RadioGroup(this@GlassesDisplaySettingsActivity).apply {
                    orientation = RadioGroup.VERTICAL
                    options.forEach { (choice, label) ->
                        addView(
                            RadioButton(this@GlassesDisplaySettingsActivity).apply {
                                id = View.generateViewId()
                                text = label
                                textSize = 13f
                                setTextColor(NexusUi.INK)
                                buttonTintList = ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                    intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                                )
                                isChecked = choice == current
                                setOnCheckedChangeListener { _, checked ->
                                    if (checked && store.choice() != choice) {
                                        store.setChoice(choice)
                                        BusHubService.onLauncherBackendChanged()
                                    }
                                }
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /**
     * How large Navigation is drawn on the glasses. Automatic leaves it to the glasses;
     * the same choice can be made on the glasses, and the phone keeps the last one either way.
     */
    private fun islandSizeCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@GlassesDisplaySettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
            )
            addView(NexusUi.rowTitle(this@GlassesDisplaySettingsActivity, "Navigation display"))
            addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 3))
            addView(
                NexusUi.rowSub(
                    this@GlassesDisplaySettingsActivity,
                    "Choose the size of Navigation on the glasses. Other plugins choose their own presentation.",
                ),
            )
            val owners = islandOwners()
            if (owners.isEmpty()) {
                addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 8))
                addView(
                    NexusUi.rowSub(
                        this@GlassesDisplaySettingsActivity,
                        "Approve Navigation to choose its display size.",
                    ),
                )
                return@apply
            }
            val store = PhoneAmbientPreferencesStore.shared(this@GlassesDisplaySettingsActivity)
            val options = listOf(
                null to "Automatic",
                AmbientSize.TEXT to "Text",
                AmbientSize.MINIMAP to "Minimap",
                AmbientSize.PANEL to "Panel",
            )
            owners.forEach { (pluginId, name) ->
                addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 10))
                addView(NexusUi.rowLabel(this@GlassesDisplaySettingsActivity, name))
                addView(
                    ambientChoiceRow(this@GlassesDisplaySettingsActivity, options, store.sizeFor(pluginId)) { size ->
                        if (store.setSize(pluginId, size)) BusHubService.onAmbientSizesChanged()
                    },
                )
            }
        }

    /** Navigation's size control is available only while its surfaces grant is approved. */
    private fun islandOwners(): List<Pair<String, String>> {
        val grants = PluginGrantStore(applicationContext)
        return PhonePluginDiscovery(packageManager).discover()
            .mapNotNull { (it as? PhonePluginCandidate.Valid)?.principal }
            .filter { AmbientPreferencesContract.hasSizeChoice(it.descriptor.id) }
            .filter { principal ->
                (grants.stateFor(principal) as? PluginGrantState.Approved)
                    ?.capabilities?.contains(PluginCapability.SURFACES) == true
            }
            .map { it.descriptor.id to it.descriptor.displayName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.bordered(this@GlassesDisplaySettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 15),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
            )
            addView(
                LinearLayout(this@GlassesDisplaySettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@GlassesDisplaySettingsActivity, title))
                    addView(BusTheme.gap(this@GlassesDisplaySettingsActivity, 3))
                    addView(NexusUi.rowSub(this@GlassesDisplaySettingsActivity, subtitle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(styledSwitch(checked, onChange))
        }

    private fun styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(NexusUi.GREEN, NexusUi.INK3),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(NexusUi.GREEN_DIM, NexusUi.LINE),
            )
            setOnCheckedChangeListener { _, enabled -> onChange(enabled) }
        }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@GlassesDisplaySettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@GlassesDisplaySettingsActivity, 10),
                        NexusUi.dp(this@GlassesDisplaySettingsActivity, 12),
                        NexusUi.dp(this@GlassesDisplaySettingsActivity, 22),
                        NexusUi.dp(this@GlassesDisplaySettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@GlassesDisplaySettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@GlassesDisplaySettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@GlassesDisplaySettingsActivity, 1),
                    )
                },
            )
        }

    private fun backButton(): TextView =
        TextView(this).apply {
            text = "‹"
            textSize = 26f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            background = NexusUi.pressed(this@GlassesDisplaySettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 44),
                NexusUi.dp(this@GlassesDisplaySettingsActivity, 44),
            )
        }
}
