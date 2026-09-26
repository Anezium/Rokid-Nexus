package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.BusTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * A damped spring, described the way motion designers describe one: how long
 * a swing takes ([responseSec]) and how much of it survives
 * ([dampingRatio]; 1 settles without overshoot, lower values bounce).
 */
internal data class HudSpring(
    val responseSec: Float,
    val dampingRatio: Float,
) {
    val omega: Float get() = (2.0 * PI / responseSec).toFloat()
    val stiffness: Float get() = omega * omega
    val damping: Float get() = 2f * dampingRatio * omega

    companion object {
        /** Everything that grows, shrinks, or changes form. */
        val STANDARD = HudSpring(responseSec = 0.42f, dampingRatio = 0.8f)

        /** The urgent flare: the same move with a bounce that is hard to miss. */
        val URGENT = HudSpring(responseSec = 0.46f, dampingRatio = 0.5f)

        /** Leaving never bounces. */
        val EXIT = HudSpring(responseSec = 0.3f, dampingRatio = 1f)
    }
}

/**
 * One sprung number. Retargeting keeps both position and velocity, so a new
 * state landing mid-motion bends the running motion instead of restarting it.
 */
internal class HudSpringValue(initial: Float) {
    var value: Float = initial
        private set
    var velocity: Float = 0f
    var target: Float = initial

    fun step(dtSec: Float, spring: HudSpring) {
        var remaining = dtSec
        while (remaining > 0f) {
            val h = min(remaining, MAX_SUBSTEP_SEC)
            val acceleration = -spring.stiffness * (value - target) - spring.damping * velocity
            velocity += acceleration * h
            value += velocity * h
            remaining -= h
        }
    }

    fun isSettled(positionEpsilon: Float = 0.5f, velocityEpsilon: Float = 4f): Boolean =
        abs(value - target) < positionEpsilon && abs(velocity) < velocityEpsilon

    fun snap(to: Float = target) {
        target = to
        value = to
        velocity = 0f
    }

    private companion object {
        // Explicit integration of a stiff spring diverges on a long frame; small
        // fixed substeps keep a dropped frame from turning into a jump.
        const val MAX_SUBSTEP_SEC = 1f / 240f
    }
}

/**
 * One continuous outline that morphs between the forms of a HUD surface, the
 * way a phone's island grows out of the notch and folds back into it.
 *
 * Each form is an ordinary child view laid out at its resting place and drawn
 * without chrome. The island draws the only outline, springs its four edges
 * towards the current form's bounds, and clips every form to that outline, so
 * content is revealed by the shape rather than cross-faded between two boxes.
 * The outgoing form fades out quickly and the incoming one fades in once the
 * outgoing is mostly gone, so two contents never read as one double image.
 */
internal open class HudIslandView(context: Context) : FrameLayout(context) {
    private class Ghost(val bitmap: Bitmap, val x: Float, val y: Float, var alpha: Float)

    private val forms = mutableListOf<View>()
    private val edges = List(4) { HudSpringValue(0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val clip = Path()
    private val cornerRadius = BusTheme.dp(context, 7).toFloat()
    private var current: View? = null
    private var spring = HudSpring.STANDARD
    private var shapeAlpha = 0f
    private var shaped = false
    private var ghost: Ghost? = null
    private var dismissal: (() -> Unit)? = null
    private var lastFrameNanos = 0L
    private var running = false
    private val frame = Runnable { onFrame() }

    init {
        setWillNotDraw(false)
        setOutline(BusTheme.hairline, BusTheme.dp(context, 1).toFloat())
    }

    fun addForm(view: View, params: LayoutParams) {
        view.alpha = 0f
        view.visibility = View.INVISIBLE
        addView(view, params)
        forms += view
    }

    /** Morph towards [form]. Calling it again with the same form only changes the spring. */
    fun show(form: View, spring: HudSpring = HudSpring.STANDARD) {
        this.spring = spring
        dismissal = null
        current = form
        form.visibility = View.VISIBLE
        if (!isLayoutRequested) retarget()
        start()
    }

    fun setOutline(color: Int, widthPx: Float) {
        outline.color = color
        outline.strokeWidth = widthPx
        invalidate()
    }

    /**
     * Swaps a form's content with a short cross-fade: what the wearer was
     * reading fades out in place while the new content fades in.
     */
    fun crossfade(form: View, change: () -> Unit) {
        val capture = form === current && form.alpha > 0.5f &&
            form.width > 0 && form.height > 0 && HudMotion.enabled && isAttachedToWindow
        if (capture) {
            ghost?.bitmap?.recycle()
            val bitmap = Bitmap.createBitmap(form.width, form.height, Bitmap.Config.ARGB_8888)
            form.draw(Canvas(bitmap))
            ghost = Ghost(bitmap, form.left.toFloat(), form.top.toFloat(), form.alpha)
            form.alpha = 0f
        }
        change()
        if (capture) start()
    }

    /** A value refreshed in place: the outline swells and springs back. */
    fun bump(amountPx: Float) {
        if (!shaped || !HudMotion.enabled) return
        val kick = amountPx * spring.omega
        edges[LEFT].velocity -= kick
        edges[TOP].velocity -= kick
        edges[RIGHT].velocity += kick
        edges[BOTTOM].velocity += kick
        start()
    }

    /** Folds into the current form's centre and fades out, then calls [onEnd] once. */
    fun dismiss(onEnd: () -> Unit) {
        dismissal = onEnd
        current = null
        spring = HudSpring.EXIT
        val cx = (edges[LEFT].target + edges[RIGHT].target) / 2f
        val cy = (edges[TOP].target + edges[BOTTOM].target) / 2f
        val halfWidth = (edges[RIGHT].target - edges[LEFT].target) * COLLAPSED_FRACTION / 2f
        val halfHeight = (edges[BOTTOM].target - edges[TOP].target) * COLLAPSED_FRACTION / 2f
        edges[LEFT].target = cx - halfWidth
        edges[RIGHT].target = cx + halfWidth
        edges[TOP].target = cy - halfHeight
        edges[BOTTOM].target = cy + halfHeight
        start()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        retarget()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        ghost?.bitmap?.recycle()
        ghost = null
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!shaped || shapeAlpha <= 0f) return
        shape.set(edges[LEFT].value, edges[TOP].value, edges[RIGHT].value, edges[BOTTOM].value)
        if (shape.width() <= 0f || shape.height() <= 0f) return
        val radius = min(cornerRadius, min(shape.width(), shape.height()) / 2f)
        val alpha = (shapeAlpha * 255).toInt()
        fill.alpha = alpha
        canvas.drawRoundRect(shape, radius, radius, fill)

        clip.reset()
        clip.addRoundRect(shape, radius, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        ghost?.let { old ->
            val paint = Paint().apply { this.alpha = (old.alpha * 255).toInt() }
            canvas.drawBitmap(old.bitmap, old.x, old.y, paint)
        }
        canvas.restoreToCount(saved)

        // Inset by half the stroke so the line sits inside the shape, as a
        // border drawable's stroke does.
        val inset = outline.strokeWidth / 2f
        shape.inset(inset, inset)
        outline.alpha = alpha
        canvas.drawRoundRect(shape, radius, radius, outline)
    }

    private fun retarget() {
        val form = current ?: return
        if (form.width == 0 || form.height == 0) return
        val target = floatArrayOf(
            form.left.toFloat(),
            form.top.toFloat(),
            form.right.toFloat(),
            form.bottom.toFloat(),
        )
        if (!shaped) {
            // First appearance grows out of the form's own centre.
            val cx = (target[LEFT] + target[RIGHT]) / 2f
            val cy = (target[TOP] + target[BOTTOM]) / 2f
            val halfWidth = (target[RIGHT] - target[LEFT]) * COLLAPSED_FRACTION / 2f
            val halfHeight = (target[BOTTOM] - target[TOP]) * COLLAPSED_FRACTION / 2f
            edges[LEFT].snap(cx - halfWidth)
            edges[RIGHT].snap(cx + halfWidth)
            edges[TOP].snap(cy - halfHeight)
            edges[BOTTOM].snap(cy + halfHeight)
            shaped = true
        }
        edges.forEachIndexed { index, edge -> edge.target = target[index] }
        start()
    }

    private fun start() {
        if (!HudMotion.enabled) {
            settleNow()
            return
        }
        if (running) return
        running = true
        lastFrameNanos = System.nanoTime()
        postOnAnimation(frame)
    }

    private fun onFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_SEC)
        lastFrameNanos = now
        edges.forEach { it.step(dt, spring) }

        val leaving = dismissal != null
        shapeAlpha = approach(shapeAlpha, if (leaving) 0f else 1f, dt / FORM_IN_SEC)
        // The incoming form waits for the outgoing one to be mostly gone.
        val outgoingVisible = forms.any { it !== current && it.alpha > INCOMING_THRESHOLD }
        forms.forEach { form ->
            val incoming = form === current && !leaving
            form.alpha = when {
                incoming && outgoingVisible -> form.alpha
                incoming -> approach(form.alpha, 1f, dt / FORM_IN_SEC)
                else -> approach(form.alpha, 0f, dt / FORM_OUT_SEC)
            }
            form.visibility = if (form.alpha > 0f || incoming) View.VISIBLE else View.INVISIBLE
        }
        ghost?.let { old ->
            old.alpha = approach(old.alpha, 0f, dt / GHOST_SEC)
            if (old.alpha <= 0f) {
                old.bitmap.recycle()
                ghost = null
            }
        }
        invalidate()

        val settled = edges.all { it.isSettled() } && ghost == null &&
            forms.all { form -> form.alpha == if (form === current && !leaving) 1f else 0f } &&
            shapeAlpha == if (leaving) 0f else 1f
        if (!settled) {
            postOnAnimation(frame)
            return
        }
        running = false
        edges.forEach { it.snap() }
        if (leaving) finishDismissal()
    }

    private fun settleNow() {
        edges.forEach { it.snap() }
        ghost?.bitmap?.recycle()
        ghost = null
        val leaving = dismissal != null
        shapeAlpha = if (leaving) 0f else 1f
        forms.forEach { form ->
            val shown = form === current && !leaving
            form.alpha = if (shown) 1f else 0f
            form.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        }
        invalidate()
        if (leaving) finishDismissal()
    }

    private fun finishDismissal() {
        val onEnd = dismissal ?: return
        dismissal = null
        shaped = false
        onEnd()
    }

    private fun approach(value: Float, target: Float, stepSize: Float): Float =
        if (value < target) min(target, value + stepSize) else maxOf(target, value - stepSize)

    private companion object {
        const val LEFT = 0
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 3
        const val COLLAPSED_FRACTION = 0.4f
        const val FORM_OUT_SEC = 0.09f
        const val FORM_IN_SEC = 0.18f
        const val GHOST_SEC = 0.16f
        const val INCOMING_THRESHOLD = 0.35f
        const val MAX_FRAME_SEC = 0.05f
    }
}
