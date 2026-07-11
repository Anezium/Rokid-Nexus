package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProxyPolicyTest {
    @Test
    fun permittedRequestIsNormalizedAndRedirectsAreDisabled() {
        val validation = validate(
            url = "https://API.TRANSITOUS.ORG:443/api/v1/geocode?text=Paris",
            method = " get ",
        )

        val request = (validation as HttpProxyPolicy.Validation.Allowed).request
        assertEquals("GET", request.method)
        assertEquals("api.transitous.org", request.url.host.lowercase())
        assertFalse(request.followRedirects)
    }

    @Test
    fun nonHttpsSchemeIsRejected() {
        assertRejected("SCHEME_NOT_ALLOWED", url = "http://api.transitous.org/api/v1")
    }

    @Test
    fun lookalikeHostIsRejected() {
        assertRejected("HOST_NOT_ALLOWED", url = "https://api.transitous.org.example.com/api/v1")
    }

    @Test
    fun userInfoIsRejected() {
        assertRejected("USER_INFO_NOT_ALLOWED", url = "https://user@api.transitous.org/api/v1")
    }

    @Test
    fun nonDefaultPortIsRejected() {
        assertRejected("PORT_NOT_ALLOWED", url = "https://api.transitous.org:444/api/v1")
    }

    @Test
    fun unsupportedMethodIsRejected() {
        assertRejected("METHOD_NOT_ALLOWED", method = "DELETE")
    }

    @Test
    fun postMethodIsAllowed() {
        val validation = validate(method = "POST", body = "payload".toByteArray())

        assertTrue(validation is HttpProxyPolicy.Validation.Allowed)
    }

    @Test
    fun requestBodyLimitIsInclusive() {
        assertTrue(validate(body = ByteArray(HttpProxyPolicy.MAX_REQUEST_BODY_BYTES)) is HttpProxyPolicy.Validation.Allowed)
        assertRejected(
            "REQUEST_BODY_TOO_LARGE",
            body = ByteArray(HttpProxyPolicy.MAX_REQUEST_BODY_BYTES + 1),
        )
    }

    @Test
    fun onlyAllowlistedHeadersAreForwardedCaseInsensitively() {
        val validation = validate(
            headers = linkedMapOf(
                "accept" to "application/json",
                "CONTENT-TYPE" to "application/json",
                "If-None-Match" to "etag",
                "if-modified-since" to "yesterday",
                "User-Agent" to "RokidNexus",
                "Authorization" to "secret",
                "Cookie" to "private",
            ),
        )

        val headers = (validation as HttpProxyPolicy.Validation.Allowed).request.headers
        assertEquals(
            setOf("Accept", "Content-Type", "If-None-Match", "If-Modified-Since", "User-Agent"),
            headers.keys,
        )
        assertFalse(headers.containsKey("Authorization"))
        assertFalse(headers.containsKey("Cookie"))
    }

    @Test
    fun responseBudgetAcceptsExactLimitThenRejectsMore() {
        val budget = HttpProxyPolicy.ResponseBudget(maxBytes = 10L)

        assertTrue(budget.accept(4))
        assertTrue(budget.accept(6))
        assertFalse(budget.accept(1))
        assertEquals(10L, budget.totalBytes)
    }

    @Test
    fun responseBudgetRejectsChunkThatWouldCrossLimitWithoutAccountingIt() {
        val budget = HttpProxyPolicy.ResponseBudget(maxBytes = 10L)

        assertTrue(budget.accept(7))
        assertFalse(budget.accept(4))
        assertEquals(7L, budget.totalBytes)
    }

    @Test
    fun malformedUrlIsRejected() {
        assertRejected("INVALID_URL", url = "https://api.transitous.org/%")
    }

    private fun validate(
        url: String = "https://api.transitous.org/api/v1/geocode",
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): HttpProxyPolicy.Validation = HttpProxyPolicy.validate(
        urlText = url,
        methodText = method,
        callerHeaders = headers,
        body = body,
    )

    private fun assertRejected(
        expectedCode: String,
        url: String = "https://api.transitous.org/api/v1/geocode",
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) {
        val rejected = validate(url, method, headers, body) as HttpProxyPolicy.Validation.Rejected
        assertEquals(expectedCode, rejected.errorCode)
    }
}
