package com.anezium.rokidbus.plugin.feeds

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

class FeedsSettingsActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }
    private lateinit var selectedSource: FeedSourceKind
    private lateinit var sourceList: LinearLayout
    private lateinit var contextPanel: LinearLayout
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
        if (::contextPanel.isInitialized && selectedSource.isXSession) renderContext()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contextPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(this@FeedsSettingsActivity, "Pick where your glasses feed comes from."),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@FeedsSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@FeedsSettingsActivity, "Source"), NexusUi.block())
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(sourceList, NexusUi.block())
            addView(BusTheme.gap(this@FeedsSettingsActivity, 22))
            addView(contextPanel, NexusUi.block())
            addView(BusTheme.gap(this@FeedsSettingsActivity, 20))
            addView(
                NexusUi.pillButton(this@FeedsSettingsActivity, "Save settings").apply {
                    setOnClickListener { saveSettings() }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@FeedsSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@FeedsSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@FeedsSettingsActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@FeedsSettingsActivity,
                    BusClientR.drawable.ic_plugin_send,
                    "Feeds",
                    "Social timelines · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@FeedsSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderSourceList()
        renderContext()
    }

    private fun renderSourceList() {
        sourceList.removeAllViews()
        FeedSourceKind.entries.forEachIndexed { index, kind ->
            if (index > 0) sourceList.addView(BusTheme.gap(this, 8))
            sourceList.addView(sourceRow(kind), NexusUi.block())
        }
    }

    private fun sourceRow(kind: FeedSourceKind) = NexusUi.pressableCard(this).apply {
        val labels = LinearLayout(this@FeedsSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(NexusUi.rowTitle(this@FeedsSettingsActivity, kind.title))
            addView(BusTheme.gap(this@FeedsSettingsActivity, 4))
            addView(NexusUi.rowSub(this@FeedsSettingsActivity, kind.blurb))
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            NexusUi.metaLabel(
                this@FeedsSettingsActivity,
                if (kind == selectedSource) "Selected" else "Choose",
                if (kind == selectedSource) NexusUi.GREEN else NexusUi.INK3,
            ),
        )
        setOnClickListener {
            if (selectedSource != kind) {
                selectedSource = kind
                renderSourceList()
                renderContext()
            }
        }
    }

    private fun renderContext() {
        contextPanel.removeAllViews()
        when (selectedSource) {
            FeedSourceKind.BLUESKY -> {
                addContextHeading("Bluesky feed")
                contextPanel.addView(
                    input(feedUriValue, "Feed generator URI", InputType.TYPE_CLASS_TEXT) { feedUriValue = it },
                    NexusUi.block(),
                )
                addNote("Any Bluesky feed generator. Leave as-is for What's Hot. No account is needed.")
            }
            FeedSourceKind.X_ACCOUNT, FeedSourceKind.X_WEBVIEW -> renderXContext()
            FeedSourceKind.X_OFFICIAL -> {
                addContextHeading("Official X API")
                contextPanel.addView(
                    input(
                        tokenValue,
                        "User-context OAuth2 token",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    ) { tokenValue = it },
                    NexusUi.block(),
                )
                contextPanel.addView(BusTheme.gap(this, 10))
                contextPanel.addView(
                    input(userIdValue, "Numeric X user id", InputType.TYPE_CLASS_NUMBER) { userIdValue = it },
                    NexusUi.block(),
                )
                addNote("Needs a paid X API token with tweet.read and users.read. App-only tokens do not work.")
            }
            FeedSourceKind.DEMO -> {
                addContextHeading("Demo")
                addNote("A handful of sample posts. Works fully offline; nothing is fetched.")
            }
        }
    }

    private fun renderXContext() {
        addContextHeading(if (selectedSource == FeedSourceKind.X_WEBVIEW) "X WebView session" else "X account")
        val connected = settingsStore.load().xAccountCookies.isConnected
        val overlayNeeded = selectedSource == FeedSourceKind.X_WEBVIEW
        contextPanel.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.rowTitle(this@FeedsSettingsActivity, if (connected) "X account connected" else "X account not connected"))
                addView(BusTheme.gap(this@FeedsSettingsActivity, 5))
                addView(
                    NexusUi.rowSub(
                        this@FeedsSettingsActivity,
                        if (!overlayNeeded) "Cookies stay on this phone" else if (Settings.canDrawOverlays(this@FeedsSettingsActivity)) "Overlay access granted" else "Overlay access needed",
                    ),
                )
            },
            NexusUi.block(),
        )
        contextPanel.addView(BusTheme.gap(this, 10))
        contextPanel.addView(
            NexusUi.pillButton(this, if (connected) "Reconnect X account" else "Connect X account").apply {
                setOnClickListener { startActivity(Intent(this@FeedsSettingsActivity, XAccountLoginActivity::class.java)) }
            },
            NexusUi.block(),
        )
        if (overlayNeeded && !Settings.canDrawOverlays(this)) {
            contextPanel.addView(BusTheme.gap(this, 10))
            contextPanel.addView(
                NexusUi.outlinePillButton(this, "Grant overlay access").apply {
                    setOnClickListener { openOverlayPermission() }
                },
                NexusUi.block(),
            )
        }
        if (connected) {
            contextPanel.addView(BusTheme.gap(this, 10))
            contextPanel.addView(
                NexusUi.pillButton(this, "Disconnect", danger = true).apply {
                    setOnClickListener { disconnectXAccount() }
                },
                NexusUi.block(),
            )
        }
        addNote(
            if (overlayNeeded) {
                "Loads your home timeline in a hidden 1 x 1 browser window and reads the feed it fetches."
            } else {
                "Reads your home timeline through X's internal API. Cookies stay on this phone."
            },
        )
    }

    private fun addContextHeading(label: String) {
        contextPanel.addView(NexusUi.sectionRow(this, label), NexusUi.block())
        contextPanel.addView(BusTheme.gap(this, 10))
    }

    private fun addNote(text: String) {
        contextPanel.addView(BusTheme.gap(this, 12))
        contextPanel.addView(NexusUi.cardBody(this, text), NexusUi.block())
    }

    private fun input(value: String, hint: String, type: Int, onChange: (String) -> Unit): EditText =
        NexusUi.field(this, hint).apply {
            setText(value)
            inputType = type
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) = onChange(value?.toString().orEmpty())
            })
        }

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
        expireXCookies()
        renderContext()
        Toast.makeText(this, "X account disconnected", Toast.LENGTH_SHORT).show()
    }

    // The WebView cookie jar is shared with the whole hub (Spotify login included),
    // so expire the X session cookies only instead of clearing everything.
    private fun expireXCookies() {
        val manager = CookieManager.getInstance()
        listOf("https://x.com", "https://twitter.com").forEach { origin ->
            val host = Uri.parse(origin).host.orEmpty()
            manager.getCookie(origin)?.split(';')?.forEach { cookie ->
                val name = cookie.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(origin, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    manager.setCookie(origin, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$host")
                }
            }
        }
        manager.flush()
    }

    private fun openOverlayPermission() {
        val packageUri = Uri.parse("package:$packageName")
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun uninstallRow() = NexusUi.pressableCard(this).apply {
        val uninstall = {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }
        addView(
            LinearLayout(this@FeedsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@FeedsSettingsActivity, "Uninstall"))
                addView(BusTheme.gap(this@FeedsSettingsActivity, 4))
                addView(NexusUi.rowSub(this@FeedsSettingsActivity, "Remove Feeds from this phone"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            NexusUi.textButton(this@FeedsSettingsActivity, "Uninstall", danger = true).apply {
                setOnClickListener { uninstall() }
            },
        )
        setOnClickListener { uninstall() }
    }
}
