package com.anezium.rokidbus.phone

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlassesApkVerificationPolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val signer = byteArrayOf(1, 2, 3)
    private val pin = signingCertificateSha256(signer)
    private val version = NexusSemVersion(1, 5, 0)
    private val archive = ArtifactArchiveInfo(GLASSES_PACKAGE, 10500, listOf(signer), "1.5.0")

    private fun verdict(
        actual: ArtifactArchiveInfo? = archive,
        expectedPin: String = pin,
        expectedVersion: NexusSemVersion = version,
    ) = GlassesApkVerificationPolicy.verdict(
        archive = actual,
        expectedPackageName = GLASSES_PACKAGE,
        expectedVersion = expectedVersion,
        expectedSignerSha256 = expectedPin,
    )

    @Test
    fun `matching archive is accepted with any configured current signer pin`() {
        assertEquals(GlassesApkVerdict.Accept, verdict())
        assertEquals(GlassesApkVerdict.Accept, verdict(expectedPin = pin.uppercase()))
        assertEquals(GlassesApkVerdict.Accept, verdict(expectedPin = "${"ab".repeat(32)},$pin"))
        assertEquals(GlassesApkVerdict.Accept, verdict(expectedPin = "$pin,${"ab".repeat(32)}"))
    }

    @Test
    fun `unreadable archive is rejected without any phone SDK exception`() {
        assertEquals(
            GlassesApkVerdict.Reject("Glasses APK signature or manifest could not be verified."),
            verdict(actual = null),
        )
    }

    @Test
    fun `wrong package is rejected`() {
        assertEquals(
            GlassesApkVerdict.Reject("Glasses APK package does not match Nexus."),
            verdict(archive.copy(packageName = "com.example.impostor")),
        )
    }

    @Test
    fun `foreign current signer is rejected even when pinned signer is in its history`() {
        assertEquals(
            GlassesApkVerdict.Reject("Glasses APK signer does not match this phone build."),
            verdict(archive.copy(signingCertificates = listOf(byteArrayOf(4)), signingCertificateHistory = listOf(signer))),
        )
    }

    @Test
    fun `missing and multiple current signers are rejected`() {
        for (certificates in listOf(emptyList(), listOf(signer, signer))) {
            assertEquals(
                GlassesApkVerdict.Reject("Glasses APK must have exactly one signing certificate."),
                verdict(archive.copy(signingCertificates = certificates)),
            )
        }
    }

    @Test
    fun `blank malformed and foreign pins fail closed`() {
        for (invalid in listOf("", " ", "ab", "zz".repeat(32), ",", "$pin,", ",$pin", "$pin,,${"ab".repeat(32)}", "$pin,ab", "$pin, $pin")) {
            assertEquals(
                GlassesApkVerdict.Reject("Glasses APK signer pin is not configured correctly."),
                verdict(expectedPin = invalid),
            )
        }
        assertEquals(
            GlassesApkVerdict.Reject("Glasses APK signer does not match this phone build."),
            verdict(expectedPin = "ab".repeat(32)),
        )
    }

    @Test
    fun `signed APK version code must match the release`() {
        for (invalid in listOf(
            archive.copy(versionCode = 0), archive.copy(versionCode = 10411),
            archive.copy(versionCode = 10501),
        )) {
            assertEquals(
                GlassesApkVerdict.Reject("Glasses APK version does not match the release."),
                verdict(invalid),
            )
        }
    }

    @Test
    fun `version name does not gate a matching signed version code`() {
        assertEquals(GlassesApkVerdict.Accept, verdict(archive.copy(versionName = null)))
        assertEquals(GlassesApkVerdict.Accept, verdict(archive.copy(versionName = "1.4.11")))
    }

    @Test
    fun `version code encoding rejects ambiguous or overflowing release versions`() {
        for (invalid in listOf(
            NexusSemVersion(1, 100, 0), NexusSemVersion(1, 0, 100),
            NexusSemVersion(Long.MAX_VALUE, 0, 0), NexusSemVersion(210000, 0, 1),
        )) {
            assertEquals(
                GlassesApkVerdict.Reject("Glasses APK version does not match the release."),
                verdict(expectedVersion = invalid),
            )
        }
    }

    @Test
    fun `download verification requires a valid digest before archive inspection`() {
        val apk = temporaryFolder.newFile().apply { writeText("downloaded APK") }
        for (invalid in listOf(null, "", "sha256:${"ab".repeat(32)}", "ab".repeat(32))) {
            var inspected = false
            assertThrows(GlassesApkVerificationException::class.java) {
                verify(apk, invalid) { inspected = true; archive }
            }
            assertFalse(inspected)
        }
    }

    @Test
    fun `matching digest cannot bypass failed inspection or signer verification`() {
        val apk = temporaryFolder.newFile().apply { writeText("downloaded APK") }
        val digest = sha256Hex(apk)
        assertThrows(GlassesApkVerificationException::class.java) { verify(apk, digest) { null } }
        val failure = assertThrows(GlassesApkVerificationException::class.java) {
            verify(apk, digest) { throw IllegalArgumentException("untrusted parser detail") }
        }
        assertEquals("Glasses APK signature or manifest could not be verified.", failure.reason)
        assertThrows(GlassesApkVerificationException::class.java) {
            verify(apk, digest) { archive.copy(signingCertificates = listOf(byteArrayOf(4))) }
        }
    }

    @Test
    fun `downloaded file with matching digest and verified archive is accepted`() {
        val apk = temporaryFolder.newFile().apply { writeText("downloaded APK") }
        val digest = sha256Hex(apk)
        verify(apk, digest) { archive }
    }

    private fun sha256Hex(apk: File) = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun verify(apk: File, digest: String?, inspect: (File) -> ArtifactArchiveInfo?) {
        GlassesApkVerificationPolicy.verifyDownloaded(
            apk, NexusReleaseAsset(version, "https://example.com/glasses.apk", digest),
            ArtifactPackageInspector(inspect), GLASSES_PACKAGE, pin,
        )
    }

    private companion object {
        private const val GLASSES_PACKAGE = "com.anezium.rokidbus.glasses"
    }
}
