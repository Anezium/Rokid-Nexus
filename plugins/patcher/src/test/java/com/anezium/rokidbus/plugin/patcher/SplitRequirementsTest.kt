package com.anezium.rokidbus.plugin.patcher

import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.*
import org.junit.Test

class SplitRequirementsTest {
    private fun reject(vararg modules: SplitRequirements) =
        assertThrows(IllegalArgumentException::class.java) { SplitRequirements.validate(modules.toList()) }

    @Test fun archiveOnlyRequiredBaseFailsJustLikeLooseBase() {
        SplitRequirements.validate(listOf(SplitRequirements()))
        reject(SplitRequirements(required = true))
        reject(SplitRequirements(requiredTypes = setOf("base__abi")))
        reject(SplitRequirements(dependencies = setOf("feature")))
        reject(SplitRequirements(required = true), SplitRequirements(name = "feature"))
    }

    @Test fun requiredTypesAndFeatureDependenciesMustBePresentForTheirOwner() {
        val base = SplitRequirements(required = true, requiredTypes = setOf("base__abi", "base__density"), dependencies = setOf("feature"))
        val abi = SplitRequirements("config.arm64_v8a", types = setOf("base__abi"))
        val density = SplitRequirements("config.xxhdpi", types = setOf("base__density"))
        val feature = SplitRequirements("feature", requiredTypes = setOf("feature__abi"))
        val featureAbi = SplitRequirements("feature.config.arm64_v8a", configFor = "feature", types = setOf("feature__abi"))
        SplitRequirements.validate(listOf(base, abi, density, feature, featureAbi))
        reject(base, abi, feature, featureAbi)
        reject(base, abi, density, feature)
        reject(base, abi, density, featureAbi)
        reject(base, abi, density, feature, featureAbi.copy(configFor = "missing"))
        // A base config cannot satisfy a feature requirement.
        reject(base, abi.copy(types = setOf("base__abi", "feature__abi")), density, feature)
    }

    @Test fun alternativesAndDuplicateSplitsHaveExplicitFailClosedPolicy() {
        assertTrue(reject(SplitRequirements(), SplitRequirements()).message!!.contains("single-base"))
        reject(SplitRequirements(), SplitRequirements("config.en"), SplitRequirements("config.en"))
        reject(SplitRequirements(), SplitRequirements("feature", dependencies = setOf("feature")))
        reject(SplitRequirements(), SplitRequirements("one", dependencies = setOf("two")),
            SplitRequirements("two", dependencies = setOf("one")))
    }

    @Test fun readsActualBinaryManifestModelIncludingLegacyMetadataAndUsesSplit() {
        val manifest = AndroidManifestBlock.empty().apply { newElement("manifest") }
        val root = manifest.manifestElement
        root.getOrCreateAndroidAttribute("isSplitRequired", AndroidManifest.ID_isSplitRequired).setValueAsBoolean(true)
        root.getOrCreateAndroidAttribute("requiredSplitTypes", AndroidManifest.ID_requiredSplitTypes).setValueAsString("base__abi, base__density")
        root.newElement("uses-split").getOrCreateAndroidAttribute("name", AndroidManifest.ID_name).setValueAsString("feature")
        val binary = java.nio.file.Files.createTempFile("manifest", ".xml").toFile()
        val parsed = try {
            manifest.refreshFull()
            manifest.writeBytes(binary)
            SplitRequirements.read(AndroidManifestBlock.load(binary))
        } finally { binary.delete() }
        assertTrue(parsed.required)
        assertEquals(setOf("base__abi", "base__density"), parsed.requiredTypes)
        assertEquals(setOf("feature"), parsed.dependencies)
        reject(parsed)

        val legacy = AndroidManifestBlock.empty().apply { newElement("manifest") }
        val metadata = legacy.orCreateApplicationElement.newElement("meta-data")
        metadata.getOrCreateAndroidAttribute("name", AndroidManifest.ID_name).setValueAsString("com.android.vending.splits.required")
        metadata.getOrCreateAndroidAttribute("value", AndroidManifest.ID_value).setValueAsBoolean(true)
        assertTrue(SplitRequirements.read(legacy).required)
        reject(SplitRequirements.read(legacy))
    }

    @Test fun selectionIsPerConfigurationOwnerAndCompletenessIsRechecked() {
        val modules = mapOf(
            java.io.File("base") to SplitRequirements(requiredTypes = setOf("base__density")),
            java.io.File("feature") to SplitRequirements("feature", requiredTypes = setOf("feature__density")),
            java.io.File("base-hdpi") to SplitRequirements("config.hdpi", types = setOf("base__density")),
            java.io.File("base-xxhdpi") to SplitRequirements("config.xxhdpi", types = setOf("base__density")),
            java.io.File("feature-xhdpi") to SplitRequirements("feature.config.xhdpi", configFor = "feature", types = setOf("feature__density"))
        )
        val chosen = ApkPreparer.chooseModules(modules, ApkPreparer.GLASSES_ABIS)
        assertEquals(setOf("base", "feature", "base-hdpi", "base-xxhdpi", "feature-xhdpi"), chosen.map { it.name }.toSet())
        SplitRequirements.validate(modules.filterKeys { it in chosen }.values)
        reject(*modules.filterKeys { it in chosen && it.name != "feature-xhdpi" }.values.toTypedArray())
    }

    @Test fun differentNativeAbisAcrossFeaturesAreNotMerged() {
        val modules = mapOf(
            java.io.File("base") to SplitRequirements(),
            java.io.File("feature") to SplitRequirements("feature"),
            java.io.File("base-arm64") to SplitRequirements("config.arm64_v8a"),
            java.io.File("feature-arm32") to SplitRequirements("feature.config.armeabi_v7a", configFor = "feature")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ApkPreparer.chooseModules(modules, listOf("arm64-v8a", "armeabi-v7a"))
        }
        val misleadingFeatureName = java.io.File("named-like-abi") to SplitRequirements("config.x86", isFeature = true)
        assertTrue(misleadingFeatureName.first in ApkPreparer.chooseModules(
            modules.filterKeys { it.name != "feature-arm32" } + misleadingFeatureName, ApkPreparer.GLASSES_ABIS))
    }

    @Test fun readsConfigurationOwnerAndProvidedTypesFromManifest() {
        val manifest = AndroidManifestBlock.empty().apply { newElement("manifest") }
        manifest.setSplit("feature.config.arm64_v8a", true)
        manifest.manifestElement.getOrCreateAttribute("configForSplit", 0).setValueAsString("feature")
        manifest.manifestElement.getOrCreateAndroidAttribute("splitTypes", AndroidManifest.ID_splitTypes).setValueAsString("feature__abi")
        val parsed = SplitRequirements.read(manifest)
        assertEquals("feature", parsed.configFor)
        assertFalse(parsed.isFeature)
        assertEquals(setOf("feature__abi"), parsed.types)
        reject(SplitRequirements(), parsed)
        SplitRequirements.validate(listOf(SplitRequirements(), SplitRequirements("feature"), parsed))
    }

    @Test fun unresolvedManifestRequirementsFailClosed() {
        val manifest = AndroidManifestBlock.empty().apply { newElement("manifest") }
        manifest.manifestElement.getOrCreateAndroidAttribute("requiredSplitTypes", AndroidManifest.ID_requiredSplitTypes).apply { valueType = com.reandroid.arsc.value.ValueType.REFERENCE; data = 0x7f010001 }
        assertThrows(IllegalArgumentException::class.java) { SplitRequirements.read(manifest) }
    }
}
