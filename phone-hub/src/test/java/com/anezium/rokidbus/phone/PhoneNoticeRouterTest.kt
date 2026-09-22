package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeRouterTest {
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.NOTICE_SURFACE,
        linkUp = true,
    )
    private val router = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { 0L }, initialSequence = 0L),
        sink = sink,
    )

    @Test
    fun `show journals OK and sends remote when the link is up`() {
        router.handleLocal(showEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(BusPaths.NOTICE_SHOW, route.path)
        assertEquals("relay", route.pluginId)
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals(null, route.reason)
        assertEquals(BusPaths.NOTICE_SHOW, sink.remote.single().path)
    }

    @Test
    fun `show is rejected when the glasses link is down`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals(NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `update with no session journals OK ignored`() {
        router.handleLocal(updateEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals("NOTICE_UPDATE_IGNORED", route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `glasses input without a current notice is dropped locally`() {
        router.handleGlassesInput(
            BusEnvelope(
                path = BusPaths.NOTICE_INPUT,
                payload = JSONObject().put("noticeId", "relay:notice"),
            ),
        )

        assertTrue(sink.remoteRoutes.isEmpty())
        assertTrue(sink.local.isEmpty())
        assertTrue(sink.logs.any { it.contains("not_current") })
    }

    @Test
    fun `replacement rejects the old answer and delivers the current answer only once`() {
        val old = showQuestion("question-a")
        val current = showQuestion("question-b")
        sink.local.clear()

        router.handleGlassesAction(answer(old))
        assertTrue(sink.local.isEmpty())
        router.handleGlassesAction(answer(current))
        router.handleGlassesAction(answer(current))

        val reply = sink.local.single()
        assertEquals("relay", reply.payload.getString("pluginId"))
        assertEquals("question-b", NoticeSurfaceContract.clientToken(reply.payload))
        assertEquals(BusPaths.NOTICE_ACTION, reply.path)
    }

    @Test
    fun `replacement close carries the previous owner question context`() {
        val previous = showQuestion("question-a")
        showQuestion("question-b", "assistant")

        val closed = sink.local.single()
        assertEquals(BusPaths.NOTICE_CLOSED, closed.path)
        assertEquals("replaced", closed.payload.getString("reason"))
        assertEquals("question-a", NoticeSurfaceContract.clientToken(closed.payload))
        assertEquals(
            NoticeSurfaceContract.interactionIdentity(previous.payload),
            NoticeSurfaceContract.interactionIdentity(closed.payload),
        )
    }

    @Test
    fun `stale and malformed glasses closes leave the current notice answerable`() {
        val old = showQuestion("question-a")
        val current = showQuestion("question-b")
        sink.local.clear()
        router.handleGlassesClosed(
            answer(old).copy(path = BusPaths.NOTICE_CLOSED).also {
                it.payload.put("reason", "user")
            },
        )
        router.handleGlassesClosed(
            answer(current).copy(path = BusPaths.NOTICE_CLOSED).also {
                it.payload.put("reason", "unknown")
            },
        )
        assertTrue(sink.local.isEmpty())

        router.handleGlassesAction(answer(current))
        assertEquals(BusPaths.NOTICE_ACTION, sink.local.single().path)
    }

    @Test
    fun `valid glasses close ends the interaction with its canonical client token`() {
        val shown = showQuestion("question-a")
        router.handleGlassesClosed(
            answer(shown).copy(path = BusPaths.NOTICE_CLOSED).also {
                it.payload.put("reason", "user")
            },
        )
        router.handleGlassesAction(answer(shown))

        val closed = sink.local.single()
        assertEquals(BusPaths.NOTICE_CLOSED, closed.path)
        assertEquals("question-a", NoticeSurfaceContract.clientToken(closed.payload))
    }

    @Test
    fun `rowless input uses the same identity and duplicate gate as actions`() {
        val show = showEnvelope("relay").also {
            it.payload.put("interactive", true)
                .put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, "rowless")
        }
        router.handleLocal(show, hudSender("relay"))
        val valid = answer(sink.remote.single()).copy(path = BusPaths.NOTICE_INPUT)
        val invalid = valid.copy(payload = JSONObject(valid.payload.toString()).apply {
            remove(NoticeSurfaceContract.FIELD_QUESTION_ID)
        })
        router.handleGlassesInput(invalid)
        assertTrue(sink.local.isEmpty())
        router.handleGlassesInput(valid)
        router.handleGlassesInput(valid)

        val reply = sink.local.single()
        assertEquals(BusPaths.NOTICE_INPUT, reply.path)
        assertEquals("rowless", NoticeSurfaceContract.clientToken(reply.payload))
        assertEquals("relay", reply.payload.getString("pluginId"))
    }

    @Test
    fun `revocation hides the notice and rejects subsequent answers`() {
        val shown = showQuestion("question-a")
        router.clearForRevokedOwner("relay", "revoked")
        router.handleGlassesAction(answer(shown))

        assertEquals(BusPaths.NOTICE_HIDE, sink.remote.last().path)
        assertTrue(sink.local.isEmpty())
    }

    @Test
    fun `transport rejection is reported against the original request`() {
        sink.sendRemoteError = "NO_LINK"
        val show = showEnvelope("relay")
        router.handleLocal(show, hudSender("relay"))

        assertEquals(listOf(show.id to "NO_LINK"), sink.errors)
    }

    private fun showQuestion(token: String, pluginId: String = "relay"): BusEnvelope {
        router.handleLocal(
            showEnvelope(pluginId).also {
                it.payload.put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, token)
                    .put("actions", JSONArray().put(
                        JSONObject().put("id", "reply").put("glyph", "phone").put("label", "Reply"),
                    ))
            },
            hudSender(pluginId),
        )
        return sink.remote.last()
    }

    private fun answer(shown: BusEnvelope) = BusEnvelope(
        path = BusPaths.NOTICE_ACTION,
        payload = NoticeSurfaceContract.withInteractionIdentity(
            JSONObject().put("noticeId", "relay:notice").put("id", "reply")
                .put("pluginId", "forged").put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, "forged"),
            requireNotNull(NoticeSurfaceContract.interactionIdentity(shown.payload)),
        ),
    )

    private fun showEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_SHOW,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("kind", NoticeSurfaceContract.KIND)
            .put("title", "Marie")
            .put("body", "On my way"),
    )

    private fun updateEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_UPDATE,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("body", "Updated"),
    )
}
