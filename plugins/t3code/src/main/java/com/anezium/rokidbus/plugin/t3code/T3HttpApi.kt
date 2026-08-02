package com.anezium.rokidbus.plugin.t3code

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal sealed interface T3LinkResult {
    data class Success(val endpoint: T3Endpoint) : T3LinkResult
    data class Failure(val message: String) : T3LinkResult
}

internal sealed interface T3TicketResult {
    data class Success(val ticket: String) : T3TicketResult
    data object Unauthorized : T3TicketResult
    data class Failure(val message: String) : T3TicketResult
}

internal class T3HttpApi(
    private val client: OkHttpClient = OkHttpClient(),
) {
    @Volatile
    private var activeCall: Call? = null

    fun link(host: String, port: Int, pairingCode: String): T3LinkResult = runCatching {
        require(pairingCode.trim().isNotEmpty()) { "Enter a pairing code" }
        val normalizedHost = normalizeHost(host)
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        val base = baseUrl(normalizedHost, port)
        val environment = execute(
            Request.Builder()
                .url(base.newBuilder().addPathSegments(".well-known/t3/environment").build())
                .get()
                .build(),
        )
        if (!environment.successful) return T3LinkResult.Failure(environment.error)
        val label = runCatching { JSONObject(environment.body).stringOrNull("label") }
            .getOrNull()
            ?: normalizedHost

        val tokenBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            .add("subject_token", pairingCode.trim())
            .add("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
            .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
            .add("scope", REQUIRED_SCOPES)
            .add("client_label", "Rokid Glasses")
            .add("client_device_type", "mobile")
            .add("client_os", "Android")
            .build()
        val tokenResponse = execute(
            Request.Builder()
                .url(base.newBuilder().addPathSegments("oauth/token").build())
                .post(tokenBody)
                .build(),
        )
        if (!tokenResponse.successful) return T3LinkResult.Failure(tokenResponse.error)
        val token = runCatching { JSONObject(tokenResponse.body).stringOrNull("access_token") }
            .getOrNull()
            ?: return T3LinkResult.Failure("T3 Code returned no access token")
        T3LinkResult.Success(T3Endpoint(normalizedHost, port, token, label))
    }.getOrElse { error -> T3LinkResult.Failure(error.message ?: "Could not link to T3 Code") }

    fun ticket(endpoint: T3Endpoint): T3TicketResult = runCatching {
        val response = execute(
            Request.Builder()
                .url(baseUrl(endpoint.host, endpoint.port).newBuilder().addPathSegments("api/auth/websocket-ticket").build())
                .header("Authorization", "Bearer ${endpoint.token}")
                .post(EMPTY_BODY)
                .build(),
        )
        if (response.code == 401) return T3TicketResult.Unauthorized
        if (!response.successful) return T3TicketResult.Failure(response.error)
        val ticket = runCatching { JSONObject(response.body).stringOrNull("ticket") }.getOrNull()
            ?: return T3TicketResult.Failure("T3 Code returned no WebSocket ticket")
        T3TicketResult.Success(ticket)
    }.getOrElse { error -> T3TicketResult.Failure(error.message ?: "Could not reach T3 Code") }

    // OkHttp websockets take an http(s) URL; HttpUrl rejects the ws scheme outright.
    fun webSocketUrl(endpoint: T3Endpoint, ticket: String): HttpUrl =
        baseUrl(endpoint.host, endpoint.port).newBuilder()
            .addPathSegment("ws")
            .addQueryParameter("wsTicket", ticket)
            .build()

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun execute(request: Request): HttpResult {
        val call = client.newCall(request)
        activeCall = call
        return try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                HttpResult(
                    code = response.code,
                    body = body,
                    error = if (body.isBlank()) "HTTP ${response.code}" else serverError(body),
                )
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private data class HttpResult(val code: Int, val body: String, val error: String) {
        val successful: Boolean get() = code in 200..299
    }

    companion object {
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val REQUIRED_SCOPES =
            "orchestration:read orchestration:operate terminal:operate review:write relay:read"

        fun baseUrl(host: String, port: Int): HttpUrl = HttpUrl.Builder()
            .scheme("http")
            .host(normalizeHost(host))
            .port(port)
            .build()

        fun normalizeHost(value: String): String {
            var host = value.trim()
            host = host.removePrefix("http://").removePrefix("https://")
            host = host.substringBefore('/').trim()
            if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
            require(host.isNotEmpty()) { "Enter the T3 Code host" }
            return host
        }

        fun serverError(body: String): String {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.trim()
            return json.stringOrNull("error_description")
                ?: json.stringOrNull("message")
                ?: when (val error = json.opt("error")) {
                    is String -> error
                    is JSONObject -> error.stringOrNull("message") ?: error.toString()
                    else -> body.trim()
                }
        }
    }
}
