package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/** Renderer owned by the `media` surface kind; no phone/plugin implementation leaks here. */
internal class MediaHudView(context: Context) : LinearLayout(context) {
    private val artworkView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        contentDescription = "Album artwork"
    }
    private val artworkFallback = monoText(52f, BusTheme.dim, bold = true).apply {
        text = "\u266A"
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val artworkFrame = FrameLayout(context).apply {
        addView(
            artworkView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            artworkFallback,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
    private val titleView = monoText(22f, BusTheme.phosphor, bold = true)
    private val artistView = monoText(15f, BusTheme.text)
    private val albumView = monoText(12f, BusTheme.muted).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val progressView = MediaProgressView(context)
    private val timeView = monoText(11f, BusTheme.dim).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var renderedArtworkKey: String? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        applyMarquee(titleView)
        applyMarquee(artistView)
        addView(
            artworkFrame,
            LayoutParams(px(168), px(168)).apply {
                topMargin = px(8)
                bottomMargin = px(10)
            },
        )
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(4)
        })
        addView(albumView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(2)
        })
        addView(progressView, LayoutParams(LayoutParams.MATCH_PARENT, px(14)).apply {
            topMargin = px(10)
        })
        addView(timeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(2)
        })
    }

    fun render(surface: NexusSurface) {
        titleView.text = surface.mediaTitle
        artistView.text = surface.mediaArtist
        artistView.visibility = visibleIf(artistView.text.isNotBlank())
        albumView.text = surface.mediaAlbum
        albumView.visibility = visibleIf(surface.mediaAlbum.isNotBlank())

        val anchor = surface.anchor
        val position = anchor?.effectivePositionMs(SystemClock.elapsedRealtime())?.coerceAtLeast(0L) ?: 0L
        val duration = anchor?.durationMs
        progressView.setProgress(
            if (duration != null && duration > 0L) position.toFloat() / duration else 0f,
        )
        val state = if (anchor?.playing == true) "PLAYING" else "PAUSED"
        timeView.text = if (duration != null) {
            "${formatDuration(position)}  $state  ${formatDuration(duration)}"
        } else {
            "${formatDuration(position)}  $state"
        }
        renderArtwork(surface.artwork)
    }

    fun clear() {
        titleView.text = ""
        artistView.text = ""
        albumView.text = ""
        timeView.text = ""
        progressView.setProgress(0f)
        clearRenderedArtwork()
    }

    override fun onDetachedFromWindow() {
        clearRenderedArtwork()
        super.onDetachedFromWindow()
    }

    private fun renderArtwork(artwork: MonoArtwork?) {
        val nextKey = artwork?.identityKey.orEmpty()
        if (renderedArtworkKey == nextKey) return
        clearRenderedArtwork()
        renderedArtworkKey = nextKey
        if (artwork == null) return
        val bitmap = artwork.toPhosphorBitmap(BusTheme.phosphor)
        artworkView.setImageDrawable(
            BitmapDrawable(resources, bitmap).apply {
                paint.isAntiAlias = false
                paint.isFilterBitmap = false
                paint.isDither = false
            },
        )
        artworkFallback.visibility = GONE
    }

    private fun clearRenderedArtwork() {
        (artworkView.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        artworkView.setImageDrawable(null)
        artworkFallback.visibility = VISIBLE
        renderedArtworkKey = null
    }

    private fun applyMarquee(view: TextView) {
        view.isSingleLine = true
        view.setHorizontallyScrolling(true)
        view.ellipsize = TextUtils.TruncateAt.MARQUEE
        view.marqueeRepeatLimit = -1
        view.isSelected = true
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE

    private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = false
            setHorizontallyScrolling(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
        }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
