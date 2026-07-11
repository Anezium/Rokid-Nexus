package com.anezium.rokidbus.phone

import java.net.URI
import java.net.URL
import java.util.Locale

internal object HttpProxyPolicy {
    const val MAX_REQUEST_BODY_BYTES = 64 * 1024
    const val MAX_RESPONSE_BODY_BYTES = 4 * 1024 * 1024L

    private const val ALLOWED_HOST = "api.transitous.org"
    private val allowedMethods = setOf("GET", "POST")
    private val allowedHeaders = linkedMapOf(
        "accept" to "Accept",
        "content-type" to "Content-Type",
        "if-none-match" to "If-None-Match",
        "if-modified-since" to "If-Modified-Since",
        "user-agent" to "User-Agent",
    )

    data class Request(
        val url: URL,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val followRedirects: Boolean = false,
    )

    sealed interface Validation {
        data class Allowed(val request: Request) : Validation
        data class Rejected(val errorCode: String) : Validation
    }

    fun validate(
        urlText: String,
        methodText: String,
        callerHeaders: Map<String, String>,
        body: ByteArray,
    ): Validation {
        val uri = runCatching { URI(urlText) }.getOrNull()
            ?: return Validation.Rejected("INVALID_URL")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return Validation.Rejected("SCHEME_NOT_ALLOWED")
        }
        if (uri.rawUserInfo != null) {
            return Validation.Rejected("USER_INFO_NOT_ALLOWED")
        }
        if (!uri.host.equals(ALLOWED_HOST, ignoreCase = true)) {
            return Validation.Rejected("HOST_NOT_ALLOWED")
        }
        if (uri.port != -1 && uri.port != 443) {
            return Validation.Rejected("PORT_NOT_ALLOWED")
        }

        val method = methodText.trim().uppercase(Locale.US)
        if (method !in allowedMethods) {
            return Validation.Rejected("METHOD_NOT_ALLOWED")
        }
        if (body.size > MAX_REQUEST_BODY_BYTES) {
            return Validation.Rejected("REQUEST_BODY_TOO_LARGE")
        }

        val headers = linkedMapOf<String, String>()
        callerHeaders.forEach { (name, value) ->
            val canonicalName = allowedHeaders[name.lowercase(Locale.US)] ?: return@forEach
            headers[canonicalName] = value
        }
        val url = runCatching { uri.toURL() }.getOrNull()
            ?: return Validation.Rejected("INVALID_URL")
        return Validation.Allowed(
            Request(
                url = url,
                method = method,
                headers = headers,
                body = body,
            ),
        )
    }

    class ResponseBudget(
        private val maxBytes: Long = MAX_RESPONSE_BODY_BYTES,
    ) {
        var totalBytes: Long = 0L
            private set

        init {
            require(maxBytes >= 0L) { "maxBytes must not be negative" }
        }

        fun accept(byteCount: Int): Boolean {
            require(byteCount >= 0) { "byteCount must not be negative" }
            if (byteCount.toLong() > maxBytes - totalBytes) return false
            totalBytes += byteCount
            return true
        }
    }
}
