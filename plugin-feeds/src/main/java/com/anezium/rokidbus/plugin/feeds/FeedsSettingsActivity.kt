package com.anezium.rokidbus.plugin.feeds

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme

class FeedsSettingsActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }
    private lateinit var sourceSpinner: Spinner
    private lateinit var tokenInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var feedUriInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderSettings()
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

        val content = BusTheme.root(this).apply {
            addView(BusTheme.wordmark(this@FeedsSettingsActivity, "Nexus plugin"))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 12))
            addView(BusTheme.hero(this@FeedsSettingsActivity).apply { text = "Feeds" })
            addView(BusTheme.gap(this@FeedsSettingsActivity, 8))
            addView(BusTheme.heroSub(this@FeedsSettingsActivity, "Choose a social feed for the glasses HUD."))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 22))
            addField("Active source", sourceSpinner)
            addField("X bearer token", tokenInput)
            addView(
                BusTheme.heroSub(
                    this@FeedsSettingsActivity,
                    "X requires a user-context OAuth2 token with tweet.read and users.read; app-only tokens do not work.",
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
    }

    private fun saveSettings() {
        settingsStore.save(
            FeedsSettings(
                source = FeedSourceKind.entries[sourceSpinner.selectedItemPosition],
                xBearerToken = tokenInput.text.toString(),
                xUserId = userIdInput.text.toString(),
                blueskyFeedGeneratorUri = feedUriInput.text.toString(),
            ),
        )
        Toast.makeText(this, "Feeds settings saved", Toast.LENGTH_SHORT).show()
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
