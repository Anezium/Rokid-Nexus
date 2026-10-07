package com.anezium.rokidbus.phone

import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.SigningCertificateLineage
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ApksigGlassesApkInspectorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        resource("test-signers.p12").use { load(it, PASSWORD.toCharArray()) }
    }
    private val inspector = ApksigGlassesApkInspector()

    @Test
    fun `API 30 verifies v2 glasses APK requiring API 31 and reads signed manifest`() {
        assertEquals(30, Build.VERSION.SDK_INT)
        val apk = signedApk(listOf("current"))
        assertTrue(ApkVerifier.Builder(apk).build().verify().isVerifiedUsingV2Scheme)
        val archive = requireNotNull(inspector.inspect(apk))
        assertEquals(PACKAGE, archive.packageName)
        assertEquals(10500L, archive.versionCode)
        assertEquals(listOf(pin("current")), archive.signingCertificates.map(::signingCertificateSha256))
        verify(apk, pin("current"))
    }

    @Test
    fun `valid APK with foreign signer fails even with matching file digest`() {
        val apk = signedApk(listOf("current"))
        assertThrows(GlassesApkVerificationException::class.java) { verify(apk, pin("old")) }
        verify(apk, "${pin("old")},${pin("current")}")
    }

    @Test
    fun `unsigned and tampered APK fail even when download digest is updated`() {
        assertThrows(GlassesApkVerificationException::class.java) { verify(unsignedApk(), pin("current")) }
        val apk = signedApk(listOf("current"))
        val bytes = apk.readBytes()
        val marker = "nexus-apk-verification-test-payload".toByteArray()
        val offset = bytes.indices.first { index ->
            index + marker.size <= bytes.size && marker.indices.all { bytes[index + it] == marker[it] }
        }
        bytes[offset] = 'X'.code.toByte()
        apk.writeBytes(bytes)
        assertThrows(GlassesApkVerificationException::class.java) { verify(apk, pin("current")) }
    }

    @Test
    fun `cryptographically valid v2 APK with two current signers fails`() {
        val apk = signedApk(listOf("old", "current"))
        assertTrue(ApkVerifier.Builder(apk).build().verify().isVerified)
        val failure = assertThrows(GlassesApkVerificationException::class.java) {
            verify(apk, "${pin("old")},${pin("current")}")
        }
        assertEquals("Glasses APK must have exactly one signing certificate.", failure.reason)
    }

    @Test
    fun `v3 rotation authorizes only current certificate rather than trusted lineage`() {
        val lineage = SigningCertificateLineage.Builder(lineageSigner("old"), lineageSigner("current"))
            .build()
        val apk = signedApk(listOf("old", "current"), lineage)
        val result = ApkVerifier.Builder(apk).build().verify()
        assertTrue(result.isVerified)
        assertTrue(result.isVerifiedUsingV3Scheme)
        assertEquals(2, result.signingCertificateLineage.certificatesInLineage.size)
        assertEquals(listOf(pin("current")), requireNotNull(inspector.inspect(apk))
            .signingCertificates.map(::signingCertificateSha256))
        assertThrows(GlassesApkVerificationException::class.java) { verify(apk, pin("old")) }
        verify(apk, pin("current"))
    }

    private fun verify(apk: File, pins: String) {
        GlassesApkVerificationPolicy.verifyDownloaded(
            apk,
            NexusReleaseAsset(NexusSemVersion(1, 5, 0), "https://example.com/glasses.apk", sha256Hex(apk)),
            inspector, PACKAGE, pins,
        )
    }

    private fun signedApk(aliases: List<String>, lineage: SigningCertificateLineage? = null): File {
        val output = temporaryFolder.newFile()
        val signer = ApkSigner.Builder(aliases.map { alias ->
            ApkSigner.SignerConfig.Builder(alias, key(alias), listOf(certificate(alias))).build()
        }).setInputApk(unsignedApk()).setOutputApk(output)
            .setV1SigningEnabled(false).setV2SigningEnabled(true)
            .setV3SigningEnabled(lineage != null).setV4SigningEnabled(false)
        if (lineage != null) signer.setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(28)
        signer.build().sign()
        return output
    }

    private fun sha256Hex(apk: File) = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unsignedApk() = temporaryFolder.newFile().apply {
        resource("unsigned.apk").use { input -> outputStream().use(input::copyTo) }
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/glasses-apk/$name"))
    private fun key(alias: String) = keyStore.getKey(alias, PASSWORD.toCharArray()) as PrivateKey
    private fun certificate(alias: String) = keyStore.getCertificate(alias) as X509Certificate
    private fun pin(alias: String) = signingCertificateSha256(certificate(alias).encoded)
    private fun lineageSigner(alias: String) = SigningCertificateLineage.SignerConfig.Builder(key(alias), certificate(alias)).build()

    private companion object {
        const val PASSWORD = "test-only"
        const val PACKAGE = "com.anezium.rokidbus.glasses"
    }
}
