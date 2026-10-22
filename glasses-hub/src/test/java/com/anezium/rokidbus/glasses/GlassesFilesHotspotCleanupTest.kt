package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import com.anezium.rokidbus.shared.GlassesFilesHotspotPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class GlassesFilesHotspotCleanupTest {
    @Test fun `camera takeover notifies the last hotspot transfer after both directions finish`() {
        val service = GlassesFilesService
        val id = service.javaClass.getDeclaredField("hotspotTransferId").apply { isAccessible = true }
        val control = service.javaClass.getDeclaredField("control").apply { isAccessible = true }.get(service) as ExecutorService
        val ended = java.util.concurrent.CopyOnWriteArrayList<String>()
        id.set(service, "last_transfer")
        try {
            service.onCameraStarted(ended::add)
            control.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("last_transfer"), ended.toList())
            assertTrue(GlassesFilesHotspotCleanup.WAIT_MS > GlassesFilesHotspotPolicy.IDLE_MS)
        } finally {
            id.set(service, null)
        }
    }

    @Test fun `batch end requests numeric cleanup once after ownership reconciliation`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val events = mutableListOf<String>()
        cleanup.onConnectSubmitted("batch")
        cleanup.onConnectSubmitted("batch")
        repeat(2) {
            cleanup.finish("batch", { events += "restore" }, { events += "forget" })
        }
        assertEquals(listOf("restore", "forget", "restore"), events)
    }

    @Test fun `no bridge submission and shared-network transfers do not forget`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.finish("not_joined", {}, { forgotten += "forget" })
        cleanup.finish("shared_lane", {}, { forgotten += "forget" })
        assertEquals(emptyList<String>(), forgotten)
    }

    @Test fun `failed joins that submitted credentials still clean up saved entries`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.onConnectSubmitted("failed_batch")
        cleanup.finish("failed_batch", {}, { forgotten += "forget" })
        assertEquals(listOf("forget"), forgotten)
    }

    @Test fun `stale release cannot consume another batch cleanup`() {
        val cleanup = GlassesFilesHotspotCleanup()
        val forgotten = mutableListOf<String>()
        cleanup.onConnectSubmitted("new_batch")
        cleanup.finish("old_batch", {}, { forgotten += "forget" })
        assertEquals(emptyList<String>(), forgotten)
        cleanup.finish("new_batch", {}, { forgotten += "forget" })
        assertEquals(listOf("forget"), forgotten)
    }
}
