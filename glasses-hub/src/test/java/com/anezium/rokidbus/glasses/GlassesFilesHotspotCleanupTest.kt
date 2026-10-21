package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesFilesHotspotCleanupTest {
    @Test fun `batch end forgets its SSID once after ownership reconciliation`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val events = mutableListOf<String>()
        cleanup.onConnectSubmitted("batch", "AndroidShare_1234")
        cleanup.onConnectSubmitted("batch", "AndroidShare_1234")
        repeat(2) {
            cleanup.finish("batch", { events += "restore" }, { events += "forget:$it" })
        }
        assertEquals(listOf("restore", "forget:AndroidShare_1234", "restore"), events)
    }

    @Test fun `no bridge submission and shared-network transfers do not forget`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.finish("not_joined", {}, forgotten::add)
        cleanup.finish("shared_lane", {}, forgotten::add)
        assertEquals(emptyList<String>(), forgotten)
    }

    @Test fun `failed joins that submitted credentials still clean up saved entries`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.onConnectSubmitted("failed_batch", "AndroidShare_5678")
        cleanup.finish("failed_batch", {}, forgotten::add)
        assertEquals(listOf("AndroidShare_5678"), forgotten)
    }

    @Test fun `stale release cannot consume another batch cleanup`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.onConnectSubmitted("new_batch", "AndroidShare_5678")
        cleanup.finish("old_batch", {}, forgotten::add)
        assertEquals(emptyList<String>(), forgotten)
        cleanup.finish("new_batch", {}, forgotten::add)
        assertEquals(listOf("AndroidShare_5678"), forgotten)
    }
}
