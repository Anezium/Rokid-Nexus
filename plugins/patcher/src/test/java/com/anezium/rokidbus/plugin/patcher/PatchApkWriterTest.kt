package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkUtils.applyTo
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

class PatchApkWriterTest {
    private fun result(resources: File, other: File? = null): PatcherResult {
        val dex = PatcherResult.PatchedDexFile::class.java.getDeclaredConstructor(String::class.java, java.io.InputStream::class.java)
            .newInstance("classes.dex", ByteArrayInputStream(ByteArray(20) { 7 }))
        val res = PatcherResult.PatchedResources::class.java.getDeclaredConstructor(File::class.java, File::class.java, Set::class.java, Set::class.java)
            .newInstance(resources, other, setOf("lib/arm64-v8a/libtest.so"), setOf("assets/remove"))
        return PatcherResult::class.java.getDeclaredConstructor(Set::class.java, PatcherResult.PatchedResources::class.java)
            .newInstance(setOf(dex), res)
    }
    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
    private fun contents(file: File) = ZipFile(file).use { zip -> zip.entries().asSequence().associate { entry ->
        entry.name to zip.getInputStream(entry).use { it.readBytes().toList() }
    } }

    @Test fun observedWriterMatchesUpstreamContentsAndReportsPhysicalWritesAfterAlign() {
        val dir = Files.createTempDirectory("writer").toFile()
        try {
            val output = File(dir, "output.apk")
            zip(output, mapOf("res/old" to byteArrayOf(1), "assets/remove" to byteArrayOf(2), "assets/keep" to byteArrayOf(3)))
            val control = File(dir, "control.apk"); output.copyTo(control)
            val resources = File(dir, "resources.apk")
            zip(resources, mapOf("res/new" to byteArrayOf(4), "classes9.dex" to byteArrayOf(9)))
            val other = File(dir, "other")
            File(other, "lib/arm64-v8a/libtest.so").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(33) { 5 }) }
            result(resources, other).applyTo(control)
            val reports = mutableListOf<PatchProgress>()
            PatchApkWriter.apply(result(resources, other), output, PatchTimings(sink = {}), reports::add)
            assertEquals(contents(control), contents(output))
            ZipFile(output).use { assertEquals(ZipEntry.STORED, it.getEntry("lib/arm64-v8a/libtest.so").method) }
            com.android.tools.build.apkzlib.zip.ZFile.openReadOnly(output).use { apk ->
                val entry = requireNotNull(apk.get("lib/arm64-v8a/libtest.so"))
                java.io.RandomAccessFile(output, "r").use { file ->
                    val offset = entry.centralDirectoryHeader.offset
                    file.seek(offset + 26)
                    fun short() = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
                    val nameLength = short(); val extraLength = short()
                    assertEquals(0L, (offset + 30 + nameLength + extraLength) % 4096)
                }
            }
            assertTrue(reports.zipWithNext().all { (a, b) -> a.phase.ordinal <= b.phase.ordinal })
            val writes = reports.filter { it.phase == PatchPhase.WRITE }
            assertTrue(writes.last().completedBytes!! > 0)
            assertTrue(writes.all { it.fraction == null })
            assertTrue(writes.any { it.substep == PatchSubstep.WRITE_DIRECTORY })
        } finally { dir.deleteRecursively() }
    }
}
