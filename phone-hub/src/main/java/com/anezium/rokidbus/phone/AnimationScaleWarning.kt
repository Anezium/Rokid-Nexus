package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.AnimationScales

/** Owner-facing text for animation scales turned off; see [AnimationScales] for why it matters. */
internal object AnimationScaleWarning {
    /** Null when nothing is off, so callers can hide the warning. */
    fun phone(disabled: List<String>): String? {
        if (disabled.isEmpty()) return null
        return "Animations are turned off on this phone (${labels(disabled)}). Hi Rokid's " +
            "authorization screen can close by itself while they are off. Set them back to 1x " +
            "in Developer options, then authorize again."
    }

    /** Null when nothing is off. Glasses have no Developer options screen, so this gives adb. */
    fun glasses(disabled: List<String>): String? {
        if (disabled.isEmpty()) return null
        return "Animations are turned off on the glasses (${labels(disabled)}). Rokid's assistant " +
            "crashes right after \"Hi Rokid\" while they are off. Restore them over adb:\n" +
            disabled.joinToString("\n") { "adb shell settings put global $it 1" }
    }

    private fun labels(disabled: List<String>): String = disabled.joinToString(", ") { AnimationScales.label(it) }
}
