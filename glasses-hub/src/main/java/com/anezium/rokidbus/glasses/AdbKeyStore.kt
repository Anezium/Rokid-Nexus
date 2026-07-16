package com.anezium.rokidbus.glasses

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** App-private, KADB-compatible key identity. Enrollment is deliberately external. */
internal object AdbKeyStore {
    /** Loads only a classic identity that a maintainer may already have enrolled with adbd. */
    fun loadExisting(context: Context): AdbKeyMaterial? {
        if (!privateKeyFile(context).exists() && !publicKeyFile(context).exists()) return null
        return loadOrCreate(context)
    }

    fun loadOrCreate(context: Context): AdbKeyMaterial? {
        val privateFile = privateKeyFile(context)
        val publicFile = publicKeyFile(context)
        if (!privateFile.exists() && !publicFile.exists()) {
            return runCatching { generateAndPersist(privateFile, publicFile) }
                .onFailure { logError("Self-arm ADB key generation failed", it) }
                .getOrNull()
        }
        if (!privateFile.exists()) {
            log("Self-arm ADB key unavailable: private key is missing")
            return null
        }

        val privatePem = runCatching { privateFile.readText() }
            .onFailure { logError("Self-arm ADB private key read failed", it) }
            .getOrNull() ?: return null
        if (publicFile.exists()) {
            return runCatching { AdbKeyMaterial.parse(privatePem, publicFile.readText()) }
                .onFailure { logError("Self-arm ADB key parse failed", it) }
                .getOrNull()
                .also { if (it == null) log("Self-arm ADB key unavailable: stored key is invalid") }
        }

        return runCatching {
            val parsed = AdbKeyMaterial.parsePrivateKey(privatePem) as? RSAPrivateCrtKey
                ?: error("stored private key is not RSA CRT")
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(parsed.modulus, parsed.publicExponent),
            ) as RSAPublicKey
            val publicText = adbPublicKey(publicKey, KEY_COMMENT)
            writeOwnerOnly(publicFile, publicText)
            AdbKeyMaterial(parsed, publicText)
        }.onFailure {
            logError("Self-arm ADB public key recovery failed", it)
        }.getOrNull()
    }

    internal fun keyDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "kadb")

    private fun generateAndPersist(privateFile: File, publicFile: File): AdbKeyMaterial {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(MODULUS_BITS, RSAKeyGenParameterSpec.F4))
        val pair = generator.generateKeyPair()
        val privatePem = privateKeyPem(pair.private.encoded)
        val publicText = adbPublicKey(pair.public as RSAPublicKey, KEY_COMMENT)
        writeOwnerOnly(privateFile, privatePem)
        writeOwnerOnly(publicFile, publicText)
        log("Created Nexus self-arm ADB key; enrollment remains manual")
        return AdbKeyMaterial(pair.private, publicText)
    }

    private fun writeOwnerOnly(file: File, content: String) {
        val dir = file.parentFile ?: error("ADB key has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            error("Could not create ADB key directory")
        }
        file.writeText(content)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun privateKeyFile(context: Context): File =
        File(keyDirectory(context), "adbkey.pem")

    private fun publicKeyFile(context: Context): File =
        File(keyDirectory(context), "adbkey.pub")

    private fun privateKeyPem(encoded: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private fun adbPublicKey(publicKey: RSAPublicKey, comment: String): String {
        val modulus = publicKey.modulus
        val buffer = ByteBuffer.allocate(ADB_PUBLIC_KEY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0inv(modulus).toInt())
        buffer.put(fixedLittleEndian(modulus, MODULUS_BYTES))
        buffer.put(fixedLittleEndian(BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(modulus), MODULUS_BYTES))
        buffer.putInt(publicKey.publicExponent.toInt())
        return "${Base64.getEncoder().encodeToString(buffer.array())} $comment"
    }

    private fun n0inv(modulus: BigInteger): Long {
        val two32 = BigInteger.ONE.shiftLeft(32)
        val inverse = modulus.and(two32.subtract(BigInteger.ONE)).modInverse(two32)
        return two32.subtract(inverse).and(two32.subtract(BigInteger.ONE)).toLong()
    }

    private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= size) { "value does not fit $size bytes" }
        return ByteArray(size).also { out ->
            unsigned.indices.forEach { index -> out[index] = unsigned[unsigned.size - 1 - index] }
        }
    }

    private const val KEY_COMMENT = "rokid-nexus@glasses"
    private const val MODULUS_BITS = 2048
    private const val MODULUS_BYTES = MODULUS_BITS / 8
    private const val MODULUS_WORDS = MODULUS_BYTES / 4
    private const val ADB_PUBLIC_KEY_BYTES = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4
}
