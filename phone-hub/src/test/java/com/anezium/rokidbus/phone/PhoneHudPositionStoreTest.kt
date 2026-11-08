package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.PageTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneHudPositionStoreTest {
    @Test
    fun `hud top inset defaults clamps and persists wearer changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = PhoneHudPositionStore(context)

        assertEquals(0, store.hudTopInsetDp())
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("hud_top_inset_dp", "garbage")
            .commit()
        assertEquals(0, store.hudTopInsetDp())
        assertEquals(
            0,
            context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
                .getInt("hud_top_inset_dp", -1),
        )
        store.setHudTopInsetDp(100)
        assertEquals(100, PhoneHudPositionStore(context).hudTopInsetDp())
        store.setHudTopInsetDp(500)
        assertEquals(120, PhoneHudPositionStore(context).hudTopInsetDp())
        store.setHudTopInsetDp(-1)
        assertEquals(0, PhoneHudPositionStore(context).hudTopInsetDp())
    }

    @Test
    fun `hud position auto defaults on and persists wearer changes`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = PhoneHudPositionStore(context)

        assertTrue(store.hudPositionAuto())
        preferences.edit().putString("hud_position_auto", "garbage").commit()
        assertTrue(store.hudPositionAuto())
        assertTrue(preferences.getBoolean("hud_position_auto", false))

        store.setHudPositionAuto(false)
        assertFalse(PhoneHudPositionStore(context).hudPositionAuto())
        store.setHudPositionAuto(true)
        assertTrue(PhoneHudPositionStore(context).hudPositionAuto())
    }

    @Test
    fun `page text size defaults to normal and persists wearer changes`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = PhoneHudPositionStore(context)

        assertEquals(PageTextSize.NORMAL, store.pageTextSize())
        preferences.edit().putInt("page_text_size", 3).commit()
        assertEquals(PageTextSize.NORMAL, store.pageTextSize())
        assertEquals("normal", preferences.getString("page_text_size", null))
        preferences.edit().putString("page_text_size", "huge").commit()
        assertEquals(PageTextSize.NORMAL, store.pageTextSize())
        assertEquals("normal", preferences.getString("page_text_size", null))

        store.setPageTextSize(PageTextSize.LARGE)
        assertEquals(PageTextSize.LARGE, PhoneHudPositionStore(context).pageTextSize())
        store.setPageTextSize(PageTextSize.NORMAL)
        assertEquals(PageTextSize.NORMAL, PhoneHudPositionStore(context).pageTextSize())
    }
}
