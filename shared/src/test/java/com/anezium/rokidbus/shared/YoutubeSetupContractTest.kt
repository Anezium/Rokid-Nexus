package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test

class YoutubeSetupContractTest {
    private val apps = YoutubeSetupContract.PACKAGES.map { YoutubePackage(it) }

    @Test fun `fixed inventory fits the CXR control transport`() {
        val installed = apps.map { it.copy(versionCode = 123, signer = "a".repeat(64), launchable = true) }
        val json = YoutubeSetupContract.result("youtube-request", 32, installed)
        assertEquals(installed, YoutubeSetupContract.parseResult(json)?.apps)
        assertTrue(json.toString().toByteArray().size < 2_000)
        assertEquals("youtube-request", YoutubeSetupContract.requestId(YoutubeSetupContract.request("youtube-request")))
    }

    @Test fun `rejects arbitrary packages duplicates and missing entries`() {
        val json = YoutubeSetupContract.result("request-id", 32, apps)
        json.getJSONArray("apps").getJSONObject(0).put("packageName", "com.google.android.gms")
        assertNull(YoutubeSetupContract.parseResult(json))
        val missing = YoutubeSetupContract.result("request-id", 32, apps)
        missing.getJSONArray("apps").remove(2)
        assertNull(YoutubeSetupContract.parseResult(missing))
    }

    @Test fun `rejects invalid versions signer digests and unversioned messages`() {
        for (bad in listOf(-1, "123", 1.5)) {
            val json = YoutubeSetupContract.result("request-id", 32, apps)
            json.getJSONArray("apps").getJSONObject(0).put("versionCode", bad)
            assertNull(YoutubeSetupContract.parseResult(json))
        }
        val json = YoutubeSetupContract.result("request-id", 32, apps)
        json.getJSONArray("apps").getJSONObject(0).put("signer", "not-a-digest")
        assertNull(YoutubeSetupContract.parseResult(json))
        assertNull(YoutubeSetupContract.requestId(YoutubeSetupContract.request("request-id").put("version", "1")))
    }
}
