package com.anezium.rokidbus.plugin.nav

import android.content.Context
import com.anezium.rokidbus.client.plugin.NexusCard

/**
 * What opening Navigation shows: the whole current instruction, which the
 * activity only has room to abbreviate, or why there is none.
 */
internal object NavCard {
    const val SURFACE_ID = "nav"

    fun build(context: Context): NexusCard {
        val guidance = NavState.guidance
        return when {
            // Checked first: without the listener the last route is not current.
            !NavState.listenerConnected -> message(context, R.string.nav_card_no_access, "nav-no-access")
            !NavSettings(context).switches().enabled -> message(context, R.string.nav_card_off, "nav-off")
            guidance != null -> NexusCard(
                title = NavText.fit(guidance.instruction ?: guidance.primary, MAX_TITLE_CHARS),
                subtitle = guidance.source.label,
                lines = (
                    listOfNotNull(
                        listOfNotNull(guidance.primary, guidance.measure, guidance.secondary).joinToString(" · "),
                        guidance.eta?.let { context.getString(R.string.nav_card_eta, it) },
                    ) + guidance.detail
                    ).map { NavText.fit(it, MAX_LINE_CHARS) },
                contentKey = "nav-route",
            )
            else -> message(context, R.string.nav_card_idle, "nav-idle")
        }
    }

    private fun message(context: Context, text: Int, key: String) = NexusCard(
        title = context.getString(R.string.app_name),
        lines = listOf(context.getString(text)),
        contentKey = key,
    )

    // The card's own caps; an instruction is the app's text and can be longer.
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_LINE_CHARS = 240
}
