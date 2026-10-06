package com.anezium.rokidbus.plugin.youtubepatcher

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.Patch
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

class PatchRuntime {
    suspend fun patch(input: File, patches: Set<Patch<*>>, work: File, key: SigningKey,
                      timings: PatchTimings = PatchTimings(), progress: (String) -> Unit): File {
        require(patches.isNotEmpty()) { "Select at least one patch." }
        val unsigned = File(work, "unsigned.apk")
        val output = File(work, "signed.apk")
        try {
            currentCoroutineContext().ensureActive()
            progress("Reading APK")
            timings.measure("patch_read") { Patcher(PatcherConfig(input, File(work, "patch-work"))) }.use { patcher ->
                patcher += patches
                var index = 0
                var patchStarted = timings.start()
                timings.measureSuspend("patch_apply_total") { patcher().collect { result ->
                    currentCoroutineContext().ensureActive()
                    timings.end("patch_apply", patchStarted, if (result.exception == null) "ok" else "failed", "patch_index=${++index}")
                    patchStarted = timings.start()
                    result.exception?.let { throw IllegalStateException("${result.patch.name}: ${it.message}", it) }
                    progress("Applying patches: ${result.patch.name}")
                } }
                currentCoroutineContext().ensureActive()
                progress("Writing APK")
                val patched = timings.measureSuspend("patch_compile") { patcher.get() }
                timings.measure("write") {
                    input.inputStream().use { source -> unsigned.outputStream().use { PatchPolicy.copyBounded(source, it) } }
                    timings.withAlignmentTiming { patched.applyTo(unsigned) }
                }
            }
            currentCoroutineContext().ensureActive()
            progress("Signing APK")
            timings.measure("sign") { key.sign(unsigned, output) }
            currentCoroutineContext().ensureActive()
            timings.measure("verify") {
            val verified = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(30).build().verify()
            require(verified.isVerified && verified.signerCertificates.size == 1 &&
                PatchPolicy.sha256(verified.signerCertificates.single().encoded) == key.fingerprint()) { "Output signature verification failed." }
            ApkModule.loadApkFile(output).use { module ->
                val expectedPackage = if (patches.any { it.name == "GmsCore support" }) "app.morphe.android.youtube" else "com.google.android.youtube"
                require(module.packageName == expectedPackage && module.androidManifest.versionName == PatchPolicy.VERSION) {
                    "Output is not the expected patched YouTube. Select GmsCore support."
                }
                require(module.listDexFiles().isNotEmpty()) { "Output has no dex files." }
            }
            }
            return output
        } catch (e: Throwable) { output.delete(); throw e }
        finally { unsigned.delete() }
    }
}
