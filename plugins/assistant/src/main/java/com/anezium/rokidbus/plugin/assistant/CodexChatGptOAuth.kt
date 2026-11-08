package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class CodexChatGptOAuthTokenBundle(
    val accessToken: String,
    val idToken: String,
    val refreshToken: String?,
    val accountId: String,
    val planType: String?,
    val email: String?,
) {
    fun displayLabel(): String =
        email?.takeIf(String::isNotBlank)
            ?: planType?.takeIf(String::isNotBlank)?.let { "ChatGPT $it" }
            ?: accountId
}

class CodexChatGptOAuthException(message: String) : Exception(message)

object CodexChatGptOAuth {
    const val authIssuer = "https://auth.openai.com"
    const val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val callbackScheme = "http"
    private const val callbackHost = "localhost"
    private const val callbackBindHost = "127.0.0.1"
    const val callbackPort = 1455
    const val callbackPath = "/auth/callback"

    data class AuthAttempt(
        val state: String,
        val codeVerifier: String,
        val redirectUri: String,
        val authorizeUrl: String,
    )

    fun createLoginAttempt(): AuthAttempt {
        val state = randomUrlSafe(32)
        val codeVerifier = randomUrlSafe(32)
        val codeChallenge = codeChallenge(codeVerifier)
        val redirectUri = "$callbackScheme://$callbackHost:$callbackPort$callbackPath"
        val authorizeUrl = "$authIssuer/oauth/authorize?" + formBody(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to "openid profile email offline_access",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
        )
        return AuthAttempt(
            state = state,
            codeVerifier = codeVerifier,
            redirectUri = redirectUri,
            authorizeUrl = authorizeUrl,
        )
    }

    fun isCallbackUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase()
        return uri.scheme == callbackScheme &&
            (host == callbackHost || host == callbackBindHost) &&
            uri.path == callbackPath
    }

    suspend fun completeAuthorization(
        context: Context,
        callbackUri: Uri,
        attempt: AuthAttempt,
    ): CodexChatGptOAuthTokenBundle {
        validateCallbackUri(callbackUri)
        val error = callbackUri.getQueryParameter("error")?.trim()
        if (!error.isNullOrEmpty()) {
            val description = callbackUri.getQueryParameter("error_description")?.trim()
            throw CodexChatGptOAuthException(description?.takeIf(String::isNotEmpty) ?: error)
        }
        if (callbackUri.getQueryParameter("state") != attempt.state) {
            throw CodexChatGptOAuthException("ChatGPT login state did not match.")
        }
        val code = callbackUri.getQueryParameter("code")?.trim()
        if (code.isNullOrEmpty()) {
            throw CodexChatGptOAuthException("ChatGPT login did not return an authorization code.")
        }

        val body = formBody(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to attempt.redirectUri,
            "client_id" to clientId,
            "code_verifier" to attempt.codeVerifier,
        )
        val tokens = exchangeToken(body)
        val apiKeyResult = runCatching { obtainApiKey(tokens.idToken) }
        val store = CodexAuthStore(context)
        store.saveOAuth(
            tokens = tokens,
            apiKey = apiKeyResult.getOrNull(),
            apiKeyExchangeError = apiKeyResult.exceptionOrNull()?.message,
        )
        store.markChatGptSignInCompleted(attempt.state)
        return tokens
    }

    suspend fun refreshStoredTokens(context: Context): CodexChatGptOAuthTokenBundle {
        val store = CodexAuthStore(context)
        val stored = store.oauthTokens()
            ?: throw CodexChatGptOAuthException("No stored ChatGPT login is available to refresh.")
        val refreshToken = stored.refreshToken?.takeIf(String::isNotBlank)
            ?: throw CodexChatGptOAuthException("No ChatGPT refresh token is available.")
        val refreshed = exchangeToken(
            formBody(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            ),
        )
        val tokensToStore = if (refreshed.refreshToken.isNullOrBlank()) {
            refreshed.copy(refreshToken = stored.refreshToken)
        } else {
            refreshed
        }
        val apiKeyResult = runCatching { obtainApiKey(tokensToStore.idToken) }
        store.saveOAuth(
            tokens = tokensToStore,
            apiKey = apiKeyResult.getOrNull(),
            apiKeyExchangeError = apiKeyResult.exceptionOrNull()?.message,
        )
        return tokensToStore
    }

    private suspend fun exchangeToken(body: String): CodexChatGptOAuthTokenBundle =
        withContext(Dispatchers.IO) {
            var networkFailure: IOException? = null
            repeat(3) { attemptIndex ->
                try {
                    return@withContext exchangeTokenOnce(body)
                } catch (error: UnknownHostException) {
                    networkFailure = error
                    if (attemptIndex == 2) {
                        throw CodexChatGptOAuthException(
                            "ChatGPT token exchange could not reach auth.openai.com. Check the phone connection.",
                        )
                    }
                    delay(500L * (attemptIndex + 1))
                } catch (error: IOException) {
                    networkFailure = error
                    if (attemptIndex == 2) throw error
                    delay(500L * (attemptIndex + 1))
                }
            }
            throw networkFailure ?: CodexChatGptOAuthException("ChatGPT token exchange failed.")
        }

    private fun exchangeTokenOnce(body: String): CodexChatGptOAuthTokenBundle {
        val payload = postForm("$authIssuer/oauth/token", body)
        val accessToken = payload.optString("access_token").trim()
        val idToken = payload.optString("id_token").trim()
        val refreshToken = payload.optString("refresh_token").trim().ifEmpty { null }
        if (accessToken.isEmpty() || idToken.isEmpty()) {
            throw CodexChatGptOAuthException(
                "ChatGPT token exchange failed: missing access_token or id_token.",
            )
        }

        val idClaims = decodeJwtClaims(idToken)
        val accessClaims = decodeJwtClaims(accessToken)
        val accountId = listOf(
            idClaims.optString("chatgpt_account_id"),
            accessClaims.optString("chatgpt_account_id"),
            idClaims.optString("organization_id"),
            accessClaims.optString("organization_id"),
        ).firstOrNull(String::isNotBlank)?.trim().orEmpty()
        if (accountId.isEmpty()) {
            throw CodexChatGptOAuthException(
                "ChatGPT login did not include an account identifier.",
            )
        }
        val planType = listOf(
            accessClaims.optString("chatgpt_plan_type"),
            idClaims.optString("chatgpt_plan_type"),
        ).firstOrNull(String::isNotBlank)?.trim()
        val email = listOf(
            idClaims.optString("email"),
            accessClaims.optString("email"),
        ).firstOrNull(String::isNotBlank)?.trim()

        return CodexChatGptOAuthTokenBundle(
            accessToken = accessToken,
            idToken = idToken,
            refreshToken = refreshToken,
            accountId = accountId,
            planType = planType,
            email = email,
        )
    }

    private suspend fun obtainApiKey(idToken: String): String = withContext(Dispatchers.IO) {
        val payload = postForm(
            "$authIssuer/oauth/token",
            formBody(
                "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "client_id" to clientId,
                "requested_token" to "openai-api-key",
                "subject_token" to idToken,
                "subject_token_type" to "urn:ietf:params:oauth:token-type:id_token",
            ),
        )
        payload.optString("access_token").trim().takeIf(String::isNotEmpty)
            ?: throw CodexChatGptOAuthException(
                "ChatGPT login did not return an OpenAI API token.",
            )
    }

    private fun postForm(endpoint: String, body: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (status !in 200..299) {
                throw CodexChatGptOAuthException(
                    "ChatGPT auth failed ($status): " +
                        responseText.redactProviderSecrets().take(300),
                )
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateCallbackUri(callbackUri: Uri) {
        if (!isCallbackUri(callbackUri)) {
            throw CodexChatGptOAuthException("ChatGPT login returned an invalid callback.")
        }
    }

    private fun decodeJwtClaims(jwt: String): JSONObject {
        val parts = jwt.split(".")
        if (parts.size < 2) return JSONObject()
        return try {
            val decoded = Base64.getUrlDecoder().decode(padBase64Url(parts[1]))
            val value = JSONObject(String(decoded, Charsets.UTF_8))
            value.optJSONObject("https://api.openai.com/auth") ?: value
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun padBase64Url(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }
}
