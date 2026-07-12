package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.media.session.MediaDeckNotificationListenerService

class MediaDeckSettingsActivity : Activity() {
    private lateinit var accessValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        renderAccessState()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        accessValue = valueText("Enable")
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@MediaDeckSettingsActivity, "Settings"), NexusUi.block())
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 12))
            addView(
                settingRow(
                    title = "Media access",
                    subtitle = "Follows what plays on your phone",
                    value = accessValue,
                    danger = false,
                ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@MediaDeckSettingsActivity, "Privacy"), NexusUi.block())
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 10))
            addView(
                NexusUi.cardBody(
                    this@MediaDeckSettingsActivity,
                    "Media Deck reads only the active media session. Audio never passes " +
                        "through Nexus; artwork is reduced locally to a 96 × 96 one-bit preview.",
                ).apply {
                    setPadding(NexusUi.dp(this@MediaDeckSettingsActivity, 4), 0, NexusUi.dp(this@MediaDeckSettingsActivity, 4), 0)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@MediaDeckSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 12))
            addView(
                settingRow(
                    title = "Uninstall",
                    subtitle = null,
                    value = valueText("Remove", NexusUi.DANGER),
                    danger = true,
                ) {
                    Toast.makeText(
                        this@MediaDeckSettingsActivity,
                        "Built-in plugins cannot be uninstalled yet.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                NexusUi.block(),
            )
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
            addView(
                NexusUi.pluginHeader(
                    this@MediaDeckSettingsActivity,
                    BusClientR.drawable.ic_plugin_disc,
                    "Media Deck",
                    "Universal now playing · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    private fun settingRow(
        title: String,
        subtitle: String?,
        value: View,
        danger: Boolean,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@MediaDeckSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@MediaDeckSettingsActivity, 4),
                        NexusUi.dp(this@MediaDeckSettingsActivity, 16),
                        NexusUi.dp(this@MediaDeckSettingsActivity, 4),
                        NexusUi.dp(this@MediaDeckSettingsActivity, 16),
                    )
                    isClickable = true
                    isFocusable = true
                    background = NexusUi.pressed(this@MediaDeckSettingsActivity, Color.TRANSPARENT, 8)
                    setOnClickListener { onClick() }
                    addView(
                        LinearLayout(this@MediaDeckSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                NexusUi.rowTitle(this@MediaDeckSettingsActivity, title).apply {
                                    textSize = 14f
                                    setTextColor(if (danger) NexusUi.DANGER else NexusUi.INK)
                                },
                            )
                            if (subtitle != null) {
                                addView(BusTheme.gap(this@MediaDeckSettingsActivity, 5))
                                addView(NexusUi.rowSub(this@MediaDeckSettingsActivity, subtitle))
                            }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = NexusUi.dp(this@MediaDeckSettingsActivity, 12)
                        },
                    )
                    addView(value)
                },
                NexusUi.block(),
            )
            addView(
                View(this@MediaDeckSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE2)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@MediaDeckSettingsActivity, 1),
                    )
                },
            )
        }

    private fun valueText(label: String, color: Int = NexusUi.INK2): TextView =
        NexusUi.metaLabel(this, "$label ›", color).apply {
            textSize = 12f
            letterSpacing = 0.04f
        }

    private fun renderAccessState() {
        val enabled = isMediaListenerEnabled()
        accessValue.text = if (enabled) "ENABLED ›" else "ENABLE ›"
        accessValue.setTextColor(if (enabled) NexusUi.GREEN else NexusUi.INK2)
    }

    private fun isMediaListenerEnabled(): Boolean {
        val target = ComponentName(this, MediaDeckNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == target
        }
    }
}
