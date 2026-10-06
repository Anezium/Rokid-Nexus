package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.bytecodePatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleUpdateTest {
    private val activeVersion = "1.39.1-rokid.2"
    private fun url(version: String, asset: String = "patches.mpp") = PatchTargets.default.bundle.downloadPrefix + "v$version/$asset"
    private fun metadata(version: String, asset: String = "patches.mpp") = """{"version":"$version","download_url":"${url(version, asset)}"}"""

    private fun bundleBytes(api: String = "1.7.0", dex: Boolean = true, tag: String = ""): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\nPatcher-Version: $api\nTag: $tag\n".toByteArray())
            zip.closeEntry()
            if (dex) {
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(112).also { "dex\n035\u0000".toByteArray().copyInto(it) })
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private inner class Fixture(val directory: File) {
        var bundled = bundleBytes()
        var bundledVersion = activeVersion
        var recordedHash: String? = null
        var metadata = ""
        var payload: (OutputStream) -> Unit = {}
        // Applied only to downloaded candidates, which are validated before publication.
        var candidatePatches: (File) -> List<Patch<*>> = { patches }
        var downloads = 0
        val patches = listOf(bytecodePatch(name = "Rokid controls"))
        fun store(build: String = "build-1") = BundleStore(directory, { name ->
            when (name) {
                "bundled.mpp" -> bundled.inputStream()
                "bundled.json" -> buildJsonObject {
                    put("source_sha256", PatchTargets.default.bundle.pinnedSourceSha256); put("version", bundledVersion); put("sha256", recordedHash ?: PatchPolicy.sha256(bundled)); put("download_url", url(bundledVersion))
                }.toString().byteInputStream()
                else -> error(name)
            }
        }, build, { metadata }, { _, output -> downloads++; payload(output) },
            { if (it.name.endsWith(".partial")) candidatePatches(it) else patches })
        val pointer get() = Json.parseToJsonElement(File(directory, "active.json").readText()).jsonObject
        fun current(build: String = "build-1") = runBlocking { store(build).current() }
        fun check(version: String = activeVersion, build: String = "build-1") = runBlocking { store(build).checkForUpdate(version) }
    }

    private fun withStore(block: Fixture.(BundleStore.Loaded) -> Unit) {
        val directory = Files.createTempDirectory("bundle-update").toFile()
        try {
            assumeTrue("Bundle protection requires POSIX permissions", Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
            Fixture(directory).run { block(current()) }
        } finally {
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private fun Fixture.assertActiveKept(active: BundleStore.Loaded) {
        assertEquals(active.file.name, pointer.getValue("file").jsonPrimitive.content)
        assertEquals(active.version, pointer.getValue("version").jsonPrimitive.content)
        assertEquals(active.hash, pointer.getValue("sha256").jsonPrimitive.content)
        assertEquals(setOf(active.file.name, "active.json"), directory.list()!!.toSet())
    }

    @Test fun rejectedJvmOnlyReleaseIsRememberedAndNeverDownloadedAgain() = withStore { active ->
        metadata = metadata("1.40.0-rokid.1")
        payload = { it.write(bundleBytes(dex = false)) }
        val first = assertThrows(IllegalArgumentException::class.java) { check() }
        assertTrue(first.message!!.contains("JVM"))
        assertEquals(1, downloads)
        assertActiveKept(active)
        assertEquals("1.40.0-rokid.1", pointer.getValue("rejected_version").jsonPrimitive.content)

        repeat(3) {
            val update = check()
            assertFalse(update.switched)
            assertTrue(update.message, update.message!!.contains("1.40.0-rokid.1 was rejected") && update.message.contains("JVM"))
        }
        assertEquals(1, downloads)
        assertActiveKept(active)
        // Reopening keeps the same bundled bundle and the rejection record.
        assertEquals(active.file, current().file)
        assertEquals("1.40.0-rokid.1", pointer.getValue("rejected_version").jsonPrimitive.content)

        // A different advertised asset or version is a new candidate and is checked once.
        metadata = metadata("1.40.0-rokid.1", "patches-android.mpp")
        assertThrows(IllegalArgumentException::class.java) { check() }
        assertEquals(2, downloads)
        metadata = metadata("1.41.0-rokid.1")
        payload = { it.write(bundleBytes("1.8.0")) }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { check() }.message!!.contains("patcher API"))
        assertEquals(3, downloads)
        assertFalse(check().switched)
        assertEquals(3, downloads)
        assertEquals("1.41.0-rokid.1", pointer.getValue("rejected_version").jsonPrimitive.content)
        assertActiveKept(active)
    }

    @Test fun incompatiblePatchesAreRememberedButNetworkFailuresAndCancellationStayRetryable() = withStore { active ->
        metadata = metadata("1.40.0-rokid.1")
        payload = { throw IOException("connection reset") }
        repeat(2) { assertThrows(IOException::class.java) { check() } }
        assertEquals(2, downloads)
        assertNull(pointer["rejected_version"])
        assertActiveKept(active)

        payload = { it.write(bundleBytes(tag = "new")) }
        candidatePatches = { throw CancellationException("screen closed") }
        assertThrows(CancellationException::class.java) { check() }
        assertEquals(3, downloads)
        assertNull(pointer["rejected_version"])
        assertActiveKept(active)

        candidatePatches = { emptyList() }
        assertThrows(IllegalArgumentException::class.java) { check() }
        assertEquals(4, downloads)
        assertTrue(check().message!!.contains("no compatible patches"))
        assertEquals(4, downloads)
        assertActiveKept(active)
    }

    @Test fun rejectionRecordedByAnotherPluginBuildDoesNotSuppressTheDownload() = withStore { active ->
        metadata = metadata("1.40.0-rokid.1")
        payload = { it.write(bundleBytes("1.8.0")) }
        assertThrows(IllegalArgumentException::class.java) { check(build = "build-1") }
        assertFalse(check(build = "build-1").switched)
        assertEquals(1, downloads)
        // An upgraded plugin may support what the older build rejected, so it checks again once.
        payload = { it.write(bundleBytes(tag = "supported")) }
        val update = check(build = "build-2")
        assertTrue(update.switched)
        assertEquals(2, downloads)
        val switched = current(build = "build-2")
        assertEquals("1.40.0-rokid.1", switched.version)
        assertNull(pointer["rejected_version"])
        // The downloaded bundle survives reopening: the bundled asset has not changed.
        assertEquals(switched.file, current(build = "build-2").file)
        assertEquals(setOf(switched.file.name, "active.json"), directory.list()!!.toSet())
        assertNotEquals(active.file, switched.file)
    }

    @Test fun upgradedBundledAssetIsAdoptedOnceAndClearsRejection() = withStore { first ->
        assertEquals(PatchPolicy.sha256(bundled), pointer.getValue("bundled_sha256").jsonPrimitive.content)
        metadata = metadata("1.40.0-rokid.1")
        payload = { it.write(bundleBytes("1.8.0")) }
        assertThrows(IllegalArgumentException::class.java) { check() }
        assertNotNull(pointer["rejected_version"])

        bundled = bundleBytes(tag = "plugin-1.1")
        bundledVersion = "1.40.0-rokid.1"
        val upgraded = current()
        assertEquals("1.40.0-rokid.1", upgraded.version)
        assertEquals(PatchPolicy.sha256(bundled), upgraded.hash)
        assertNotEquals(first.file, upgraded.file)
        ReadOnlyBundleFile.requireReadOnly(upgraded.file)
        assertEquals(PatchPolicy.sha256(bundled), pointer.getValue("bundled_sha256").jsonPrimitive.content)
        assertNull(pointer["rejected_version"])
        assertNull(pointer["rejected_reason"])

        // Adopted once: the next open neither re-installs it nor keeps the superseded file.
        val reopened = current()
        assertEquals(upgraded.file, reopened.file)
        assertEquals(setOf(upgraded.file.name, "active.json"), directory.list()!!.toSet())
        assertEquals(1, downloads)
        assertFalse(check(upgraded.version).switched)
        assertEquals(1, downloads)
    }

    @Test fun failedAdoptionKeepsSavedBundleAndRetriesNextOpen() = withStore { saved ->
        val savedPointer = pointer
        // An included bundle without DEX, then one that does not match its recorded hash.
        for ((bytes, hash) in listOf(bundleBytes(dex = false, tag = "broken") to null, bundleBytes(tag = "tampered") to "0".repeat(64))) {
            bundled = bytes
            recordedHash = hash
            bundledVersion = "1.40.0-rokid.1"
            val loaded = current()
            assertEquals(saved.file, loaded.file)
            assertEquals(activeVersion, loaded.version)
            assertTrue(loaded.notice, loaded.notice!!.startsWith("Could not install the bundle included in this plugin version ("))
            assertEquals(savedPointer, pointer)
            assertEquals(setOf(saved.file.name, "active.json"), directory.list()!!.toSet())
        }
        // Adoption is retried and succeeds once the included bundle is valid.
        bundled = bundleBytes(tag = "fixed")
        recordedHash = null
        val adopted = current()
        assertNull(adopted.notice)
        assertEquals("1.40.0-rokid.1", adopted.version)
        assertEquals(PatchPolicy.sha256(bundled), pointer.getValue("bundled_sha256").jsonPrimitive.content)
    }

    @Test fun firstInstallFailureStillFailsClosed() {
        val directory = Files.createTempDirectory("bundle-first").toFile()
        try {
            assumeTrue("Bundle protection requires POSIX permissions", Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
            Fixture(directory).run {
                bundled = bundleBytes(dex = false)
                assertThrows(IllegalArgumentException::class.java) { current() }
                assertEquals(emptySet<String>(), directory.list()!!.toSet())
            }
        } finally { directory.walkBottomUp().forEach { it.setWritable(true); it.delete() } }
    }

    @Test fun pointerWithoutBundledHashMigratesOnce() = withStore { _ ->
        val legacy = ReadOnlyBundleFile.install(directory, write = { it.write(bundleBytes(tag = "legacy")) }, validate = {})
        File(directory, "active.json").writeText(buildJsonObject {
            put("file", legacy.name); put("version", "legacy"); put("sha256", PatchPolicy.sha256(legacy))
        }.toString())
        val migrated = current()
        assertEquals(activeVersion, migrated.version)
        assertEquals(PatchPolicy.sha256(bundled), pointer.getValue("bundled_sha256").jsonPrimitive.content)
        assertEquals(migrated.file, current().file)
        assertEquals(setOf(migrated.file.name, "active.json"), directory.list()!!.toSet())
    }

    @Test fun currentVersionNeedsNoDownload() {
        val directory = Files.createTempDirectory("bundle-current").toFile()
        try {
            Fixture(directory).run {
                metadata = metadata(activeVersion)
                val update = check()
                assertFalse(update.switched)
                assertNull(update.message)
                assertEquals(0, downloads)
                assertEquals(emptySet<String>(), directory.list()!!.toSet())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun decisionIgnoresStaleRejections() {
        val rejected = buildJsonObject {
            put("rejected_version", "2"); put("rejected_url", url("2")); put("rejected_plugin", "build-1"); put("rejected_reason", "No DEX.")
        }
        assertEquals("No DEX.", BundleStore.rejection(rejected, PatchPolicy.Metadata("2", url("2")), "build-1"))
        assertNull(BundleStore.rejection(rejected, PatchPolicy.Metadata("2", url("2")), "build-2"))
        assertNull(BundleStore.rejection(rejected, PatchPolicy.Metadata("3", url("3")), "build-1"))
        assertNull(BundleStore.rejection(rejected, PatchPolicy.Metadata("2", url("2", "other.mpp")), "build-1"))
        assertNull(BundleStore.rejection(buildJsonObject {}, PatchPolicy.Metadata("2", url("2")), "build-1"))
        // Records written before the plugin-build key never suppress a download.
        assertNull(BundleStore.rejection(JsonObject(rejected - "rejected_plugin"), PatchPolicy.Metadata("2", url("2")), "build-1"))
    }
}
