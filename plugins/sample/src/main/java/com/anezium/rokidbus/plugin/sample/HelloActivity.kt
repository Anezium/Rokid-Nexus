package com.anezium.rokidbus.plugin.sample

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

class HelloActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
            addView(NexusUi.sectionRow(this@HelloActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@HelloActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@HelloActivity,
                    BusClientR.drawable.ic_plugin_bus,
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

    private fun uninstallRow() = NexusUi.pressableCard(this).apply {
        val uninstall = {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }
        addView(
            LinearLayout(this@HelloActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@HelloActivity, "Uninstall"))
                addView(BusTheme.gap(this@HelloActivity, 4))
                addView(NexusUi.rowSub(this@HelloActivity, "Remove Sample Plugin from this phone"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            NexusUi.textButton(this@HelloActivity, "Uninstall", danger = true).apply {
                setOnClickListener { uninstall() }
            },
        )
        setOnClickListener { uninstall() }
    }
}
