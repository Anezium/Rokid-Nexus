package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.AnimationScales
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationScaleWarningTest {
    @Test
    fun `nothing disabled gives no warning`() {
        assertNull(AnimationScaleWarning.phone(emptyList()))
        assertNull(AnimationScaleWarning.glasses(emptyList()))
    }

    @Test
    fun `phone warning names the Developer options setting`() {
        val message = AnimationScaleWarning.phone(listOf(AnimationScales.ANIMATOR))!!
        assertTrue(message.contains("Animator duration scale"))
        assertTrue(message.contains("Developer options"))
    }

    @Test
    fun `glasses warning gives one adb command per disabled scale`() {
        val message = AnimationScaleWarning.glasses(
            listOf(AnimationScales.TRANSITION, AnimationScales.ANIMATOR),
        )!!
        assertTrue(message.contains("adb shell settings put global transition_animation_scale 1"))
        assertTrue(message.contains("adb shell settings put global animator_duration_scale 1"))
        assertTrue(!message.contains("window_animation_scale"))
    }
}
