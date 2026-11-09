package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

internal class ModelCatalogRequest(
    val providerId: String,
    val scope: String,
    val url: String,
    val headers: Map<String, String>,
)

internal data class ModelCatalogSnapshot(val models: List<AvailableModel>, val updatedAtMs: Long)
internal data class ModelCatalogResponse(val status: Int, val body: String = "")

internal fun interface ModelCatalogTransport {
    suspend fun get(request: ModelCatalogRequest): ModelCatalogResponse
}

internal class HttpModelCatalogTransport : ModelCatalogTransport {
    override suspend fun get(request: ModelCatalogRequest): ModelCatalogResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) return@withContext ModelCatalogResponse(status)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (output.size() <= AvailableModels.MAX_BYTES) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= AvailableModels.MAX_BYTES) { "Model catalog is too large." }
            ModelCatalogResponse(status, bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

internal enum class ModelCatalogRefresh { CURRENT, UPDATED, UNAVAILABLE }

internal class ModelCatalogRepository(
    private val authStore: CodexAuthStore,
    private val transport: ModelCatalogTransport = HttpModelCatalogTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshChatGpt: (suspend () -> Unit)? = null,
) {
    private val locks = mutableMapOf<String, Mutex>()
    private val attempts = ConcurrentHashMap<String, Long>()

    suspend fun refresh(providerId: String, force: Boolean = false): ModelCatalogRefresh {
        val lock = synchronized(locks) { locks.getOrPut(providerId) { Mutex() } }
        return lock.withLock {
            var request = authStore.modelCatalogRequest(providerId) ?: return@withLock ModelCatalogRefresh.UNAVAILABLE
            val now = clock()
            val updated = authStore.cachedModelCatalog(providerId)?.updatedAtMs
            if (!force && updated != null && now - updated in 0 until REFRESH_INTERVAL_MS) {
                return@withLock ModelCatalogRefresh.CURRENT
            }
            val previous = attempts[request.scope]
            if (!force && previous != null && now - previous in 0 until RETRY_INTERVAL_MS) {
                return@withLock ModelCatalogRefresh.UNAVAILABLE
            }
            attempts[request.scope] = now
            try {
                var response = transport.get(request)
                if (response.status == 401 && providerId == CodexAuthStore.CHATGPT_PROVIDER_ID && refreshChatGpt != null) {
                    refreshChatGpt.invoke()
                    val renewed = authStore.modelCatalogRequest(providerId) ?: return@withLock ModelCatalogRefresh.UNAVAILABLE
                    if (renewed.scope != request.scope) return@withLock ModelCatalogRefresh.UNAVAILABLE
                    request = renewed
                    response = transport.get(request)
                }
                if (response.status !in 200..299) return@withLock ModelCatalogRefresh.UNAVAILABLE
                val models = AvailableModels.parse(response.body, providerId == CodexAuthStore.CHATGPT_PROVIDER_ID)
                if (models.isEmpty()) return@withLock ModelCatalogRefresh.UNAVAILABLE
                if (!authStore.saveModelCatalog(request, ModelCatalogSnapshot(models, now))) {
                    return@withLock ModelCatalogRefresh.UNAVAILABLE
                }
                ModelCatalogRefresh.UPDATED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ModelCatalogRefresh.UNAVAILABLE
            }
        }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L
        const val RETRY_INTERVAL_MS = 5 * 60 * 1_000L
    }
}
