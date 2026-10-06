package com.anezium.rokidbus.plugin.youtubepatcher

import app.morphe.patcher.patch.loadPatchesFromJar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RealBundleTest {
    @Test fun genuineRokidReleaseLoadsWithPinnedPatcher() {
        val fixture = File(requireNotNull(System.getProperty("patchBundleFixture")))
        assertTrue("Real bundle missing: pass -PpatchBundleInput=/path/to/patches-1.39.1-rokid.2.mpp", fixture.isFile)
        assertEquals("d07e9aae4a5b9fffdd8e2eb81dfcdf8f0305805a9b777ac094a5065d96df6601", PatchPolicy.sha256(fixture))
        val all = loadPatchesFromJar(setOf(fixture))
        val compatible = BundleStore.compatible(all)
        assertEquals(137, all.size)
        assertEquals(80, compatible.size)
        assertEquals(76, compatible.count { it.default })
        for (name in listOf("GmsCore support", "Hide ads", "SponsorBlock")) {
            assertTrue("Missing default $name", compatible.any { it.name == name && it.default })
        }
        assertTrue(compatible.any { it.name!!.contains("Rokid") && it.default })
        // Published source really lacks DEX. Android preparation must not be skipped.
        assertThrows(IllegalArgumentException::class.java) { PatchPolicy.requireDex(fixture) }
        val prepared = File(requireNotNull(System.getProperty("preparedBundle")))
        PatchPolicy.requireDex(prepared)
        java.util.zip.ZipFile(prepared).use { zip ->
            val dex = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readNBytes(8) }
            assertTrue(dex.toString(Charsets.US_ASCII).startsWith("dex\n"))
            assertNotNull(zip.getEntry("license/MORPHE_LICENSE_NOTICE.TXT"))
        }
        // Conversion retains real JVM patches/resources as well as adding Android DEX.
        assertEquals(137, loadPatchesFromJar(setOf(prepared)).size)
    }

    @Test fun gmsCoreSupportDefaultsToThePackageThatPatchRuntimeExpects() {
        val fixture = File(requireNotNull(System.getProperty("patchBundleFixture")))
        val gms = BundleStore.compatible(loadPatchesFromJar(setOf(fixture))).single { it.name == "GmsCore support" }
        val seen = mutableSetOf<app.morphe.patcher.patch.Patch<*>>()
        fun walk(patch: app.morphe.patcher.patch.Patch<*>): List<Pair<String, Any?>> =
            if (!seen.add(patch)) emptyList()
            else patch.options.map { (name, option) -> name to option.default } + patch.dependencies.flatMap(::walk)
        // The only package option is a "Default" sentinel; the YouTube GmsCore resource patch
        // resolves it through setOrGetFallbackPackageName with its Morphe package name.
        assertEquals(listOf("packageName" to "Default"), walk(gms).filter { (name, _) -> name.contains("package", true) })
        java.net.URLClassLoader(arrayOf(fixture.toURI().toURL()), javaClass.classLoader).use { loader ->
            val morphePackage = loader.loadClass("app.morphe.patches.youtube.misc.gms.Constants")
                .getField("MORPHE_YOUTUBE_PACKAGE_NAME").get(null)
            assertEquals("app.morphe.android.youtube", morphePackage)
            val fallback = loader.loadClass("app.morphe.patches.all.misc.clone.CloneAppPatchKt")
                .getMethod("setOrGetFallbackPackageName", String::class.java)
            assertEquals("app.morphe.android.youtube", fallback.invoke(null, morphePackage))
        }
    }
}
