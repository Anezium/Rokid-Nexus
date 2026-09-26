package com.anezium.rokidbus.client.ui

import com.anezium.rokidbus.client.R
import com.anezium.rokidbus.shared.GlyphContract

/**
 * The HUD's shared vocabulary of **state** glyphs.
 *
 * This is deliberately a different thing from [NexusPluginIcons], even though
 * both resolve to the same kind of asset (a 24x24 stroked vector). A plugin
 * icon says *who is speaking* and is chosen once; a glyph says *what is
 * happening right now* and changes many times per session — a route emits
 * `turn-left`, `straight`, `turn-right`, `arrive` in the space of a few
 * minutes. Branding belongs to the plugin; verbs belong to the platform, which
 * is what keeps the HUD coherent when several plugins are live at once.
 *
 * **Unknown values are not an error.** [drawableFor] falls back to `dot`, and
 * validation upstream only checks that a value is [wellFormed]. That is the
 * same forgiving contract `iconKey` already has, and it is what makes this set
 * additive: a plugin built against a newer SDK degrades on an older hub instead
 * of being rejected by it. Removing a value is the breaking change; adding one
 * never was.
 */
object NexusGlyphs {

    /** Drawn when a glyph is absent, malformed, or newer than this build. */
    const val FALLBACK = GlyphContract.FALLBACK_GLYPH

    /**
     * Every glyph this build can draw. A plugin may send a value outside this
     * set; it renders as [FALLBACK] rather than failing.
     */
    val builtIn: Set<String> get() = drawables.keys

    /** Resolve to a drawable, falling back to `dot` for anything unrecognised. */
    fun drawableFor(glyph: String?): Int =
        drawables[glyph?.trim()] ?: R.drawable.ic_glyph_dot

    /** True when this build can draw [glyph] itself, rather than falling back. */
    fun isBuiltIn(glyph: String?): Boolean = drawables.containsKey(glyph?.trim())

    /**
     * Shape check only — not a membership check. See
     * [GlyphContract.isWellFormedName] for why, and note that the rule lives
     * there rather than here so the hubs, the SDK and the renderer cannot drift
     * into disagreeing about what a glyph name is.
     */
    fun wellFormed(glyph: String?): Boolean = GlyphContract.isWellFormedName(glyph)

    // Wire values stay kebab-case; Android resource names cannot contain a
    // hyphen, so the two spellings differ on purpose and only here.
    private val drawables: Map<String, Int> = mapOf(
        // Transport controls — an activity's actions.
        "play" to R.drawable.ic_glyph_play,
        "pause" to R.drawable.ic_glyph_pause,
        "stop" to R.drawable.ic_glyph_stop,
        "next" to R.drawable.ic_glyph_next,
        "prev" to R.drawable.ic_glyph_prev,
        // Navigation maneuvers.
        "straight" to R.drawable.ic_glyph_straight,
        "turn-left" to R.drawable.ic_glyph_turn_left,
        "turn-right" to R.drawable.ic_glyph_turn_right,
        "turn-slight-left" to R.drawable.ic_glyph_turn_slight_left,
        "turn-slight-right" to R.drawable.ic_glyph_turn_slight_right,
        "turn-sharp-left" to R.drawable.ic_glyph_turn_sharp_left,
        "turn-sharp-right" to R.drawable.ic_glyph_turn_sharp_right,
        "u-turn" to R.drawable.ic_glyph_u_turn,
        "roundabout" to R.drawable.ic_glyph_roundabout,
        "arrive" to R.drawable.ic_glyph_arrive,
        // Ongoing state.
        "package" to R.drawable.ic_glyph_package,
        "walk" to R.drawable.ic_glyph_walk,
        "timer" to R.drawable.ic_glyph_timer,
        "phone" to R.drawable.ic_glyph_phone,
        // Getting there: the vehicle of the current leg of a route.
        "bus" to R.drawable.ic_glyph_bus,
        "tram" to R.drawable.ic_glyph_tram,
        "train" to R.drawable.ic_glyph_train,
        "metro" to R.drawable.ic_glyph_metro,
        // Answering someone. These are conversation marks, not one plugin's
        // marks: the first relay needed them, but a reply is a reply in every
        // plugin that ever asks a question, and left as per-plugin custom paths
        // they would become five slightly different arrows.
        "reply" to R.drawable.ic_glyph_reply,
        "send" to R.drawable.ic_glyph_send,
        "retry" to R.drawable.ic_glyph_retry,
        "cancel" to R.drawable.ic_glyph_cancel,
        "mic" to R.drawable.ic_glyph_mic,
        "keyboard" to R.drawable.ic_glyph_keyboard,
        FALLBACK to R.drawable.ic_glyph_dot,
    )
}
