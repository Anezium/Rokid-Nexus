package com.anezium.rokidbus.plugin.youtubepatcher

import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ResConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the real morphe-patcher 1.7.0 ApkMerger over synthetic ARSCLib-built splits. */
class ApkMergeTest {
    private val pkg = "example.test.merge"
    private val densities = listOf("hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private fun ResXmlElement.named(tag: String, name: String) = newElement(tag).also {
        it.getOrCreateAndroidAttribute("name", AndroidManifest.ID_name).setValueAsString(name)
    }

    private fun apk(file: File, split: String?, qualifiers: String?, extra: Map<String, ByteArray> = emptyMap(),
                    manifest: (AndroidManifestBlock) -> Unit = {}) = ApkModule().use { module ->
        val block = AndroidManifestBlock.empty().apply {
            packageName = pkg; versionName = "1.0"; versionCode = 7; minSdkVersion = 30; targetSdkVersion = 36
            val application = getOrCreateApplicationElement()
            if (split == null) {
                // ARSCLib recognises the base module by its launcher activity, as in stock YouTube.
                application.named("activity", "$pkg.Main").newElement("intent-filter").apply {
                    named("action", "android.intent.action.MAIN")
                    named("category", "android.intent.category.LAUNCHER")
                }
            } else setSplit(split, true)
        }
        manifest(block)
        module.setManifest(block)
        if (qualifiers != null) {
            val table = TableBlock()
            val path = "res/drawable${if (qualifiers.isEmpty()) "" else "-$qualifiers"}/icon.png"
            table.newPackage(0x7f, pkg).getOrCreate(ResConfig.parse(qualifiers), "drawable", "icon").setValueAsString(path)
            module.setTableBlock(table)
            module.add(ByteInputSource(qualifiers.toByteArray() + 1, path))
        }
        extra.forEach { (path, bytes) -> module.add(ByteInputSource(bytes, path)) }
        module.writeApk(file)
    }

    @Test fun realMergerCombinesEveryDensitySplitWithTheGlassesAbiIntoOneStandaloneApk() {
        val work = Files.createTempDirectory("merge-density").toFile()
        try {
            val splits = File(work, "splits").apply { mkdirs() }
            apk(File(splits, "split-0.apk"), null, "")
            densities.forEachIndexed { index, density -> apk(File(splits, "split-${index + 1}.apk"), "config.$density", density) }
            apk(File(splits, "split-9.apk"), "config.arm64_v8a", null, mapOf("lib/arm64-v8a/libexample.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46)))
            val requirements = splits.listFiles()!!.associateWith { file -> ApkModule.loadApkFile(file).use { SplitRequirements.read(it.androidManifest) } }
            assertEquals(requirements.keys, ApkPreparer.chooseModules(requirements, ApkPreparer.GLASSES_ABIS))
            SplitRequirements.validate(requirements.values)

            val output = File(work, "merged.apk")
            ApkPreparer.merge(splits, output)
            ApkPreparer.requireGlassesNative(output)
            ApkModule.loadApkFile(output).use { merged ->
                assertEquals(pkg, merged.packageName)
                assertNull(merged.androidManifest.split)
                val entries = merged.tableBlock.getResource(pkg, "drawable", "icon")!!.toList()
                assertEquals(setOf("") + densities, entries.map { it.resConfig.qualifiers.removePrefix("-") }.toSet())
                for (entry in entries) assertTrue(entry.valueAsString, merged.containsFile(entry.valueAsString))
                assertTrue(merged.containsFile("lib/arm64-v8a/libexample.so"))
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun realMergerStripsEverySplitDeclarationBeforeTheStandaloneCheck() {
        val work = Files.createTempDirectory("merge-sanitize").toFile()
        try {
            val splits = File(work, "splits").apply { mkdirs() }
            apk(File(splits, "split-0.apk"), null, "") { manifest ->
                val root = manifest.manifestElement
                root.getOrCreateAndroidAttribute("requiredSplitTypes", AndroidManifest.ID_requiredSplitTypes).setValueAsString("base__density")
                root.getOrCreateAndroidAttribute("isSplitRequired", AndroidManifest.ID_isSplitRequired).setValueAsBoolean(true)
                root.named("uses-split", "config.hdpi")
                val application = manifest.applicationElement
                application.getOrCreateAndroidAttribute("isSplitRequired", AndroidManifest.ID_isSplitRequired).setValueAsBoolean(true)
                application.getOrCreateAndroidAttribute("requiredSplitTypes", AndroidManifest.ID_requiredSplitTypes).setValueAsString("base__density")
                application.named("meta-data", "com.android.vending.splits.required")
                    .getOrCreateAndroidAttribute("value", AndroidManifest.ID_value).setValueAsBoolean(true)
                application.named("meta-data", "com.android.vending.splits")
                    .getOrCreateAndroidAttribute("value", AndroidManifest.ID_value).setValueAsString("splits0")
            }
            apk(File(splits, "split-1.apk"), "config.hdpi", "hdpi") { manifest ->
                manifest.manifestElement.getOrCreateAndroidAttribute("splitTypes", AndroidManifest.ID_splitTypes).setValueAsString("base__density")
            }
            val inputs = splits.listFiles()!!.map { file -> ApkModule.loadApkFile(file).use { SplitRequirements.read(it.androidManifest) } }
            val base = inputs.single { it.name == null }
            assertTrue(base.required)
            assertEquals(setOf("base__density"), base.requiredTypes)
            assertEquals(setOf("config.hdpi"), base.dependencies)
            SplitRequirements.validate(inputs)

            val output = File(work, "merged.apk")
            ApkPreparer.merge(splits, output)
            ApkModule.loadApkFile(output).use { merged ->
                val manifest = merged.androidManifest
                assertEquals(SplitRequirements(), SplitRequirements.read(manifest))
                assertTrue(manifest.applicationElement.listElements("meta-data").none { child ->
                    AndroidManifestBlock.getAndroidNameValue(child as ResXmlElement).orEmpty().startsWith("com.android.vending")
                })
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun leftoverSplitDeclarationsFailTheStandaloneCheck() {
        fun manifest(configure: (AndroidManifestBlock) -> Unit) = AndroidManifestBlock.empty().apply {
            packageName = pkg
            getOrCreateApplicationElement()
            configure(this)
        }
        ApkPreparer.requireStandalone(manifest {})
        val leftovers = listOf<(AndroidManifestBlock) -> Unit>(
            { it.setSplit("config.hdpi", true) },
            { it.manifestElement.getOrCreateAndroidAttribute("isSplitRequired", AndroidManifest.ID_isSplitRequired).setValueAsBoolean(true) },
            { it.applicationElement.getOrCreateAndroidAttribute("isSplitRequired", AndroidManifest.ID_isSplitRequired).setValueAsBoolean(true) },
            { it.manifestElement.getOrCreateAndroidAttribute("requiredSplitTypes", AndroidManifest.ID_requiredSplitTypes).setValueAsString("base__abi") },
            { it.manifestElement.getOrCreateAndroidAttribute("splitTypes", AndroidManifest.ID_splitTypes).setValueAsString("base__abi") },
            { it.manifestElement.named("uses-split", "feature") },
            { it.manifestElement.getOrCreateAndroidAttribute("isFeatureSplit", AndroidManifest.ID_isFeatureSplit).setValueAsBoolean(true) },
            { it.applicationElement.named("meta-data", "com.android.vending.splits").getOrCreateAndroidAttribute("value", AndroidManifest.ID_value).setValueAsString("x") },
            { it.applicationElement.named("meta-data", "com.android.vending.splits.required").getOrCreateAndroidAttribute("value", AndroidManifest.ID_value).setValueAsBoolean(false) },
        )
        for (leftover in leftovers) {
            assertThrows(IllegalArgumentException::class.java) { ApkPreparer.requireStandalone(manifest(leftover)) }
        }
    }

    @Test fun nativeCodeMustIncludeTheGlassesAbi() {
        val work = Files.createTempDirectory("merge-abi").toFile()
        try {
            val plain = File(work, "plain.apk").also { apk(it, null, "") }
            ApkPreparer.requireGlassesNative(plain)
            val arm64 = File(work, "arm64.apk").also { apk(it, null, "", mapOf("lib/arm64-v8a/libx.so" to byteArrayOf(1), "lib/armeabi-v7a/libx.so" to byteArrayOf(1))) }
            ApkPreparer.requireGlassesNative(arm64)
            val arm32 = File(work, "arm32.apk").also { apk(it, null, "", mapOf("lib/armeabi-v7a/libx.so" to byteArrayOf(1))) }
            assertThrows(IllegalArgumentException::class.java) { ApkPreparer.requireGlassesNative(arm32) }
        } finally { work.deleteRecursively() }
    }
}
