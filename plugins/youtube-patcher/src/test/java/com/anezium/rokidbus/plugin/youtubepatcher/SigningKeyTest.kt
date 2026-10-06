package com.anezium.rokidbus.plugin.youtubepatcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class SigningKeyTest {
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
