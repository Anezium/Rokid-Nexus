package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensWireContractTest {
    @Test
    fun `normalization is pinned for reply matching`() {
        assertEquals(
            "hello world next line",
            LensWireContract.normalizeText(" \t hello\r\nworld  next\nline \t"),
        )
        assertEquals("", LensWireContract.normalizeText(" \r\n\t "))
    }

    @Test
    fun `glasses deadlines include grace beyond phone deadlines`() {
        assertEquals(
            LensWireContract.PHONE_REQUEST_TIMEOUT_MS + LensWireContract.GLASSES_TIMEOUT_GRACE_MS,
            LensWireContract.GLASSES_REQUEST_TIMEOUT_MS,
        )
        assertEquals(
            LensWireContract.PHONE_MODEL_DOWNLOAD_TIMEOUT_MS + LensWireContract.GLASSES_TIMEOUT_GRACE_MS,
            LensWireContract.GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS,
        )
        assertTrue(LensWireContract.GLASSES_REQUEST_TIMEOUT_MS > LensWireContract.PHONE_REQUEST_TIMEOUT_MS)
        assertTrue(
            LensWireContract.GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS >
                LensWireContract.PHONE_MODEL_DOWNLOAD_TIMEOUT_MS,
        )
    }
}
