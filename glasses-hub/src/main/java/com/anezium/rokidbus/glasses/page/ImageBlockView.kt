package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.anezium.rokidbus.glasses.drawable
import com.anezium.rokidbus.glasses.session.ImageFit
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.shared.LauncherArtwork

/**
 * An image page's picture over its caption. The block fills the height its rows leave; the
 * picture is fitted inside that, aspect kept, under the hairline every launcher card wears.
 * A JPEG is drawn as given and filtered; one-bit art keeps its pixels square. A dimmed block is
 * the retained picture of an unavailable page, faded like its text.
 */
internal class ImageBlockView(context: Context, block: ScreenBlock.Image) : LinearLayout(context) {
    private val picture = PictureView(context, block.image, block.dimmed)
    private val caption = PageStyle.text(context, block.caption.orEmpty(), PageStyle.ASIDE_PX, if (block.dimmed) PageStyle.muted else PageStyle.ink, PageStyle.sans, 2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(PageStyle.TEXT_SIDE_PX, CAPTION_GAP_PX, PageStyle.TEXT_SIDE_PX, 0)
        visibility = if (block.caption.isNullOrBlank()) GONE else VISIBLE
    }

    init {
        orientation = VERTICAL
        addView(picture, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private class PictureView(context: Context, private val image: LauncherArtwork, private val dimmed: Boolean) : View(context) {
        private val drawable = image.drawable(context, PageStyle.phosphor)?.apply { if (dimmed) alpha = DIMMED_ALPHA }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = PageStyle.HAIR_STROKE_PX
            color = PageStyle.outline
        }
        private val missing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PageStyle.muted
            textSize = PageStyle.scaledPx(context, PageStyle.CAPTION_PX)
            textAlign = Paint.Align.CENTER
        }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            val fit = ImageFit.fit(image.width, image.height, width - 2 * INSET_PX, height - 2 * INSET_PX,
                wholeFactors = image.encoding == LauncherArtwork.MONO1) ?: return
            bounds.set(
                (INSET_PX + fit.left).toFloat(), (INSET_PX + fit.top).toFloat(),
                (INSET_PX + fit.right).toFloat(), (INSET_PX + fit.bottom).toFloat(),
            )
            val drawable = drawable
            if (drawable == null) {
                // A picture the hub cannot decode says so where it would have been; the page's actions still work.
                canvas.drawText(UNREADABLE, width / 2f, height / 2f, missing)
                return
            }
            drawable.setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            drawable.draw(canvas)
            bounds.inset(-PageStyle.HAIR_STROKE_PX, -PageStyle.HAIR_STROKE_PX)
            canvas.drawRoundRect(bounds, FRAME_RADIUS_PX, FRAME_RADIUS_PX, frame)
        }
    }

    private companion object {
        const val CAPTION_GAP_PX = 8
        /** Room for the hairline around the picture, so no part of it crosses the column's edge. */
        const val INSET_PX = 2
        const val FRAME_RADIUS_PX = 4f
        const val DIMMED_ALPHA = 110
        const val UNREADABLE = "Image unreadable"
    }
}
