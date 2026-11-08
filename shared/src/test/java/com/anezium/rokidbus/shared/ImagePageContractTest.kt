package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ImagePageContractTest {
    private val image = LauncherArtwork(512, 512, Base64.getEncoder().encodeToString(ByteArray(32_768)))
    private val actions = listOf("send-photo", "retake", "cancel").map {
        PageSurfaceAction(it, it, PageSurfaceContract.KIND_PLUGIN, true)
    }

    private fun decode(body: JSONObject, version: Int = 6) = PageTemplateContract.decode(
        "image", body.toString(), actions, "photo", launcherVersion = version,
    )

    private fun page(body: JSONObject) = PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "photo:main"))
        .put("revision", 1).put("title", "Preview").put("template", "image").put("body", body)
        .put("actions", JSONArray().put(JSONObject().put("id", "send-photo").put("label", "Send").put("kind", "plugin").put("confirm", true)))
        .put("live", true)

    @Test fun `full-page mono image round trips only with v6 and fits the page envelope`() {
        val body = PageBody.Image(image, "Ready to send")
        assertEquals(body, (decode(PageTemplateContract.encode(body)) as PageSurfaceValidationResult.Valid).value)
        for (version in 0..5) assertTrue(decode(PageTemplateContract.encode(body), version) is PageSurfaceValidationResult.Invalid)
        assertTrue(PageSurfaceContract.validateResponse(page(PageTemplateContract.encode(body))) is PageSurfaceValidationResult.Valid)
        assertTrue(PageSurfaceContract.serializedBytes(page(PageTemplateContract.encode(body))) < 65_536)
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork.parse(image.toJson()) }
    }

    @Test fun `JPEG previews keep larger bounds without widening media or activity covers`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0, 17, 8, 2, 0, 2, 0, 3, 1, 17, 0, 2, 17, 0, 3, 17, 0, 0xff.toByte(), 0xd9.toByte())
            .copyOf(LauncherArtwork.MAX_PAGE_BYTES)
        val artwork = LauncherArtwork(512, 512, Base64.getEncoder().encodeToString(jpeg), LauncherArtwork.JPEG)
        assertTrue(decode(PageTemplateContract.encode(PageBody.Image(artwork))) is PageSurfaceValidationResult.Valid)
        assertTrue(PageSurfaceContract.validateResponse(page(PageTemplateContract.encode(PageBody.Image(artwork)))) is PageSurfaceValidationResult.Valid)
        assertThrows(IllegalArgumentException::class.java) { LauncherArtwork.parse(artwork.toJson()) }
        assertThrows(IllegalArgumentException::class.java) { artwork.copy(data = Base64.getEncoder().encodeToString(jpeg.copyOf(jpeg.size + 1))) }
        assertThrows(IllegalArgumentException::class.java) { artwork.copy(width = 511) }
        val media = PageBody.Media("Track", MediaPlaybackState.PLAYING, artwork = artwork)
        assertTrue(PageTemplateContract.decode("media", PageTemplateContract.encode(media).toString(), actions, "photo", true) is PageSurfaceValidationResult.Invalid)
        assertTrue(ActivitySurfaceContract.validateStart(JSONObject().put("kind", "activity").put("glyph", "disc")
            .put("primary", "Music").put("artwork", artwork.toJson())) is ActivitySurfaceValidationResult.Invalid)
    }

    @Test fun `missing malformed oversized or external images and long captions fail closed`() {
        val body = PageTemplateContract.encode(PageBody.Image(image))
        for (bad in listOf(JSONObject(), JSONObject().put("image", JSONObject.NULL),
            JSONObject().put("image", JSONObject().put("uri", "https://example.com/photo")),
            JSONObject(body.toString()).put("caption", "x".repeat(241)),
            JSONObject(body.toString()).apply { getJSONObject("image").put("width", 513) },
            JSONObject(body.toString()).apply { getJSONObject("image").put("width", 1.5) },
            JSONObject(body.toString()).apply { getJSONObject("image").put("data", "invalid") })) {
            assertTrue(decode(bad) is PageSurfaceValidationResult.Invalid)
        }
    }

    @Test fun `old-peer stripping removes pixels and actions in responses and result replacements`() {
        val original = page(PageTemplateContract.encode(PageBody.Image(image, "Caption")))
        assertEquals(original.toString(), PageSurfaceContract.stripImagePages(original, 6).toString())
        for (version in 0..5) {
            val result = PageSurfaceContract.stripImagePages(JSONObject().put("invocationId", "i1").put("status", "done")
                .put("replacement", original), version)
            val fallback = result.getJSONObject("replacement")
            assertEquals("summary", fallback.getString("template"))
            assertFalse(fallback.getJSONObject("body").has("image"))
            assertEquals("Caption", fallback.getJSONObject("body").getString("secondary"))
            assertEquals(0, fallback.getJSONArray("actions").length())
            assertEquals(1, fallback.getInt("revision"))
            assertTrue(PageSurfaceContract.validateBoundedResult(result) is PageSurfaceValidationResult.Valid)
        }
        assertEquals("image", original.getString("template"))
    }
}
