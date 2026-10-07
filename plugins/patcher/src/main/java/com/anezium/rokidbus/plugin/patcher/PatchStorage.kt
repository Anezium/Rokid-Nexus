package com.anezium.rokidbus.plugin.patcher

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class PatchStorage(private val files: File) {
    val retry = File(files, "retry/stock.apk")

    fun retain(stock: File): String {
        retry.parentFile!!.mkdirs()
        if (stock.canonicalFile != retry.canonicalFile) {
            Files.move(stock.toPath(), retry.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            retry.setLastModified(System.currentTimeMillis())
        }
        return "retry/stock.apk"
    }

    fun discardRetry() { remove(File(files, "retry")) }

    fun sweep(keepWorkId: String? = null, now: Long = System.currentTimeMillis()) {
        File(files, "jobs").listFiles()?.filter { it.name != keepWorkId }?.forEach(::remove)
        if (retry.exists() && (now < retry.lastModified() || now - retry.lastModified() >= RETRY_MAX_AGE_MS)) remove(retry)
        retry.parentFile?.listFiles()?.filter { it != retry }?.forEach(::remove)
    }

    companion object {
        const val RETRY_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val bundleLock = Any()

        fun cleanBundleCache(cache: File): Unit = synchronized(bundleLock) {
            cache.listFiles()?.filter { it.name.startsWith("morphe-extracted-patches") }?.forEach(::remove)
        }

        fun <T> loadBundle(cache: File, block: () -> T): T = synchronized(bundleLock) {
            cleanBundleCache(cache)
            // 1.7.0 closes its mapped DEX read result but leaves the extraction directory.
            try { block() } finally { cleanBundleCache(cache) }
        }

        private fun remove(file: File) {
            if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(path); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(path); return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
