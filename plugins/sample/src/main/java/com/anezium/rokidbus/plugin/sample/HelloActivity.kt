package com.anezium.rokidbus.plugin.sample

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class HelloActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private var pushClient: NexusPluginClient? = null
    private val todo by lazy { TodoSection(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        todo.start()
    }

    override fun onStop() {
        todo.stop()
        super.onStop()
    }

    override fun onDestroy() {
        releaseClient()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@HelloActivity, "About this template"), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 10))
            addView(
                NexusUi.cardBody(
                    this@HelloActivity,
                    "A headless Nexus plugin demonstrating image and card surfaces, swipe input, tap actions, and back-to-close.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@HelloActivity, 24))
            addView(NexusUi.sectionRow(this@HelloActivity, "Background pin"), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 10))
            addView(backgroundPinCard(), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 8))
            addView(statusLine(), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 24))
            todo.addTo(this)
            addView(BusTheme.gap(this@HelloActivity, 24))
            addView(NexusUi.sectionRow(this@HelloActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@HelloActivity,
                    NexusPluginIcons.drawableFor("star"),
                    "Sample Plugin",
                    "Headless plugin template · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@HelloActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun backgroundPinCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(NexusUi.rowTitle(this@HelloActivity, "Pin something from the phone"))
        addView(
            NexusUi.rowSub(
                this@HelloActivity,
                "No surface. Works with the glasses off.",
            ),
        )
        setOnClickListener { pushBackgroundPin() }
    }

    private fun statusLine(): TextView = NexusUi.statusLine(this).also {
        it.text = "Idle"
        status = it
    }

    /**
     * The shape a real background plugin uses, and the reason `showPin` lives on the
     * client rather than a surface session: build a client, connect, push once, close.
     * A ride-hailing plugin does exactly this from its notification listener — nothing
     * here needs a surface, a launcher entry, or the hub having opened us first.
     */
    private fun pushBackgroundPin() {
        releaseClient()
        report("Connecting…")
        val client = NexusPluginClient.create(
            context = applicationContext,
            pluginId = PLUGIN_ID,
            callbacks = object : NexusPluginCallbacks {
                override fun onOpen() = Unit
                override fun onClose() = Unit
                override fun onInput(event: NexusInputEvent) = Unit
                override fun onLinkState(state: Int) = Unit

                override fun onRegistrationState(result: Int) {
                    if (result != PluginRegistrationResult.APPROVED) {
                        main.post { report("Not approved — grant this plugin in Nexus first.") }
                        return
                    }
                    main.post { pushAndDisconnect() }
                }
            },
        )
        pushClient = client
        client.connect()
    }

    private fun pushAndDisconnect() {
        val client = pushClient ?: return
        val result = client.showPin(
            NexusPin(
                title = "PUSHED FROM PHONE",
                lines = listOf("no surface involved"),
            ),
        )
        report(
            when (result) {
                NexusSdkResult.SENT ->
                    "Pinned. Disconnected — it stays without us, for 30 minutes."
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE ->
                    "These glasses cannot show pins."
                NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                    "Surfaces not granted to this plugin."
                else -> "Could not pin: $result"
            },
        )
        // Going away immediately is the point, not a shortcut: the pin is the hub's now.
        releaseClient()
    }

    private fun releaseClient() {
        pushClient?.let { runCatching { it.close() } }
        pushClient = null
    }

    private fun report(message: String) {
        status?.text = message
    }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Hello Nexus") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val PLUGIN_ID = "hello"
    }
}
