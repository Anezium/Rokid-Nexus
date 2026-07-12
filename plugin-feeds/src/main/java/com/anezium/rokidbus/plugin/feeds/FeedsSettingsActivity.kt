package com.anezium.rokidbus.plugin.feeds

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * Feeds settings — one visible source picker, then only the controls that the
 * chosen source actually needs. Everything else stays out of the way.
 */
class FeedsSettingsActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }

    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private lateinit var selectedSource: FeedSourceKind
    private lateinit var sourceList: LinearLayout
    private lateinit var contextPanel: LinearLayout

    // Held across context rebuilds so nothing typed is lost when switching source.
    private var tokenValue = ""
    private var userIdValue = ""
    private var feedUriValue = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = settingsStore.load()
        selectedSource = settings.source
        tokenValue = settings.xBearerToken
        userIdValue = settings.xUserId
        feedUriValue = settings.blueskyFeedGeneratorUri
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the X login or the overlay grant screen: refresh live status.
        if (::contextPanel.isInitialized && selectedSource.isXSession) renderContext()
    }

    private fun buildUi() {
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg

        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contextPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@FeedsSettingsActivity, "Nexus plugin"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 12))
            addView(BusTheme.hero(this@FeedsSettingsActivity).apply { text = "Feeds" })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
            addView(BusTheme.heroSub(this@FeedsSettingsActivity, "Pick where your glasses feed comes from."))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 24))
            addView(BusTheme.tinyLabel(this@FeedsSettingsActivity, "Source"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(sourceList)
            addView(BusTheme.gap(this@FeedsSettingsActivity, 22))
            addView(contextPanel)
            addView(BusTheme.gap(this@FeedsSettingsActivity, 6))
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Save settings").apply {
                setOnClickListener { saveSettings() }
            })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 16))
            addView(advancedLink())
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BusTheme.bg)
                isFillViewport = true
                addView(content)
            },
        )

        renderSourceList()
        renderContext()
    }

    // ---- Source picker -------------------------------------------------------

    private fun renderSourceList() {
        sourceList.removeAllViews()
        FeedSourceKind.entries.forEachIndexed { index, kind ->
            if (index > 0) sourceList.addView(BusTheme.gap(this, 8))
            sourceList.addView(sourceRow(kind))
        }
    }

    private fun sourceRow(kind: FeedSourceKind): View {
        val selected = kind == selectedSource
        val name = TextView(this).apply {
            text = kind.title
            textSize = 15f
            typeface = sansMedium
            setTextColor(if (selected) BusTheme.phosphor else BusTheme.text)
        }
        val subtitle = TextView(this).apply {
            text = kind.blurb
            textSize = 11f
            letterSpacing = 0.02f
            setTextColor(BusTheme.dim)
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(BusTheme.gap(this@FeedsSettingsActivity, 3))
            addView(subtitle)
        }
        val marker = TextView(this).apply {
            text = if (selected) "●" else "○"
            textSize = 13f
            setTextColor(if (selected) BusTheme.phosphor else BusTheme.dim)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (selected) BusTheme.cardPressed else BusTheme.card, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(marker)
            setOnClickListener {
                if (selectedSource != kind) {
                    selectedSource = kind
                    renderSourceList()
                    renderContext()
                }
            }
        }
    }

    // ---- Contextual controls -------------------------------------------------

    private fun renderContext() {
        contextPanel.removeAllViews()
        when (selectedSource) {
            FeedSourceKind.BLUESKY -> {
                sectionLabel("Bluesky feed")
                contextPanel.addView(
                    input(feedUriValue, "Feed generator URI", InputType.TYPE_CLASS_TEXT) { feedUriValue = it },
                )
                note("Any Bluesky feed generator. Leave as-is for What's Hot — no account needed.")
            }

            FeedSourceKind.X_ACCOUNT, FeedSourceKind.X_WEBVIEW -> {
                val connected = settingsStore.load().xAccountCookies.isConnected
                val tiles = mutableListOf(
                    BusTheme.Tile(this, "X account").also {
                        it.set(connected, if (connected) "Connected" else "Sign in")
                    },
                )
                if (selectedSource == FeedSourceKind.X_WEBVIEW) {
                    val overlay = Settings.canDrawOverlays(this)
                    tiles += BusTheme.Tile(this, "Overlay").also {
                        it.set(overlay, if (overlay) "Granted" else "Needed")
                    }
                }
                contextPanel.addView(BusTheme.tileRow(this, tiles))
                contextPanel.addView(BusTheme.gap(this, 14))
                contextPanel.addView(
                    BusTheme.pill(this, if (connected) "Reconnect X account" else "Connect X account").apply {
                        setOnClickListener { startActivity(Intent(this@FeedsSettingsActivity, XAccountLoginActivity::class.java)) }
                    },
                )
                if (selectedSource == FeedSourceKind.X_WEBVIEW && !Settings.canDrawOverlays(this)) {
                    contextPanel.addView(BusTheme.gap(this, 10))
                    contextPanel.addView(
                        BusTheme.pill(this, "Grant overlay access").apply {
                            setOnClickListener { openOverlayPermission() }
                        },
                    )
                }
                if (connected) {
                    contextPanel.addView(BusTheme.gap(this, 10))
                    contextPanel.addView(
                        BusTheme.pill(this, "Disconnect", dangerVariant = true).apply {
                            setOnClickListener { disconnectXAccount() }
                        },
                    )
                }
                note(
                    if (selectedSource == FeedSourceKind.X_WEBVIEW) {
                        "Loads your home timeline in a hidden 1×1 browser window and reads the feed it fetches."
                    } else {
                        "Reads your home timeline through X's internal API. Cookies stay on this phone."
                    },
                )
            }

            FeedSourceKind.X_OFFICIAL -> {
                sectionLabel("Official X API")
                contextPanel.addView(
                    input(tokenValue, "User-context OAuth2 token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { tokenValue = it },
                )
                contextPanel.addView(BusTheme.gap(this, 12))
                contextPanel.addView(
                    input(userIdValue, "Numeric X user id", InputType.TYPE_CLASS_NUMBER) { userIdValue = it },
                )
                note("Needs a paid X API token with tweet.read + users.read. App-only tokens do not work.")
            }

            FeedSourceKind.DEMO -> {
                sectionLabel("Demo")
                note("A handful of sample posts. Works fully offline — nothing is fetched.")
            }
        }
    }

    private fun sectionLabel(label: String) {
        contextPanel.addView(BusTheme.tinyLabel(this, label))
        contextPanel.addView(BusTheme.gap(this, 10))
    }

    private fun note(text: String) {
        contextPanel.addView(BusTheme.gap(this, 12))
        contextPanel.addView(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                letterSpacing = 0.02f
                setTextColor(BusTheme.muted)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
        )
    }

    private fun input(value: String, hintText: String, type: Int, onChange: (String) -> Unit): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintText
            inputType = type
            setSingleLine(true)
            setHintTextColor(BusTheme.dim)
            setTextColor(BusTheme.text)
            textSize = 14f
            background = rounded(BusTheme.well, 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun advancedLink(): TextView =
        TextView(this).apply {
            text = "Nexus plugin access"
            textSize = 12f
            typeface = sansLight
            letterSpacing = 0.04f
            setTextColor(BusTheme.dim)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { openNexusPluginAccess() }
        }

    // ---- Actions -------------------------------------------------------------

    private fun saveSettings() {
        settingsStore.save(
            FeedsSettings(
                source = selectedSource,
                xAccountCookies = settingsStore.load().xAccountCookies,
                xBearerToken = tokenValue,
                xUserId = userIdValue,
                blueskyFeedGeneratorUri = feedUriValue,
            ),
        )
        Toast.makeText(this, "Feeds settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectXAccount() {
        settingsStore.clearXAccountCookies()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        renderContext()
        Toast.makeText(this, "X account disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openNexusPluginAccess() {
        val intent = Intent().setComponent(
            ComponentName(
                HubTarget.PHONE.packageName,
                "com.anezium.rokidbus.phone.PluginPermissionsActivity",
            ),
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Install or update Rokid Nexus first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = BusTheme.dp(this, value)

    private fun rounded(fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
        }
}
