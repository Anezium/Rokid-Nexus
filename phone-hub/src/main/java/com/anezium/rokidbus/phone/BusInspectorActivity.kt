package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BusInspectorActivity : Activity() {
    private lateinit var countValue: TextView
    private lateinit var filterBar: LinearLayout
    private lateinit var eventColumn: LinearLayout
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var pluginFilter: String? = null
    private var lastRendered: List<PluginBusJournal.Event> = emptyList()

    private val refreshTick = object : Runnable {
        override fun run() {
            render()
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        countValue = NexusUi.rowValue(this)
        filterBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eventColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@BusInspectorActivity, "Bus inspector"), NexusUi.block())
            addView(BusTheme.gap(this@BusInspectorActivity, 6))
            addView(
                NexusUi.metaLabel(
                    this@BusInspectorActivity,
                    "Live plugin traffic and rejections while developer mode is on",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@BusInspectorActivity, 12))
            addView(
                HorizontalScrollView(this@BusInspectorActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(filterBar)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@BusInspectorActivity, 12))
            addView(eventCard(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.screen(this@BusInspectorActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        lastRendered = emptyList()
        refreshHandler.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshTick)
    }

    private fun eventCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                LinearLayout(this@BusInspectorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(this@BusInspectorActivity, "Events"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(countValue)
                },
            )
            addView(BusTheme.gap(this@BusInspectorActivity, 10))
            addView(eventColumn)
        }

    private fun render() {
        val events = BusHubService.busJournal.snapshot().asReversed()
        if (events == lastRendered) return
        lastRendered = events

        renderFilterBar(events)

        val visible = pluginFilter?.let { filter ->
            events.filter { it.pluginId == filter }
        } ?: events
        countValue.text = if (pluginFilter == null) {
            "${events.size}"
        } else {
            "${visible.size} / ${events.size}"
        }

        eventColumn.removeAllViews()
        if (visible.isEmpty()) {
            eventColumn.addView(
                NexusUi.metaLabel(
                    this,
                    if (BusHubService.busJournal.enabled.get()) {
                        "No events yet. Open a plugin from the glasses launcher."
                    } else {
                        "Developer mode is off — the journal is not recording."
                    },
                ),
            )
            return
        }
        visible.take(MAX_ROWS).forEach { event -> eventColumn.addView(eventRow(event)) }
        if (visible.size > MAX_ROWS) {
            eventColumn.addView(BusTheme.gap(this, 6))
            eventColumn.addView(
                NexusUi.metaLabel(this, "…${visible.size - MAX_ROWS} older events not shown"),
            )
        }
    }

    private fun renderFilterBar(events: List<PluginBusJournal.Event>) {
        val plugins = events.mapNotNull { it.pluginId }.distinct().sorted()
        filterBar.removeAllViews()
        filterBar.addView(filterChip("All", pluginFilter == null) { pluginFilter = null })
        plugins.forEach { pluginId ->
            filterBar.addView(
                android.view.View(this),
                LinearLayout.LayoutParams(NexusUi.dp(this, 8), 1),
            )
            filterBar.addView(
                filterChip(pluginId, pluginFilter == pluginId) { pluginFilter = pluginId },
            )
        }
        if (pluginFilter != null && pluginFilter !in plugins) pluginFilter = null
    }

    private fun filterChip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(if (selected) NexusUi.BG else NexusUi.INK2)
            background = if (selected) {
                NexusUi.rounded(this@BusInspectorActivity, NexusUi.GREEN, 999)
            } else {
                NexusUi.bordered(this@BusInspectorActivity, NexusUi.PANEL, NexusUi.LINE, 999)
            }
            setPadding(
                NexusUi.dp(this@BusInspectorActivity, 12),
                NexusUi.dp(this@BusInspectorActivity, 6),
                NexusUi.dp(this@BusInspectorActivity, 12),
                NexusUi.dp(this@BusInspectorActivity, 6),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                lastRendered = emptyList()
                render()
            }
        }

    private fun eventRow(event: PluginBusJournal.Event): TextView =
        TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(0, NexusUi.dp(this@BusInspectorActivity, 3), 0, NexusUi.dp(this@BusInspectorActivity, 3))
            val rejected = event.verdict == PluginBusJournal.Verdict.REJECTED
            setTextColor(if (rejected) NexusUi.AMBER else NexusUi.INK3)
            text = buildString {
                append(timeFormat.format(Date(event.timestampMillis)))
                append("  ")
                append(event.category.name.padEnd(12))
                append(directionGlyph(event.direction))
                append("  ")
                append(event.pluginId ?: "—")
                event.path?.let { append("  ").append(it) }
                event.sizeBytes?.let { append("  ").append(formatSize(it)) }
                if (rejected) {
                    append("  ✕ ")
                    append(event.reason ?: "rejected")
                }
            }
        }

    private fun directionGlyph(direction: PluginBusJournal.Direction): String = when (direction) {
        PluginBusJournal.Direction.PLUGIN_TO_HUB -> " →hub"
        PluginBusJournal.Direction.HUB_TO_PLUGIN -> " →plug"
        PluginBusJournal.Direction.GLASSES_TO_HUB -> " ⇠glass"
        PluginBusJournal.Direction.HUB_TO_GLASSES -> " ⇢glass"
    }

    private fun formatSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1fMiB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "%.1fKiB".format(bytes / 1024f)
        else -> "${bytes}B"
    }

    private companion object {
        const val REFRESH_MS = 1500L
        const val MAX_ROWS = 200
    }
}
