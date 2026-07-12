package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.app.Dialog
import android.content.Intent
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
import com.anezium.rokidbus.lyrics.lyrics.SpotifySpDcCookie
import com.anezium.rokidbus.lyrics.settings.LyricsProviderSettingsStore

private const val LYRICS_PREFS = "nexus_plugin_lyrics"
private const val LYRICS_PREF_AUTO_OPEN = "auto_open"

class LyricsSettingsActivity : Activity() {
    private lateinit var toggleTrack: FrameLayout
    private lateinit var toggleKnob: View
    private lateinit var spotifyValue: TextView
    private lateinit var musixmatchValue: TextView
    private lateinit var providerStore: LyricsProviderSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerStore = LyricsProviderSettingsStore(applicationContext)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshProviderValues()
    }

    private fun refreshProviderValues() {
        if (::spotifyValue.isInitialized) {
            spotifyValue.text = if (providerStore.hasSpotifySpDc()) "Connected ›" else "Sign in ›"
        }
        if (::musixmatchValue.isInitialized) {
            musixmatchValue.text = if (providerStore.hasMusixmatchCredentials()) "Signed in ›" else "Sign in ›"
        }
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
                    subtitle = "Timed-lines synced",
                    value = toggleView(),
                    danger = false,
                ) {
                    setShowOnGlasses(!showOnGlasses())
                },
                NexusUi.block(),
            )
            spotifyValue = valueText(if (providerStore.hasSpotifySpDc()) "Connected" else "Sign in")
            addView(
                settingRow(
                    title = "Spotify",
                    subtitle = "Own synced lyrics, tried first",
                    value = spotifyValue,
                    danger = false,
                ) { showSpotifyDialog() },
                NexusUi.block(),
            )
            musixmatchValue = valueText(if (providerStore.hasMusixmatchCredentials()) "Signed in" else "Sign in")
            addView(
                settingRow(
                    title = "Musixmatch",
                    subtitle = "Better lyrics, optional",
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
            addView(
                NexusUi.pluginHeader(
                    this@LyricsSettingsActivity,
                    BusClientR.drawable.ic_plugin_music,
                    "Lyrics",
                    "Now-playing lyrics · v1.0",
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
        setShowOnGlasses(showOnGlasses(), persist = false)
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

    private fun showSpotifyDialog() {
        val connected = providerStore.hasSpotifySpDc()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val cookieField = NexusUi.field(this, "sp_dc cookie").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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
            addView(NexusUi.cardTitle(this@LyricsSettingsActivity, "Spotify"))
            addView(BusTheme.gap(this@LyricsSettingsActivity, 6))
            addView(
                NexusUi.cardBody(
                    this@LyricsSettingsActivity,
                    if (connected) {
                        "Connected. Spotify's own synced lyrics are tried first for Spotify tracks."
                    } else {
                        "Sign in to use Spotify's own synced lyrics. The session cookie stays encrypted on this phone."
                    },
                ),
            )
            addView(BusTheme.gap(this@LyricsSettingsActivity, 14))
            addView(
                NexusUi.pillButton(
                    this@LyricsSettingsActivity,
                    if (connected) "Sign in again" else "Sign in with Spotify",
                ).apply {
                    setOnClickListener {
                        dialog.dismiss()
                        startActivity(
                            Intent(this@LyricsSettingsActivity, SpotifyLoginActivity::class.java),
                        )
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LyricsSettingsActivity, 12))
            addView(NexusUi.metaLabel(this@LyricsSettingsActivity, "or paste it manually"), NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 8))
            addView(cookieField, NexusUi.block())
            addView(BusTheme.gap(this@LyricsSettingsActivity, 14))
            addView(
                LinearLayout(this@LyricsSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (connected) {
                        addView(
                            NexusUi.textButton(this@LyricsSettingsActivity, "Disconnect", danger = true).apply {
                                setOnClickListener {
                                    providerStore.clearSpotifySpDc()
                                    LyricsRuntimeGraph.onSpotifyCookieChanged()
                                    refreshProviderValues()
                                    Toast.makeText(
                                        this@LyricsSettingsActivity,
                                        "Spotify disconnected.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    dialog.dismiss()
                                }
                            },
                        )
                    }
                    addView(
                        View(this@LyricsSettingsActivity),
                        LinearLayout.LayoutParams(0, 1, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@LyricsSettingsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(this@LyricsSettingsActivity, "Save").apply {
                            setOnClickListener {
                                val pasted = cookieField.text.toString()
                                if (SpotifySpDcCookie.extractValue(pasted) == null) {
                                    Toast.makeText(
                                        this@LyricsSettingsActivity,
                                        "No sp_dc cookie found in the pasted text.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@setOnClickListener
                                }
                                if (!providerStore.saveSpotifySpDc(pasted)) {
                                    Toast.makeText(
                                        this@LyricsSettingsActivity,
                                        "Secure storage unavailable; the cookie was not saved.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    return@setOnClickListener
                                }
                                LyricsRuntimeGraph.start(applicationContext)
                                LyricsRuntimeGraph.onSpotifyCookieChanged()
                                refreshProviderValues()
                                Toast.makeText(
                                    this@LyricsSettingsActivity,
                                    "Spotify connected.",
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
                                if (!providerStore.saveMusixmatchCredentials(emailText, passwordText)) {
                                    Toast.makeText(
                                        this@LyricsSettingsActivity,
                                        "Secure storage unavailable; credentials were not saved.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    return@setOnClickListener
                                }
                                LyricsRuntimeGraph.start(applicationContext)
                                refreshProviderValues()
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
