package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.BusTheme
import kotlin.math.roundToInt

/** Geometry shared by the notice band and the Ink card it becomes. */
internal object HudBandGeometry {
    const val WIDTH_FRACTION = 0.92f
    private const val EDGE_MARGIN_DP = 12

    fun widthPx(displayWidthPx: Int): Int =
        (displayWidthPx.coerceAtLeast(0) * WIDTH_FRACTION).toInt()

    fun topPx(context: Context, hudTopInsetDp: Int): Int =
        BusTheme.dp(context, EDGE_MARGIN_DP + HudTopInset.sanitize(hudTopInsetDp))

    fun availableHeightPx(displayHeightPx: Int, topPx: Int): Int =
        (displayHeightPx - topPx).coerceAtLeast(0)
}

internal enum class SurfaceHudMode {
    FULL_BLEED,
    INK_CARD,
}

internal fun surfaceHudMode(kind: String): SurfaceHudMode =
    if (kind == NexusSurface.KIND_INK) SurfaceHudMode.INK_CARD else SurfaceHudMode.FULL_BLEED

/**
 * What the surface host paints around the surface itself. An Ink card is its
 * own opaque canvas ([InkHudView] backs itself with the theme black), so the
 * host contributes nothing and the panel around the card stays transparent;
 * every other kind keeps the full-bleed panel.
 *
 * This is a value rather than a pair of methods because `SurfaceHudView` cannot
 * be instantiated under Robolectric (`ReaderSurfaceView` calls an API the
 * sandbox does not shadow), and the decision still deserves a test.
 */
internal data class SurfaceHostChrome(
    val backgroundColor: Int?,
    val paddingLeftDp: Int,
    val paddingTopDp: Int,
    val paddingRightDp: Int,
    val paddingBottomDp: Int,
)

internal fun surfaceHostChrome(mode: SurfaceHudMode, hudTopInsetDp: Int): SurfaceHostChrome =
    when (mode) {
        SurfaceHudMode.INK_CARD -> SurfaceHostChrome(null, 0, 0, 0, 0)
        SurfaceHudMode.FULL_BLEED -> SurfaceHostChrome(
            backgroundColor = BusTheme.glassesBg,
            paddingLeftDp = 18,
            paddingTopDp = 16 + HudTopInset.sanitize(hudTopInsetDp),
            paddingRightDp = 18,
            paddingBottomDp = 12,
        )
    }

internal fun surfaceDisplayPath(
    surface: NexusSurface,
    configured: SurfaceDisplayPath,
): SurfaceDisplayPath = when {
    surface.isInk -> SurfaceDisplayPath.OVERLAY
    // The overlay window is TYPE_ACCESSIBILITY_OVERLAY and deliberately
    // non-focusable — the same reason a notice can never take a keystroke.
    // An editable field needs real window focus to reach the system IME
    // (and a keyboard bonded straight to the glasses), so it forces the
    // one path that can actually give it that, regardless of the wearer's
    // general Display setting.
    surface.kind == NexusSurface.KIND_CARD && surface.editable != null -> SurfaceDisplayPath.ACTIVITY
    else -> configured
}

internal fun shouldKeepInkPresentation(
    nextIsInk: Boolean,
    nextSurfaceId: String,
    presentationSurfaceId: String?,
    pendingGeneration: Long?,
    presentationGeneration: Long?,
): Boolean = nextIsInk && nextSurfaceId == presentationSurfaceId &&
    (pendingGeneration == null || pendingGeneration == presentationGeneration)

/**
 * Measures the Ink projection once at its final card bounds. Reveal changes
 * only [View.getClipBounds], so rpx geometry never sees an animated container.
 */
internal class InkCardClipHost(context: Context) : FrameLayout(context) {
    var heightCapPx: Int = Int.MAX_VALUE
        set(value) {
            val clean = value.coerceAtLeast(0)
            if (field == clean) return
            field = clean
            requestLayout()
        }

    var revealHeightPx: Int? = null
        private set

    fun revealTo(heightPx: Int) {
        revealHeightPx = heightPx.coerceAtLeast(0)
        applyRevealClip()
    }

    fun revealFully() {
        revealHeightPx = null
        clipBounds = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null || child.visibility == GONE) {
            setMeasuredDimension(
                resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(suggestedMinimumHeight, heightMeasureSpec),
            )
            return
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val childWidthSpec = when (widthMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        val parentHeightLimit = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }
        val heightLimit = minOf(parentHeightLimit, heightCapPx)
        val childHeightSpec = if (heightLimit == Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST)
        }
        child.measure(childWidthSpec, childHeightSpec)

        val measuredHeight = child.measuredHeight.coerceAtMost(heightLimit)
        if (heightLimit != Int.MAX_VALUE && measuredHeight == heightLimit) {
            // The cap is a real Ink viewport, not a taller page clipped after layout.
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            resolveSize(child.measuredWidth, widthMeasureSpec),
            resolveSize(measuredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyRevealClip()
    }

    private fun applyRevealClip() {
        val reveal = revealHeightPx
        clipBounds = if (reveal == null) null else Rect(0, 0, width, reveal)
    }
}

internal data class InkCardMorphFrame(
    val clipHeightPx: Int,
    val cardAlpha: Float,
    val bandAlpha: Float,
)

internal fun inkCardMorphFrame(
    bandHeightPx: Int,
    cardHeightPx: Int,
    clipProgress: Float,
    alphaProgress: Float,
    initialBandAlpha: Float = 1f,
): InkCardMorphFrame {
    val clipAmount = clipProgress.coerceIn(0f, 1f)
    val alphaAmount = alphaProgress.coerceIn(0f, 1f)
    return InkCardMorphFrame(
        clipHeightPx = (bandHeightPx + (cardHeightPx - bandHeightPx) * clipAmount)
            .roundToInt(),
        cardAlpha = alphaAmount,
        bandAlpha = initialBandAlpha.coerceIn(0f, 1f) * (1f - alphaAmount),
    )
}

internal enum class InkCardMorphPhase {
    WAITING_FOR_FIRST_FRAME,
    ANIMATING,
    COMMITTED,
    CANCELLED,
}

/** Pure ordering guard for the draw, timeout, and animator races. */
internal class InkCardMorphState {
    var phase: InkCardMorphPhase = InkCardMorphPhase.WAITING_FOR_FIRST_FRAME
        private set

    val bandMayTearDown: Boolean
        get() = phase == InkCardMorphPhase.COMMITTED

    fun startAnimation(): Boolean {
        if (phase != InkCardMorphPhase.WAITING_FOR_FIRST_FRAME) return false
        phase = InkCardMorphPhase.ANIMATING
        return true
    }

    fun commitInstant(): Boolean {
        if (phase == InkCardMorphPhase.COMMITTED || phase == InkCardMorphPhase.CANCELLED) {
            return false
        }
        phase = InkCardMorphPhase.COMMITTED
        return true
    }

    fun completeAnimation(): Boolean {
        if (phase != InkCardMorphPhase.ANIMATING) return false
        phase = InkCardMorphPhase.COMMITTED
        return true
    }

    fun cancel() {
        if (phase != InkCardMorphPhase.COMMITTED) phase = InkCardMorphPhase.CANCELLED
    }
}
