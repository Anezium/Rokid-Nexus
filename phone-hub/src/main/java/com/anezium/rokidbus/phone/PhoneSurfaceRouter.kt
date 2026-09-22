package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns foreground surface sequence stamps and the set to hide when a plugin closes. */
internal class PhoneSurfaceRouter(
    private val sink: PhoneHudRouteSink,
    private val onPluginSelfHid: (pluginId: String, detach: Boolean) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val surfaceSeq = ConcurrentHashMap<String, AtomicLong>()
    private val surfaceIds = ConcurrentHashMap<String, MutableSet<String>>()

    fun withMetadata(
        envelope: BusEnvelope,
        pluginId: String,
        closeOnHide: Boolean,
    ): BusEnvelope {
        val payload = JSONObject(envelope.payload.toString())
        val wireSurfaceId = payload.getString("surfaceId")
        val sequence = surfaceSeq.computeIfAbsent(wireSurfaceId) {
            AtomicLong(nowMs())
        }.incrementAndGet()
        val pluginSurfaces = surfaceIds.computeIfAbsent(pluginId) {
            ConcurrentHashMap.newKeySet()
        }
        if (envelope.path == BusPaths.SURFACE_HIDE) {
            if (closeOnHide) {
                release(
                    pluginId = pluginId,
                    wireSurfaceId = wireSurfaceId,
                    detach = payload.optBoolean("detach", false),
                )
            }
        } else {
            pluginSurfaces += wireSurfaceId
        }
        return envelope.copy(payload = payload.put("seq", sequence))
    }

    fun hidePlugin(pluginId: String): List<String> {
        val ids = surfaceIds.remove(pluginId).orEmpty().toList()
        ids.forEach { surfaceId -> sendHide(pluginId, surfaceId) }
        return ids
    }

    fun sendHide(pluginId: String, surfaceId: String) {
        val sequence = surfaceSeq.computeIfAbsent(surfaceId) {
            AtomicLong(nowMs())
        }.incrementAndGet()
        sink.sendRemote(
            BusEnvelope(
                path = BusPaths.SURFACE_HIDE,
                payload = JSONObject()
                    .put("surfaceId", surfaceId)
                    .put("ownerPluginId", pluginId)
                    .put("seq", sequence),
            ),
        )
    }

    fun forget(pluginId: String, wireSurfaceId: String) {
        val pluginSurfaces = surfaceIds[pluginId] ?: return
        pluginSurfaces.remove(wireSurfaceId)
        if (pluginSurfaces.isEmpty()) surfaceIds.remove(pluginId, pluginSurfaces)
    }

    fun release(pluginId: String, wireSurfaceId: String, detach: Boolean = false) {
        val pluginSurfaces = surfaceIds[pluginId] ?: return
        pluginSurfaces.remove(wireSurfaceId)
        if (pluginSurfaces.isNotEmpty()) return
        surfaceIds.remove(pluginId, pluginSurfaces)
        onPluginSelfHid(pluginId, detach)
    }
}
