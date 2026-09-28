package com.anezium.rokidbus.phone

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile

/** Verifies the glasses APK independently of the phone's supported manifest SDK. */
internal class ApksigGlassesApkInspector : ArtifactPackageInspector {
    override fun inspect(apk: File): ArtifactArchiveInfo? = RandomAccessFile(apk, "r").use { file ->
        val source = DataSources.asDataSource(file)
        val result = ApkVerifier.Builder(source).build().verify()
        if (!result.isVerified) return null
        val manifest = ApkUtils.getAndroidManifest(source)
        ArtifactArchiveInfo(
            packageName = ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest.duplicate()),
            versionCode = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(manifest.duplicate()),
            signingCertificates = result.signerCertificates.map { it.encoded },
        )
    }
}
