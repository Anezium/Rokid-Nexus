package com.anezium.rokidbus.glasses

class TripleTapDetector(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val suppressionMs: Long = DEFAULT_SUPPRESSION_MS,
    private val notificationKeyCode: Int = KEYCODE_NOTIFICATION,
    private val backKeyCode: Int = KEYCODE_BACK,
    private val enterKeyCode: Int = KEYCODE_ENTER,
) {
    enum class Decision {
        PASS,
        CONSUME,
        TRIGGER,
    }

    private val contactTimes = ArrayDeque<Long>()
    private var suppressClassificationsUntilMs = Long.MIN_VALUE

    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Decision {
        if (isSuppressedClassification(keyCode, eventTimeMs)) {
            return Decision.CONSUME
        }
        if (keyCode != notificationKeyCode) {
            // Swipes also start with a NOTIFICATION contact before their DPAD
            // pair, so any other key DOWN must break the pending tap streak or
            // three fast swipes would count as a triple tap.
            if (action == ACTION_DOWN) contactTimes.clear()
            return Decision.PASS
        }
        if (action != ACTION_DOWN || repeatCount != 0) {
            return Decision.PASS
        }

        val cutoff = eventTimeMs - windowMs
        while (contactTimes.isNotEmpty() && contactTimes.first() < cutoff) {
            contactTimes.removeFirst()
        }
        contactTimes.addLast(eventTimeMs)
        if (contactTimes.size < 3) {
            return Decision.PASS
        }

        contactTimes.clear()
        suppressClassificationsUntilMs = eventTimeMs + suppressionMs
        return Decision.TRIGGER
    }

    fun consumeExpiredTapCount(eventTimeMs: Long): Int {
        val latest = contactTimes.lastOrNull() ?: return 0
        if (eventTimeMs - latest <= windowMs) return 0
        val count = contactTimes.size.coerceAtMost(2)
        contactTimes.clear()
        return count
    }

    private fun isSuppressedClassification(keyCode: Int, eventTimeMs: Long): Boolean =
        eventTimeMs <= suppressClassificationsUntilMs && (keyCode == backKeyCode || keyCode == enterKeyCode)

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_ENTER = 66
        const val KEYCODE_NOTIFICATION = 83
        const val DEFAULT_WINDOW_MS = 600L
        const val DEFAULT_SUPPRESSION_MS = 800L
    }
}

class DpadPairDedupe(
    private val pairWindowMs: Long = DEFAULT_PAIR_WINDOW_MS,
) {
    enum class Direction {
        FORWARD,
        BACKWARD,
    }

    private var lastAcceptedDirection: Direction? = null
    private var lastAcceptedAtMs = Long.MIN_VALUE

    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Direction? {
        if (action != TripleTapDetector.ACTION_DOWN || repeatCount != 0) return null
        val direction = when (keyCode) {
            KEYCODE_DPAD_RIGHT,
            KEYCODE_DPAD_DOWN,
            -> Direction.FORWARD
            KEYCODE_DPAD_LEFT,
            KEYCODE_DPAD_UP,
            -> Direction.BACKWARD
            else -> null
        } ?: return null

        if (direction == lastAcceptedDirection && eventTimeMs - lastAcceptedAtMs <= pairWindowMs) {
            return null
        }
        lastAcceptedDirection = direction
        lastAcceptedAtMs = eventTimeMs
        return direction
    }

    companion object {
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22

        // Hardware swipes emit their duplicated key 20-80ms apart (measured on
        // device 2026-07-08); 50ms sat inside that jitter and let doubles through.
        // Deliberate repeat swipes are never faster than ~200ms, so 150ms is safe.
        const val DEFAULT_PAIR_WINDOW_MS = 150L
    }
}
