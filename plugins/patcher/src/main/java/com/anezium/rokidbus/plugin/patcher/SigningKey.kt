package com.anezium.rokidbus.plugin.patcher

import app.morphe.patcher.apk.ApkSigner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Private PKCS12 storage; portable backup uses authenticated password encryption. */
class SigningKey(private val file: File) {
    private val provider = BouncyCastleProvider()
    private fun read(bytes: ByteArray): ApkSigner.PrivateKeyCertificatePair {
        val store = KeyStore.getInstance("PKCS12", provider)
        store.load(bytes.inputStream(), CharArray(0))
        val aliases = store.aliases().toList().filter { store.isKeyEntry(it) }
        require(aliases.size == 1) { "Backup must contain exactly one signing key." }
        val alias = aliases.single()
        val privateKey = store.getKey(alias, CharArray(0)) as PrivateKey
        val certificate = store.getCertificate(alias) as X509Certificate
        // Validate that the private key belongs to this certificate before replacing state.
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey); signer.update(byteArrayOf(1, 2, 3)); val signature = signer.sign()
        signer.initVerify(certificate); signer.update(byteArrayOf(1, 2, 3))
        require(signer.verify(signature)) { "Key and certificate do not match." }
        return ApkSigner.PrivateKeyCertificatePair(privateKey, certificate)
    }
    @Synchronized fun pair(): ApkSigner.PrivateKeyCertificatePair {
        if (!file.exists()) {
            val pair = ApkSigner.newPrivateKeyCertificatePair("Patcher", Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000))
            val store = KeyStore.getInstance("PKCS12", provider).apply {
                load(null, CharArray(0))
                setKeyEntry("patcher", pair.privateKey, CharArray(0), arrayOf(pair.certificate))
            }
            val bytes = java.io.ByteArrayOutputStream().also { store.store(it, CharArray(0)) }.toByteArray()
            save(bytes)
        }
        return read(file.readBytes())
    }
    fun fingerprint() = PatchPolicy.sha256(pair().certificate.encoded)
    fun sign(input: File, output: File) = ApkSigner.newApkSigner("Patcher", pair()).signApk(input, output)
    fun export(output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Use at least eight characters for the backup password." }
        pair()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce)
        output.write(MAGIC); output.write(salt); output.write(nonce); output.write(cipher.doFinal(file.readBytes()))
    }
    fun import(input: InputStream, password: CharArray) {
        val buffer = java.io.ByteArrayOutputStream()
        PatchPolicy.copyBounded(input, buffer, 1024 * 1024)
        val bytes = buffer.toByteArray()
        require(bytes.size >= 48 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not a Patcher key backup." }
        val decoded = cipher(Cipher.DECRYPT_MODE, password, bytes.copyOfRange(4, 20), bytes.copyOfRange(20, 32)).doFinal(bytes.copyOfRange(32, bytes.size))
        read(decoded)
        save(decoded)
    }
    private fun save(bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val pending = File(file.parentFile, "key.tmp")
        pending.outputStream().use { it.write(bytes); it.fd.sync() }
        require(pending.renameTo(file)) { "Cannot save signing key." }
    }
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, 210000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(MAGIC)
        }.also { key.fill(0) }
    }
    companion object { private val MAGIC = byteArrayOf(89, 80, 75, 49) }
}
