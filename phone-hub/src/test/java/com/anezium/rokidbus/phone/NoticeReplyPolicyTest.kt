package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoticeReplyPolicyTest {
    @Test
    fun `reply destination and client token come from the accepted owner`() {
        val incoming = BusEnvelope(
            path = BusPaths.NOTICE_ACTION,
            payload = JSONObject().put("noticeId", "relay:notice").put("id", "reply")
                .put("pluginId", "other").put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, "forged"),
        )
        val reply = noticeOwnerReply(incoming, PhoneNoticeActionResult.Owner("relay", "question-a"))
        assertEquals("relay", reply.payload.getString("pluginId"))
        assertEquals("question-a", NoticeSurfaceContract.clientToken(reply.payload))
        assertEquals("reply", reply.payload.getString("id"))
        assertEquals("forged", NoticeSurfaceContract.clientToken(incoming.payload))
    }

    @Test
    fun `a legacy sender never inherits a client token claimed by glasses`() {
        val incoming = BusEnvelope(
            path = BusPaths.NOTICE_INPUT,
            payload = JSONObject().put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, "forged"),
        )
        val reply = noticeOwnerReply(incoming, PhoneNoticeActionResult.Owner("relay"))
        assertFalse(reply.payload.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
    }
}
