package com.anezium.rokidbus.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NexusReleaseAssetResolverTest {
    @Test
    fun `latest glasses release with missing or malformed digest never falls back to older release`() {
        for (digest in listOf(null, "", "sha256:bad", "sha512:${"ab".repeat(32)}")) {
            val body = JSONArray()
                .put(release("1.4.11", "sha256:${"ab".repeat(32)}"))
                .put(release("1.5.0", digest)).toString()
            val result = NexusReleaseAssetResolver.parseLatest(body, NexusReleaseArtifact.GLASSES)
            assertEquals(NexusSemVersion(1, 5, 0), result?.version)
            assertNull(result?.sha256)
        }
    }

    @Test
    fun `latest valid glasses release returns normalized digest`() {
        val result = NexusReleaseAssetResolver.parseLatest(
            JSONArray().put(release("1.4.11", null))
                .put(release("1.5.0", "sha256:${"AB".repeat(32)}")).toString(),
            NexusReleaseArtifact.GLASSES,
        )
        assertEquals(NexusSemVersion(1, 5, 0), result?.version)
        assertEquals("ab".repeat(32), result?.sha256)
    }

    @Test
    fun `phone release optional digest behavior is unchanged`() {
        val result = NexusReleaseAssetResolver.parseLatest(
            JSONArray().put(release("1.5.0", null, "nexus-phone")).toString(),
            NexusReleaseArtifact.PHONE,
        )
        assertEquals(NexusSemVersion(1, 5, 0), result?.version)
        assertNull(result?.sha256)
    }

    private fun release(version: String, digest: String?, prefix: String = "nexus-glasses") = JSONObject()
        .put("tag_name", "v$version")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "$prefix-$version.apk")
            .put("browser_download_url", "https://example.com/$prefix-$version.apk")
            .put("digest", digest ?: JSONObject.NULL)))
}
