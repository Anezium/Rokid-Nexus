package com.anezium.rokidbus.shared

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A pairing belongs to this installation; it must not follow app backup/restore. */
class SppKeyStore internal constructor(
    private val file: AtomicFile,
    private val wrappingKey: () -> SecretKey,
) : SppPairingKeyStore {
    constructor(context: Context, peerId: String? = null) : this(
        AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName(peerId))),
        ::androidWrappingKey,
    )

    @Synchronized
    override fun load(): ByteArray? = try {
        val record = file.openRead().use { input ->
            val bytes = ByteArray(61)
            DataInputStream(input).readFully(bytes)
            require(input.read() == -1)
            bytes
        }
        require(record[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, record.copyOfRange(1, 13)))
        cipher.doFinal(record.copyOfRange(13, record.size)).also { require(it.size == SppAuthProtocol.KEY_BYTES) }
    } catch (_: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw IOException("SPP key unavailable")
        null
    } catch (_: Exception) {
        throw IOException("SPP key unavailable")
    }

    @Synchronized
    override fun save(key: ByteArray): Boolean = runCatching {
        require(key.size == SppAuthProtocol.KEY_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        require(cipher.iv.size == 12)
        val record = byteArrayOf(1) + cipher.iv + cipher.doFinal(key)
        val stream = file.startWrite()
        try {
            stream.write(record)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }.getOrDefault(false)

    companion object {
        private fun androidWrappingKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

        private const val KEY_ALIAS = "nexus_spp_pairing_aes_v1"

        internal fun fileName(peerId: String?): String {
            if (peerId == null) return "spp-pairing-key"
            val digest = MessageDigest.getInstance("SHA-256").digest(peerId.toByteArray(Charsets.UTF_8))
            return "spp-pairing-key-" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
