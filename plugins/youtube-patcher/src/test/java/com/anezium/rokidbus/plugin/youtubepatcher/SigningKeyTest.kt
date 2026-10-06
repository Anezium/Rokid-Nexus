package com.anezium.rokidbus.plugin.youtubepatcher

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.SigningCertificateLineage
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class SigningKeyTest {
    @Test fun inspectionUsesOriginalSignerWhenV31RotatesAtApi33() {
        val dir = Files.createTempDirectory("rotated-signer").toFile()
        try {
            val original = SigningKey(File(dir, "original.p12")).pair()
            val rotated = SigningKey(File(dir, "rotated.p12")).pair()
            val lineage = SigningCertificateLineage.Builder(
                SigningCertificateLineage.SignerConfig.Builder(original.privateKey, original.certificate).build(),
                SigningCertificateLineage.SignerConfig.Builder(rotated.privateKey, rotated.certificate).build(),
            ).build()
            val input = File(dir, "unsigned.apk")
            ApkModule().use { module ->
                module.setManifest(AndroidManifestBlock.empty().apply {
                    packageName = "com.google.android.youtube"
                    versionName = PatchPolicy.VERSION
                    versionCode = 1
                    minSdkVersion = 24
                    targetSdkVersion = 36
                    getOrCreateApplicationElement()
                })
                module.writeApk(input)
            }
            val signed = File(dir, "rotated.apk")
            ApkSigner.Builder(listOf(
                ApkSigner.SignerConfig.Builder("original", original.privateKey, listOf(original.certificate)).build(),
                ApkSigner.SignerConfig.Builder("rotated", rotated.privateKey, listOf(rotated.certificate)).build(),
            )).setInputApk(input).setOutputApk(signed).setSigningCertificateLineage(lineage)
                .setMinSdkVersionForRotation(33).setV4SigningEnabled(false).build().sign()

            val api33 = ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(33)
                .setMaxCheckedPlatformVersion(33).build().verify()
            assertTrue(api33.errors.toString(), api33.isVerified)
            assertTrue(api33.isVerifiedUsingV31Scheme)
            assertEquals(listOf(rotated.certificate), api33.signerCertificates)
            val inspected = ApkPreparer.inspect(signed)
            assertEquals(setOf(PatchPolicy.sha256(original.certificate.encoded)), inspected.signers)
            assertFalse(inspected.signers.contains(PatchPolicy.sha256(rotated.certificate.encoded)))
            assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(inspected) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun keyPersistsAndPasswordBackupRestoresWithoutChangingSigner() {
        val dir = Files.createTempDirectory("key").toFile()
        try {
            val file = File(dir, "key.p12")
            val key = SigningKey(file)
            val signer = key.fingerprint()
            assertEquals(signer, SigningKey(file).fingerprint())
            val input = File(dir, "unsigned.apk")
            com.reandroid.apk.ApkModule().use { module ->
                val manifest = com.reandroid.arsc.chunk.xml.AndroidManifestBlock.empty().apply {
                    packageName = "example.test.signing"
                    versionName = "1.0"
                    versionCode = 1
                    minSdkVersion = 30
                    targetSdkVersion = 36
                    getOrCreateApplicationElement()
                }
                module.setManifest(manifest)
                module.writeApk(input)
            }
            val signed = File(dir, "signed.apk")
            key.sign(input, signed)
            val verified = com.android.apksig.ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(30).build().verify()
            assertTrue(verified.isVerified)
            assertEquals(signer, PatchPolicy.sha256(verified.signerCertificates.single().encoded))
            val inspected = ApkPreparer.inspect(signed)
            assertEquals("example.test.signing", inspected.packageName)
            assertEquals(setOf(signer), inspected.signers)
            assertThrows(IllegalArgumentException::class.java) { PatchPolicy.validate(inspected) }
            val backup = ByteArrayOutputStream()
            key.export(backup, "strong-password".toCharArray())
            assertFalse(backup.toByteArray().contentEquals(file.readBytes()))
            val target = SigningKey(File(dir, "restored.p12"))
            target.import(backup.toByteArray().inputStream(), "strong-password".toCharArray())
            assertEquals(signer, target.fingerprint())
            val old = File(dir, "restored.p12").readBytes()
            assertThrows(Exception::class.java) { target.import(backup.toByteArray().inputStream(), "wrong-password".toCharArray()) }
            assertArrayEquals(old, File(dir, "restored.p12").readBytes())
            val corrupt = backup.toByteArray().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { target.import(corrupt.inputStream(), "strong-password".toCharArray()) }
            assertArrayEquals(old, File(dir, "restored.p12").readBytes())
            assertThrows(IllegalArgumentException::class.java) { key.export(ByteArrayOutputStream(), "short".toCharArray()) }
        } finally { dir.deleteRecursively() }
    }
}
