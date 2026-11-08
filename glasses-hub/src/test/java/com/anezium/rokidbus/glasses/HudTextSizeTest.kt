package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Looper
import com.anezium.rokidbus.shared.PageTextSize
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class HudTextSizeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        context.getSharedPreferences(HudTopInset.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `defaults to normal and keeps what the phone announced`() {
        assertEquals(PageTextSize.NORMAL, HudTextSize.current(context))

        HudTextSize.set(context, PageTextSize.LARGE)

        assertEquals(PageTextSize.LARGE, HudTextSize.current(context))
    }

    @Test
    fun `garbage in the store reads as normal`() {
        context.getSharedPreferences(HudTopInset.PREFS, Context.MODE_PRIVATE).edit().putInt("page_text_size", 3).commit()

        assertEquals(PageTextSize.NORMAL, HudTextSize.current(context))
    }

    @Test
    fun `observers get the current size then each change once`() {
        HudTextSize.set(context, PageTextSize.LARGE)
        val seen = mutableListOf<PageTextSize>()
        val unsubscribe = HudTextSize.observe(context) { seen += it }
        shadowOf(Looper.getMainLooper()).idle()

        HudTextSize.set(context, PageTextSize.LARGE)
        HudTextSize.set(context, PageTextSize.NORMAL)
        unsubscribe()
        HudTextSize.set(context, PageTextSize.LARGE)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(PageTextSize.LARGE, PageTextSize.NORMAL), seen)
    }
}
