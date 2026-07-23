package com.anezium.rokidbus.glasses

/** Pure R08-to-temple input translation for an active plugin surface. */
internal class RingSurfaceInputPolicy(
    private val tapPolicy: RingTapPolicy = RingTapPolicy(),
) {
    data class MappedKeyEvent(
        val keyCode: Int,
        val action: Int,
    )

    sealed interface Resolution {
        data class Forward(
            val events: List<MappedKeyEvent>,
        ) : Resolution

        data object Back : Resolution

        data object Ignore : Resolution
    }

    fun onKeyDown(keyCode: Int, eventTimeMs: Long): Resolution? =
        when (keyCode) {
            RING_KEYCODE_FORWARD -> forwardPair(KEYCODE_DPAD_RIGHT)
            RING_KEYCODE_BACKWARD -> forwardPair(KEYCODE_DPAD_LEFT)
            RING_KEYCODE_TAP -> {
                tapPolicy.onTap(eventTimeMs)
                null
            }
            else -> null
        }

    fun resolveExpired(eventTimeMs: Long): Resolution? =
        when (tapPolicy.resolveExpired(eventTimeMs)) {
            RingTapPolicy.Resolution.SINGLE -> forwardPair(KEYCODE_ENTER)
            RingTapPolicy.Resolution.DOUBLE -> Resolution.Back
            RingTapPolicy.Resolution.IGNORE -> Resolution.Ignore
            null -> null
        }

    fun reset() {
        tapPolicy.reset()
    }

    private fun forwardPair(keyCode: Int): Resolution.Forward =
        Resolution.Forward(
            listOf(
                MappedKeyEvent(keyCode, ACTION_DOWN),
                MappedKeyEvent(keyCode, ACTION_UP),
            ),
        )

    companion object {
        const val RING_KEYCODE_TAP = 85
        const val RING_KEYCODE_FORWARD = 87
        const val RING_KEYCODE_BACKWARD = 88

        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_ENTER = 66

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}
