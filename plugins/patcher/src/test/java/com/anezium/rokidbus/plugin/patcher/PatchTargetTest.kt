package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.patch.loadPatchesFromJar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PatchTargetTest {
    @Test fun shippedTargetRetainsStockApiRangeSignerPinAndActualDefaultNames() {
        val target = PatchTargets.default
        assertEquals(30, target.minVerificationApi)
        assertEquals(32, target.maxVerificationApi)
        assertEquals(setOf("3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"), target.stockSigners)
        val fixture = File(requireNotNull(System.getProperty("patchBundleFixture")))
        assertEquals(target.bundle.pinnedSourceSha256, PatchPolicy.sha256(fixture))
        val patches = BundleStore.compatible(loadPatchesFromJar(setOf(fixture)), target)
        assertTrue(patches.map { it.name }.containsAll(target.featuredPatches))
        assertTrue(target.defaultSelection.keys.all { name -> patches.any { it.name == name && it.default } })
        assertEquals(target.outputPackage, target.expectedOutput(target.defaultSelection.keys))
        assertEquals(target.stockPackage, target.expectedOutput(emptySet()))
        assertNull(PatchTargets.find("unknown"))
    }

    @Test fun secondTargetDataCannotAcceptTheFirstTargetsIdentityOrReleaseOrigin() {
        val first = PatchTargets.default
        val second = first.copy(id = "second", displayName = "Second app", stockPackage = "example.second",
            acceptedVersions = setOf("2.0"), stockSigners = setOf("a".repeat(64)),
            bundle = first.bundle.copy(downloadPrefix = "https://github.com/Example/second/releases/download/"),
            outputPackage = "example.second.patched")
        val stock = PatchPolicy.Stock(first.stockPackage, first.acceptedVersions.single(), first.stockSigners, null, 1)
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(stock, target = second) }
        val secondStock = stock.copy(packageName = second.stockPackage, version = "2.0", signers = second.stockSigners)
        PatchPolicy.validate(secondStock, target = second)
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(secondStock, target = first) }
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(secondStock.copy(signers = second.stockSigners + first.stockSigners), target = second) }
        val metadata = """{"version":"2","download_url":"${first.bundle.downloadPrefix}v2/patches.mpp"}"""
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.metadata(metadata, second) }
    }
}
