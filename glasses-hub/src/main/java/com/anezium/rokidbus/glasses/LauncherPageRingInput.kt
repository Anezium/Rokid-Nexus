package com.anezium.rokidbus.glasses

/** The R08 ring on the launcher page: swipes move the selection, one tap opens, two go back. */
internal class LauncherPageRingInput(
    private val onMove: (Int) -> Unit,
    private val onOpen: () -> Unit,
    private val onBack: () -> Unit,
) {
    private val taps = RingTapPolicy()

    /** Returns true when a tap now waits for [resolveTaps], once its window has passed. */
    fun onPress(keyCode: Int, eventTimeMs: Long): Boolean {
        when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD -> onMove(1)
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD -> onMove(-1)
            RingSurfaceInputPolicy.RING_KEYCODE_TAP -> {
                taps.onTap(eventTimeMs)
                return true
            }
        }
        return false
    }

    fun resolveTaps(nowMs: Long) {
        when (taps.resolveExpired(nowMs)) {
            RingTapPolicy.Resolution.SINGLE -> onOpen()
            RingTapPolicy.Resolution.DOUBLE -> onBack()
            RingTapPolicy.Resolution.IGNORE,
            null,
            -> Unit
        }
    }

    fun reset() = taps.reset()

    companion object {
        fun isRingGesture(keyCode: Int): Boolean = when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_TAP,
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD,
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD,
            -> true
            else -> false
        }
    }
}
