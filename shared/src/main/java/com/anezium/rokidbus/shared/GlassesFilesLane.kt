package com.anezium.rokidbus.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Wi-Fi lane: a TCP side channel that carries one transfer's bytes when the phone and the
 * glasses share a Wi-Fi network, about fifty times faster than the Bluetooth bus.
 *
 * Control stays on the authenticated bus. The glasses open a one-shot listening socket on their
 * Wi-Fi address and send its endpoint plus a fresh 256-bit key inside `upload_ready` /
 * `download_ready`. The phone proves it holds the key in the first frame, and from then on every
 * frame in both directions is AES-256-GCM sealed with that key, so another device on the same
 * network can neither read a book in flight nor slip a byte into it. Integrity end to end is still
 * the transfer's SHA-256, exactly as on the bus.
 */
object GlassesFilesLane {
    const val KEY_BYTES = 32
    const val CHUNK_BYTES = 256 * 1024

    /** How far a lane sender may run ahead of the receiver's acknowledgement. */
    const val WINDOW_BYTES = 4 * 1024 * 1024

    /** The receiver acknowledges at least this often, and always on the last byte. */
    const val ACK_EVERY_BYTES = 1024 * 1024

    const val CONNECT_TIMEOUT_MS = 3_000
    const val ACCEPT_TIMEOUT_MS = 8_000

    /** How long one connection gets to prove the key before the glasses listen for the next. */
    const val HELLO_TIMEOUT_MS = 1_000

    /**
     * How long the phone waits for the glasses to answer its hello. Longer than the glasses'
     * whole accept budget, which started before the phone even heard of the lane, so an answer
     * the glasses sent is never one the phone already gave up on.
     */
    const val ANSWER_TIMEOUT_MS = ACCEPT_TIMEOUT_MS + 1_000

    /**
     * The glasses answer a good hello with this sealed frame. Only that answer tells the phone it
     * reached its own glasses rather than whatever else listens at that address, so a lane that
     * never answers leaves the transfer on the bus instead of failing it.
     */
    val ANSWER: Frame = Frame.Ack(0)

    private const val MAGIC = 0x4E58464C // "NXFL"
    private const val VERSION = 1
    private const val TYPE_DATA: Byte = 1
    private const val TYPE_ACK: Byte = 2
    private const val TAG_BITS = 128
    private const val MAX_FRAME_BYTES = CHUNK_BYTES + 64

    /** Which side sealed a frame; it is part of the nonce, so the two directions never collide. */
    enum class Direction(internal val wire: Byte) { PHONE_TO_GLASSES(0x50), GLASSES_TO_PHONE(0x47) }

    sealed interface Frame {
        data class Data(val offset: Long, val bytes: ByteArray) : Frame
        data class Ack(val stagedBytes: Long) : Frame
    }

    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun encodeKey(key: ByteArray): String = java.util.Base64.getEncoder().encodeToString(key)

    fun decodeKey(value: String): ByteArray? =
        runCatching { java.util.Base64.getDecoder().decode(value) }.getOrNull()?.takeIf { it.size == KEY_BYTES }

    /** First frame from the phone: proves it was given this transfer's key over the bus. */
    fun writeHello(output: DataOutputStream, transferId: String, key: ByteArray) {
        val id = transferId.toByteArray(Charsets.UTF_8)
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.writeShort(id.size)
        output.write(id)
        output.write(proof(transferId, key))
        output.flush()
    }

    /** True when the hello names [transferId] and proves [key]; anything else closes the socket. */
    fun readHello(input: DataInputStream, transferId: String, key: ByteArray): Boolean {
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return false
        val idLength = input.readUnsignedShort()
        if (idLength == 0 || idLength > 128) return false
        val id = ByteArray(idLength).also(input::readFully)
        val proof = ByteArray(32).also(input::readFully)
        return String(id, Charsets.UTF_8) == transferId && MessageDigest.isEqual(proof, proof(transferId, key))
    }

    private fun proof(transferId: String, key: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal("nexus-files-lane-hello:$transferId".toByteArray(Charsets.UTF_8))

    /** Seals frames in one direction; each frame consumes the next counter value of its nonce. */
    class Sealer(private val key: ByteArray, private val transferId: String, private val direction: Direction) {
        private var counter = 0L

        @Synchronized
        fun write(output: DataOutputStream, frame: Frame) {
            val plain = when (frame) {
                is Frame.Data -> ByteBuffer.allocate(8 + frame.bytes.size).putLong(frame.offset).put(frame.bytes).array()
                is Frame.Ack -> ByteBuffer.allocate(8).putLong(frame.stagedBytes).array()
            }
            val type = if (frame is Frame.Data) TYPE_DATA else TYPE_ACK
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, nonce(direction, counter++), aad(transferId, type)).doFinal(plain)
            output.writeByte(type.toInt())
            output.writeInt(sealed.size)
            output.write(sealed)
            output.flush()
        }
    }

    /** Opens frames from one direction, in order; a reordered, replayed or forged frame throws. */
    class Opener(private val key: ByteArray, private val transferId: String, private val direction: Direction) {
        private var counter = 0L

        fun read(input: DataInputStream): Frame {
            val type = input.readByte()
            if (type != TYPE_DATA && type != TYPE_ACK) throw IOException("unknown lane frame")
            val length = input.readInt()
            if (length <= 0 || length > MAX_FRAME_BYTES) throw IOException("bad lane frame length")
            val sealed = ByteArray(length).also(input::readFully)
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, nonce(direction, counter++), aad(transferId, type)).doFinal(sealed)
            } catch (error: Exception) {
                throw IOException("lane frame failed authentication", error)
            }
            val buffer = ByteBuffer.wrap(plain)
            if (plain.size < 8) throw IOException("short lane frame")
            val offset = buffer.long
            return if (type == TYPE_DATA) {
                if (plain.size == 8) throw IOException("empty lane chunk")
                Frame.Data(offset, ByteArray(plain.size - 8).also { buffer.get(it) })
            } else {
                if (plain.size != 8) throw IOException("bad lane ack")
                Frame.Ack(offset)
            }
        }
    }

    private fun nonce(direction: Direction, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(direction.wire).put(0).put(0).put(0).putLong(counter).array()

    private fun aad(transferId: String, type: Byte): ByteArray =
        "nexus-files-lane:$transferId:".toByteArray(Charsets.UTF_8) + type

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
}
