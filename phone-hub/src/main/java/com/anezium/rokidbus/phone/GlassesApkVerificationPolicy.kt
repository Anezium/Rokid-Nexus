package com.anezium.rokidbus.phone

import java.io.File
import java.io.IOException

internal sealed interface GlassesApkVerdict {
    data object Accept : GlassesApkVerdict

    data class Reject(val reason: String) : GlassesApkVerdict
}

/** Only fixed verification reasons may be shown in the installation UI. */
internal class GlassesApkVerificationException(val reason: String) : IOException(reason)

/** Every check is required, including on phones unable to parse a newer APK. */
internal object GlassesApkVerificationPolicy {
    fun verifyDownloaded(
        apk: File,
        release: NexusReleaseAsset,
        inspector: ArtifactPackageInspector,
        expectedPackageName: String,
        expectedSignerSha256: String,
    ) {
        val digest = release.sha256?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw GlassesApkVerificationException("Latest glasses release has no valid SHA-256 digest.")
        if (!PluginInstaller.sha256Matches(apk, digest)) {
            throw GlassesApkVerificationException("Glasses APK SHA-256 verification failed.")
        }
        val archive = runCatching { inspector.inspect(apk) }.getOrNull()
        val verdict = verdict(
            archive, expectedPackageName, release.version, expectedSignerSha256,
        )
        if (verdict is GlassesApkVerdict.Reject) throw GlassesApkVerificationException(verdict.reason)
    }

    fun verdict(
        archive: ArtifactArchiveInfo?,
        expectedPackageName: String,
        expectedVersion: NexusSemVersion,
        expectedSignerSha256: String,
    ): GlassesApkVerdict {
        if (archive == null) return GlassesApkVerdict.Reject("Glasses APK signature or manifest could not be verified.")
        if (archive.packageName != expectedPackageName) {
            return GlassesApkVerdict.Reject("Glasses APK package does not match Nexus.")
        }
        val pins = expectedSignerSha256.split(',')
        if (pins.any { !it.matches(Regex("[0-9a-fA-F]{64}")) }) {
            return GlassesApkVerdict.Reject("Glasses APK signer pin is not configured correctly.")
        }
        if (archive.signingCertificates.size != 1) {
            return GlassesApkVerdict.Reject("Glasses APK must have exactly one signing certificate.")
        }
        val signer = signingCertificateSha256(archive.signingCertificates.single())
        if (pins.none { it.equals(signer, ignoreCase = true) }) {
            return GlassesApkVerdict.Reject("Glasses APK signer does not match this phone build.")
        }
        val expectedCode = releaseVersionCode(expectedVersion)
        if (expectedCode == null || archive.versionCode != expectedCode) {
            return GlassesApkVerdict.Reject("Glasses APK version does not match the release.")
        }
        return GlassesApkVerdict.Accept
    }

    // The hub release convention is major * 10000 + minor * 100 + patch.
    private fun releaseVersionCode(version: NexusSemVersion): Long? {
        if (version.major !in 0..210000L || version.minor !in 0..99L || version.patch !in 0..99L) {
            return null
        }
        return (version.major * 10000 + version.minor * 100 + version.patch)
            .takeIf { it in 1..2100000000L }
    }
}
