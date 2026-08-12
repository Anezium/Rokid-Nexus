package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** The only high-bandwidth P2P purposes supported by the shared lease. */
enum class BulkLinkPurpose(val wireValue: String) {
    CAMERA("camera"),
    VIDEO("video");

    companion object {
        fun fromWireValue(value: String): BulkLinkPurpose? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class BulkLinkState(val wireValue: String) {
    OFFERED("offered"), CONNECTING("connecting"), OPEN("open"), RELEASED("released"), ERROR("error");

    companion object {
        fun fromWireValue(value: String): BulkLinkState? = entries.firstOrNull { it.wireValue == value }
    }
}

data class BulkLinkOffer(
    val sessionId: String,
    val purpose: BulkLinkPurpose,
    val ownerPluginId: String? = null,
    val epoch: Long,
    val token: String,
    val ssid: String,
    val passphrase: String,
    val goIp: String,
    val port: Int = BulkLinkContract.PORT,
)

data class BulkLinkLeaseState(
    val sessionId: String,
    val purpose: BulkLinkPurpose,
    val epoch: Long,
    val state: BulkLinkState,
    val reason: String? = null,
)

/**
 * Hub-to-hub P2P lease metadata. The offer stays inside trusted hubs: plugins obtain only a
 * validated local file descriptor, never Wi-Fi credentials or the handshake token.
 */
object BulkLinkContract {
    const val VERSION = 1
    const val PORT = 38_400
    const val WARM_GRACE_MS = 40_000L
    const val MAX_HANDSHAKE_META_BYTES = 4_096
    const val MAX_SESSION_ID_CHARS = 64
    const val MAX_OWNER_PLUGIN_ID_CHARS = 64
    const val MAX_TOKEN_CHARS = 512
    const val MAX_SSID_CHARS = 32
    const val MAX_PASSPHRASE_CHARS = 63

    fun toJson(offer: BulkLinkOffer): JSONObject {
        require(validate(offer)) { "Invalid bulk link offer" }
        return JSONObject()
            .put("version", VERSION)
            .put("sessionId", offer.sessionId)
            .put("purpose", offer.purpose.wireValue)
            .put("epoch", offer.epoch)
            .put("token", offer.token)
            .put("ssid", offer.ssid)
            .put("passphrase", offer.passphrase)
            .put("goIp", offer.goIp)
            .put("port", offer.port)
            .also { offer.ownerPluginId?.let(it::putOwnerPluginId) }
    }

    fun fromJson(payload: JSONObject): BulkLinkOffer? = runCatching {
        if (payload.optInt("version", 0) != VERSION) return null
        BulkLinkOffer(
            sessionId = payload.getString("sessionId"),
            purpose = BulkLinkPurpose.fromWireValue(payload.getString("purpose")) ?: return null,
            ownerPluginId = payload.optString("ownerPluginId").ifBlank { null },
            epoch = payload.getLong("epoch"),
            token = payload.getString("token"),
            ssid = payload.getString("ssid"),
            passphrase = payload.getString("passphrase"),
            goIp = payload.getString("goIp"),
            port = payload.optInt("port", PORT),
        ).takeIf(::validate)
    }.getOrNull()

    fun validate(offer: BulkLinkOffer): Boolean =
        isSessionId(offer.sessionId) &&
            offer.ownerPluginId?.let(::isPluginId) != false &&
            offer.epoch > 0 &&
            offer.token.isBoundedSecret(MAX_TOKEN_CHARS) &&
            offer.ssid.isBoundedSecret(MAX_SSID_CHARS) &&
            offer.passphrase.length in 8..MAX_PASSPHRASE_CHARS &&
            offer.goIp.isIpAddress() &&
            offer.port == PORT

    fun handshakeMeta(sessionId: String, purpose: BulkLinkPurpose, epoch: Long, token: String): JSONObject {
        require(isSessionId(sessionId) && epoch > 0 && token.isBoundedSecret(MAX_TOKEN_CHARS)) {
            "Invalid bulk link handshake"
        }
        return JSONObject()
            .put("version", VERSION)
            .put("sessionId", sessionId)
            .put("purpose", purpose.wireValue)
            .put("epoch", epoch)
            .put("token", token)
            .also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_HANDSHAKE_META_BYTES) }
    }

    /** Four-byte big-endian length plus UTF-8 JSON; this is mandatory before raw lease bytes. */
    fun writeHandshake(output: OutputStream, handshake: JSONObject) {
        val bytes = handshake.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_HANDSHAKE_META_BYTES) { "Bulk link handshake metadata too large" }
        output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
        output.write(bytes)
        output.flush()
    }

    /** Returns null only for a clean EOF before a handshake frame begins. */
    fun readHandshake(input: InputStream): JSONObject? {
        val header = ByteArray(Int.SIZE_BYTES)
        val count = readFullyOrEof(input, header)
        if (count == -1) return null
        if (count != header.size) throw EOFException("Short bulk link handshake length")
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(length in 1..MAX_HANDSHAKE_META_BYTES) { "Invalid bulk link handshake length: $length" }
        val bytes = ByteArray(length)
        if (readFullyOrEof(input, bytes) != length) throw EOFException("Short bulk link handshake metadata")
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun stateToJson(value: BulkLinkLeaseState): JSONObject {
        require(isSessionId(value.sessionId) && value.epoch > 0 && value.reason.orEmpty().length <= 160) {
            "Invalid bulk link state"
        }
        return JSONObject()
            .put("version", VERSION)
            .put("sessionId", value.sessionId)
            .put("purpose", value.purpose.wireValue)
            .put("epoch", value.epoch)
            .put("state", value.state.wireValue)
            .also { value.reason?.let { reason -> it.put("reason", reason) } }
    }

    fun stateFromJson(payload: JSONObject): BulkLinkLeaseState? = runCatching {
        if (payload.optInt("version", 0) != VERSION) return null
        BulkLinkLeaseState(
            sessionId = payload.getString("sessionId"),
            purpose = BulkLinkPurpose.fromWireValue(payload.getString("purpose")) ?: return null,
            epoch = payload.getLong("epoch"),
            state = BulkLinkState.fromWireValue(payload.getString("state")) ?: return null,
            reason = payload.optString("reason").ifBlank { null },
        ).takeIf { isSessionId(it.sessionId) && it.epoch > 0 && it.reason.orEmpty().length <= 160 }
    }.getOrNull()

    private fun JSONObject.putOwnerPluginId(value: String) = put("ownerPluginId", value)
    private fun isSessionId(value: String): Boolean = value.length in 1..MAX_SESSION_ID_CHARS &&
        runCatching { UUID.fromString(value) }.isSuccess
    private fun isPluginId(value: String): Boolean = value.length <= MAX_OWNER_PLUGIN_ID_CHARS &&
        value.matches(Regex("[a-z][a-z0-9._-]{2,63}"))
    private fun String.isBoundedSecret(max: Int): Boolean = length in 1..max && all { it.code in 0x21..0x7e }
    private fun String.isIpAddress(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return if (offset == 0) -1 else offset
            if (count > 0) offset += count
        }
        return offset
    }
}
