package com.anezium.rokidbus.glasses

import android.view.KeyEvent

internal enum class CameraInputAction { TOGGLE_FREEZE, ZOOM_IN, ZOOM_OUT }
internal data class CameraInputDecision(val consumed: Boolean, val action: CameraInputAction? = null)

/** Normalizes Rokid swipe aliases and suppresses paired direction events. */
internal class CameraInputRouter(
    private val directionalDebounceMs: Long = 400L,
    private val activationDebounceMs: Long = 400L,
) {
    private var lastDirectionalAtMs: Long? = null
    private var lastActivationAtMs: Long? = null

    fun routeKey(keyCode: Int, repeatCount: Int, eventTimeMs: Long): CameraInputDecision {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> CameraInputAction.TOGGLE_FREEZE
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, 183 -> CameraInputAction.ZOOM_IN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, 184 -> CameraInputAction.ZOOM_OUT
            else -> return CameraInputDecision(false)
        }
        if (repeatCount != 0) return CameraInputDecision(true)
        val previous = if (action == CameraInputAction.TOGGLE_FREEZE) lastActivationAtMs else lastDirectionalAtMs
        val debounce = if (action == CameraInputAction.TOGGLE_FREEZE) activationDebounceMs else directionalDebounceMs
        val accepted = previous == null || eventTimeMs < previous || eventTimeMs - previous >= debounce
        if (accepted) {
            if (action == CameraInputAction.TOGGLE_FREEZE) lastActivationAtMs = eventTimeMs
            else lastDirectionalAtMs = eventTimeMs
        }
        return CameraInputDecision(true, action.takeIf { accepted })
    }

    fun routeTap(eventTimeMs: Long): CameraInputDecision =
        routeKey(KeyEvent.KEYCODE_DPAD_CENTER, 0, eventTimeMs)

    fun handlesKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, 183, 184 -> true
        else -> false
    }
}
