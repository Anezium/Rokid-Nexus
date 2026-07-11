package com.anezium.rokidbus.lens

import android.view.KeyEvent

internal enum class LensInputAction {
    TOGGLE_FREEZE,
    ZOOM_IN,
    ZOOM_OUT,
}

internal data class LensInputDecision(
    val consumed: Boolean,
    val action: LensInputAction? = null,
)

/**
 * Normalizes the key aliases emitted by Rokid touchpads and suppresses paired events from one
 * physical gesture (for example RIGHT followed by DOWN).
 */
internal class LensInputRouter(
    private val directionalDebounceMs: Long = DIRECTIONAL_DEBOUNCE_MS,
    private val activationDebounceMs: Long = ACTIVATION_DEBOUNCE_MS,
) {
    private var lastDirectionalAtMs: Long? = null
    private var lastActivationAtMs: Long? = null

    fun routeKey(keyCode: Int, repeatCount: Int, eventTimeMs: Long): LensInputDecision {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> LensInputAction.TOGGLE_FREEZE

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KEYCODE_ROKID_SWIPE_FORWARD,
            -> LensInputAction.ZOOM_IN

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KEYCODE_ROKID_SWIPE_BACK,
            -> LensInputAction.ZOOM_OUT

            else -> return LensInputDecision(consumed = false)
        }

        // Consume repeats and paired aliases so they cannot fall through to View focus/click logic.
        if (repeatCount != 0) return LensInputDecision(consumed = true)

        return when (action) {
            LensInputAction.TOGGLE_FREEZE -> routeDebouncedActivation(eventTimeMs)
            LensInputAction.ZOOM_IN,
            LensInputAction.ZOOM_OUT,
            -> routeDebouncedDirection(action, eventTimeMs)
        }
    }

    fun routeTap(eventTimeMs: Long): LensInputDecision = routeDebouncedActivation(eventTimeMs)

    fun handlesKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KEYCODE_ROKID_SWIPE_FORWARD,
            KEYCODE_ROKID_SWIPE_BACK,
            -> true

            else -> false
        }

    private fun routeDebouncedDirection(action: LensInputAction, eventTimeMs: Long): LensInputDecision {
        val accepted = shouldAccept(lastDirectionalAtMs, eventTimeMs, directionalDebounceMs)
        if (accepted) lastDirectionalAtMs = eventTimeMs
        return LensInputDecision(
            consumed = true,
            action = action.takeIf { accepted },
        )
    }

    private fun routeDebouncedActivation(eventTimeMs: Long): LensInputDecision {
        val accepted = shouldAccept(lastActivationAtMs, eventTimeMs, activationDebounceMs)
        if (accepted) lastActivationAtMs = eventTimeMs
        return LensInputDecision(
            consumed = true,
            action = LensInputAction.TOGGLE_FREEZE.takeIf { accepted },
        )
    }

    private fun shouldAccept(previousMs: Long?, currentMs: Long, debounceMs: Long): Boolean =
        previousMs == null || currentMs < previousMs || currentMs - previousMs >= debounceMs

    private companion object {
        const val DIRECTIONAL_DEBOUNCE_MS = 400L
        const val ACTIVATION_DEBOUNCE_MS = 400L
        const val KEYCODE_ROKID_SWIPE_FORWARD = 183
        const val KEYCODE_ROKID_SWIPE_BACK = 184
    }
}
