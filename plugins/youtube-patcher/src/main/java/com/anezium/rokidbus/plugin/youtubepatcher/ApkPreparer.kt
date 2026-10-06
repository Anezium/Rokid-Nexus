package com.anezium.rokidbus.plugin.youtubepatcher

import app.morphe.patcher.apk.ApkMerger
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File
import java.util.zip.ZipFile

class ApkPreparer(private val abis: List<String> = GLASSES_ABIS) {
    fun prepare(input: File, work: File): File {
        work.mkdirs()
        val isApk = ZipFile(input).use { it.getEntry("AndroidManifest.xml") != null }
        if (isApk) {
            val stock = inspect(input)
            PatchPolicy.validate(stock)
            ApkModule.loadApkFile(input).use { module ->
                SplitRequirements.validate(listOf(SplitRequirements.read(module.androidManifest)))
            }
            requireGlassesNative(input)
            return input
        }
        val directory = File(work, "splits").apply { mkdirs() }
        val files = extract(input, directory)
        val inspected = files.associateWith(::inspect)
        val bases = inspected.filterValues { it.split.isNullOrBlank() }
        require(bases.size == 1) { "Only single-base archives are supported; alternative base/universal APKS sets are not supported." }
        val stock = bases.values.single()
        PatchPolicy.validate(stock)
        inspected.values.forEach {
            PatchPolicy.validate(it.copy(version = it.version ?: stock.version), allowSplit = true)
            require(it.versionCode == stock.versionCode) { "Bundle mixes APK versions." }
        }
        val requirements = files.associateWith { file ->
            ApkModule.loadApkFile(file).use { SplitRequirements.read(it.androidManifest) }
        }
        SplitRequirements.validate(requirements.values)
        // Choose the ABI separately for each feature's configuration group.
        val chosen = chooseModules(requirements, abis)
        require(bases.keys.single() in chosen)
        SplitRequirements.validate(requirements.filterKeys { it in chosen }.values)
        for (file in files) if (file !in chosen) require(file.delete())
        val output = File(work, "merged.apk")
        merge(directory, output)
        // A merge is necessarily unsigned: signatures were verified on every input above.
        ApkModule.loadApkFile(output).use { module ->
            require(module.packageName == stock.packageName && module.androidManifest.versionName == stock.version)
        }
        requireGlassesNative(output)
        return output
    }
    companion object {
        // The patched app runs on the glasses, not on this phone. The glasses are arm64.
        val GLASSES_ABIS = listOf("arm64-v8a")

        internal fun merge(directory: File, output: File) {
            ApkMerger().merge(directory, output, validateModules = true)
            ApkModule.loadApkFile(output).use { module -> requireStandalone(module.androidManifest) }
        }

        // The glasses installer has only this one APK: any leftover split declaration
        // would fail there with INSTALL_FAILED_MISSING_SPLIT.
        internal fun requireStandalone(manifest: AndroidManifestBlock) {
            val merged = SplitRequirements.read(manifest)
            require(merged.name == null && merged.configFor == null && !merged.isFeature && !merged.required &&
                merged.requiredTypes.isEmpty() && merged.types.isEmpty() && merged.dependencies.isEmpty()) {
                "Split merge did not produce a standalone APK."
            }
            val splitMetadata = manifest.applicationElement?.listElements("meta-data")?.any { child ->
                AndroidManifestBlock.getAndroidNameValue(child as ResXmlElement)?.startsWith("com.android.vending.splits") == true
            } ?: false
            require(!splitMetadata) { "Split merge did not produce a standalone APK." }
        }

        fun requireGlassesNative(file: File) = ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
                .mapNotNull { it.split('/').getOrNull(1) }.toSet()
            require(abis.isEmpty() || GLASSES_ABIS.any { it in abis }) { "This YouTube APK has no arm64-v8a native code for the glasses." }
        }
        fun inspect(file: File): PatchPolicy.Stock {
            // Google rotated YouTube's key for API 33+ (v3.1); the glasses run API 32, where
            // the original certificate is the one Android checks.
            val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(30)
                .setMaxCheckedPlatformVersion(32).build().verify()
            require(verified.isVerified) { "APK signature verification failed." }
            val signers = verified.signerCertificates.map { PatchPolicy.sha256(it.encoded) }.toSet()
            return ApkModule.loadApkFile(file).use { module ->
                val manifest = module.androidManifest ?: error("APK manifest missing.")
                PatchPolicy.Stock(module.packageName, manifest.versionName, signers, manifest.split, module.versionCode.toLong())
            }
        }
        fun extract(input: File, directory: File): List<File> = ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.toList()
            require(entries.size in 1..128) { "Invalid split count." }
            var remaining = PatchPolicy.MAX_BYTES
            entries.mapIndexed { index, entry ->
                require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." } && !entry.name.contains('\\')) { "Unsafe archive path." }
                // Never use untrusted ZIP paths for extraction.
                File(directory, "split-$index.apk").also { file ->
                    zip.getInputStream(entry).use { source -> file.outputStream().use { PatchPolicy.copyBounded(source, it, remaining) } }
                    remaining -= file.length()
                }
            }
        }
        internal fun chooseModules(files: Map<File, SplitRequirements>, abis: List<String>): Set<File> {
            val configurations = files.filterValues { it.name != null && !it.isFeature }
            val groups = configurations.entries.groupBy {
                it.value.configFor?.takeUnless { owner -> owner == "base" }.orEmpty()
            }.values
            val abiNames = setOf("arm64_v8a", "armeabi_v7a", "x86_64", "x86")
            val nativeGroups = groups.map { group ->
                group.mapNotNull { it.value.name?.substringAfterLast('.') }.filter { it in abiNames }.toSet()
            }.filter { it.isNotEmpty() }
            val common = nativeGroups.reduceOrNull { left, right -> left intersect right }
            val abi = abis.firstOrNull { it.replace('-', '_') in (common ?: emptySet()) }
            require(common == null || abi != null) { "Bundle has no single glasses-compatible ABI across required modules." }
            val chosen = groups.flatMap { group ->
                chooseSplits(group.associate { it.key to it.value.name }, abi?.let { listOf(it) } ?: abis)
            }.toSet()
            return chosen + files.filterValues { it.name == null || it.isFeature }.keys
        }

        // No glasses screen density is known here, so every density split is kept and the
        // merge is density-universal rather than tuned to this phone's screen.
        fun chooseSplits(files: Map<File, String?>, abis: List<String>): Set<File> {
            val abiNames = listOf("arm64_v8a", "armeabi_v7a", "x86_64", "x86")
            val available = files.values.mapNotNull { it?.substringAfterLast('.') }.toSet()
            val abi = abis.map { it.replace('-', '_') }.firstOrNull { it in available }
            require(available.none { it in abiNames } || abi != null) { "Bundle has no arm64-v8a native split for the glasses." }
            return files.filter { (_, split) ->
                val suffix = split?.substringAfterLast('.')
                suffix !in abiNames || suffix == abi // Keep density, language, and feature modules.
            }.keys
        }
    }
}
