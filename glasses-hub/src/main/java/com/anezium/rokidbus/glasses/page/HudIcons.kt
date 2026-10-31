package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.client.ui.NexusGlyphs
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.session.IconPlace
import com.anezium.rokidbus.glasses.session.IconRef

/**
 * Turns the screen model's [IconRef]s into drawables. The model names an icon; this decides
 * the pixels, so the session's pure state never holds a resource or a bitmap.
 */
internal fun interface SessionIconSource {
    /** A drawable for [ref], or null when nothing honest can be drawn: the slot then stays empty. */
    fun drawable(context: Context, ref: IconRef): Drawable?
}

/** How bright an icon is drawn: grey at rest, phosphor when selected or in a page header, grey when ended. */
internal enum class IconTone { REST, BRIGHT, ENDED }

/**
 * The hub's own resolution, in the launcher's order: a plugin's built-in icon key, then the
 * glyph it registered, then the legacy table, then the generic grid. A native app's own launcher
 * icon is drawn as provided; one that cannot be found is the grid, never a guessed logo.
 */
internal object HubIconSource : SessionIconSource {
    override fun drawable(context: Context, ref: IconRef): Drawable? = when (ref) {
        is IconRef.Plugin -> GlassesHub.launcherDrawable(context, GlassesHub.LauncherEntry(ref.pluginId, ref.pluginId, ref.iconKey))
        is IconRef.Place -> placeDrawable(context, ref.place)
        is IconRef.Native -> runCatching { context.packageManager.getApplicationIcon(ref.packageName) }.getOrNull()
            ?: placeDrawable(context, IconPlace.GRID)
        is IconRef.Glyph -> HudGlyphs.drawable(context, ref.name) { owner, glyph -> GlassesHub.activityGlyphDrawable(context, owner, glyph) }
    }

    fun placeDrawable(context: Context, place: IconPlace): Drawable? {
        val key = when (place) {
            IconPlace.BELL -> "bell"
            IconPlace.GRID -> "grid"
        }
        return NexusPluginIcons.drawableForBuiltIn(key)?.let(context::getDrawable)
    }
}

/**
 * Glyph names as the session HUD draws them. The hub's own marks come first (the Open app slot,
 * the composer's hand-over arrow, the lost-position ring); then the shared vocabulary; then the
 * hub's place marks; then, for a name qualified as `owner:glyph`, the glyph that owner
 * registered; anything else is the `dot` fallback.
 */
internal object HudGlyphs {
    /** The hub's Open app slot: a box with an arrow leaving it. */
    const val OPEN_APP = "open-app"

    /** Hands a reply to the app: r17's arrow, which the session draws for `send` too. */
    const val HAND_OVER = "hand-over"

    /** A position that can no longer be trusted: a dashed ring round a dot. */
    const val GPS_LOST = "gps-lost"

    private val paths = mapOf(
        OPEN_APP to "M14,4 h6 v6 M20,4 l-9,9 M18,14 v5 a1,1 0 0,1 -1,1 h-12 a1,1 0 0,1 -1,-1 v-12 a1,1 0 0,1 1,-1 h5",
        HAND_OVER to "M4,12 h15 M13,6 l6,6 -6,6",
    )

    /**
     * The composer's Send is r17's hand-over arrow, never the paper plane the ambient band
     * draws; inside the session one verb keeps one shape.
     */
    private val aliases = mapOf("send" to HAND_OVER)

    fun drawable(context: Context, name: String, ownerGlyph: (owner: String, glyph: String) -> Drawable): Drawable {
        val trimmed = name.trim()
        val hub = aliases[trimmed] ?: trimmed
        if (hub == GPS_LOST) return GpsLostDrawable()
        paths[hub]?.let { return GlyphDrawable(it) }
        if (NexusGlyphs.isBuiltIn(trimmed)) return requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(trimmed)))
        // The hub's place marks (`bell`, `grid`) are named like glyphs by the screens that lead to them.
        NexusPluginIcons.drawableForBuiltIn(trimmed)?.let { return requireNotNull(context.getDrawable(it)) }
        val separator = trimmed.indexOf(':')
        if (separator > 0 && separator < trimmed.lastIndex) {
            return ownerGlyph(trimmed.substring(0, separator), trimmed.substring(separator + 1))
        }
        return requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(null)))
    }
}

/** A dashed ring round a small dot, in the muted grey: the map says it no longer knows. */
internal class GpsLostDrawable : Drawable() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = PageStyle.muted
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PageStyle.muted
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty) return
        val unit = minOf(box.width(), box.height()) / VIEWPORT
        ring.strokeWidth = 2f * unit
        ring.pathEffect = DashPathEffect(floatArrayOf(2.6f * unit, 2.6f * unit), 0f)
        canvas.drawCircle(box.exactCenterX(), box.exactCenterY(), 8.4f * unit, ring)
        canvas.drawCircle(box.exactCenterX(), box.exactCenterY(), 2.1f * unit, dot)
    }

    override fun setAlpha(alpha: Int) {
        ring.alpha = alpha
        dot.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ring.colorFilter = colorFilter
        dot.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable, still abstract.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = VIEWPORT.toInt()

    override fun getIntrinsicHeight(): Int = VIEWPORT.toInt()

    private companion object {
        const val VIEWPORT = 24f
    }
}

internal object IconTint {
    fun colorOf(tone: IconTone): Int = when (tone) {
        IconTone.BRIGHT -> PageStyle.phosphor
        IconTone.REST, IconTone.ENDED -> PageStyle.muted
    }

    /**
     * Stroke marks take the tone's colour; a native app's own icon is drawn as provided, since
     * recolouring a bitmap would only smear it.
     */
    fun apply(drawable: Drawable, ref: IconRef, color: Int): Drawable {
        if (ref is IconRef.Native) return drawable
        return drawable.mutate().also { it.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
    }
}

/** A square icon slot of [sizePx], filled from [source]; GONE when there is nothing to draw. */
internal fun iconView(context: Context, source: SessionIconSource, ref: IconRef?, tone: IconTone, sizePx: Int): ImageView? {
    val icon = ref ?: return null
    val drawable = runCatching { source.drawable(context, icon) }.getOrNull() ?: return null
    return ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(IconTint.apply(drawable, icon, IconTint.colorOf(tone)))
        minimumWidth = sizePx
        minimumHeight = sizePx
        tag = IconTag(icon, tone)
    }
}

/** What an icon slot shows, kept on the view for tests and accessibility. */
internal data class IconTag(val ref: IconRef, val tone: IconTone)

/** A glyph by name in [color], resolved the way [HudGlyphs] does. */
internal fun glyphDrawable(context: Context, source: SessionIconSource, name: String, color: Int): Drawable? {
    val ref = IconRef.Glyph(name)
    val drawable = runCatching { source.drawable(context, ref) }.getOrNull() ?: return null
    return IconTint.apply(drawable, ref, color)
}
