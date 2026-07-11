package com.anezium.rokidbus.plugin.feeds

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme

class FeedsSettingsActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }
    private lateinit var sourceSpinner: Spinner
    private lateinit var tokenInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var feedUriInput: EditText
    private lateinit var xAccountStatus: TextView
    private lateinit var xWebViewOverlayStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderSettings()
    }

    override fun onResume() {
        super.onResume()
        if (::xAccountStatus.isInitialized) renderXAccountStatus()
        if (::xWebViewOverlayStatus.isInitialized) renderXWebViewOverlayStatus()
    }

    private fun buildUi() {
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg
        sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@FeedsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                FeedSourceKind.entries.map(FeedSourceKind::displayName),
            )
        }
        tokenInput = textInput("User-context OAuth2 access token").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        userIdInput = textInput("Numeric X user id").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        feedUriInput = textInput("Bluesky feed generator URI")
        xAccountStatus = BusTheme.heroSub(this, "")
        xWebViewOverlayStatus = BusTheme.heroSub(this, "")

        val content = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@FeedsSettingsActivity, "Nexus plugin"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 12))
            addView(BusTheme.hero(this@FeedsSettingsActivity).apply { text = "Feeds" })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
            addView(BusTheme.heroSub(this@FeedsSettingsActivity, "Choose a social feed for the glasses HUD."))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 22))
            addField("Active source", sourceSpinner)
            addView(BusTheme.tinyLabel(this@FeedsSettingsActivity, "X account"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
            addView(xAccountStatus)
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Connect X account").apply {
                setOnClickListener { startActivity(Intent(this@FeedsSettingsActivity, XAccountLoginActivity::class.java)) }
            })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Disconnect X account").apply {
                setOnClickListener { disconnectXAccount() }
            })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 18))
            addView(BusTheme.tinyLabel(this@FeedsSettingsActivity, "X WebView host"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
            addView(xWebViewOverlayStatus)
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Grant display-over-apps access").apply {
                setOnClickListener { openOverlayPermission() }
            })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 18))
            addField("Official X API bearer token", tokenInput)
            addView(
                BusTheme.heroSub(
                    this@FeedsSettingsActivity,
                    "X (account) and X (WebView) share the login above. Only X (official API) uses this OAuth2 token and numeric user id.",
                ),
            )
            addView(BusTheme.gap(this@FeedsSettingsActivity, 16))
            addField("X user id", userIdInput)
            addField("Bluesky generator", feedUriInput)
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Save settings").apply {
                setOnClickListener { saveSettings() }
            })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 12))
            addView(BusTheme.pill(this@FeedsSettingsActivity, "Open Nexus plugin access").apply {
                setOnClickListener { openNexusPluginAccess() }
            })
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BusTheme.bg)
                isFillViewport = true
                addView(content)
            },
        )
    }

    private fun LinearLayout.addField(label: String, view: android.view.View) {
        addView(BusTheme.tinyLabel(this@FeedsSettingsActivity, label))
        addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(BusTheme.gap(this@FeedsSettingsActivity, 18))
    }

    private fun textInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(BusTheme.dim)
        setTextColor(BusTheme.text)
        setSingleLine(true)
    }

    private fun renderSettings() {
        val settings = settingsStore.load()
        sourceSpinner.setSelection(FeedSourceKind.entries.indexOf(settings.source))
        tokenInput.setText(settings.xBearerToken)
        userIdInput.setText(settings.xUserId)
        feedUriInput.setText(settings.blueskyFeedGeneratorUri)
        renderXAccountStatus()
        renderXWebViewOverlayStatus()
    }

    private fun saveSettings() {
        settingsStore.save(
            FeedsSettings(
                source = FeedSourceKind.entries[sourceSpinner.selectedItemPosition],
                xAccountCookies = settingsStore.load().xAccountCookies,
                xBearerToken = tokenInput.text.toString(),
                xUserId = userIdInput.text.toString(),
                blueskyFeedGeneratorUri = feedUriInput.text.toString(),
            ),
        )
        Toast.makeText(this, "Feeds settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun renderXAccountStatus() {
        xAccountStatus.text = if (settingsStore.load().xAccountCookies.isConnected) {
            "Connected. Cookies are stored privately on this phone."
        } else {
            "Disconnected. Sign in to load your X home timeline."
        }
    }

    private fun disconnectXAccount() {
        settingsStore.clearXAccountCookies()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        renderXAccountStatus()
        Toast.makeText(this, "X account disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun renderXWebViewOverlayStatus() {
        xWebViewOverlayStatus.text = if (Settings.canDrawOverlays(this)) {
            "Granted. X (WebView) can attach its invisible 1 x 1 browser window."
        } else {
            "One-time grant required only for X (WebView). The browser window stays fully transparent."
        }
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
}
