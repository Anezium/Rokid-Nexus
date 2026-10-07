package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PatchDeliveryTest {
    @Test fun onlyTheWatchingInstanceOrMatchingReadyNoticeMayAutoDeliver() {
        val result = PatchJobState(id = "job", status = PatchJobStatus.SUCCESS)
        assertFalse(PatchDelivery.canAutoDeliver(result, null, null))
        assertFalse(PatchDelivery.canAutoDeliver(result, "older", "older"))
        assertTrue(PatchDelivery.canAutoDeliver(result, "job", null))
        assertTrue(PatchDelivery.canAutoDeliver(result, null, "job"))
        assertFalse(PatchDelivery.canAutoDeliver(result.copy(delivered = true), "job", "job"))
    }

    @Test fun deliverySurvivesReopeningAndPatchAgainGetsAFreshIdentity() {
        val directory = Files.createTempDirectory("delivery").toFile()
        try {
            val store = PatchJobStore(directory)
            val job = store.prepare()
            java.io.File(store.work(job.id), "stock.apk").writeBytes(byteArrayOf(1))
            val output = java.io.File(directory, "results/patched-complete.apk").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
            store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, stock = "stock.apk", result = output.name) }
            store.markDelivered(job.id)
            val reopened = PatchJobStore(directory)
            assertTrue(reopened.state.value.delivered)
            assertFalse(PatchDelivery.canAutoDeliver(reopened.state.value, job.id, job.id))
            val fresh = reopened.patch("hash", listOf("patch"))
            assertNotEquals(job.id, fresh.id)
            assertFalse(fresh.delivered)
            assertNull(fresh.result)
        } finally { directory.deleteRecursively() }
    }
}
