package com.anezium.rokidbus.phone

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * A one-time walkthrough of the on-glasses setup (enable accessibility + secure
 * self-arm) so the wearer knows what to expect before putting the glasses on.
 */
class GlassesSetupGuideActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this)
        content.addView(
            NexusUi.cardBody(
                this,
                "A one-time setup, done on the glasses themselves. Put them on and " +
                    "follow the prompts on the lens — about a minute. It sticks: you " +
                    "won't redo it after a reboot.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 20))

        content.addView(
            guideStep(
                1,
                "Put the glasses on",
                "Open Nexus on the glasses (or just wear them). A short setup card " +
                    "appears on the lens.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            guideStep(
                2,
                "Turn on “Rokid Nexus Glasses”",
                "Tap Open settings on the lens, then switch on “Rokid Nexus Glasses” " +
                    "in the Accessibility list. Nexus brings you back on its own — no " +
                    "need to press Back.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            guideStep(
                3,
                "Let it arm itself",
                "Nexus finishes a secure setup by itself over an encrypted link. When " +
                    "the plugin launcher appears on the lens, you're done.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 20))
        content.addView(
            NexusUi.cardBody(
                this,
                "Stuck on a step? The setup card on the lens guides each part and can retry.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 28))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(header(), NexusUi.block())
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(NexusUi.dp(this@GlassesSetupGuideActivity, 14), NexusUi.dp(this@GlassesSetupGuideActivity, 12), NexusUi.dp(this@GlassesSetupGuideActivity, 22), NexusUi.dp(this@GlassesSetupGuideActivity, 12))
        addView(NexusUi.metaLabel(this@GlassesSetupGuideActivity, "‹", NexusUi.INK).apply {
            textSize = 26f
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            setPadding(0, 0, NexusUi.dp(this@GlassesSetupGuideActivity, 18), 0)
        })
        addView(NexusUi.metaLabel(this@GlassesSetupGuideActivity, "Set up on glasses", NexusUi.INK).apply {
            textSize = 12f
            letterSpacing = 0.16f
        })
    }

    private fun guideStep(number: Int, title: String, body: String): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                LinearLayout(this@GlassesSetupGuideActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(numberBadge(number))
                    addView(
                        LinearLayout(this@GlassesSetupGuideActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(NexusUi.cardTitle(this@GlassesSetupGuideActivity, title))
                            addView(BusTheme.gap(this@GlassesSetupGuideActivity, 4))
                            addView(NexusUi.cardBody(this@GlassesSetupGuideActivity, body))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = NexusUi.dp(this@GlassesSetupGuideActivity, 12)
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun numberBadge(number: Int): TextView =
        TextView(this).apply {
            text = number.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            val size = NexusUi.dp(this@GlassesSetupGuideActivity, 26)
            background = NexusUi.bordered(
                this@GlassesSetupGuideActivity,
                NexusUi.alpha(NexusUi.GREEN, 30),
                NexusUi.GREEN,
                999,
            )
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
}
