package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class LauncherArtworkTest {
    private val cover = LauncherArtwork(8, 8, Base64.getEncoder().encodeToString(ByteArray(8) { 0x55 }))

    @Test fun `bounded previews round trip without resource references`() {
        assertEquals(cover, LauncherArtwork.parse(cover.toJson()))
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork(129, 1, "AA==") }
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork(8, 8, "AA==") }
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork.parse(cover.toJson().put("encoding", "uri")) }
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork.parse(cover.toJson().put("width", 1.5)) }
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork(1, 1, "A".repeat(2733)) }
    }

    @Test fun `media carries a cover and bounded progress and rejects malformed values`() {
        val body = PageBody.Media("Track", MediaPlaybackState.PLAYING, artwork = cover, positionMs = 1200, durationMs = 90000)
        val json = PageTemplateContract.encode(body)
        fun decode(payload: JSONObject) = PageTemplateContract.decode("media", payload.toString(), emptyList<PageSurfaceAction>(), "media", extended = true)
        assertEquals(body, (decode(json) as PageSurfaceValidationResult.Valid).value)
        for (bad in listOf(-1, 1.5, "100", 604800001L)) {
            assertTrue("$bad", decode(JSONObject(json.toString()).put("positionMs", bad)) is PageSurfaceValidationResult.Invalid)
        }
        assertTrue(decode(JSONObject(json.toString()).put("artwork", JSONObject().put("uri", "file:///x"))) is PageSurfaceValidationResult.Invalid)
    }

    @Test fun `activity patches preserve absent cover and clear explicit null`() {
        val payload = JSONObject().put("kind", "activity").put("glyph", "disc").put("primary", "Music").put("artwork", cover.toJson())
        val content = (ActivitySurfaceContract.validateStart(payload) as ActivitySurfaceValidationResult.Valid).content
        assertEquals(cover, content.artwork)
        val unchanged = (ActivitySurfaceContract.validateUpdate(JSONObject().put("primary", "Next")) as ActivitySurfacePatchResult.Valid).patch
        assertEquals(cover, unchanged.applyTo(content).artwork)
        val cleared = (ActivitySurfaceContract.validateUpdate(JSONObject().put("artwork", JSONObject.NULL)) as ActivitySurfacePatchResult.Valid).patch
        assertNull(cleared.applyTo(content).artwork)
        assertFalse(ActivitySurfaceContract.stripLauncherFields(payload).has("artwork"))
    }
    private fun jpegCover(): com.anezium.rokidbus.shared.LauncherArtwork {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0, 17, 8, 1, 0, 1, 0, 3, 1, 17, 0, 2, 17, 0, 3, 17, 0, 0xff.toByte(), 0xd9.toByte())
        return com.anezium.rokidbus.shared.LauncherArtwork(256, 256,
            java.util.Base64.getEncoder().encodeToString(bytes), "jpeg")
    }

    @Test fun `JPEG covers keep dimensions and fail closed on invalid data or bounds`() {
        val image = jpegCover()
        assertEquals(image, LauncherArtwork.parse(image.toJson()))
        assertThrows(IllegalArgumentException::class.java) { image.copy(width = 257) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(height = 255) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(data = "AA==") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(data = Base64.getEncoder().encodeToString(ByteArray(32769))) }
        val container = JSONObject().put("artwork", image.toJson())
        LauncherArtwork.stripUnsupported(container, 3)
        assertEquals("jpeg", container.getJSONObject("artwork").getString("encoding"))
        LauncherArtwork.stripUnsupported(container, 2)
        assertTrue(container.has("artwork"))
        assertTrue(container.isNull("artwork"))
    }

}
