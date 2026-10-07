package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatchPolicyTest {
    private val valid = PatchPolicy.Stock(PatchTargets.default.stockPackage, PatchTargets.default.acceptedVersions.single(), setOf(PatchTargets.default.stockSigners.single()), null, 1)
    @Test fun stockIdentityFailsClosed() {
        PatchPolicy.validate(valid)
        for (bad in listOf(valid.copy(packageName = "app.morphe.android.youtube"), valid.copy(version = "21.05.1"),
            valid.copy(signers = emptySet()), valid.copy(signers = valid.signers + "other"), valid.copy(split = "config.arm64_v8a"))) {
            assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(bad) }
        }
        PatchPolicy.validate(valid.copy(split = "config.arm64_v8a"), true)
    }
    @Test fun metadataPinsExactReleaseOriginAndPath() {
        val good = PatchTargets.default.bundle.downloadPrefix + "v1.39.1-rokid.2/patches.mpp"
        fun metadata(url: String) = PatchPolicy.metadata("""{"version":"1.39.1-rokid.2","download_url":"$url"}""")
        assertEquals(good, metadata(good).url)
        for (bad in listOf(good.replace("https:", "http:"), good.replace("github.com", "github.com.evil.org"),
            good + "?token=x", good + "#x", good.replace("/v1.39", "/../v1.39"),
            good.replace("patches.mpp", "%70atches.mpp"), "https://github.com/Other/repo/releases/download/v/a.mpp")) {
            assertThrows(IllegalArgumentException::class.java) { metadata(bad) }
        }
    }
    @Test fun selectionsPersistAndNewDefaultsMergeAcrossVersions() {
        val dir = Files.createTempDirectory("selection").toFile()
        try {
            val store = SelectionStore(File(dir, "selection.json"))
            assertEquals(mapOf("Rokid" to true, "Ads" to true), store.load("1", mapOf("Rokid" to true, "Ads" to true)))
            store.save("1", mapOf("Rokid" to false, "Ads" to true))
            val reloaded = SelectionStore(File(dir, "selection.json"))
            assertEquals(mapOf("Rokid" to false, "Ads" to true, "New" to true), reloaded.load("2", mapOf("Rokid" to true, "Ads" to false, "New" to true)))
            assertEquals(false, reloaded.load("1", mapOf("Rokid" to true))["Rokid"])
        } finally { dir.deleteRecursively() }
    }
    @Test fun splitChoiceTargetsGlassesAbiAndKeepsEveryDensityLanguageAndFeature() {
        val files = listOf("base", "config.arm64_v8a", "config.armeabi_v7a", "config.x86", "config.hdpi", "config.xxhdpi", "config.en", "feature")
            .associate { File(it) to if (it == "base") null else it }
        assertEquals(listOf("arm64-v8a"), ApkPreparer.GLASSES_ABIS)
        val chosen = ApkPreparer.chooseSplits(files, ApkPreparer.GLASSES_ABIS).map { it.name }.toSet()
        assertEquals(setOf("base", "config.arm64_v8a", "config.hdpi", "config.xxhdpi", "config.en", "feature"), chosen)
        // A 32-bit-only archive would not run on the arm64 glasses, whatever this phone supports.
        assertThrows(IllegalArgumentException::class.java) {
            ApkPreparer.chooseSplits(files.filterKeys { it.name != "config.arm64_v8a" }, ApkPreparer.GLASSES_ABIS)
        }
    }
    @Test fun resultRetentionKeepsNewestAndGraceWindowButBoundsTheRest() {
        val now = 100L * PatchPolicy.RESULT_MAX_AGE_MS
        val minute = 60_000L
        fun expired(vararg ages: Pair<String, Long>, keep: String? = null) = PatchPolicy.expiredResults(
            ages.associate { (name, age) -> File(name) to now - age }, now, keep?.let(::File)).map { it.name }.toSet()
        assertEquals(emptySet<String>(), expired())
        // The newest result is the one the hub or a share target is most likely still reading.
        assertEquals(emptySet<String>(), expired("a" to 23 * 60 * minute))
        // Older results inside the grace period may still be copied through their URI grant.
        assertEquals(emptySet<String>(), expired("new" to minute, "recent" to 9 * minute))
        assertEquals(setOf("old", "older"), expired("new" to minute, "recent" to 9 * minute, "old" to 10 * minute, "older" to 5 * 60 * minute))
        // Nothing outlives a day, not even the newest; a fresh result being returned is never purged.
        assertEquals(setOf("stale", "staler"), expired("stale" to 24 * 60 * minute, "staler" to 48 * 60 * minute))
        assertEquals(setOf("previous"), expired("fresh" to 0, "previous" to 30 * minute, keep = "fresh"))
        assertEquals(emptySet<String>(), expired("clock" to 25 * 60 * minute, keep = "clock"))
        // A wildly future timestamp from a clock change is still bounded.
        assertEquals(setOf("future"), expired("future" to -25 * 60 * minute, "new" to minute))
        assertEquals(setOf("old"), expired("tie-b" to 0, "tie-a" to 0, "old" to 11 * minute))
        assertTrue(PatchPolicy.isResult("patched-0b6f.apk"))
        for (name in listOf(".patched-0b6f.apk.partial", "patched-0b6f.apk.partial", "other.apk")) assertFalse(PatchPolicy.isResult(name))
    }
    @Test fun traversalAndOversizedCopiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.copyBounded(ByteArray(11).inputStream(), java.io.ByteArrayOutputStream(), 10) }
        val dir = Files.createTempDirectory("split").toFile()
        try {
            val archive = File(dir, "bad.apkm")
            ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("../evil.apk")); it.write(byteArrayOf(1)); it.closeEntry() }
            assertThrows(IllegalArgumentException::class.java) { ApkPreparer.extract(archive, dir) }
            assertFalse(File(dir.parentFile, "evil.apk").exists())
        } finally { dir.deleteRecursively() }
    }
}
