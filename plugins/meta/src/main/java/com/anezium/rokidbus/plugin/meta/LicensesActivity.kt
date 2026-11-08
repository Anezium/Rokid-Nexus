package com.anezium.rokidbus.plugin.meta

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

/** The bundled third-party notices, read from the plugin's assets and shown as one scrolling page. */
class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this, topDp = 14)
        content.addView(TextView(this).apply {
            text = readNotice() ?: "Licenses unavailable"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(NexusUi.INK2)
            setTextIsSelectable(true)
        }, NexusUi.block())
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@LicensesActivity, NexusUi.iconTile(this@LicensesActivity, "§", 48),
                "Open-source licenses", "Meta Messages", onBack = { finish() }), NexusUi.block())
            addView(NexusUi.screen(this@LicensesActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun readNotice(): String? = runCatching {
        assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
