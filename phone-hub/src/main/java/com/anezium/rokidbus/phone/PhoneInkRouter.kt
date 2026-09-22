package com.anezium.rokidbus.phone

import android.os.IBinder
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Ink Surface routing: local show/update/hide, glasses events, and link-loss close.
 *
 * Compile/session state stays in [PhoneInkSurfaceCoordinator]. Wire metadata and
 * occupancy release go through [PhoneSurfaceRouter], shared with ordinary surfaces.
 */
internal class PhoneInkRouter(
    private val sink: PhoneHudRouteSink,
    private val coordinator: PhoneInkSurfaceCoordinator,
    private val surfaceRouter: PhoneSurfaceRouter,
) {
    fun matches(path: String): Boolean =
        path == BusPaths.INK_SHOW ||
            path == BusPaths.INK_UPDATE ||
            path == BusPaths.INK_HIDE

    fun handleLocal(envelope: BusEnvelope, sender: PhoneHudRouteSender) {
        val owner = ownerFrom(envelope)
        if (owner == null || sender.pluginId != owner.pluginId) {
            sink.recordLocalRoute(
                envelope,
                sender.uid,
                sender.pluginId,
                PluginBusJournal.Verdict.REJECTED,
                "INVALID_SURFACE_ID",
            )
            sink.deliverError(sender.replyBinder, envelope.id, "INVALID_SURFACE_ID")
            return
        }
        if (envelope.binary != null) {
            reject(
                envelope,
                sender,
                owner,
                InkProblem(InkProblemCodes.WIRE_TYPE, "Ink commands do not accept binary data"),
            )
            return
        }
        if (envelope.path != BusPaths.INK_HIDE &&
            sink.capabilities() and BusCapabilityBits.INK_SURFACE == 0
        ) {
            reject(
                envelope,
                sender,
                owner,
                InkProblem(
                    "CAPABILITY_NOT_AVAILABLE",
                    "Ink Surface requires compatible glasses and the SPP data plane",
                ),
            )
            return
        }

        val callback: (PhoneInkCommandResult) -> Unit = { result ->
            when (result) {
                is PhoneInkCommandResult.Outgoing ->
                    publish(result, envelope.id, sender.replyBinder)
                is PhoneInkCommandResult.Noop -> Unit
                is PhoneInkCommandResult.Error ->
                    deliverError(result.owner, envelope.id, sender.replyBinder, result.problems)
            }
        }
        val payload = JSONObject(envelope.payload.toString())
        when (envelope.path) {
            BusPaths.INK_SHOW -> {
                val page = payload.opt("page") as? String
                val rawData = payload.opt("data")
                val data = when (rawData) {
                    null, JSONObject.NULL -> null
                    is JSONObject -> rawData
                    else -> {
                        reject(
                            envelope,
                            sender,
                            owner,
                            InkProblem(InkProblemCodes.WIRE_TYPE, "Ink show data must be a JSON object"),
                        )
                        return
                    }
                }
                if (page == null) {
                    reject(
                        envelope,
                        sender,
                        owner,
                        InkProblem(InkProblemCodes.WIRE_TYPE, "Ink show page must be a string"),
                    )
                    return
                }
                sink.recordLocalRoute(
                    envelope,
                    sender.uid,
                    sender.pluginId,
                    PluginBusJournal.Verdict.OK,
                )
                coordinator.show(
                    owner,
                    page,
                    data,
                    payload.optBoolean("handlesBack", false),
                    callback,
                )
            }
            BusPaths.INK_UPDATE -> {
                val data = payload.optJSONObject("data")
                if (data == null) {
                    reject(
                        envelope,
                        sender,
                        owner,
                        InkProblem(InkProblemCodes.WIRE_TYPE, "Ink update data must be a JSON object"),
                    )
                    return
                }
                sink.recordLocalRoute(
                    envelope,
                    sender.uid,
                    sender.pluginId,
                    PluginBusJournal.Verdict.OK,
                )
                coordinator.update(owner, data, callback)
            }
            BusPaths.INK_HIDE -> {
                sink.recordLocalRoute(
                    envelope,
                    sender.uid,
                    sender.pluginId,
                    PluginBusJournal.Verdict.OK,
                )
                coordinator.hide(owner, callback)
            }
        }
    }

    fun handleGlassesEvent(envelope: BusEnvelope) {
        if (envelope.binary != null) {
            sink.recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "INK_EVENT_BINARY")
            return
        }
        val payload = JSONObject(envelope.payload.toString())
        val surfaceId = payload.optString("surfaceId")
        val type = payload.optString("type")
        if (surfaceId.isBlank() || type !in setOf(
                InkSurfaceContract.EVENT_READY,
                InkSurfaceContract.EVENT_ACTION,
                InkSurfaceContract.EVENT_CLOSED,
                InkSurfaceContract.EVENT_RESYNC,
            )
        ) {
            sink.recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "INVALID_INK_EVENT")
            return
        }
        if (type == InkSurfaceContract.EVENT_CLOSED &&
            !InkSurfaceContract.isCloseReason(payload.optString("reason"))
        ) {
            sink.recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "INVALID_INK_CLOSE_REASON")
            return
        }
        if (type == InkSurfaceContract.EVENT_ACTION && payload.optString("actionId").isBlank()) {
            sink.recordRemoteRoute(envelope, PluginBusJournal.Verdict.REJECTED, "INVALID_INK_ACTION")
            return
        }
        sink.recordRemoteRoute(envelope, PluginBusJournal.Verdict.OK)
        coordinator.onRemoteEvent(surfaceId, type) { result ->
            when (result) {
                is PhoneInkRemoteEventResult.Forward -> when (type) {
                    InkSurfaceContract.EVENT_READY -> deliverEvent(
                        result.owner,
                        type,
                        envelope.id,
                    )
                    InkSurfaceContract.EVENT_ACTION -> deliverEvent(
                        owner = result.owner,
                        type = type,
                        id = envelope.id,
                        extra = JSONObject()
                            .put("actionId", payload.optString("actionId"))
                            .put("dataset", payload.optJSONObject("dataset") ?: JSONObject()),
                    )
                }
                is PhoneInkRemoteEventResult.Closed -> {
                    deliverEvent(
                        owner = result.owner,
                        type = type,
                        id = envelope.id,
                        extra = JSONObject().put("reason", payload.optString("reason")),
                    )
                    surfaceRouter.release(result.owner.pluginId, result.owner.wireSurfaceId)
                }
                is PhoneInkRemoteEventResult.Resync -> publish(
                    result.outgoing,
                    UUID.randomUUID().toString(),
                    replyBinder = null,
                )
                PhoneInkRemoteEventResult.Ignore -> Unit
            }
        }
    }

    fun clearOwner(
        pluginId: String,
        onOwners: (List<PhoneInkSurfaceOwner>) -> Unit = {},
    ) {
        coordinator.clearOwner(pluginId, onOwners)
    }

    fun clearForLinkLoss() {
        coordinator.clearForLinkLoss { owners ->
            owners.forEach { owner ->
                deliverEvent(
                    owner = owner,
                    type = InkSurfaceContract.EVENT_CLOSED,
                    id = UUID.randomUUID().toString(),
                    extra = JSONObject().put("reason", InkSurfaceContract.CLOSE_LINK_LOST),
                )
                surfaceRouter.release(owner.pluginId, owner.wireSurfaceId)
            }
        }
    }

    fun closeOwnerForLinkLoss(pluginId: String) {
        coordinator.clearOwner(pluginId) { owners ->
            owners.forEach { owner ->
                deliverEvent(
                    owner = owner,
                    type = InkSurfaceContract.EVENT_CLOSED,
                    id = UUID.randomUUID().toString(),
                    extra = JSONObject().put("reason", InkSurfaceContract.CLOSE_LINK_LOST),
                )
                surfaceRouter.release(owner.pluginId, owner.wireSurfaceId)
            }
        }
    }

    fun ownerFrom(envelope: BusEnvelope): PhoneInkSurfaceOwner? {
        val pluginId = envelope.payload.optString("ownerPluginId")
        val localSurfaceId = envelope.payload.optString("localSurfaceId")
        val wireSurfaceId = envelope.payload.optString("surfaceId")
        if (pluginId.isBlank() || localSurfaceId.isBlank() || wireSurfaceId != "$pluginId:$localSurfaceId") {
            return null
        }
        return PhoneInkSurfaceOwner(pluginId, localSurfaceId, wireSurfaceId)
    }

    fun deliverProblems(
        owner: PhoneInkSurfaceOwner?,
        id: String,
        targetBinder: IBinder?,
        problems: List<InkProblem>,
    ) = deliverError(owner, id, targetBinder, problems)

    fun close() = coordinator.close()

    private fun reject(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
        owner: PhoneInkSurfaceOwner,
        problem: InkProblem,
    ) {
        sink.recordLocalRoute(
            envelope,
            sender.uid,
            sender.pluginId,
            PluginBusJournal.Verdict.REJECTED,
            problem.code,
        )
        deliverError(owner, envelope.id, sender.replyBinder, listOf(problem))
    }

    private fun publish(
        result: PhoneInkCommandResult.Outgoing,
        envelopeId: String,
        replyBinder: IBinder?,
    ) {
        val envelope = surfaceRouter.withMetadata(
            BusEnvelope(path = result.path, id = envelopeId, payload = result.payload),
            result.owner.pluginId,
            closeOnHide = false,
        )
        val error = sink.sendRemote(envelope)
        if (error != null) {
            deliverError(
                result.owner,
                envelopeId,
                replyBinder,
                listOf(
                    InkProblem(
                        "CAPABILITY_NOT_AVAILABLE",
                        "Ink Surface could not reach the glasses ($error)",
                    ),
                ),
            )
            result.replaced.forEach { replaced ->
                deliverEvent(
                    owner = replaced,
                    type = InkSurfaceContract.EVENT_CLOSED,
                    id = UUID.randomUUID().toString(),
                    extra = JSONObject().put("reason", InkSurfaceContract.CLOSE_LINK_LOST),
                )
                surfaceRouter.release(replaced.pluginId, replaced.wireSurfaceId)
            }
            closeOwnerForLinkLoss(result.owner.pluginId)
            return
        }
        result.replaced.forEach { replaced ->
            deliverEvent(
                owner = replaced,
                type = InkSurfaceContract.EVENT_CLOSED,
                id = UUID.randomUUID().toString(),
                extra = JSONObject().put("reason", InkSurfaceContract.CLOSE_REPLACED),
            )
            surfaceRouter.release(replaced.pluginId, replaced.wireSurfaceId)
        }
    }

    private fun deliverError(
        owner: PhoneInkSurfaceOwner?,
        id: String,
        targetBinder: IBinder?,
        problems: List<InkProblem>,
    ) {
        if (owner == null) {
            sink.deliverError(targetBinder, id, problems.firstOrNull()?.code ?: "INVALID_PAYLOAD")
            return
        }
        deliverEvent(
            owner = owner,
            type = InkSurfaceContract.EVENT_ERROR,
            id = id,
            extra = JSONObject().put(
                "problems",
                JSONArray().also { array -> problems.forEach { array.put(it.toJsonObject()) } },
            ),
            targetBinder = targetBinder,
        )
    }

    private fun deliverEvent(
        owner: PhoneInkSurfaceOwner,
        type: String,
        id: String,
        extra: JSONObject = JSONObject(),
        targetBinder: IBinder? = null,
    ) {
        val payload = JSONObject(extra.toString())
            .put("pluginId", owner.pluginId)
            .put("surfaceId", owner.localSurfaceId)
            .put("type", type)
        sink.deliverLocal(
            BusEnvelope(path = BusPaths.INK_EVENT, id = id, payload = payload),
            targetBinder = targetBinder,
        )
    }
}
