package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.patch.loadPatchesFromJar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RedditBundleTest {
    @Test fun pinnedRedditBundleContainsTheRealHudAndKeepsYouTubeSeparate() {
        val target = PatchTargets.reddit
        val source = File(requireNotNull(System.getProperty("redditBundleFixture")))
        assertEquals(target.bundle.pinnedSourceSha256, PatchPolicy.sha256(source))
        val patches = BundleStore.compatible(loadPatchesFromJar(setOf(source)), target)
        assertTrue(patches.map { it.name }.containsAll(target.featuredPatches))
        assertTrue(patches.none { it.name == "GmsCore support" || it.name == "Rokid controls" })
        assertEquals("com.reddit.frontpage", target.expectedOutput(target.featuredPatches.toSet()))
        assertEquals("bundled.mpp", PatchTargets.youtube.bundle.asset)
        assertEquals("reddit.mpp", target.bundle.asset)
        assertFalse(target.bundle.updatesEnabled)
        PatchPolicy.requireDex(File(requireNotNull(System.getProperty("preparedRedditBundle"))))
    }

    @Test fun redditRejectsAnotherVersionCodeSignerAndAnyUnpublishedUpdate() {
        val target = PatchTargets.reddit
        val stock = PatchPolicy.Stock(target.stockPackage, "2026.14.0", target.stockSigners, null, 2614001)
        PatchPolicy.validate(stock, target = target)
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(stock.copy(versionCode = 2614002), target = target) }
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(stock.copy(signers = PatchTargets.youtube.stockSigners), target = target) }
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(stock, target = PatchTargets.youtube) }
        assertThrows(IllegalArgumentException::class.java) {
            PatchPolicy.metadata("""{"version":"preview","download_url":"https://github.com/Other/patches/releases/download/v1/patches.mpp"}""", target)
        }
    }
}
