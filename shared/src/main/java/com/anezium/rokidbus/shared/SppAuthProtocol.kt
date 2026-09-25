package com.anezium.rokidbus.shared

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Hub transport authentication; independent of the plugin API and inner frame version. */
object SppAuthProtocol {
    const val KEY_BYTES = 32
    const val HELLO_TIMEOUT_MS = 1_000L
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    private val random = SecureRandom()
    private val magic = byteArrayOf(0x4e, 0x58, 0x53, 0x50, 1)
    private val domain = "RokidBus-SPP-v1\u0000".toByteArray(Charsets.US_ASCII)

    fun newSecret(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    class RecentNonces(private val capacity: Int = 256) {
        private val seen = ArrayDeque<ByteArray>()

        @Synchronized
        internal fun accept(nonce: ByteArray) {
            checkAuth(seen.none { MessageDigest.isEqual(it, nonce) })
            seen.addLast(nonce.copyOf())
            if (seen.size > capacity) seen.removeFirst()
        }
    }

    fun connect(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        recentNonces: RecentNonces,
        nonce: () -> ByteArray = ::newSecret,
    ): Session {
        checkAuth(key.size == KEY_BYTES)
        val authKey = authenticationKey(key)
        val phone = nonce().also { checkAuth(it.size == KEY_BYTES) }
        writeHandshake(output, 1, phone)
        val challenge = readHandshake(input, 2, 64)
        val glasses = challenge.copyOfRange(0, 32)
        checkAuth(!MessageDigest.isEqual(phone, glasses))
        verify(proof(authKey, "glasses-proof", phone, glasses), challenge.copyOfRange(32, 64))
        recentNonces.accept(glasses)
        writeHandshake(output, 3, proof(authKey, "phone-proof", phone, glasses))
        verify(proof(authKey, "glasses-ready", phone, glasses), readHandshake(input, 4, 32))
        return session(key, phone, glasses, phoneSide = true)
    }

    fun accept(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        recentNonces: RecentNonces,
        onHello: () -> Unit = {},
        nonce: () -> ByteArray = ::newSecret,
    ): Session {
        checkAuth(key.size == KEY_BYTES)
        val authKey = authenticationKey(key)
        val phone = readHandshake(input, 1, 32)
        onHello()
        recentNonces.accept(phone)
        val glasses = nonce().also { checkAuth(it.size == KEY_BYTES) }
        checkAuth(!MessageDigest.isEqual(phone, glasses))
        writeHandshake(output, 2, glasses + proof(authKey, "glasses-proof", phone, glasses))
        verify(proof(authKey, "phone-proof", phone, glasses), readHandshake(input, 3, 32))
        writeHandshake(output, 4, proof(authKey, "glasses-ready", phone, glasses))
        return session(key, phone, glasses, phoneSide = false)
    }

    private fun authenticationKey(key: ByteArray): ByteArray =
        expand(hmac(domain, key), "handshake-auth")

    private fun session(key: ByteArray, phone: ByteArray, glasses: ByteArray, phoneSide: Boolean): Session {
        val extracted = hmac(phone + glasses, key)
        val toGlasses = expand(extracted, "phone-to-glasses")
        val toPhone = expand(extracted, "glasses-to-phone")
        return if (phoneSide) Session(toGlasses, toPhone) else Session(toPhone, toGlasses)
    }

    private fun expand(key: ByteArray, label: String): ByteArray =
        hmac(key, domain, label.toByteArray(Charsets.US_ASCII), byteArrayOf(1))

    private fun proof(key: ByteArray, label: String, phone: ByteArray, glasses: ByteArray): ByteArray =
        hmac(key, domain, label.toByteArray(Charsets.US_ASCII), phone, glasses)

    private fun writeHandshake(output: OutputStream, type: Int, payload: ByteArray) {
        output.write(magic + byteArrayOf(type.toByte()) + payload)
        output.flush()
    }

    private fun readHandshake(input: InputStream, type: Int, size: Int): ByteArray {
        val stream = DataInputStream(input)
        val header = ByteArray(6).also(stream::readFully)
        checkAuth(header.contentEquals(magic + byteArrayOf(type.toByte())))
        return ByteArray(size).also(stream::readFully)
    }

    class Session internal constructor(private val sendKey: ByteArray, private val receiveKey: ByteArray) {
        private val writeLock = Any()
        private val readLock = Any()
        private var sendSequence = 0L
        private var receiveSequence = 0L
        @Volatile private var failed = false

        fun write(output: OutputStream, envelope: BusEnvelope) = synchronized(writeLock) {
            guarded {
                checkAuth(sendSequence < Long.MAX_VALUE)
                val body = FrameProtocol.toFrameBody(envelope)
                checkAuth(body.size in 1..FrameProtocol.MAX_FRAME_BYTES)
                val record = ByteBuffer.allocate(4 + 1 + 8 + body.size)
                    .putInt(1 + 8 + body.size + 32)
                    .put(2.toByte())
                    .putLong(sendSequence)
                    .put(body)
                    .array()
                val tag = hmac(sendKey, domain, record)
                output.write(record)
                output.write(tag)
                output.flush()
                sendSequence++
                Unit
            }
        }

        fun read(input: InputStream): BusEnvelope? = synchronized(readLock) {
            guarded {
                val first = input.read()
                if (first == -1) {
                    failed = true
                    return@guarded null
                }
                val stream = DataInputStream(input)
                val header = byteArrayOf(first.toByte(), 0, 0, 0)
                stream.readFully(header, 1, 3)
                val length = ByteBuffer.wrap(header).int
                checkAuth(length in 42..(FrameProtocol.MAX_FRAME_BYTES + 41))
                val record = ByteArray(length - 32).also(stream::readFully)
                val tag = ByteArray(32).also(stream::readFully)
                verify(hmac(receiveKey, domain, header, record), tag)
                val fields = ByteBuffer.wrap(record)
                checkAuth(fields.get() == 2.toByte())
                checkAuth(receiveSequence < Long.MAX_VALUE && fields.long == receiveSequence)
                val envelope = FrameProtocol.fromFrameBody(record.copyOfRange(9, record.size))
                receiveSequence++
                envelope
            }
        }

        private inline fun <T> guarded(action: () -> T): T {
            checkAuth(!failed)
            return try {
                action()
            } catch (_: Exception) {
                failed = true
                // Parsers and streams may include peer-controlled content in exception messages.
                throw IOException("SPP authenticated frame rejected")
            }
        }
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            data.forEach(::update)
            doFinal()
        }

    private fun verify(expected: ByteArray, actual: ByteArray) =
        checkAuth(MessageDigest.isEqual(expected, actual))

    private fun checkAuth(valid: Boolean) {
        if (!valid) throw IOException("SPP authentication rejected")
    }
}
