package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject

/** Callback correlation belongs to the authenticated sender, never the glasses payload. */
internal fun noticeOwnerReply(
    envelope: BusEnvelope,
    owner: PhoneNoticeActionResult.Owner,
): BusEnvelope = envelope.copy(
    payload = JSONObject(envelope.payload.toString()).apply {
        put("pluginId", owner.ownerPluginId)
        remove(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)
        owner.clientToken?.let { put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, it) }
    },
)
