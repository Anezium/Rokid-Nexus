package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitLocationAccessTest {
    @Test
    fun `precise permission is required before background permission`() {
        assertEquals(
            TransitLocationAccess.MISSING_PRECISE,
            TransitLocationAccess.from(preciseGranted = false, backgroundGranted = false),
        )
        assertEquals(
            TransitLocationAccess.MISSING_PRECISE,
            TransitLocationAccess.from(preciseGranted = false, backgroundGranted = true),
        )
    }

    @Test
    fun `background permission is required after precise permission`() {
        assertEquals(
            TransitLocationAccess.MISSING_BACKGROUND,
            TransitLocationAccess.from(preciseGranted = true, backgroundGranted = false),
        )
        assertEquals(
            TransitLocationAccess.READY,
            TransitLocationAccess.from(preciseGranted = true, backgroundGranted = true),
        )
    }
}
