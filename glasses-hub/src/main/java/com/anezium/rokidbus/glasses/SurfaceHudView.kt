package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

class SurfaceHudView(context: Context) : LinearLayout(context) {
    private val titleView = monoText(17f, BusTheme.text, bold = true)
    private val subtitleView = monoText(11f, BusTheme.muted)
    private val previousView = monoText(15f, BusTheme.dim)
    private val currentView = monoText(25f, BusTheme.phosphor, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 5
    }
    private val nextView = monoText(17f, BusTheme.muted).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 3
    }
    private val footerView = monoText(10.5f, BusTheme.dim).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var surface: NexusSurface? = null

    private val ticker = object : Runnable {
        override fun run() {
            val active = surface ?: return
            renderNow(active)
            if (active.anchor?.playing == true && active.isTimed) {
                postDelayed(this, TICK_MS)
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(BusTheme.glassesBg)
        setPadding(px(18), px(16), px(18), px(12))
        isFocusable = true
        isFocusableInTouchMode = true

        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        subtitleView.maxLines = 1
        subtitleView.ellipsize = TextUtils.TruncateAt.END
        previousView.gravity = Gravity.CENTER
        previousView.textAlignment = TEXT_ALIGNMENT_CENTER
        previousView.maxLines = 2
        previousView.ellipsize = TextUtils.TruncateAt.END

        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(3)
        })
        addView(previousView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(30)
        })
        addView(currentView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(8)
        })
        addView(nextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })
        addView(footerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(16)
        })
    }

    fun render(next: NexusSurface?) {
        removeCallbacks(ticker)
        surface = next
        if (next == null) {
            clear()
            return
        }
        renderNow(next)
        if (next.anchor?.playing == true && next.isTimed) {
            postDelayed(ticker, TICK_MS)
        }
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun renderNow(surface: NexusSurface) {
        titleView.text = surface.title
        titleView.visibility = visibleIf(surface.title.isNotBlank())
        subtitleView.text = surface.subtitle
        subtitleView.visibility = visibleIf(surface.subtitle.isNotBlank())
        footerView.text = surface.footer
        footerView.visibility = visibleIf(surface.footer.isNotBlank())

        if (surface.isTimed) {
            renderTimed(surface)
        } else {
            renderCard(surface)
        }
    }

    private fun renderTimed(surface: NexusSurface) {
        val index = currentTimedIndex(surface)
        previousView.text = surface.timedLines.getOrNull(index - 1)?.text.orEmpty()
        previousView.visibility = visibleIf(previousView.text.isNotBlank())
        currentView.text = surface.timedLines.getOrNull(index)?.text
            ?.takeIf { it.isNotBlank() }
            ?: surface.timedLines.firstOrNull()?.text
            ?: ""
        nextView.text = surface.timedLines.getOrNull(index + 1)?.text.orEmpty()
        nextView.visibility = visibleIf(nextView.text.isNotBlank())
    }

    private fun renderCard(surface: NexusSurface) {
        previousView.visibility = GONE
        currentView.text = surface.textLines.filter { it.isNotBlank() }.joinToString("\n")
        nextView.visibility = GONE
    }

    private fun currentTimedIndex(surface: NexusSurface): Int {
        val position = surface.anchor?.effectivePositionMs(SystemClock.elapsedRealtime()) ?: 0L
        var candidate = 0
        for (index in surface.timedLines.indices) {
            if (surface.timedLines[index].timeMs <= position) {
                candidate = index
            } else {
                break
            }
        }
        return candidate.coerceIn(0, (surface.timedLines.size - 1).coerceAtLeast(0))
    }

    private fun clear() {
        titleView.text = ""
        subtitleView.text = ""
        previousView.text = ""
        currentView.text = ""
        nextView.text = ""
        footerView.text = ""
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

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

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val TICK_MS = 100L
    }
}
