package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
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
        val binarySends = mutableListOf<Triple<String, JSONObject, ByteArray>>()
        var featureBits = 0
        var binaryAccepted = true
        override fun connect(listener: NexusPluginTransport.Listener) { this.listener = listener }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
            binarySends += Triple(path, JSONObject(payload.toString()), data.copyOf())
            return binaryAccepted
        }
        override fun capabilities(): Int = featureBits
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

    private fun validJpeg(width: Int = 512, height: Int = 512): ByteArray = ByteArray(128).also { bytes ->
        byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0x00, 0x11, 0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        ).copyInto(bytes)
    }

    private fun image() = NexusImage(
        contentKey = "photo-1",
        mimeType = ImageSurfaceContract.MIME_JPEG,
        pixelWidth = 512,
        pixelHeight = 512,
        title = "Photo",
        caption = "A real image",
        footer = "Back",
        handlesBack = true,
    )

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
    fun `media image artwork uses binary envelope and nested metadata`() {
        val (client, transport) = client("surfaces")
        transport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        val bytes = validJpeg(width = 256, height = 128)
        val media = NexusMedia(
            title = "MEDIA DECK",
            contentKey = "track-image",
            mediaTitle = "Track",
            anchor = NexusMediaAnchor(0, false, 1f, 99),
            imageArtwork = NexusMediaImageArtwork(
                mimeType = ImageSurfaceContract.MIME_JPEG,
                pixelWidth = 256,
                pixelHeight = 128,
            ),
        )

        assertEquals(NexusSdkResult.SENT, client.surfaceSession("media").showMedia(media, bytes))

        val (path, payload, sentBytes) = transport.binarySends.single()
        assertEquals(BusPaths.SURFACE_SHOW, path)
        assertEquals("media", payload.getString("kind"))
        assertEquals(1, payload.getInt("mediaVersion"))
        val artwork = payload.getJSONObject("artwork")
        assertEquals("binary", artwork.getString("encoding"))
        assertEquals("image/jpeg", artwork.getString("mimeType"))
        assertEquals(256, artwork.getInt("pixelWidth"))
        assertEquals(ImageSurfaceContract.sha256(bytes), artwork.getString("sha256"))
        assertTrue(bytes.contentEquals(sentBytes))
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun `media image artwork requires image capability and binary overload`() {
        val (client, transport) = client("surfaces")
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        val bytes = validJpeg(width = 256, height = 256)
        val media = NexusMedia(
            title = "MEDIA DECK",
            contentKey = "track-image",
            mediaTitle = "Track",
            anchor = NexusMediaAnchor(0, false, 1f, 99),
            imageArtwork = NexusMediaImageArtwork(ImageSurfaceContract.MIME_JPEG, 256, 256),
        )

        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.surfaceSession("media").showMedia(media))
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            client.surfaceSession("media-2").showMedia(media, bytes),
        )
        assertTrue(transport.sends.isEmpty())
        assertTrue(transport.binarySends.isEmpty())
    }

    @Test
    fun `capability helpers fail locally without their grants`() {
        val (client, transport) = client("surfaces")
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, client.requestHttp(JSONObject().put("url", "https://example.com")))
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_GRANTED,
            client.audioSession(
                object : NexusAudioCallbacks {
                    override fun onAudioStarted(format: NexusAudioFormat) = Unit
                    override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) = Unit
                    override fun onAudioStopped(reason: NexusAudioStopReason) = Unit
                },
            ).start(),
        )
        assertEquals(0, transport.sends.size)
    }

    @Test
    fun `audio session requires approval before sending acquire`() {
        val transport = FakeTransport()
        val unapproved = NexusPluginClient("hello", callbacks, transport)
        unapproved.connect()
        val stopped = mutableListOf<NexusAudioStopReason>()
        val session = unapproved.audioSession(
            object : NexusAudioCallbacks {
                override fun onAudioStarted(format: NexusAudioFormat) = Unit
                override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) = Unit
                override fun onAudioStopped(reason: NexusAudioStopReason) {
                    stopped += reason
                }
            },
        )

        assertEquals(NexusSdkResult.NOT_REGISTERED, session.start())
        assertTrue(transport.sends.isEmpty())
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `audio session routes granted frame and revoke without raw callbacks`() {
        val genericMessages = mutableListOf<String>()
        val transport = FakeTransport()
        val genericCallbacks = object : NexusPluginCallbacks {
            override fun onOpen() = Unit
            override fun onClose() = Unit
            override fun onInput(event: NexusInputEvent) = Unit
            override fun onLinkState(state: Int) = Unit
            override fun onRegistrationState(result: Int) = Unit
            override fun onMessage(path: String, id: String, payload: JSONObject) {
                genericMessages += path
            }
            override fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
                genericMessages += path
            }
        }
        val client = NexusPluginClient("hello", genericCallbacks, transport)
        client.connect()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-audio",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "microphone"),
        )

        val started = mutableListOf<NexusAudioFormat>()
        val frames = mutableListOf<Triple<ByteArray, Long, Long>>()
        val stopped = mutableListOf<NexusAudioStopReason>()
        val session = client.audioSession(
            object : NexusAudioCallbacks {
                override fun onAudioStarted(format: NexusAudioFormat) {
                    started += format
                }
                override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                    frames += Triple(pcm.copyOf(), seq, elapsedRealtimeMs)
                }
                override fun onAudioStopped(reason: NexusAudioStopReason) {
                    stopped += reason
                }
            },
        )

        assertEquals(NexusSdkResult.SENT, session.start())
        assertEquals(NEXUS_AUDIO_LEASE_ACQUIRE_PATH, transport.sends.single().first)
        assertEquals(0, transport.sends.single().second.length())
        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH,
            "acquire-reply-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("granted", true)
                .put("leaseId", "lease-1")
                .put("sampleRate", 16_000)
                .put("channels", 1)
                .put("encoding", "s16le"),
        )
        assertEquals(listOf(NexusAudioFormat(16_000, 1, "s16le")), started)
        assertTrue(session.isActive)

        val pcm = byteArrayOf(1, 2, 3, 4)
        transport.listener.onBinary(
            NEXUS_AUDIO_FRAMES_PATH,
            "frame-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("leaseId", "lease-1")
                .put("seq", 42L)
                .put("elapsedRealtime", 7_654L),
            pcm,
        )
        transport.listener.onBinary(
            NEXUS_AUDIO_FRAMES_PATH,
            "frame-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("leaseId", "lease-1")
                .put("seq", 43L)
                .put("elapsedRealtime", 7_674L),
            byteArrayOf(5, 6),
        )
        transport.listener.onBinary(
            NEXUS_AUDIO_FRAMES_PATH,
            "frame-other-lease",
            JSONObject()
                .put("pluginId", "hello")
                .put("leaseId", "other-lease")
                .put("seq", 44L)
                .put("elapsedRealtime", 7_694L),
            byteArrayOf(7, 8),
        )
        assertEquals(1, frames.size)
        assertTrue(pcm.contentEquals(frames.single().first))
        assertEquals(42L, frames.single().second)
        assertEquals(7_654L, frames.single().third)

        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_REVOKED_PATH,
            "revoked-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("leaseId", "lease-1")
                .put("reason", "PREEMPTED"),
        )
        assertEquals(listOf(NexusAudioStopReason.REVOKED), stopped)
        assertFalse(session.isActive)
        assertTrue(genericMessages.isEmpty())
    }

    @Test
    fun `audio stop releases once and registration loss terminates once`() {
        val (client, transport) = client("microphone")
        val stopped = mutableListOf<NexusAudioStopReason>()
        val session = client.audioSession(
            object : NexusAudioCallbacks {
                override fun onAudioStarted(format: NexusAudioFormat) = Unit
                override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) = Unit
                override fun onAudioStopped(reason: NexusAudioStopReason) {
                    stopped += reason
                }
            },
        )
        assertEquals(NexusSdkResult.SENT, session.start())
        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH,
            "acquire-reply-stop",
            JSONObject()
                .put("pluginId", "hello")
                .put("granted", true)
                .put("leaseId", "lease-stop")
                .put("sampleRate", 16_000)
                .put("channels", 1)
                .put("encoding", "s16le"),
        )

        session.stop()
        assertEquals(NEXUS_AUDIO_LEASE_RELEASE_PATH, transport.sends.last().first)
        assertEquals("lease-stop", transport.sends.last().second.getString("leaseId"))
        assertEquals(listOf(NexusAudioStopReason.RELEASED), stopped)
        assertFalse(session.isActive)

        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH,
            "release-reply-stop",
            JSONObject().put("pluginId", "hello").put("released", true),
        )
        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_REVOKED_PATH,
            "late-revoke",
            JSONObject().put("pluginId", "hello").put("leaseId", "lease-stop"),
        )
        assertEquals(listOf(NexusAudioStopReason.RELEASED), stopped)

        assertEquals(NexusSdkResult.SENT, session.start())
        transport.listener.onMessage(
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH,
            "acquire-reply-drop",
            JSONObject()
                .put("pluginId", "hello")
                .put("granted", true)
                .put("leaseId", "lease-drop")
                .put("sampleRate", 16_000)
                .put("channels", 1)
                .put("encoding", "s16le"),
        )
        assertTrue(session.isActive)
        transport.listener.onRegistrationState(PluginRegistrationResult.DENIED)
        assertEquals(
            listOf(NexusAudioStopReason.RELEASED, NexusAudioStopReason.ERROR),
            stopped,
        )
        assertFalse(session.isActive)
    }

    @Test
    fun `audio acquire denial reasons map to SDK stop reasons`() {
        val (client, transport) = client("microphone")
        val stopped = mutableListOf<NexusAudioStopReason>()
        val session = client.audioSession(
            object : NexusAudioCallbacks {
                override fun onAudioStarted(format: NexusAudioFormat) = Unit
                override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) = Unit
                override fun onAudioStopped(reason: NexusAudioStopReason) {
                    stopped += reason
                }
            },
        )
        val cases = listOf(
            "BUSY" to NexusAudioStopReason.DENIED_BUSY,
            "NO_CXR" to NexusAudioStopReason.DENIED_NO_LINK,
            "START_FAILED" to NexusAudioStopReason.DENIED_START_FAILED,
            "NOT_GRANTED" to NexusAudioStopReason.ERROR,
        )

        cases.forEachIndexed { index, (wireReason, _) ->
            assertEquals(NexusSdkResult.SENT, session.start())
            transport.listener.onMessage(
                NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH,
                "denied-$index",
                JSONObject()
                    .put("pluginId", "hello")
                    .put("granted", false)
                    .put("reason", wireReason),
            )
        }

        assertEquals(cases.map { it.second }, stopped)
    }

    @Test
    fun `unknown registration fields do not hide approved capabilities`() {
        val (client, _) = client("surfaces,http_proxy")
        assertTrue(client.hasCapability(com.anezium.rokidbus.shared.plugin.PluginCapability.SURFACES))
        assertTrue(client.hasCapability(com.anezium.rokidbus.shared.plugin.PluginCapability.HTTP_PROXY))
    }

    @Test
    fun `image metadata validates and delegates binary bytes`() {
        val (client, transport) = client("surfaces")
        transport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        val bytes = validJpeg()
        val session = client.surfaceSession("main")

        assertEquals(NexusSdkResult.SENT, session.showImage(image(), bytes))

        val (path, payload, sentBytes) = transport.binarySends.single()
        assertEquals(BusPaths.SURFACE_SHOW, path)
        assertEquals("image", payload.getString("kind"))
        assertEquals("photo-1", payload.getString("contentKey"))
        assertEquals(ImageSurfaceContract.sha256(bytes), payload.getString("sha256"))
        assertTrue(bytes.contentEquals(sentBytes))
        assertEquals(0, transport.sends.size)
        assertEquals(
            NexusSdkResult.IMAGE_RATE_LIMITED,
            session.updateImage(image(), bytes),
        )
    }

    @Test
    fun `image send fails for missing grant feature and SPP link`() {
        val bytes = validJpeg()
        val (noGrant, noGrantTransport) = client("http_proxy")
        noGrantTransport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        noGrantTransport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, noGrant.surfaceSession("main").showImage(image(), bytes))

        val (noFeature, noFeatureTransport) = client("surfaces")
        noFeatureTransport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, noFeature.surfaceSession("main").showImage(image(), bytes))

        val (noSpp, noSppTransport) = client("surfaces")
        noSppTransport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        noSppTransport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, noSpp.surfaceSession("main").showImage(image(), bytes))
        assertTrue(noGrantTransport.binarySends.isEmpty())
        assertTrue(noFeatureTransport.binarySends.isEmpty())
        assertTrue(noSppTransport.binarySends.isEmpty())
    }

    @Test
    fun `image body and metadata caps fail before transport`() {
        assertThrows(IllegalArgumentException::class.java) {
            NexusImage("photo", "image/webp", 10, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusImage("photo", ImageSurfaceContract.MIME_JPEG, 513, 10)
        }

        val (client, transport) = client("surfaces")
        transport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        val oversized = validJpeg() + ByteArray(ImageSurfaceContract.MAX_IMAGE_BYTES)
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.surfaceSession("main").showImage(image(), oversized))
        assertTrue(transport.binarySends.isEmpty())
    }

    @Test
    fun `offline binary rejection is not reported as sent`() {
        val (client, transport) = client("surfaces")
        transport.featureBits = BusCapabilityBits.IMAGE_SURFACE
        transport.binaryAccepted = false
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        val session = client.surfaceSession("main")

        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            session.showImage(image(), validJpeg()),
        )
        assertFalse(client.supportsImageSurface)

        transport.binaryAccepted = true
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertEquals(NexusSdkResult.SENT, session.showImage(image(), validJpeg()))
    }
}
