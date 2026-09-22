package com.anezium.rokidbus.glasses

import android.view.KeyEvent

/** Notice priority also applies to keys delivered directly to a Nexus window. */
internal object NoticeKeyDispatcher {
    private val router = NoticeKeyInputRouter(
        editableSurfaceActive = { SurfaceController.hasFocusedEditableSurface() },
        dismiss = { NoticeController.dismissFromBack() },
        claimsDirection = { NoticeController.claimsDirection() },
        moveDirection = { NoticeController.handleDirection(it) },
        confirm = { NoticeController.handleConfirm(it) },
        claimsAllInput = { NoticeController.claimsAllInput() },
    )

    fun handleKeyEvent(event: KeyEvent): Boolean = router.handleKey(
        keyCode = event.keyCode,
        action = event.action,
        repeatCount = event.repeatCount,
        eventTimeMs = event.eventTime,
        downTimeMs = event.downTime,
        deviceId = event.deviceId,
    )

    fun reset() = router.reset()
}

internal class NoticeKeyInputRouter(
    private val editableSurfaceActive: () -> Boolean,
    private val dismiss: () -> Boolean,
    private val claimsDirection: () -> Boolean,
    private val moveDirection: (Int) -> Unit,
    private val confirm: (Int) -> Boolean,
    private val claimsAllInput: () -> Boolean,
) {
    private data class Key(val deviceId: Int, val keyCode: Int)

    private val consumedPresses = mutableMapOf<Key, Long>()
    private var directionDedupe = DpadPairDedupe()

    fun handleKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTimeMs: Long,
        downTimeMs: Long,
        deviceId: Int,
    ): Boolean {
        val key = Key(deviceId, keyCode)
        val samePress = consumedPresses[key] == downTimeMs
        if (action == KeyEvent.ACTION_UP) {
            if (samePress) consumedPresses.remove(key)
            return samePress
        }
        if (action != KeyEvent.ACTION_DOWN) return false
        // A notice can disappear or become answered before repeat/UP. The
        // remainder of that press still belongs to it, never the surface below.
        if (samePress) return true
        consumedPresses.remove(key)

        val handled = when {
            keyCode == KeyEvent.KEYCODE_BACK -> dismiss()
            editableSurfaceActive() -> false
            keyCode in DIRECTION_KEYS && claimsDirection() -> {
                when (directionDedupe.onKey(keyCode, action, repeatCount, eventTimeMs)) {
                    DpadPairDedupe.Direction.FORWARD -> moveDirection(1)
                    DpadPairDedupe.Direction.BACKWARD -> moveDirection(-1)
                    null -> Unit
                }
                true
            }
            keyCode in CONFIRM_KEYS && confirm(keyCode) -> true
            else -> NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = claimsAllInput(),
                keyCode = keyCode,
                action = action,
            )
        }
        if (handled) consumedPresses[key] = downTimeMs
        return handled
    }

    fun reset() {
        consumedPresses.clear()
        directionDedupe = DpadPairDedupe()
    }

    private companion object {
        val DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        // NOTIFICATION is a raw contact, before firmware decides tap or swipe.
        val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
    }
}
