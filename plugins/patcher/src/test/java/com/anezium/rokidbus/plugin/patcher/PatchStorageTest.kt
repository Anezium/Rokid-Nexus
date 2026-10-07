package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatchStorageTest {
    @Test fun startupSweepsKilledJobsAndRetainsOnlyOneValidatedInput() {
        val files = Files.createTempDirectory("storage").toFile()
        try {
            val store = PatchJobStore(files)
            val job = store.prepare()
            val work = store.work(job.id)
            File(work, "input.zip").writeBytes(ByteArray(20))
            File(work, "patch-work/decoded/resources").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(20)) }
            File(work, "prepare/stock.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(7)) }
            store.change(job.id) { it.copy(stock = "prepare/stock.apk") }
            val orphan = File(files, "jobs/orphan/leak").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(20)) }
            val recovered = PatchJobStore(files)
            assertEquals(PatchJobStatus.INTERRUPTED, recovered.state.value.status)
            assertEquals(7L, recovered.stock()!!.length())
            assertEquals("retry/stock.apk", recovered.state.value.stock)
            assertFalse(work.exists()); assertFalse(orphan.exists())
            val storage = PatchStorage(files)
            storage.sweep(now = storage.retry.lastModified() + PatchStorage.RETRY_MAX_AGE_MS)
            assertNull(recovered.stock())
        } finally { files.deleteRecursively() }
    }

    @Test fun loaderCleanupRunsOnFailureAndLeavesOtherCacheEntriesAlone() {
        val cache = Files.createTempDirectory("bundle-cache").toFile()
        try {
            File(cache, "morphe-extracted-patches-old/classes.dex").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            val other = File(cache, "other/keep").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            assertThrows(IllegalStateException::class.java) {
                PatchStorage.loadBundle(cache) {
                    assertFalse(File(cache, "morphe-extracted-patches-old").exists())
                    File(cache, "morphe-extracted-patches-new/classes.dex").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
                    error("loader failed")
                }
            }
            assertTrue(other.exists())
            assertEquals(listOf("other"), cache.listFiles()!!.map { it.name })
        } finally { cache.deleteRecursively() }
    }

    @Test fun successfulTerminalCleanupLeavesOnlyRetryAndPublishedResult() {
        val files = Files.createTempDirectory("terminal-storage").toFile()
        try {
            val store = PatchJobStore(files)
            val job = store.prepare()
            File(store.work(job.id), "input.zip").writeBytes(ByteArray(7))
            val result = File(files, "results/patched-result.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(9)) }
            store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, stock = "input.zip", result = result.name) }
            store.cleanStorage()
            assertTrue(File(files, "jobs").listFiles()!!.isEmpty())
            assertEquals(7L, store.stock()!!.length())
            assertEquals(9L, store.result()!!.length())
        } finally { files.deleteRecursively() }
    }
}
