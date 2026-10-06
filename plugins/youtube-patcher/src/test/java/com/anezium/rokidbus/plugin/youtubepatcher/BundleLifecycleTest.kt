package com.anezium.rokidbus.plugin.youtubepatcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleLifecycleTest {
    @Test fun genuineBundleIsProtectedBeforeFirstByteAndAtValidationAndPublication() {
        val directory = Files.createTempDirectory("bundle-lifecycle").toFile()
        try {
            val source = File(requireNotNull(System.getProperty("preparedBundle")))
            // Embedded and downloaded bytes use exactly this shared production lifecycle.
            repeat(2) {
                var validated = false
                val target = ReadOnlyBundleFile.install(directory, write = { output ->
                    val staging = directory.listFiles()!!.single { it.name.endsWith(".partial") }
                    assertEquals(0, staging.length())
                    ReadOnlyBundleFile.requireReadOnly(staging)
                    assertFalse(directory.listFiles()!!.any { it.name.endsWith(".mpp") && it.length() == 0L })
                    source.inputStream().use { PatchPolicy.copyBounded(it, output) }
                    ReadOnlyBundleFile.requireReadOnly(staging)
                }, validate = { candidate ->
                    validated = true
                    assertTrue(candidate.name.endsWith(".partial"))
                    ReadOnlyBundleFile.requireReadOnly(candidate)
                    assertEquals(PatchPolicy.sha256(source), PatchPolicy.sha256(candidate))
                    PatchPolicy.requireDex(candidate)
                })
                assertTrue(validated)
                assertTrue(target.name.endsWith(".mpp"))
                ReadOnlyBundleFile.requireReadOnly(target)
                assertEquals(PatchPolicy.sha256(source), PatchPolicy.sha256(target))
                assertFalse(directory.listFiles()!!.any { it.name.endsWith(".partial") })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationAtEveryBoundaryAndWriteOrValidationFailureCleanUnpublishedFiles() {
        val directory = Files.createTempDirectory("bundle-cancellation").toFile()
        try {
            val active = File(directory, "active.json").apply { writeText("last working bundle") }
            for (boundary in 1..4) {
                var checks = 0
                assertThrows(CancellationException::class.java) {
                    ReadOnlyBundleFile.install(directory, checkCancelled = {
                        if (++checks == boundary) throw CancellationException("cancel")
                    }, write = { it.write(byteArrayOf(1, 2, 3)) }, validate = {})
                }
                assertEquals(listOf(active.name), directory.listFiles()!!.map { it.name })
                assertEquals("last working bundle", active.readText())
            }
            for (duringWrite in listOf(true, false)) {
                assertThrows(IllegalArgumentException::class.java) {
                    ReadOnlyBundleFile.install(directory, write = {
                        it.write(1)
                        if (duringWrite) errorArgument()
                    }, validate = { errorArgument() })
                }
                assertEquals(listOf(active.name), directory.listFiles()!!.map { it.name })
            }
        } finally { directory.deleteRecursively() }
    }
    private fun errorArgument(): Nothing = throw IllegalArgumentException("rejected")

    @Test fun processDeathRecoveryRemovesPartialsAndUncommittedRenamesButRetainsActive() {
        val directory = Files.createTempDirectory("bundle-recovery").toFile()
        try {
            val active = ReadOnlyBundleFile.install(directory, write = { it.write(1) }, validate = {})
            ReadOnlyBundleFile.install(directory, write = { it.write(2) }, validate = {})
            File(directory, "bundle-abandoned.partial").writeText("interrupted")
            File(directory, "active.tmp").writeText("interrupted pointer")
            ReadOnlyBundleFile.cleanUnused(directory, active.name)
            assertEquals(listOf(active.name), directory.listFiles()!!.map { it.name })
            assertEquals(1, active.readBytes().single().toInt())
            ReadOnlyBundleFile.requireReadOnly(active)
        } finally { directory.deleteRecursively() }
    }

    @Test fun writableExistingFileIsRejectedAndCopiedToNewProtectedInode() {
        val directory = Files.createTempDirectory("bundle-migration").toFile()
        try {
            val old = File(directory, "old.mpp").apply { writeText("existing") }
            assertThrows(IllegalArgumentException::class.java) { ReadOnlyBundleFile.requireReadOnly(old) }
            val target = ReadOnlyBundleFile.install(directory, write = { out -> old.inputStream().use { it.copyTo(out) } },
                validate = { assertEquals(PatchPolicy.sha256(old), PatchPolicy.sha256(it)) })
            assertNotEquals(old, target)
            ReadOnlyBundleFile.requireReadOnly(target)
            // Migration never merely chmods executable bytes written while writable.
            assertThrows(IllegalArgumentException::class.java) { ReadOnlyBundleFile.requireReadOnly(old) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun runtimeArtifactPolicyRejectsJvmOnlyWrongApiAndBogusDex() {
        val directory = Files.createTempDirectory("bundle-policy").toFile()
        try {
            fun bundle(api: String, dex: ByteArray?): File = File(directory, "test.mpp").also { file ->
                ZipOutputStream(file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    zip.write("Manifest-Version: 1.0\nPatcher-Version: $api\n".toByteArray())
                    zip.closeEntry()
                    if (dex != null) { zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex); zip.closeEntry() }
                }
            }
            val header = ByteArray(112).also { "dex\n035\u0000".toByteArray().copyInto(it) }
            assertTrue(assertThrows(IllegalArgumentException::class.java) { PatchPolicy.requireDex(bundle("1.7.0", null)) }.message!!.contains("JVM"))
            assertThrows(IllegalArgumentException::class.java) { PatchPolicy.requireDex(bundle("1.8.0", header)) }
            assertThrows(IllegalArgumentException::class.java) { PatchPolicy.requireDex(bundle("1.7.0", ByteArray(112))) }
        } finally { directory.deleteRecursively() }
    }
}
