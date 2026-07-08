package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme

class StoreActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val list = NexusUi.contentColumn(this, topDp = 8).apply {
            addView(
                storeCard(
                    glyph = "\u2726",
                    title = "Relay",
                    meta = "Messaging \u00B7 Anezium",
                    description = "Reply to your phone notifications from your glasses, by voice.",
                    size = "1.2 MB",
                    button = "Install",
                    featured = true,
                    badge = "New",
                ) { comingSoon() },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@StoreActivity, 10))
            addView(
                storeCard(
                    glyph = "\u266A",
                    title = "Lyrics",
                    meta = "Audio \u00B7 installed",
                    description = "Synced lyrics for whatever is playing on your phone.",
                    size = "0.9 MB",
                    button = "Open",
                    featured = false,
                    badge = null,
                ) {
                    startActivity(Intent(this@StoreActivity, LyricsSettingsActivity::class.java))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@StoreActivity, 10))
            addView(
                storeCard(
                    glyph = "\u25CF",
                    title = "Scribe",
                    meta = "Audio \u00B7 Anezium",
                    description = "Dictate voice notes, transcribed on your phone.",
                    size = "1.6 MB",
                    button = "Install",
                    featured = false,
                    badge = null,
                ) { comingSoon() },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@StoreActivity, 10))
            addView(
                storeCard(
                    glyph = "\u25C6",
                    title = "Transit",
                    meta = "Transit \u00B7 installed",
                    description = "Nearby departures on your glasses.",
                    size = "0.8 MB",
                    button = "Open",
                    featured = false,
                    badge = null,
                ) {
                    startActivity(Intent(this@StoreActivity, TransitSettingsActivity::class.java))
                },
                NexusUi.block(),
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(storeHeader(), NexusUi.block())
            addView(chips(), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    private fun storeHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                NexusUi.dp(this@StoreActivity, 22),
                NexusUi.dp(this@StoreActivity, 16),
                NexusUi.dp(this@StoreActivity, 22),
                0,
            )
            addView(
                NexusUi.metaLabel(this@StoreActivity, "\u2039 Home", NexusUi.INK3).apply {
                    letterSpacing = 0.2f
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { finish() }
                    background = NexusUi.pressed(this@StoreActivity, android.graphics.Color.TRANSPARENT, 12)
                    setPadding(0, NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8))
                },
            )
            addView(BusTheme.gap(this@StoreActivity, 8))
            addView(
                NexusUi.hero(this@StoreActivity, 30f).apply {
                    val label = "Store."
                    text = SpannableString(label).apply {
                        setSpan(
                            ForegroundColorSpan(NexusUi.GREEN),
                            label.lastIndex,
                            label.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                },
            )
            addView(BusTheme.gap(this@StoreActivity, 5))
            addView(NexusUi.rowSub(this@StoreActivity, "Plugins for your glasses"))
        }

    private fun chips(): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                LinearLayout(this@StoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(
                        NexusUi.dp(this@StoreActivity, 22),
                        NexusUi.dp(this@StoreActivity, 16),
                        NexusUi.dp(this@StoreActivity, 22),
                        NexusUi.dp(this@StoreActivity, 4),
                    )
                    listOf("All", "Audio", "Transit", "Messaging", "Tools").forEachIndexed { index, label ->
                        addView(
                            chip(label, selected = index == 0),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (index > 0) marginStart = NexusUi.dp(this@StoreActivity, 8)
                            },
                        )
                    }
                },
            )
        }

    private fun chip(label: String, selected: Boolean): TextView =
        NexusUi.metaLabel(this, label, if (selected) NexusUi.ON_ACCENT else NexusUi.INK2).apply {
            setPadding(
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
            )
            background = if (selected) {
                NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 20)
            } else {
                NexusUi.bordered(this@StoreActivity, android.graphics.Color.TRANSPARENT, NexusUi.LINE2, 20)
            }
        }

    private fun storeCard(
        glyph: String,
        title: String,
        meta: String,
        description: String,
        size: String,
        button: String,
        featured: Boolean,
        badge: String?,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                context = this@StoreActivity,
                fill = if (featured) NexusUi.alpha(NexusUi.GREEN, 0x08) else NexusUi.PANEL,
                stroke = if (featured) 0xFF2C4A37.toInt() else NexusUi.LINE2,
                radius = 16,
            )
            setPadding(
                NexusUi.dp(this@StoreActivity, 15),
                NexusUi.dp(this@StoreActivity, 15),
                NexusUi.dp(this@StoreActivity, 15),
                NexusUi.dp(this@StoreActivity, 15),
            )
            addView(
                LinearLayout(this@StoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(NexusUi.iconTile(this@StoreActivity, glyph, 40))
                    addView(
                        LinearLayout(this@StoreActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(NexusUi.cardTitle(this@StoreActivity, title))
                            addView(BusTheme.gap(this@StoreActivity, 5))
                            addView(NexusUi.metaLabel(this@StoreActivity, meta, NexusUi.INK3).apply {
                                letterSpacing = 0.06f
                            })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = NexusUi.dp(this@StoreActivity, 13)
                        },
                    )
                    if (badge != null) {
                        addView(
                            NexusUi.metaLabel(this@StoreActivity, badge, NexusUi.ON_ACCENT).apply {
                                textSize = 9f
                                letterSpacing = 0.16f
                                background = NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 6)
                                setPadding(
                                    NexusUi.dp(this@StoreActivity, 7),
                                    NexusUi.dp(this@StoreActivity, 4),
                                    NexusUi.dp(this@StoreActivity, 7),
                                    NexusUi.dp(this@StoreActivity, 4),
                                )
                            },
                        )
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@StoreActivity, 11))
            addView(
                NexusUi.cardBody(this@StoreActivity, description).apply {
                    textSize = 12.5f
                    maxLines = 2
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@StoreActivity, 13))
            addView(
                LinearLayout(this@StoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.metaLabel(this@StoreActivity, size, NexusUi.INK4).apply {
                            letterSpacing = 0.06f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(storeButton(button, filled = button == "Install", onClick = onClick))
                },
                NexusUi.block(),
            )
        }

    private fun storeButton(label: String, filled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setAllCaps(false)
            stateListAnimator = null
            minHeight = NexusUi.dp(this@StoreActivity, 38)
            minimumHeight = NexusUi.dp(this@StoreActivity, 38)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(NexusUi.dp(this@StoreActivity, 18), 0, NexusUi.dp(this@StoreActivity, 18), 0)
            setTextColor(if (filled) NexusUi.ON_ACCENT else NexusUi.GREEN)
            background = if (filled) {
                NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 20)
            } else {
                NexusUi.bordered(this@StoreActivity, android.graphics.Color.TRANSPARENT, 0xFF2C4A37.toInt(), 20)
            }
            setOnClickListener { onClick() }
        }

    private fun comingSoon() {
        Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
    }
}
