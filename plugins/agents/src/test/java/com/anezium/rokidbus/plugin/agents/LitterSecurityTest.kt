package com.anezium.rokidbus.plugin.agents

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LitterSecurityTest {
    @Test fun `credentials require TLS loopback or explicit network opt in`() {
        assertNull(LitterEndpoint("Computer", "wss://computer.example/ws", "secret").validate())
        assertNull(LitterEndpoint("Tunnel", "ws://127.0.0.1:8390", "secret").validate())
        assertNull(LitterEndpoint("Tunnel", "ws://[::1]:8390").validate())
        assertNotNull(LitterEndpoint("LAN", "ws://192.168.1.10:8390", "secret").validate())
        assertNull(LitterEndpoint("LAN", "ws://192.168.1.10:8390", "secret", true).validate())
        assertNotNull(LitterEndpoint("Bad", "wss://user:secret@server.test/").validate())
        assertNotNull(LitterEndpoint("Bad", "wss://server.test/?token=secret").validate())
        assertNotNull(LitterEndpoint("Bad", "wss://server.test/#secret").validate())
        assertNotNull(LitterEndpoint("Bad", "https://server.test/").validate())
        assertNotNull(LitterEndpoint("Bad", "wss://server.test/", "token\r\nHost: attacker").validate())
        assertEquals("LitterEndpoint(redacted)", LitterEndpoint("Private", "wss://private.test", "secret").toString())
    }

    @Test fun `approval belongs to exact connection session and turn and can be answered once`() {
        val approvals = LitterApprovals { 1_000 }
        val pending = approvals.offer(request(), 7, "turn-a", null)!!
        val id = pending.display.requestId
        assertNull(approvals.answer(id, "other", 7, "turn-a", true))
        assertNull(approvals.answer(id, "thread-a", 8, "turn-a", true))
        assertNull(approvals.answer(id, "thread-a", 7, "turn-b", true))
        assertNotNull(approvals.answer(id, "thread-a", 7, "turn-a", true))
        assertNull(approvals.answer(id, "thread-a", 7, "turn-a", true))
        assertNull(approvals.offer(request(), 7, "turn-a", null))
    }

    @Test fun `stale malformed and incomplete requests never become grantable`() {
        var now = 1_000L
        val approvals = LitterApprovals { now }
        assertNull(approvals.offer(request(), 1, "different-turn", null))
        assertNull(approvals.offer(request().apply { getJSONObject("params").remove("itemId") }, 1, "turn-a", null))
        val pending = approvals.offer(request(), 1, "turn-a", null)!!
        now += LitterApprovals.TTL_MS
        assertNull(approvals.answer(pending.display.requestId, "thread-a", 1, "turn-a", true))
        assertEquals(listOf(pending), approvals.expired())
        val noPreview = request("other").apply { getJSONObject("params").remove("command") }
        val blocked = approvals.offer(noPreview, 1, "turn-a", null)!!
        assertFalse(blocked.canAllow)
        assertNull(approvals.answer(blocked.display.requestId, "thread-a", 1, "turn-a", true))
        assertNotNull(approvals.answer(blocked.display.requestId, "thread-a", 1, "turn-a", false))
    }

    @Test fun `resolution is scoped and disconnect removes every pending request`() {
        val approvals = LitterApprovals { 0 }
        val pending = approvals.offer(request(), 1, "turn-a", null)!!
        assertTrue(approvals.resolve("request-a", "different-thread").isEmpty())
        assertTrue(approvals.finishItem("thread-a", "turn-a", "different-item").isEmpty())
        assertEquals(listOf(pending), approvals.finishItem("thread-a", "turn-a", "item-a"))
        val next = approvals.offer(request("next"), 1, "turn-a", null)!!
        approvals.clear()
        assertNull(approvals.answer(next.display.requestId, "thread-a", 1, "turn-a", true))
    }

    @Test fun `numeric and string request ids are distinct`() {
        assertNotEquals(rpcIdentity(1), rpcIdentity("1"))
        assertNull(rpcIdentity(1.5))
        assertNull(rpcIdentity(JSONObject.NULL))
    }

    private fun request(id: String = "request-a") = JSONObject().put("id", id)
        .put("method", LitterApprovals.COMMAND).put("params", JSONObject()
            .put("threadId", "thread-a").put("turnId", "turn-a").put("itemId", "item-a")
            .put("command", "git status --short"))
}
