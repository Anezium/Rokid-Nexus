package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCookieCaptureTest {
    @Test
    fun spDcFromCookieHeaders_readsFirstHeaderThatContainsTheCookie() {
        assertEquals(
            "AQD_first",
            spDcFromCookieHeaders(
                "sp_t=abc; sp_dc=AQD_first",
                "sp_dc=AQD_second",
            ),
        )
    }

    @Test
    fun spDcFromCookieHeaders_skipsNullAndCookielessHeaders() {
        assertEquals(
            "AQD_fallback",
            spDcFromCookieHeaders(
                null,
                "sp_t=abc; other=1",
                "sp_dc=AQD_fallback",
            ),
        )
    }

    @Test
    fun spDcFromCookieHeaders_returnsNullWhenNoHeaderHasTheCookie() {
        assertNull(spDcFromCookieHeaders(null, "sp_t=abc; other=1", ""))
    }
}
