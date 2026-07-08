package com.anezium.rokidbus.phone

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.lyrics.settings.LyricsProviderSettingsStore

private const val LYRICS_PREFS = "nexus_plugin_lyrics"
private const val LYRICS_PREF_AUTO_OPEN = "auto_open"

class LyricsSettingsActivity : Activity() {
    private lateinit var toggleTrack: FrameLayout
    private lateinit var toggleKnob: View
    private lateinit var musixmatchValue: TextView
    private lateinit var providerStore: LyricsProviderSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerStore = LyricsProviderSettingsStore(applicationContext)
        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@LyricsSettingsActivity, "Settings"), NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 12))
            addView(
                settingRow(
                    title = "Show on glasses",
                    subtitle = "timed-lines synced",
                    value = toggleView(),
                    danger = false,
                ) {
                    setShowOnGlasses(!showOnGlasses())
                },
                NexusUi.block(),
            )
            musixmatchValue = valueText(if (providerStore.hasMusixmatchCredentials()) "Signed in" else "Sign in")
            addView(
                settingRow(
                    title = "Musixmatch",
                    subtitle = "better lyrics, optional",
                    value = musixmatchValue,
                    danger = false,
                ) { showMusixmatchDialog() },
                NexusUi.block(),
            )
            addView(
                settingRow(
                    title = "Lyrics sources",
                    subtitle = "LrcLib \u00B7 Netease",
                    value = valueText("2 active"),
                    danger = false,
                ) { Toast.makeText(this@LyricsSettingsActivity, "Coming soon", Toast.LENGTH_SHORT).show() },
                NexusUi.block(),
            )
            addView(
                settingRow(
                    title = "Sync offset",
                    subtitle = null,
                    value = valueText("0 ms"),
                    danger = false,
                ) { Toast.makeText(this@LyricsSettingsActivity, "Coming soon", Toast.LENGTH_SHORT).show() },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LyricsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@LyricsSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 12))
            addView(
                settingRow(
                    title = "Uninstall",
                    subtitle = null,
                    value = valueText("Remove", NexusUi.DANGER),
                    danger = true,
                ) {
                    Toast.makeText(
                        this@LyricsSettingsActivity,
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
            addView(pluginHeader(), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setShowOnGlasses(showOnGlasses(), persist = false)
        setContentView(root)
    }

    private fun pluginHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@LyricsSettingsActivity, 22),
                        NexusUi.dp(this@LyricsSettingsActivity, 18),
                        NexusUi.dp(this@LyricsSettingsActivity, 22),
                        NexusUi.dp(this@LyricsSettingsActivity, 18),
                    )
                    addView(NexusUi.iconTile(this@LyricsSettingsActivity, "\u266A", 48))
                    addView(
                        LinearLayout(this@LyricsSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                NexusUi.cardTitle(this@LyricsSettingsActivity, "Lyrics").apply {
                                    textSize = 20f
                                },
                            )
                            addView(BusTheme.gap(this@LyricsSettingsActivity, 5))
                            addView(NexusUi.rowSub(this@LyricsSettingsActivity, "now-playing lyrics \u00B7 v1.0"))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = NexusUi.dp(this@LyricsSettingsActivity, 14)
                        },
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@LyricsSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@LyricsSettingsActivity, 1),
                    )
                },
            )
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
                LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@LyricsSettingsActivity, 4),
                        NexusUi.dp(this@LyricsSettingsActivity, 16),
                        NexusUi.dp(this@LyricsSettingsActivity, 4),
                        NexusUi.dp(this@LyricsSettingsActivity, 16),
                    )
                    isClickable = true
                    isFocusable = true
                    background = NexusUi.pressed(
                        this@LyricsSettingsActivity,
                        android.graphics.Color.TRANSPARENT,
                        8,
                    )
                    setOnClickListener { onClick() }
                    addView(
                        LinearLayout(this@LyricsSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                NexusUi.rowTitle(this@LyricsSettingsActivity, title).apply {
                                    textSize = 14f
                                    setTextColor(if (danger) NexusUi.DANGER else NexusUi.INK)
                                },
                            )
                            if (subtitle != null) {
                                addView(BusTheme.gap(this@LyricsSettingsActivity, 5))
                                addView(NexusUi.rowSub(this@LyricsSettingsActivity, subtitle))
                            }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = NexusUi.dp(this@LyricsSettingsActivity, 12)
                        },
                    )
                    addView(value)
                },
                NexusUi.block(),
            )
            addView(
                View(this@LyricsSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE2)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@LyricsSettingsActivity, 1),
                    )
                },
            )
        }

    private fun valueText(label: String, color: Int = NexusUi.INK2): TextView =
        NexusUi.metaLabel(this, "$label \u203A", color).apply {
            textSize = 12f
            letterSpacing = 0.04f
        }

    private fun toggleView(): FrameLayout {
        toggleKnob = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NexusUi.ON_ACCENT)
            }
        }
        toggleTrack = FrameLayout(this).apply {
            background = NexusUi.rounded(this@LyricsSettingsActivity, NexusUi.GREEN, 14)
            addView(
                toggleKnob,
                FrameLayout.LayoutParams(
                    NexusUi.dp(this@LyricsSettingsActivity, 18),
                    NexusUi.dp(this@LyricsSettingsActivity, 18),
                    Gravity.CENTER_VERTICAL or Gravity.END,
                ).apply { marginEnd = NexusUi.dp(this@LyricsSettingsActivity, 3) },
            )
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@LyricsSettingsActivity, 42),
                NexusUi.dp(this@LyricsSettingsActivity, 24),
            )
        }
        return toggleTrack
    }

    private fun showOnGlasses(): Boolean =
        getSharedPreferences(LYRICS_PREFS, MODE_PRIVATE)
            .getBoolean(LYRICS_PREF_AUTO_OPEN, true)

    private fun setShowOnGlasses(enabled: Boolean, persist: Boolean = true) {
        if (persist) {
            getSharedPreferences(LYRICS_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(LYRICS_PREF_AUTO_OPEN, enabled)
                .apply()
        }
        if (!::toggleTrack.isInitialized || !::toggleKnob.isInitialized) return
        toggleTrack.background = NexusUi.rounded(
            this,
            if (enabled) NexusUi.GREEN else 0xFF1C2B21.toInt(),
            14,
        )
        val params = FrameLayout.LayoutParams(
            NexusUi.dp(this, 18),
            NexusUi.dp(this, 18),
            Gravity.CENTER_VERTICAL or if (enabled) Gravity.END else Gravity.START,
        ).apply {
            if (enabled) marginEnd = NexusUi.dp(this@LyricsSettingsActivity, 3)
            else marginStart = NexusUi.dp(this@LyricsSettingsActivity, 3)
        }
        toggleKnob.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (enabled) NexusUi.ON_ACCENT else NexusUi.INK3)
        }
        toggleTrack.updateViewLayout(toggleKnob, params)
    }

    private fun showMusixmatchDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val email = NexusUi.field(this, "Email").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = NexusUi.field(this, "Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@LyricsSettingsActivity, NexusUi.PANEL, NexusUi.LINE2, 16)
            setPadding(
                NexusUi.dp(this@LyricsSettingsActivity, 18),
                NexusUi.dp(this@LyricsSettingsActivity, 18),
                NexusUi.dp(this@LyricsSettingsActivity, 18),
                NexusUi.dp(this@LyricsSettingsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@LyricsSettingsActivity, "Musixmatch"))
            addView(BusTheme.gap(this@LyricsSettingsActivity, 6))
            addView(NexusUi.cardBody(this@LyricsSettingsActivity, "Save credentials on this phone for optional lyric lookups."))
            addView(BusTheme.gap(this@LyricsSettingsActivity, 14))
            addView(email, NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 10))
            addView(password, NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 14))
            addView(
                LinearLayout(this@LyricsSettingsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@LyricsSettingsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(this@LyricsSettingsActivity, "Save").apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setOnClickListener {
                                val emailText = email.text.toString().trim()
                                val passwordText = password.text.toString()
                                if (emailText.isBlank() || passwordText.isBlank()) {
                                    Toast.makeText(
                                        this@LyricsSettingsActivity,
                                        "Enter email and password.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@setOnClickListener
                                }
                                providerStore.saveMusixmatchCredentials(emailText, passwordText)
                                LyricsRuntimeGraph.start(applicationContext)
                                musixmatchValue.text = "SIGNED IN \u203A"
                                Toast.makeText(
                                    this@LyricsSettingsActivity,
                                    "Musixmatch saved.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                dialog.dismiss()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
