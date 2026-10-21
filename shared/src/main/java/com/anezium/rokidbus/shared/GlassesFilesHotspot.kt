package com.anezium.rokidbus.shared

data class GlassesFilesHotspotCredentials(val ssid: String, val passphrase: String, val security: String) {
    fun isValid(): Boolean = ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32 &&
        ssid.none { it.isISOControl() || Character.isSurrogate(it) } &&
        passphrase.none { it.isISOControl() || Character.isSurrogate(it) } && when (security) {
            "open" -> passphrase.isEmpty()
            "wpa2", "wpa3" -> passphrase.length in 8..128
            else -> false
        }

    override fun toString(): String = "GlassesFilesHotspotCredentials(redacted)"
}

data class GlassesFilesClientNetwork(val host: String, val prefix: Int) {
    fun isValid(): Boolean = GlassesFilesContract.isValidLaneHost(host) && prefix in 0..32

    fun contains(address: String): Boolean {
        if (!isValid() || !GlassesFilesContract.isValidLaneHost(address)) return false
        val local = host.split('.').map(String::toInt)
        val remote = address.split('.').map(String::toInt)
        return (0 until prefix).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (local[bit / 8] and mask) == (remote[bit / 8] and mask)
        }
    }
}

object GlassesFilesHotspotPolicy {
    const val MIN_BYTES = 3_670_016L
    const val SETUP_MS = 25_000L
    const val IDLE_MS = 35_000L

    fun shouldStart(remainingBytes: Long, sharedLaneAvailable: Boolean, cameraActive: Boolean,
        permissionGranted: Boolean, peerSupported: Boolean): Boolean =
        remainingBytes >= MIN_BYTES && !sharedLaneAvailable && !cameraActive && permissionGranted && peerSupported
}

/** One bus-to-lane snapshot, followed at most once by the existing lane-to-bus resync. */
class GlassesFilesLaneUpgrade {
    var offset: Long? = null
        private set

    fun snapshot(staged: Long, maximum: Long): Boolean {
        if (staged !in 0..maximum) return false
        if (offset != null) return offset == staged
        offset = staged
        return true
    }
}
