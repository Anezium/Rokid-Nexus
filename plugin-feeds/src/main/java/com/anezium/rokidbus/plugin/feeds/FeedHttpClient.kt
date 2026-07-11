package com.anezium.rokidbus.plugin.feeds

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface FeedHttpClient {
    fun get(url: String, headers: Map<String, String>): String

    fun getResponse(url: String, headers: Map<String, String>): FeedHttpResponse =
        FeedHttpResponse(statusCode = 200, body = get(url, headers))
}

data class FeedHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

internal class UrlConnectionFeedHttpClient : FeedHttpClient {
    override fun get(url: String, headers: Map<String, String>): String {
        val response = getResponse(url, headers)
        if (response.statusCode !in 200..299) {
            throw IOException("HTTP ${response.statusCode}: ${response.body.take(120)}")
        }
        return response.body
    }

    override fun getResponse(url: String, headers: Map<String, String>): FeedHttpResponse {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                return getOnce(url, headers)
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt == 0) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        throw IOException(lastFailure?.message ?: "Feed request failed", lastFailure)
    }

    private fun getOnce(url: String, headers: Map<String, String>): FeedHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach(::setRequestProperty)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            FeedHttpResponse(
                statusCode = status,
                body = body,
                headers = connection.headerFields.entries
                    .mapNotNull { (name, values) ->
                        name?.lowercase()?.let { it to values.firstOrNull().orEmpty() }
                    }
                    .toMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "RokidNexus/0.1 (+https://github.com/Anezium)"
        const val TIMEOUT_MS = 10_000
        const val RETRY_DELAY_MS = 750L
    }
}
