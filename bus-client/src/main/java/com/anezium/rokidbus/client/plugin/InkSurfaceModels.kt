package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.UUID

data class NexusInkProblem(
    val code: String,
    val message: String,
    val severity: String = "error",
    val line: Int? = null,
    val column: Int? = null,
    val feature: String? = null,
) {
    val sdkResult: NexusSdkResult
        get() = when (code) {
            "SURFACE_BUSY" -> NexusSdkResult.SURFACE_BUSY
            "DISPLAY_MUTED" -> NexusSdkResult.DISPLAY_MUTED
            "CAPABILITY_NOT_GRANTED" -> NexusSdkResult.CAPABILITY_NOT_GRANTED
            "CAPABILITY_NOT_AVAILABLE" -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            else -> NexusSdkResult.INVALID_PAYLOAD
        }

    internal companion object {
        fun fromJson(payload: JSONObject): NexusInkProblem? {
            val code = payload.optString("code")
            val message = payload.optString("message")
            if (code.isBlank() || message.isBlank()) return null
            return NexusInkProblem(
                code = code,
                message = message,
                severity = payload.optString("severity", "error"),
                line = payload.optInt("line", -1).takeIf { it > 0 },
                column = payload.optInt("col", -1).takeIf { it > 0 },
                feature = payload.optString("feature").takeIf(String::isNotBlank),
            )
        }
    }
}

enum class NexusInkCloseReason(val wireValue: String) {
    USER(InkSurfaceContract.CLOSE_USER),
    PLUGIN(InkSurfaceContract.CLOSE_PLUGIN),
    REPLACED(InkSurfaceContract.CLOSE_REPLACED),
    LINK_LOST(InkSurfaceContract.CLOSE_LINK_LOST),
    RENDERER_ERROR(InkSurfaceContract.CLOSE_RENDERER_ERROR),
    ;

    internal companion object {
        fun fromWire(value: String): NexusInkCloseReason? = entries.firstOrNull { it.wireValue == value }
    }
}

class NexusInkSurfaceSession internal constructor(
    private val client: NexusPluginClient,
    val localSurfaceId: String,
) {
    init {
        require(LOCAL_SURFACE_ID.matches(localSurfaceId))
    }

    fun show(
        page: String,
        data: JSONObject? = null,
        handlesBack: Boolean = false,
    ): NexusSdkResult {
        preflight(requireAvailable = true)?.let { return it }
        if (page.toByteArray(Charsets.UTF_8).size > InkEngine.MAX_PAGE_BYTES) {
            return invalid(
                NexusInkProblem(
                    InkProblemCodes.BUDGET_SIZE,
                    "Input page exceeds the ${InkEngine.MAX_PAGE_BYTES / 1024} KiB page budget",
                    feature = "page",
                ),
            )
        }
        if (data != null && data.toString().toByteArray(Charsets.UTF_8).size > InkEngine.MAX_DATA_BYTES) {
            return invalid(
                NexusInkProblem(
                    InkProblemCodes.BUDGET_SIZE,
                    "Input data exceeds the ${InkEngine.MAX_DATA_BYTES / 1024} KiB data budget",
                    feature = "data",
                ),
            )
        }
        val payload = JSONObject()
            .put("surfaceId", localSurfaceId)
            .put("page", page)
            .put("handlesBack", handlesBack)
        data?.let { payload.put("data", JSONObject(it.toString())) }
        return send(BusPaths.INK_SHOW, payload)
    }

    fun update(data: JSONObject): NexusSdkResult {
        preflight(requireAvailable = true)?.let { return it }
        if (data.toString().toByteArray(Charsets.UTF_8).size > InkEngine.MAX_DATA_BYTES) {
            return invalid(
                NexusInkProblem(
                    InkProblemCodes.BUDGET_SIZE,
                    "Input patch exceeds the ${InkEngine.MAX_DATA_BYTES / 1024} KiB data budget",
                    feature = "data",
                ),
            )
        }
        return send(
            BusPaths.INK_UPDATE,
            JSONObject()
                .put("surfaceId", localSurfaceId)
                .put("data", JSONObject(data.toString())),
        )
    }

    fun hide(): NexusSdkResult {
        preflight(requireAvailable = false)?.let { return it }
        return send(BusPaths.INK_HIDE, JSONObject().put("surfaceId", localSurfaceId))
    }

    private fun preflight(requireAvailable: Boolean): NexusSdkResult? = when {
        !client.isApproved -> NexusSdkResult.NOT_REGISTERED
        !client.hasCapability(PluginCapability.INK_SURFACE) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        requireAvailable && !client.supportsInkSurface -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    private fun invalid(problem: NexusInkProblem): NexusSdkResult {
        client.reportInkError(localSurfaceId, listOf(problem))
        return NexusSdkResult.INVALID_PAYLOAD
    }

    private fun send(path: String, payload: JSONObject): NexusSdkResult =
        if (client.send(path, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }

    private companion object {
        val LOCAL_SURFACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

fun NexusPluginClient.inkSurfaceSession(localSurfaceId: String): NexusInkSurfaceSession =
    NexusInkSurfaceSession(this, localSurfaceId)
