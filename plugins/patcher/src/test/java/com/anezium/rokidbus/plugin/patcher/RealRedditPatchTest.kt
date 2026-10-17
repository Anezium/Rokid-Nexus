package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** Opt-in verification through Nexus' real preparer, patcher 1.7.0, writer and signing key. */
class RealRedditPatchTest {
    @Test(timeout = 900_000) fun genuineCompleteBundlePatchesAndSignsWithTheNexusEngine(): Unit = runBlocking {
        val stock = File(System.getProperty("redditStockApkm", "").orEmpty())
        assumeTrue("Pass -PredditStockApkm=/path/to/the/official/complete.apkm", stock.isFile)
        assertEquals("1f6a8939589fb88205a857fb1efd00b7fa9304347ee7ced50d4add10f342da3b", PatchPolicy.sha256(stock))
        val source = File(requireNotNull(System.getProperty("redditBundleFixture")))
        val target = PatchTargets.reddit
        assertEquals(target.bundle.pinnedSourceSha256, PatchPolicy.sha256(source))
        val tempParent = File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile
        val directory = Files.createTempDirectory(tempParent.toPath(), "reddit-real-patch-").toFile()
        try {
            val timings = PatchTimings(sink = {})
            val prepared = ApkPreparer(target = target).prepare(stock, File(directory, "prepare"), timings) {}
            val patches = BundleStore.compatible(loadPatchesFromJar(setOf(source)), target)
                .filter { it.name in target.featuredPatches }.toSet()
            assertEquals(target.featuredPatches.toSet(), patches.map { it.name }.toSet())
            val work = File(directory, "patch").apply { mkdirs() }
            val result = PatchRuntime(target).patch(prepared, patches, work,
                SigningKey(File(directory, "signing.p12")), timings) {}
            assertTrue(result.length() > 0)
            val dex = DexFileFactory.loadDexContainer(result, Opcodes.getDefault())
            for (type in listOf(
                "Lapp/morphe/extension/reddit/rokid/nativebridge/NativePopular;",
                "Lapp/morphe/extension/reddit/rokid/RokidRingController;",
            )) assertTrue("The patched APK must contain $type",
                dex.dexEntryNames.any { name ->
                    requireNotNull(dex.getEntry(name)).dexFile.classes.any {
                        it.type == type
                    }
                })
            ZipFile(result).use { zip ->
                val marker = zip.getEntry("assets/rokid/reddit.json")
                assertNotNull(marker)
                assertTrue(zip.getInputStream(marker).use { it.readBytes().toString(Charsets.UTF_8) }.contains("rokid-reddit-1"))
                assertTrue(zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .all { it.name.startsWith("lib/arm64-v8a/") })
            }
            System.getProperty("redditTestOutput", "").orEmpty().takeIf { it.isNotBlank() }?.let { path ->
                val output = File(path)
                require(output.isAbsolute && output.extension == "apk" && requireNotNull(output.parentFile).isDirectory)
                Files.copy(result.toPath(), output.toPath())
            }
        } finally {
            check(directory.canonicalFile.parentFile == tempParent && directory.name.startsWith("reddit-real-patch-"))
            directory.deleteRecursively()
        }
    }
}
