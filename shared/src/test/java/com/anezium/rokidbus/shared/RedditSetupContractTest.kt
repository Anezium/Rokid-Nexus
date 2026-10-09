package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test

class RedditSetupContractTest {
    private val app = YoutubePackage(RedditSetupContract.REDDIT, 2614001, "a".repeat(64), true)

    @Test fun `Reddit inventory round trips within CXR and rejects YouTube messages`() {
        val json = RedditSetupContract.result("reddit-request", 32, listOf(app))
        assertEquals(listOf(app), RedditSetupContract.parseResult(json)?.apps)
        assertTrue(json.toString().toByteArray().size < 2_000)
        assertNull(YoutubeSetupContract.parseResult(json))
        assertNull(YoutubeSetupContract.requestId(RedditSetupContract.request("reddit-request")))
        assertNull(RedditSetupContract.requestId(YoutubeSetupContract.request("youtube-request")))
    }

    @Test fun `fixed Reddit identity rejects arbitrary packages missing entries and false types`() {
        val json = RedditSetupContract.result("reddit-request", 32, listOf(app))
        json.getJSONArray("apps").getJSONObject(0).put("packageName", "com.example.editor")
        assertNull(RedditSetupContract.parseResult(json))
        val wrongType = RedditSetupContract.result("reddit-request", 32, listOf(app))
        wrongType.getJSONArray("apps").getJSONObject(0).put("launchable", "true")
        assertNull(RedditSetupContract.parseResult(wrongType))
        val missing = RedditSetupContract.result("reddit-request", 32, listOf(app))
        missing.getJSONArray("apps").remove(0)
        assertNull(RedditSetupContract.parseResult(missing))
    }
}
