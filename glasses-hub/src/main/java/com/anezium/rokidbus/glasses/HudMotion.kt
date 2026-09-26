package com.anezium.rokidbus.glasses

import android.animation.ValueAnimator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * The shared motion vocabulary for the glasses HUD.
 *
 * Three rules hold the whole thing together, and they are the reason this is a
 * layer rather than an `animate()` call sprinkled into each renderer:
 *
 *  1. **Motion is glasses-local.** The phone sends state, never frames. The
 *     transport is far too slow to drive a 280 ms morph over the wire, so a
 *     renderer receives "this changed" and decides the motion itself.
 *  2. **New state always wins, and it resumes from where the eye is.** An
 *     update landing mid-animation retargets the running animation from its
 *     current value. Nothing ever snaps back to a start value; that read is
 *     what makes a HUD look broken rather than alive.
 *  3. **Motion means something happened.** These helpers exist for state
 *     changes, not decoration. On additive AR optics a moving element sits in
 *     the wearer's field of view while they are walking, so an idle loop is not
 *     a flourish, it is a distraction with a thermal bill.
 */
object HudMotion {

    /** A value refreshing in place: a number ticking over. */
    const val MICRO_MS = 180L

    /** A panel arriving, leaving its anchor, or changing shape. */
    const val STANDARD_MS = 280L

    /** Anything on its way out. Exits are quicker than entrances. */
    const val EXIT_MS = 240L

    /** How long a flare holds at full size before collapsing back. */
    const val HOLD_MS = 3_500L

    /** Fast-out-slow-in. Everything arriving or growing. */
    val enter: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Fast-out-linear-in. Everything leaving. */
    val exit: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /**
     * Global kill switch. When false every [HudMotionValue] lands on its target
     * instantly and the HUD behaves exactly as it did before this layer existed.
     * Wired to nothing yet; the intent is battery saver, a wearer preference,
     * and the platform's own "remove animations" accessibility setting.
     */
    @Volatile
    var enabled: Boolean = true
}

/**
 * One animatable number that always knows where it currently is.
 *
 * This is the whole interruption model: [animateTo] cancels whatever was
 * running and starts a fresh animation from [current], so a taxi update that
 * lands while the pin is halfway through growing continues from halfway
 * instead of jumping.
 */
class HudMotionValue(
    initial: Float,
    private val onChange: (Float) -> Unit,
) {
    var current: Float = initial
        private set

    private var animator: ValueAnimator? = null

    val isRunning: Boolean
        get() = animator?.isRunning == true

    fun animateTo(
        target: Float,
        durationMs: Long = HudMotion.STANDARD_MS,
        interpolator: Interpolator = HudMotion.enter,
        onEnd: (() -> Unit)? = null,
    ) {
        animator?.cancel()
        animator = null
        if (!HudMotion.enabled || current == target || durationMs <= 0L) {
            apply(target)
            onEnd?.invoke()
            return
        }
        animator = ValueAnimator.ofFloat(current, target).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { apply(it.animatedValue as Float) }
            // Deliberately on the update listener rather than an end listener:
            // a cancelled animation must not fire the continuation of a
            // sequence that has already been superseded.
            if (onEnd != null) {
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!cancelled) onEnd()
                        }
                    },
                )
            }
            start()
        }
    }

    /** Jump straight there, cancelling anything in flight. */
    fun snapTo(target: Float) {
        animator?.cancel()
        animator = null
        apply(target)
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    private fun apply(value: Float) {
        current = value
        onChange(value)
    }
}
