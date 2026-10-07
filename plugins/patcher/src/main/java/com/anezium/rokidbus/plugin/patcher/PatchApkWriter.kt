/*
 * Adapted from Morphe Patcher 1.7.0 ApkUtils.applyTo (GPLv3).
 * https://github.com/MorpheApp/morphe-patcher/blob/v1.7.0/src/main/kotlin/app/morphe/patcher/apk/ApkUtils.kt
 * Original fork: revanced/revanced-library, 06733072045c8016a75f232dec76505c0ba2e1cd.
 * Modified here to observe physical ZIP writes and expose progress.
 */
package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.PatcherResult
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.StoredEntry
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileExtension
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.File

internal object PatchApkWriter {
    fun apply(result: PatcherResult, output: File, timings: PatchTimings, report: (PatchProgress) -> Unit) {
        val options = ZFileOptions().setAlignmentRule(AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096), AlignmentRules.constant(4)))
        var writing = false
        var bytes = 0L
        var lastReport = 0L
        var step = PatchSubstep.WRITE_ENTRIES
        val archive = object : ZFile(output, options) {
            override fun directWrite(offset: Long, data: ByteArray, start: Int, length: Int) {
                super.directWrite(offset, data, start, length)
                if (writing) {
                    bytes += length
                    val now = System.nanoTime()
                    if (now - lastReport >= 500_000_000) {
                        report(PatchProgress(PatchPhase.WRITE, substep = step, completedBytes = bytes))
                        lastReport = now
                    }
                }
            }
        }
        archive.use { apk ->
            report(PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.STAGE_RESOURCES))
            result.resources.resourcesApk?.let { resourceFile ->
                ZFile.openReadOnly(resourceFile).use { resources ->
                    apk.entries().filter { it.centralDirectoryHeader.name.startsWith("res/") }.forEach(StoredEntry::delete)
                    apk.mergeFrom(resources) { name -> (name.startsWith("classes") && name.endsWith(".dex")) || name in result.resources.deleteResources }
                }
            }
            result.resources.otherResources?.let { other ->
                apk.addAllRecursively(other) { file -> file.relativeTo(other).invariantSeparatorsPath !in result.resources.doNotCompress }
            }
            apk.entries().filter { it.centralDirectoryHeader.name in result.resources.deleteResources }.forEach(StoredEntry::delete)
            report(PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.STAGE_DEX))
            result.dexFiles.forEach { dex -> dex.stream.use { apk.add(dex.name, it) } }
            report(PatchProgress(PatchPhase.ALIGN))
            timings.measure("align") { apk.realign() }
            apk.addZFileExtension(object : ZFileExtension() {
                override fun entriesWritten() {
                    step = PatchSubstep.WRITE_DIRECTORY
                    report(PatchProgress(PatchPhase.WRITE, substep = step, completedBytes = bytes))
                }
            })
            report(PatchProgress(PatchPhase.WRITE, substep = PatchSubstep.COMPRESS))
            writing = true
        }
        report(PatchProgress(PatchPhase.WRITE, substep = step, completedBytes = bytes))
    }
}
