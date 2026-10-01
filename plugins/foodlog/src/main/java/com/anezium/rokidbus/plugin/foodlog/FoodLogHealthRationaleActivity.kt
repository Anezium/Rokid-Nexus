package com.anezium.rokidbus.plugin.foodlog

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi

/** Privacy policy shown from Health Connect's permission surface. */
class FoodLogHealthRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@FoodLogHealthRationaleActivity, "Health Connect"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogHealthRationaleActivity, 10))
            addView(
                NexusUi.card(this@FoodLogHealthRationaleActivity).apply {
                    addView(NexusUi.cardTitle(this@FoodLogHealthRationaleActivity, "Your nutrition stays under your control"))
                    addView(BusTheme.gap(this@FoodLogHealthRationaleActivity, 8))
                    addView(
                        NexusUi.cardBody(
                            this@FoodLogHealthRationaleActivity,
                            "Food Log only asks for permission to write nutrition records. It never reads Health Connect. " +
                                "Synchronization is off by default and only runs after you enable it in Food Log. " +
                                "Your local journal remains the source of truth, and no food history is sent to Rokid Nexus or Open Food Facts.",
                        ),
                    )
                },
                NexusUi.block(),
            )
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(
                    NexusUi.pluginHeader(
                        this@FoodLogHealthRationaleActivity,
                        NexusPluginIcons.drawableFor("heart"),
                        "Food Log privacy",
                        "Health Connect rationale",
                    ),
                    NexusUi.block(),
                )
                addView(
                    NexusUi.screen(this@FoodLogHealthRationaleActivity, content),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }
}
