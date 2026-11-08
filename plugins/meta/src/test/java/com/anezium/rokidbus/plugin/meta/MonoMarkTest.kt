package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row-icon packing: row-major, MSB first, within the contract's bounds. */
class MonoMarkTest {
    @Test fun `pixels pack row-major with the most significant bit first`() {
        val mark = MonoMark.pack(10, 2) { x, y -> (x == 0 && y == 0) || (x == 9 && y == 1) }
        assertEquals(LauncherArtwork.MONO1, mark.encoding)
        val bytes = mark.bytes()
        assertEquals(3, bytes.size)
        assertEquals(0x80, bytes[0].toInt() and 0xff)
        assertEquals(0x00, bytes[1].toInt() and 0xff)
        // Pixel index 19 lives in byte 2, bit 3 from the top.
        assertEquals(0x10, bytes[2].toInt() and 0xff)
    }

    @Test fun `the service mark size fits the row icon bound`() {
        assertTrue(ServiceMarks.EDGE <= PageTemplateContract.MAX_ROW_ICON_EDGE)
        val full = MonoMark.pack(ServiceMarks.EDGE, ServiceMarks.EDGE) { _, _ -> true }
        assertEquals(ServiceMarks.EDGE * ServiceMarks.EDGE / 8, full.bytes().size)
        assertTrue(full.bytes().all { it == 0xff.toByte() })
    }
}
