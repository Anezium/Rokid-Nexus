package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SurfaceEpochContract
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground card-surface metadata: sequence, occupancy epoch, and hide/release.
 *
 * The epoch instance is injected so [BusHubService] and [PhonePluginRegistry]
 * keep sharing a single wall-clock-seeded counter.
 */
internal class PhoneSurfaceRouter(
    private val sink: PhoneHudRouteSink,
    private val surfaceEpoch: ForegroundSurfaceEpoch,
    private val onPluginSelfHid: (String) -> Unit,
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
            AtomicLong(System.currentTimeMillis())
        }.incrementAndGet()
        val pluginSurfaces = surfaceIds.computeIfAbsent(pluginId) {
            ConcurrentHashMap.newKeySet()
        }
        if (envelope.path == BusPaths.SURFACE_HIDE) {
            if (closeOnHide) release(pluginId, wireSurfaceId)
        } else {
            pluginSurfaces += wireSurfaceId
        }
        payload.put("seq", sequence)
        if (envelope.path == BusPaths.SURFACE_SHOW || envelope.path == BusPaths.SURFACE_UPDATE) {
            payload.put(SurfaceEpochContract.FIELD, surfaceEpoch.assign(pluginId))
        }
        return envelope.copy(payload = payload)
    }

    fun hidePlugin(pluginId: String): List<String> {
        surfaceEpoch.release(pluginId)
        val ids = surfaceIds.remove(pluginId).orEmpty().toList()
        ids.forEach { surfaceId -> sendHide(pluginId, surfaceId) }
        return ids
    }

    fun sendHide(pluginId: String, surfaceId: String) {
        val sequence = surfaceSeq.computeIfAbsent(surfaceId) {
            AtomicLong(System.currentTimeMillis())
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

    fun release(pluginId: String, wireSurfaceId: String) {
        val pluginSurfaces = surfaceIds[pluginId] ?: return
        pluginSurfaces.remove(wireSurfaceId)
        if (pluginSurfaces.isNotEmpty()) return
        surfaceIds.remove(pluginId, pluginSurfaces)
        surfaceEpoch.release(pluginId)
        onPluginSelfHid(pluginId)
    }
}
