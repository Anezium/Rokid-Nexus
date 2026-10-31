package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HudTopInsetTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `effective inset follows automatic and manual modes`() {
        val preferences = reset()

        assertEquals(0, HudTopInset.current(context))
        HudTopInset.set(context, manualDp = 40, auto = false)
        assertEquals(40, HudTopInset.current(context))

        HudTopInset.onRomRowMeasured(context, centerYpx = 524, density = 2f)
        assertEquals(40, HudTopInset.current(context))
        assertEquals(40, preferences.getInt("top_inset_dp", -1))
        assertEquals(80, preferences.getInt("auto_inset_dp", -1))
        assertFalse(preferences.getBoolean("auto", true))

        HudTopInset.set(context, manualDp = 30, auto = true)
        assertEquals(80, HudTopInset.current(context))
        assertEquals(80, HudTopInset.restore(context))
        assertEquals(30, preferences.getInt("top_inset_dp", -1))
        assertEquals(80, preferences.getInt("auto_inset_dp", -1))
        assertTrue(preferences.getBoolean("auto", false))

        HudTopInset.set(context, manualDp = 50, auto = false)
        assertEquals(50, HudTopInset.current(context))
    }

    @Test
    fun `rom row measurement follows calibrated formula and clamps`() {
        reset()

        HudTopInset.onRomRowMeasured(context, centerYpx = 364, density = 2f)
        assertEquals(0, HudTopInset.current(context))
        HudTopInset.onRomRowMeasured(context, centerYpx = 524, density = 2f)
        assertEquals(80, HudTopInset.current(context))
        HudTopInset.onRomRowMeasured(context, centerYpx = 1_000, density = 2f)
        assertEquals(120, HudTopInset.current(context))
        HudTopInset.onRomRowMeasured(context, centerYpx = 100, density = 2f)
        assertEquals(0, HudTopInset.current(context))
    }

    @Test
    fun `rom row measurement ignores changes below two dp`() {
        val preferences = reset()

        HudTopInset.onRomRowMeasured(context, centerYpx = 524, density = 2f)
        assertEquals(80, HudTopInset.current(context))
        HudTopInset.onRomRowMeasured(context, centerYpx = 526, density = 2f)
        assertEquals(80, HudTopInset.current(context))
        assertEquals(80, preferences.getInt("auto_inset_dp", -1))

        HudTopInset.onRomRowMeasured(context, centerYpx = 528, density = 2f)
        assertEquals(82, HudTopInset.current(context))
        assertEquals(82, preferences.getInt("auto_inset_dp", -1))
    }

    @Test
    fun `manual inset observers still hear accepted ROM row changes`() {
        val preferences = reset()
        HudTopInset.set(context, manualDp = 40, auto = false)
        val observed = mutableListOf<Int>()
        val unobserve = HudTopInset.observe(context) { observed += it }
        try {
            assertEquals(listOf(40), observed)
            HudTopInset.onRomRowMeasured(context, centerYpx = 524, density = 1.5f)
            assertEquals(listOf(40, 40), observed)
            assertEquals(107, preferences.getInt("auto_inset_dp", -1))
            HudTopInset.onRomRowMeasured(context, centerYpx = 525, density = 1.5f)
            assertEquals(listOf(40, 40), observed)
            HudTopInset.onRomRowMeasured(context, centerYpx = 544, density = 1.5f)
            assertEquals(listOf(40, 40, 40), observed)
            assertEquals(40, HudTopInset.current(context))
            assertEquals(40, preferences.getInt("top_inset_dp", -1))
            assertFalse(preferences.getBoolean("auto", true))
        } finally {
            unobserve()
            reset()
        }
    }

    @Test
    fun `restoring a different measured row also invalidates a manual layout`() {
        val preferences = reset()
        HudTopInset.set(context, manualDp = 40, auto = false)
        val observed = mutableListOf<Int>()
        val unobserve = HudTopInset.observe(context) { observed += it }
        try {
            preferences.edit().putInt("auto_inset_dp", 107).commit()
            assertEquals(40, HudTopInset.restore(context))
            assertEquals(listOf(40, 40), observed)
            assertEquals(40, HudTopInset.restore(context))
            assertEquals(listOf(40, 40), observed)
        } finally {
            unobserve()
            reset()
        }
    }

    @Test
    fun `persisted modes and inset values are sanitized on restore`() {
        val preferences = context.getSharedPreferences("hud_position", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("auto", false)
            .putInt("top_inset_dp", 500)
            .putInt("auto_inset_dp", -1)
            .commit()

        assertEquals(120, HudTopInset.restore(context))
        assertEquals(120, preferences.getInt("top_inset_dp", -1))
        assertEquals(0, preferences.getInt("auto_inset_dp", -1))

        preferences.edit()
            .putString("auto", "garbage")
            .putString("top_inset_dp", "garbage")
            .putString("auto_inset_dp", "garbage")
            .commit()
        assertEquals(0, HudTopInset.restore(context))
        assertTrue(preferences.getBoolean("auto", false))
        assertEquals(0, preferences.getInt("top_inset_dp", -1))
        assertEquals(0, preferences.getInt("auto_inset_dp", -1))
    }

    private fun reset(): SharedPreferences =
        context.getSharedPreferences("hud_position", Context.MODE_PRIVATE).also { preferences ->
            preferences.edit().clear().commit()
            assertEquals(0, HudTopInset.restore(context))
        }
}
