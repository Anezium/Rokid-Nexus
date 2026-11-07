package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Hub-to-hub audio on its own authenticated RFCOMM socket, never on the bus.
 *
 * Internal capture IPC is loopback TCP: capture first writes the 32 bytes obtained by
 * hex-decoding sha256("<secretHex>:audio-session:<nonce>"). Records then consist of
 * [type:u8][length:u16 BE][body, at most 4096 bytes]. Capture sends 0x01 HEADER (ready
 * without v), 0x02 PACKET (one Opus packet), 0x03 STATUS ({"silent":boolean}). The
 * app sends empty 0x10 KEEPALIVE or 0x11 STOP. EOF and a five-second keepalive timeout
 * both terminate capture. No shell-created response, log or pid file is read by the app.
 */
object GlassesAudioRelayContract {
    const val VERSION = 1
    const val START_TIMEOUT_MS = 3000L
    const val CAPTURE_ATTACH_TIMEOUT_MS = 5000L
    const val KEEPALIVE_INTERVAL_MS = 2000L
    const val PEER_TIMEOUT_MS = 6000L
    const val LOCAL_KEEPALIVE_INTERVAL_MS = 1000L
    const val LOCAL_KEEPALIVE_TIMEOUT_MS = 5000L
    const val JITTER_PRIME_FRAMES = 2
    const val JITTER_MAX_FRAMES = 8
    const val SILENCE_PEAK = 16
    const val SILENCE_HOLD_BLOCKS = 25
    const val MAX_FRAME_BYTES = 4096
    const val MAX_PACKETS = 4
    const val FRAME_MS = 20
    const val PACKETS_PER_FRAME = 2
    const val OPUS = "opus"
    const val PCM16 = "pcm16"
    val ERROR_CODES = setOf("BRIDGE_UNAVAILABLE", "CAPTURE_FAILED", "ENCODER_UNAVAILABLE", "BUSY", "UNSUPPORTED")

    sealed interface ValidationResult<out T> {
        data class Valid<T>(val value: T) : ValidationResult<T>
        data class Invalid(val reason: String) : ValidationResult<Nothing>
    }

    data class Start(val codecs: List<String>)
    data class Ready(val codec: String, val csd: List<ByteArray>)
    data class Status(val seq: Long, val silent: Boolean)

    fun start(codecs: List<String> = listOf(OPUS)): JSONObject = JSONObject()
        .put("v", VERSION).put("codecs", JSONArray(codecs)).put("keepaliveMs", KEEPALIVE_INTERVAL_MS)

    fun ready(codec: String, csd: List<ByteArray> = emptyList()): JSONObject = JSONObject()
        .put("v", VERSION).put("codec", codec).put("sampleRate", if (codec == OPUS) 48000 else 16000)
        .put("channels", 1).put("frameMs", FRAME_MS).put("packetsPerFrame", PACKETS_PER_FRAME)
        .apply { csd.forEachIndexed { index, bytes -> put("csd$index", Base64.getEncoder().encodeToString(bytes)) } }

    fun status(seq: Long, silent: Boolean): JSONObject = JSONObject()
        .put("v", VERSION).put("seq", seq).put("silent", silent)

    fun error(code: String): JSONObject = JSONObject().put("v", VERSION).put("code", code)

    fun validateStart(json: JSONObject?): ValidationResult<Start> {
        if (!version(json)) return invalid("version")
        val codecs = json!!.optJSONArray("codecs") ?: return invalid("codecs")
        if (codecs.length() !in 1..2 || integer(json, "keepaliveMs") != KEEPALIVE_INTERVAL_MS) return invalid("start bounds")
        val names = (0 until codecs.length()).map { codecs.opt(it) as? String ?: return invalid("codec type") }
        if (names.distinct().size != names.size || names.any { it != OPUS && it != PCM16 }) return invalid("codecs")
        return ValidationResult.Valid(Start(names))
    }

    fun validateReady(json: JSONObject?): ValidationResult<Ready> {
        if (!version(json)) return invalid("version")
        val codec = json!!.opt("codec") as? String ?: return invalid("codec")
        if (codec != OPUS && codec != PCM16) return invalid("codec")
        if (integer(json, "sampleRate") != (if (codec == OPUS) 48000L else 16000L) ||
            integer(json, "channels") != 1L || integer(json, "frameMs") != FRAME_MS.toLong() ||
            integer(json, "packetsPerFrame") != PACKETS_PER_FRAME.toLong()) return invalid("ready bounds")
        val csd = if (codec == OPUS) (0..2).map { index ->
            val encoded = json.opt("csd$index") as? String ?: return invalid("missing csd")
            if (encoded.length > MAX_FRAME_BYTES * 2) return invalid("csd size")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return invalid("csd base64")
            if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) return invalid("csd size")
            bytes
        } else {
            if ((0..2).any { json.has("csd$it") }) return invalid("pcm16 csd")
            emptyList()
        }
        return ValidationResult.Valid(Ready(codec, csd))
    }

    fun validateStatus(json: JSONObject?): ValidationResult<Status> {
        if (!version(json)) return invalid("version")
        val seq = integer(json!!, "seq") ?: return invalid("seq")
        val silent = json.opt("silent") as? Boolean ?: return invalid("silent")
        if (seq !in -1L..Int.MAX_VALUE.toLong()) return invalid("seq bounds")
        return ValidationResult.Valid(Status(seq, silent))
    }

    fun validateError(json: JSONObject?): ValidationResult<String> {
        if (!version(json)) return invalid("version")
        val code = json!!.opt("code") as? String ?: return invalid("code")
        return if (code in ERROR_CODES) ValidationResult.Valid(code) else invalid("code")
    }

    fun frameId(seq: Long): String {
        require(seq in 0L..Int.MAX_VALUE.toLong())
        return "ar-$seq"
    }

    fun frame(seq: Long, ms: Long, packets: List<ByteArray>): BusEnvelope {
        require(ms >= 0)
        return BusEnvelope(BusPaths.CORE_AUDIO_RELAY_FRAME, frameId(seq),
            JSONObject().put("seq", seq).put("ms", ms), pack(packets))
    }

    fun validateFrame(envelope: BusEnvelope): ValidationResult<List<ByteArray>> {
        if (envelope.v != VERSION || envelope.path != BusPaths.CORE_AUDIO_RELAY_FRAME) return invalid("frame envelope")
        val seq = integer(envelope.payload, "seq") ?: return invalid("seq")
        val ms = integer(envelope.payload, "ms") ?: return invalid("ms")
        if (seq !in 0L..Int.MAX_VALUE.toLong() || ms < 0 || envelope.id != frameId(seq)) return invalid("frame metadata")
        return unpack(envelope.binary ?: return invalid("binary required"))
    }

    fun pack(packets: List<ByteArray>): ByteArray {
        require(packets.size in 1..MAX_PACKETS)
        require(packets.all { it.isNotEmpty() && it.size <= MAX_FRAME_BYTES - 2 })
        require(packets.sumOf { it.size + 2 } <= MAX_FRAME_BYTES)
        val out = ByteArrayOutputStream()
        packets.forEach { packet ->
            out.write(packet.size ushr 8)
            out.write(packet.size and 255)
            out.write(packet)
        }
        return out.toByteArray()
    }

    fun unpack(data: ByteArray): ValidationResult<List<ByteArray>> {
        if (data.isEmpty() || data.size > MAX_FRAME_BYTES) return invalid("frame size")
        var offset = 0
        val packets = mutableListOf<ByteArray>()
        while (offset < data.size) {
            if (data.size - offset < 2 || packets.size == MAX_PACKETS) return invalid("packet count or length")
            val length = ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
            offset += 2
            if (length == 0 || length > data.size - offset) return invalid("packet length")
            packets += data.copyOfRange(offset, offset + length)
            offset += length
        }
        return ValidationResult.Valid(packets)
    }

    private fun version(json: JSONObject?): Boolean = json != null && integer(json, "v") == VERSION.toLong()
    private fun integer(json: JSONObject, key: String): Long? = when (val value = json.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
    private fun invalid(reason: String) = ValidationResult.Invalid(reason)
}
