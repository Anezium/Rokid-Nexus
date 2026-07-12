package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceModelsTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Pair<String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) { this.listener = listener }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun close() = Unit
    }

    private val callbacks = object : NexusPluginCallbacks {
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
    }

    private fun client(capabilities: String): Pair<NexusPluginClient, FakeTransport> {
        val transport = FakeTransport()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities)
                .put("futureField", "ignored"),
        )
        return client to transport
    }

    @Test
    fun `valid card sends only local surface fields`() {
        val (client, transport) = client("surfaces")
        val result = client.surfaceSession("main").showCard(
            NexusCard("Hello", listOf("One", "Two"), footer = "Tap"),
        )
        assertEquals(NexusSdkResult.SENT, result)
        val payload = transport.sends.single().second
        assertEquals("main", payload.getString("surfaceId"))
        assertFalse(payload.has("pluginId"))
        assertFalse(payload.has("seq"))
    }

    @Test
    fun `rich card lines preserve badge and trail metadata`() {
        val (client, transport) = client("surfaces")
        val result = client.surfaceSession("main").showCard(
            NexusCard(
                title = "Transit",
                lines = emptyList(),
                richLines = listOf(NexusCardLine("Downtown", badge = "11", trail = listOf("2m", "8m"))),
                handlesBack = true,
            ),
        )
        assertEquals(NexusSdkResult.SENT, result)
        val line = transport.sends.single().second.getJSONArray("lines").getJSONObject(0)
        assertEquals("11", line.getString("badge"))
        assertEquals("8m", line.getJSONArray("trail").getString(1))
        assertTrue(transport.sends.single().second.getBoolean("handlesBack"))
    }

    @Test
    fun `invalid card and local IDs fail locally`() {
        assertThrows(IllegalArgumentException::class.java) { NexusCard("", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { NexusCard("Title", listOf("x".repeat(241))) }
        val (client, _) = client("surfaces")
        assertThrows(IllegalArgumentException::class.java) { client.surfaceSession("bad/id") }
    }

    @Test
    fun `timed lines full and anchor-only updates preserve content key`() {
        val (client, transport) = client("surfaces")
        val session = client.surfaceSession("lyrics")
        val anchor = NexusPlaybackAnchor(1200, true, 99)
        assertEquals(
            NexusSdkResult.SENT,
            session.showTimedLines(
                NexusTimedLines("Lyrics", "track-1", listOf(NexusTimedLine(0, "Line")), anchor),
            ),
        )
        assertEquals(NexusSdkResult.SENT, session.updateTimedLinesAnchor("track-1", anchor))
        val update = transport.sends.last().second
        assertEquals("track-1", update.getString("contentKey"))
        assertTrue(update.has("anchor"))
        assertFalse(update.has("lines"))
    }

    @Test
    fun `media full and anchor-only updates preserve the versioned surface`() {
        val (client, transport) = client("surfaces")
        val session = client.surfaceSession("media")
        val anchor = NexusMediaAnchor(1200, true, 1f, 99, durationMs = 2400)
        assertEquals(
            NexusSdkResult.SENT,
            session.showMedia(
                NexusMedia(
                    title = "MEDIA DECK",
                    contentKey = "track-1",
                    mediaTitle = "Track",
                    mediaArtist = "Artist",
                    anchor = anchor,
                    artwork = NexusMonoArtwork(8, 1, byteArrayOf(0x55), "art-1"),
                ),
            ),
        )
        assertEquals(NexusSdkResult.SENT, session.updateMediaAnchor("track-1", anchor))
        val full = transport.sends.first().second
        assertEquals(1, full.getInt("mediaVersion"))
        assertEquals("mono1", full.getJSONObject("artwork").getString("encoding"))
        val update = transport.sends.last().second
        assertEquals("media", update.getString("kind"))
        assertEquals("track-1", update.getString("contentKey"))
        assertTrue(update.has("anchor"))
        assertFalse(update.has("mediaTitle"))
    }

    @Test
    fun `capability helpers fail locally and microphone stays unavailable`() {
        val (client, transport) = client("surfaces")
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, client.requestHttp(JSONObject().put("url", "https://example.com")))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.requestAudioLease())
        assertEquals(0, transport.sends.size)
    }

    @Test
    fun `unknown registration fields do not hide approved capabilities`() {
        val (client, _) = client("surfaces,http_proxy")
        assertTrue(client.hasCapability(com.anezium.rokidbus.shared.plugin.PluginCapability.SURFACES))
        assertTrue(client.hasCapability(com.anezium.rokidbus.shared.plugin.PluginCapability.HTTP_PROXY))
    }
}
