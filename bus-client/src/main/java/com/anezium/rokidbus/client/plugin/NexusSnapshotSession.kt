package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class NexusSnapshotError {
    BUSY,
    LINK_DOWN,
    CAPTURE_FAILED,
    TIMEOUT,
    CANCELLED,
    ERROR,
}

interface NexusSnapshotCallbacks {
    fun onSnapshotCaptured(jpeg: ByteArray)
    fun onSnapshotError(error: NexusSnapshotError)
}

/**
 * One camera-granted still capture. An open immersion is not required: a visible page action
 * may call [capture]. Return a live "Capturing…" page immediately and publish the image on
 * completion; never block the page action's deadline. Cover/close must discard that work.
 */
class NexusSnapshotSession internal constructor(
    private val client: NexusPluginClient,
    private val callbacks: NexusSnapshotCallbacks,
) {
    private val stateLock = Any()
    private var requestId: String? = null

    val isPending: Boolean
        get() = synchronized(stateLock) { requestId != null }

    fun capture(): NexusSdkResult = synchronized(stateLock) {
        if (!client.isApprovedForSnapshot()) return@synchronized NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.CAMERA)) {
            return@synchronized NexusSdkResult.CAPABILITY_NOT_GRANTED
        }
        if (requestId != null) return@synchronized NexusSdkResult.SENT
        if (!client.registerSnapshotSession(this)) {
            return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }

        val nextRequestId = UUID.randomUUID().toString()
        requestId = nextRequestId
        if (client.sendSnapshotRequest(this, nextRequestId)) {
            NexusSdkResult.SENT
        } else {
            clear()
            client.unregisterSnapshotSession(this)
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun cancel() {
        synchronized(stateLock) {
            if (requestId == null) {
                client.unregisterSnapshotSession(this)
                return
            }
            clear()
            client.unregisterSnapshotSession(this)
        }
    }

    internal fun onSnapshotResult(
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ) {
        synchronized(stateLock) {
            if (!matchesRequest(id, payload)) return
            if (payload.optString("mimeType") != "image/jpeg" || data.isEmpty()) {
                finishError(NexusSnapshotError.CAPTURE_FAILED)
                return
            }
            clear()
            client.unregisterSnapshotSession(this)
            callbacks.onSnapshotCaptured(data)
        }
    }

    internal fun onSnapshotError(id: String, payload: JSONObject) {
        synchronized(stateLock) {
            if (!matchesRequest(id, payload)) return
            finishError(payload.optString("code").toSnapshotError())
        }
    }

    internal fun terminate(error: NexusSnapshotError) {
        synchronized(stateLock) {
            if (requestId == null) {
                client.unregisterSnapshotSession(this)
                return
            }
            finishError(error)
        }
    }

    private fun matchesRequest(id: String, payload: JSONObject): Boolean {
        val pending = requestId ?: return false
        val payloadRequestId = payload.optString("requestId")
        return id == pending && (payloadRequestId.isBlank() || payloadRequestId == pending)
    }

    private fun finishError(error: NexusSnapshotError) {
        if (requestId == null) return
        clear()
        client.unregisterSnapshotSession(this)
        callbacks.onSnapshotError(error)
    }

    private fun clear() {
        requestId = null
    }
}

fun NexusPluginClient.snapshotSession(
    callbacks: NexusSnapshotCallbacks,
): NexusSnapshotSession = NexusSnapshotSession(this, callbacks)

private fun String.toSnapshotError(): NexusSnapshotError = when (trim().uppercase(Locale.US)) {
    "BUSY" -> NexusSnapshotError.BUSY
    "LINK_DOWN" -> NexusSnapshotError.LINK_DOWN
    "CAPTURE_FAILED" -> NexusSnapshotError.CAPTURE_FAILED
    "TIMEOUT" -> NexusSnapshotError.TIMEOUT
    else -> NexusSnapshotError.ERROR
}

internal const val NEXUS_SNAPSHOT_REQUEST_PATH = BusPaths.CAMERA_SNAPSHOT_REQUEST
internal const val NEXUS_SNAPSHOT_RESULT_PATH = BusPaths.CAMERA_SNAPSHOT_RESULT
internal const val NEXUS_SNAPSHOT_ERROR_PATH = BusPaths.CAMERA_SNAPSHOT_ERROR
