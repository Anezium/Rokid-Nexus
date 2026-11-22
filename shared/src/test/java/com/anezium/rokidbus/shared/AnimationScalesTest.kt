package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationScalesTest {
    @Test
    fun `only scales at zero count as disabled`() {
        val scales = AnimationScales(window = 0.5f, transition = 0f, animator = 0f)
        assertEquals(listOf(AnimationScales.TRANSITION, AnimationScales.ANIMATOR), scales.disabled)
        assertTrue(scales.anyDisabled)
        assertFalse(AnimationScales(1f, 1f, 1f).anyDisabled)
    }

    @Test
    fun `an unreported scale is not treated as off`() {
        assertFalse(AnimationScales(window = null, transition = null, animator = null).anyDisabled)
    }

    @Test
    fun `the accessibility check reply carries the disabled scales`() {
        val json = GlassesAccessibilityCheckContract.replyToJson(
            foreignServices = emptyList(),
            nexusEnabled = true,
            animationScales = AnimationScales(window = 0f, transition = 0f, animator = 0f),
        )
        val reply = GlassesAccessibilityCheckContract.fromReply(json)!!
        assertEquals(
            listOf(AnimationScales.WINDOW, AnimationScales.TRANSITION, AnimationScales.ANIMATOR),
            reply.animationScalesOff,
        )
    }

    @Test
    fun `an older glasses reply leaves the scales unknown`() {
        val json = GlassesAccessibilityCheckContract.replyToJson(emptyList(), nexusEnabled = true)
        assertNull(GlassesAccessibilityCheckContract.fromReply(json)!!.animationScalesOff)
    }

    @Test
    fun `unknown keys from the peer are dropped`() {
        val parsed = AnimationScales.disabledFromJson(
            JSONArray(listOf("animator_duration_scale", "rm -rf /", "animator_duration_scale")),
        )
        assertEquals(listOf(AnimationScales.ANIMATOR), parsed)
    }
}
