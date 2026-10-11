package com.anezium.rokidbus.plugin.media

import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.media.MediaDeckActivityControl
import com.anezium.rokidbus.media.MediaDeckActivityModel
import com.anezium.rokidbus.media.MediaDeckPreferences
import com.anezium.rokidbus.media.session.isMediaDeckListenerEnabled

class MediaDeckSettingsActivity : Activity() {
    private lateinit var accessValue: TextView
    private lateinit var activityValue: TextView
    private lateinit var activitySub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Opening the settings applies the stored switch, which also resumes after a revoke.
        MediaDeckActivityControl.resume(this)
        renderAccessState()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        accessValue = valueText("Enable")
        activityValue = valueText("Off")
        activitySub = NexusUi.rowSub(this, "")
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@MediaDeckSettingsActivity, "Settings"), NexusUi.block())
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 12))
            addView(
                settingRow(
                    title = "Media access",
                    subtitle = NexusUi.rowSub(this@MediaDeckSettingsActivity, "Follows what plays on your phone"),
                    value = accessValue,
                    danger = false,
                ) { openAccessSettings() },
                NexusUi.block(),
            )
            addView(
                settingRow(
                    title = "Show in activities",
                    subtitle = activitySub,
                    value = activityValue,
                    danger = false,
                ) { toggleActivity() },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@MediaDeckSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@MediaDeckSettingsActivity, 12))
            addView(
                NexusUi.uninstallCard(this@MediaDeckSettingsActivity, "Media Deck") {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
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
                    NexusPluginIcons.drawableFor("disc"),
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
        subtitle: TextView?,
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
                                addView(subtitle)
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

    /**
     * The activity needs notification access to be turned on; turning it off never does, so a
     * switch left on through a revoke can still be turned off.
     */
    private fun toggleActivity() {
        val enabled = MediaDeckPreferences(this).showInActivities
        if (!enabled && !isMediaDeckListenerEnabled(this)) {
            openAccessSettings()
            return
        }
        MediaDeckActivityControl.setEnabled(this, !enabled)
        renderAccessState()
    }

    private fun openAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun renderAccessState() {
        val access = isMediaDeckListenerEnabled(this)
        accessValue.text = if (access) "ENABLED ›" else "ENABLE ›"
        accessValue.setTextColor(if (access) NexusUi.GREEN else NexusUi.INK2)

        val shown = MediaDeckPreferences(this).showInActivities
        activityValue.text = if (shown) "ON ›" else "OFF ›"
        activityValue.setTextColor(if (shown) NexusUi.GREEN else NexusUi.INK2)
        activitySub.text = when {
            shown -> MediaDeckActivityModel.summaryOf(MediaDeckActivityControl.status(this))
            access -> "Keep what plays among your glasses' activities"
            else -> "Needs media access first"
        }
    }
}
