package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.util.Base64

interface SppPairingKeyStore {
    /** Null means absent; read or unwrap failures throw and require trusted CXR recovery. */
    fun load(): ByteArray?
    fun save(key: ByteArray): Boolean
}

/** This control is consumed at the CXR boundary, never by a bus route. */
object SppKeyProvisioning {
    private const val PREFIX = "/hub/spp/"
    const val PATH = "${PREFIX}provision"

    fun isReserved(path: String): Boolean = path == "/hub/spp" || path.startsWith(PREFIX)

    fun phoneKey(store: SppPairingKeyStore): ByteArray? = synchronized(store) {
        val loaded = runCatching { store.load() }
        if (loaded.isFailure) return@synchronized null
        loaded.getOrNull()?.let { return@synchronized it }
        val key = SppAuthProtocol.newSecret()
        key.takeIf { store.save(it) }
    }

    fun offer(key: ByteArray): BusEnvelope {
        require(key.size == SppAuthProtocol.KEY_BYTES)
        return BusEnvelope(
            path = PATH,
            payload = JSONObject()
                .put("version", 1)
                .put("key", Base64.getEncoder().encodeToString(key)),
        )
    }

    fun receive(envelope: BusEnvelope, fromCxr: Boolean, install: (ByteArray) -> Unit): Boolean {
        if (!isReserved(envelope.path)) return false
        if (!fromCxr || envelope.path != PATH || envelope.binary != null || envelope.v != 1) return true
        val key = runCatching {
            val payload = envelope.payload
            if (payload.opt("version") != 1) return true
            val encoded = payload.opt("key") as? String ?: return true
            if (encoded.length != 44) return true
            Base64.getDecoder().decode(encoded).takeIf { it.size == SppAuthProtocol.KEY_BYTES }
        }.getOrNull() ?: return true
        install(key)
        return true
    }
}
