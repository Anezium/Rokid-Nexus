package com.anezium.rokidbus.shared

import android.content.ContentResolver
import android.provider.Settings
import org.json.JSONArray

/**
 * The three Developer options animation scales of one device.
 *
 * Rokid's own software does not survive them being turned off: with the animator
 * scale at 0, the glasses assistant's listening animation ends the instant it
 * starts, its end callback replays it, and the recursion kills the assistant
 * shortly after every "Hi Rokid", and in the same report the Hi Rokid app's
 * authorization screen closed before its Continue button enabled; restoring
 * all three scales to 1x fixed both (issue #116). Nexus cannot change
 * either app or write these global settings, so it detects the state and tells
 * the owner. A null scale is one this device did not report and is not treated
 * as off.
 */
data class AnimationScales(
    val window: Float?,
    val transition: Float?,
    val animator: Float?,
) {
    /** The scales set to 0, by the setting key Android uses for each. */
    val disabled: List<String>
        get() = buildList {
            if (window.isOff()) add(WINDOW)
            if (transition.isOff()) add(TRANSITION)
            if (animator.isOff()) add(ANIMATOR)
        }

    val anyDisabled: Boolean
        get() = disabled.isNotEmpty()

    companion object {
        const val WINDOW = "window_animation_scale"
        const val TRANSITION = "transition_animation_scale"
        const val ANIMATOR = "animator_duration_scale"

        private val KNOWN = listOf(WINDOW, TRANSITION, ANIMATOR)

        /** An absent setting means the platform default of 1x. */
        fun read(resolver: ContentResolver): AnimationScales = AnimationScales(
            window = readScale(resolver, Settings.Global.WINDOW_ANIMATION_SCALE),
            transition = readScale(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE),
            animator = readScale(resolver, Settings.Global.ANIMATOR_DURATION_SCALE),
        )

        fun disabledToJson(scales: AnimationScales): JSONArray = JSONArray(scales.disabled)

        /** Keeps only the known keys, so a peer cannot inject text into the warning. */
        fun disabledFromJson(array: JSONArray?): List<String>? {
            array ?: return null
            return (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it in KNOWN } }
                .distinct()
        }

        /** The names Developer options shows, for a warning the owner can act on. */
        fun label(key: String): String = when (key) {
            WINDOW -> "Window animation scale"
            TRANSITION -> "Transition animation scale"
            ANIMATOR -> "Animator duration scale"
            else -> key
        }

        private fun readScale(resolver: ContentResolver, key: String): Float? =
            runCatching { Settings.Global.getFloat(resolver, key, 1f) }.getOrNull()

        private fun Float?.isOff(): Boolean = this != null && this <= 0f
    }
}
