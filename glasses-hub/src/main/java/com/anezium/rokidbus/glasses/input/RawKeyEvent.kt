// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.input

/** Which physical source a key came from; classified once, by device name. */
internal enum class DeviceClass {
    R08,
    TOUCHPAD,

    /** Declared for the arbiter's rules; never assigned until a device trace tells it from the touchpad. */
    KEYBOARD_DPAD,

    /** Declared for the arbiter's rules; never assigned until a device trace tells it from the touchpad. */
    OTHER,
}

/** A key event with everything [InputArbiter] needs and nothing from the Android framework. */
internal data class RawKeyEvent(
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int,
    /** `KeyEvent.getEventTime()`, on the uptime clock like every deadline of the arbiter. */
    val eventTime: Long,
    /** `KeyEvent.getDownTime()`: with [deviceId] and [keyCode], the identity of one press. */
    val downTime: Long,
    val deviceId: Int,
    val deviceClass: DeviceClass,
) {
    val isDown: Boolean get() = action == ACTION_DOWN
    val isUp: Boolean get() = action == ACTION_UP

    /** The first DOWN of a press; repeats and the UP continue it. */
    val startsPress: Boolean get() = isDown && repeatCount == 0

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}

/** The glasses' key codes the arbiter routes, kept here so the package stays free of `android.*`. */
internal object InputKeys {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val ENTER = 66
    const val NOTIFICATION = 83
    const val RING_TAP = 85
    const val RING_FORWARD = 87
    const val RING_BACKWARD = 88
    const val PROG_BLUE = 186

    val DIRECTIONS = setOf(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT)
    val CONFIRMS = setOf(ENTER, DPAD_CENTER)
}

/** Which launcher answers the global gesture. Only one of them ever has a window. */
internal enum class LauncherBackend {
    LEGACY,
    SESSION,
    ;

    companion object {
        val DEFAULT = LEGACY

        fun parse(value: String?): LauncherBackend? = entries.firstOrNull { it.name == value }
    }
}
