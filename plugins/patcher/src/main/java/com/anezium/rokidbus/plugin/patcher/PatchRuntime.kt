package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.Patch
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

class PatchRuntime(private val target: PatchTarget = PatchTargets.default) {
    suspend fun patch(input: File, patches: Set<Patch<*>>, work: File, key: SigningKey,
                      timings: PatchTimings = PatchTimings(), progress: (PatchProgress) -> Unit): File {
        require(patches.isNotEmpty()) { "Select at least one patch." }
        val unsigned = File(work, "unsigned.apk")
        val output = File(work, "signed.apk")
        for (partial in listOf(unsigned, output)) require(!partial.exists() || partial.delete()) { "Cannot discard previous partial output." }
        var inputVersion: String? = null
        try {
            currentCoroutineContext().ensureActive()
            progress(PatchProgress(PatchPhase.READ_APK))
            timings.measure("patch_read") { Patcher(PatcherConfig(input, File(work, "patch-work"))) }.use { patcher ->
                inputVersion = patcher.context.packageMetadata.versionName
                require(patcher.context.packageMetadata.packageName == target.stockPackage && inputVersion in target.acceptedVersions) { "Prepared stock identity does not match the target." }
                patcher += patches
                var index = 0
                val completed = mutableSetOf<Patch<*>>()
                var patchStarted = timings.start()
                progress(PatchProgress(PatchPhase.APPLY_PATCHES, 0.0, patchTotal = patches.size))
                timings.measureSuspend("patch_apply_total") {
                    patcher().collect { result ->
                        currentCoroutineContext().ensureActive()
                        timings.end("patch_apply", patchStarted, if (result.exception == null) "ok" else "failed", "patch_index=${++index}")
                        patchStarted = timings.start()
                        result.exception?.let { throw IllegalStateException("${result.patch.name}: ${it.message}", it) }
                        if (result.patch in patches) {
                            completed += result.patch
                            progress(PatchProgress(PatchPhase.APPLY_PATCHES, completed.size.toDouble() / patches.size,
                                result.patch.name, completed.size, patches.size))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                progress(PatchProgress(PatchPhase.COMPILE))
                val patched = timings.measureSuspend("patch_compile") { patcher.get() }
                progress(PatchProgress(PatchPhase.WRITE))
                timings.measure("write") {
                    input.inputStream().use { source -> unsigned.outputStream().use { PatchPolicy.copyBounded(source, it) } }
                    timings.withAlignmentTiming({ progress(PatchProgress(PatchPhase.ALIGN)) }) { patched.applyTo(unsigned) }
                }
            }
            currentCoroutineContext().ensureActive()
            progress(PatchProgress(PatchPhase.SIGN))
            timings.measure("sign") { key.sign(unsigned, output) }
            currentCoroutineContext().ensureActive()
            progress(PatchProgress(PatchPhase.VERIFY))
            timings.measure("verify") {
                val verified = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(30).build().verify()
                require(verified.isVerified && verified.signerCertificates.size == 1 &&
                    PatchPolicy.sha256(verified.signerCertificates.single().encoded) == key.fingerprint()) { "Output signature verification failed." }
                ApkModule.loadApkFile(output).use { module ->
                    val expectedPackage = target.expectedOutput(patches.mapNotNull { it.name }.toSet())
                    require(module.packageName == expectedPackage && module.androidManifest.versionName == inputVersion) {
                        "Output is not the expected patched ${target.displayName}. Review the target's recommended patches."
                    }
                    require(module.listDexFiles().isNotEmpty()) { "Output has no dex files." }
                }
            }
            return output
        } catch (e: Throwable) { output.delete(); throw e }
        finally { unsigned.delete() }
    }
}
