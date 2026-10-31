package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.anezium.rokidbus.glasses.drawable
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.shared.MediaPlaybackState

/** The player lives in the page lease; its clock never asks the phone for refreshes. */
internal class MediaPlayerBlockView(context: Context, private val icons: SessionIconSource, private var block: ScreenBlock.Media) : LinearLayout(context) {
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(PageStyle.phosphor)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(PageStyle.outline)
    }
    private val time = PageStyle.text(context, "", 12f, PageStyle.muted, PageStyle.mono, 1)
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            if (isAttachedToWindow && block.positionMs != null && block.durationMs != null &&
                (block.advancing ?: (block.state == MediaPlaybackState.PLAYING))) postDelayed(this, 1000L)
        }
    }

    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Album cover"
    }
    private val title = PageStyle.text(context, "", 24f, PageStyle.phosphor, PageStyle.sansBold, 2).apply { gravity = Gravity.CENTER }
    private val artist = PageStyle.text(context, "", 17f, PageStyle.ink, PageStyle.sans, 2).apply { gravity = Gravity.CENTER; setPadding(0, 5, 0, 0) }
    private val album = PageStyle.text(context, "", 13f, PageStyle.muted, PageStyle.sans, 1).apply { gravity = Gravity.CENTER; setPadding(0, 4, 0, 0) }
    private val status = PageStyle.text(context, "", 13f, PageStyle.muted, PageStyle.sans).apply { gravity = Gravity.CENTER; setPadding(0, 8, 0, 0) }
    private val timeline = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, 4))
        addView(time, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
    }
    private var renderedArtwork: com.anezium.rokidbus.shared.LauncherArtwork? = null
    private var renderedPresent: Boolean? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(PageStyle.black)
        setPadding(18, 4, 18, 8)
        addView(cover, LayoutParams(176, 176).apply { bottomMargin = 14 })
        addView(title)
        addView(artist)
        addView(album)
        addView(timeline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 14 })
        addView(status)
        bind(block)
    }

    /** Keep the media view tree attached as controls, metadata and the playback anchor change. */
    fun bind(next: ScreenBlock.Media) {
        val changed = block != next
        block = next
        val present = next.state !in setOf(MediaPlaybackState.NONE, MediaPlaybackState.UNAVAILABLE)
        if (renderedArtwork != next.artwork || renderedPresent != present) {
            cover.setImageDrawable(if (present) next.artwork?.drawable(context, PageStyle.phosphor)
                ?: glyphDrawable(context, icons, "disc", PageStyle.muted) else null)
            renderedArtwork = next.artwork
            renderedPresent = present
        }
        cover.visibility = if (present) VISIBLE else GONE
        title.text = next.title
        title.setTextColor(if (present) PageStyle.phosphor else PageStyle.muted)
        fun bindText(view: android.widget.TextView, value: String?) {
            view.text = value.orEmpty()
            view.visibility = if (value.isNullOrBlank()) GONE else VISIBLE
        }
        bindText(artist, next.artist)
        bindText(album, next.album)
        bindText(status, next.status)
        timeline.visibility = if (next.positionMs != null && (next.durationMs ?: 0) > 0) VISIBLE else GONE
        updatePosition()
        if (changed) {
            removeCallbacks(tick)
            if (isAttachedToWindow) tick.run()
        }
    }

    private fun updatePosition() {
        val duration = block.durationMs?.takeIf { it > 0 } ?: return
        val elapsed = if ((block.advancing ?: (block.state == MediaPlaybackState.PLAYING)) && block.receivedAtMs > 0)
            (SystemClock.uptimeMillis() - block.receivedAtMs).coerceAtLeast(0) else 0
        val position = ((block.positionMs ?: 0) + (elapsed * block.playbackSpeed).toLong()).coerceIn(0, duration)
        progress.progress = (position * 1000 / duration).toInt()
        fun stamp(ms: Long) = "%d:%02d".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60)
        time.text = "${stamp(position)}  /  ${stamp(duration)}"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick.run() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
