package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.PluginCustomIcon
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import java.util.UUID

/**
 * The owner's launcher favourites: up to four applications pinned on the glasses home, under
 * the activities, in the order chosen here. Two sections: the pinned rows, each with up, down
 * and remove; then every other application the glasses list, one tap to pin.
 *
 * The glasses' own apps arrive asynchronously, like on the glasses apps screen; the Nexus
 * plugins and the camera come from the hub straight away. A pinned application the phone
 * cannot see any more is still listed, marked, with its remove control: it is never dropped
 * behind the owner's back.
 */
class LauncherFavouritesActivity : Activity() {
    private lateinit var store: PhoneLauncherFavouritesStore
    private lateinit var content: LinearLayout
    private var nativeApps: List<NativeGlassesApp> = emptyList()
    private var nativeStatus: String? = null
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.let(NativeAppsPhoneContract::parseState) ?: return
            when (state) {
                NativeAppsUiState.Loading -> nativeStatus = STATUS_LOADING
                NativeAppsUiState.Empty -> {
                    nativeApps = emptyList()
                    nativeStatus = null
                }
                is NativeAppsUiState.Error -> nativeStatus = state.message
                is NativeAppsUiState.Content -> {
                    nativeApps = state.apps
                    nativeStatus = null
                }
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PhoneLauncherFavouritesStore.shared(this)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        render()
    }

    override fun onStart() {
        super.onStart()
        BusHubService.start(applicationContext)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(NativeAppsPhoneContract.ACTION_STATE),
                INTERNAL_CORE_PERMISSION,
                null,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        nativeStatus = STATUS_LOADING
        render()
        sendBroadcast(NativeAppsPhoneContract.requestList(this, UUID.randomUUID().toString()))
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun catalog(): LauncherFavouritesCatalog = LauncherFavouritesCatalog.build(
        favourites = store.entries(),
        plugins = BusHubService.launcherApplications(this),
        cameraName = BusHubService.launcherCameraName(),
        nativeApps = nativeApps,
    )

    private fun render() {
        val catalog = catalog()
        val full = catalog.pinned.size >= LauncherFavouritesContract.MAX_ENTRIES
        content.removeAllViews()
        content.addView(
            NexusUi.cardBody(this, "Four slots on the glasses home, right under the activities. One tap opens the app."),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 22))

        content.addView(
            NexusUi.sectionRow(this, "Pinned", "${catalog.pinned.size} / ${LauncherFavouritesContract.MAX_ENTRIES}"),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            listCard().apply {
                if (catalog.pinned.isEmpty()) {
                    addView(emptyRow("Nothing pinned yet. Pick from the list below."), NexusUi.block())
                }
                catalog.pinned.forEachIndexed { index, item ->
                    if (index > 0) addView(hairline())
                    addView(pinnedRow(item, index, catalog.pinned.lastIndex), NexusUi.block())
                }
            },
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Add", if (full) "Full" else null), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        if (full) {
            content.addView(NexusUi.cardBody(this, "Remove one above to pin another."), NexusUi.block())
            content.addView(BusTheme.gap(this, 10))
        }
        content.addView(
            listCard().apply {
                catalog.available.forEachIndexed { index, item ->
                    if (index > 0) addView(hairline())
                    addView(availableRow(item, enabled = !full), NexusUi.block())
                }
                val status = nativeStatus
                if (status != null) {
                    if (catalog.available.isNotEmpty()) addView(hairline())
                    addView(emptyRow(status), NexusUi.block())
                } else if (catalog.available.isEmpty()) {
                    addView(emptyRow("Everything the glasses list is already pinned."), NexusUi.block())
                }
            },
            NexusUi.block(),
        )
    }

    private fun listCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@LauncherFavouritesActivity, NexusUi.PANEL, NexusUi.LINE2, 15)
        setPadding(0, NexusUi.dp(this@LauncherFavouritesActivity, 4), 0, NexusUi.dp(this@LauncherFavouritesActivity, 4))
    }

    private fun hairline(): View = View(this).apply {
        setBackgroundColor(NexusUi.LINE2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(this@LauncherFavouritesActivity, 1)).apply {
            marginStart = NexusUi.dp(this@LauncherFavouritesActivity, 15)
            marginEnd = NexusUi.dp(this@LauncherFavouritesActivity, 15)
        }
    }

    private fun emptyRow(text: String): TextView = NexusUi.cardBody(this, text).apply {
        setPadding(
            NexusUi.dp(this@LauncherFavouritesActivity, 15),
            NexusUi.dp(this@LauncherFavouritesActivity, 12),
            NexusUi.dp(this@LauncherFavouritesActivity, 15),
            NexusUi.dp(this@LauncherFavouritesActivity, 12),
        )
    }

    /** Slot number, mark, name, then up, down and remove. The ends of the list grey out their arrow. */
    private fun pinnedRow(item: LauncherFavouriteItem, index: Int, lastIndex: Int): LinearLayout =
        row().apply {
            addView(
                NexusUi.metaLabel(this@LauncherFavouritesActivity, "${index + 1}", NexusUi.INK4).apply {
                    textSize = 11f
                    minWidth = NexusUi.dp(this@LauncherFavouritesActivity, 14)
                },
            )
            addView(gap(10))
            addView(iconTile(item))
            addView(gap(12))
            addView(labels(item), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(glyphButton("↑", enabled = index > 0) { change { store.move(item.favourite, -1) } })
            addView(glyphButton("↓", enabled = index < lastIndex) { change { store.move(item.favourite, +1) } })
            addView(glyphButton("×", enabled = true, color = NexusUi.INK2) { change { store.unpin(item.favourite) } })
        }

    /** Mark, name and kind; the whole row pins. Once four are pinned the rows dim and stop reacting. */
    private fun availableRow(item: LauncherFavouriteItem, enabled: Boolean): LinearLayout =
        row().apply {
            if (enabled) {
                background = NexusUi.pressed(this@LauncherFavouritesActivity, Color.TRANSPARENT, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { change { store.pin(item.favourite) } }
            } else {
                alpha = 0.4f
            }
            addView(iconTile(item))
            addView(gap(12))
            addView(labels(item), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                NexusUi.metaLabel(this@LauncherFavouritesActivity, "Pin", if (enabled) NexusUi.GREEN else NexusUi.INK3).apply {
                    textSize = 11f
                },
            )
        }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = NexusUi.dp(this@LauncherFavouritesActivity, 58)
        setPadding(
            NexusUi.dp(this@LauncherFavouritesActivity, 15),
            NexusUi.dp(this@LauncherFavouritesActivity, 8),
            NexusUi.dp(this@LauncherFavouritesActivity, 9),
            NexusUi.dp(this@LauncherFavouritesActivity, 8),
        )
    }

    private fun labels(item: LauncherFavouriteItem): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            NexusUi.rowTitle(this@LauncherFavouritesActivity, item.label).apply {
                maxLines = 1
                if (!item.available) setTextColor(NexusUi.INK2)
            },
        )
        addView(BusTheme.gap(this@LauncherFavouritesActivity, 2))
        addView(
            NexusUi.rowSub(this@LauncherFavouritesActivity, item.detail).apply {
                if (!item.available) setTextColor(NexusUi.AMBER)
            },
        )
    }

    private fun iconTile(item: LauncherFavouriteItem): View = when (val icon = item.icon) {
        is LauncherFavouriteIcon.Plugin -> NexusUi.iconTileDrawable(
            this,
            NexusPluginIcons.resolve(
                context = this,
                iconKey = icon.iconKey,
                customIcon = icon.iconDrawableResId?.let { resId -> icon.packageName?.let { PluginCustomIcon(it, resId) } },
                pluginId = icon.pluginId,
            ),
        )
        is LauncherFavouriteIcon.Letter -> NexusUi.iconTile(this, icon.glyph)
    }.apply { if (!item.available) alpha = 0.5f }

    private fun glyphButton(glyph: String, enabled: Boolean, color: Int = NexusUi.INK, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 18f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(if (enabled) color else NexusUi.INK4)
            background = NexusUi.pressed(this@LauncherFavouritesActivity, Color.TRANSPARENT, 18)
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            if (enabled) setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@LauncherFavouritesActivity, 36),
                NexusUi.dp(this@LauncherFavouritesActivity, 36),
            )
        }

    private fun gap(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(NexusUi.dp(this@LauncherFavouritesActivity, widthDp), 1)
    }

    /** Applies one store change: the glasses get the whole list again, then the screen redraws. */
    private fun change(apply: () -> Boolean) {
        if (!apply()) return
        BusHubService.onLauncherFavouritesChanged()
        render()
    }

    private fun titleHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(this@LauncherFavouritesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    NexusUi.dp(this@LauncherFavouritesActivity, 10),
                    NexusUi.dp(this@LauncherFavouritesActivity, 12),
                    NexusUi.dp(this@LauncherFavouritesActivity, 22),
                    NexusUi.dp(this@LauncherFavouritesActivity, 12),
                )
                addView(NexusUi.backButton(this@LauncherFavouritesActivity) { finish() })
                addView(
                    NexusUi.metaLabel(this@LauncherFavouritesActivity, "Favourites", NexusUi.INK).apply {
                        textSize = 12f
                        letterSpacing = 0.2f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
            NexusUi.block(),
        )
        addView(
            View(this@LauncherFavouritesActivity).apply {
                setBackgroundColor(NexusUi.LINE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(this@LauncherFavouritesActivity, 1))
            },
        )
    }

    private companion object {
        const val STATUS_LOADING = "Looking for apps on the glasses…"
    }
}
