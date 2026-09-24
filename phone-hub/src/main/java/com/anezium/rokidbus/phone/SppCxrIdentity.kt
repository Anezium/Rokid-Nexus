package com.anezium.rokidbus.phone

/** Waits for this connection's device info before allowing key provisioning. */
internal class SppCxrIdentity(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onReady: (Long) -> Unit,
) {
    private var connected = false
    private var identity: String? = null
    private var revision = 0L
    private var cancelTimeout: (() -> Unit)? = null

    @Synchronized
    fun current(): String? = identity.takeIf { connected }

    @Synchronized
    fun isCurrentOffer(revision: Long): Boolean =
        connected && identity != null && this.revision == revision

    @Synchronized
    fun onConnectionChanged(up: Boolean) {
        if (connected == up) return
        connected = up
        revision++
        cancelTimeout?.invoke()
        cancelTimeout = null
        if (!up) {
            identity = null
        } else if (identity != null) {
            onReady(revision)
        } else {
            val expected = revision
            cancelTimeout = schedule(DEVICE_INFO_TIMEOUT_MS) { onTimeout(expected) }
        }
    }

    @Synchronized
    fun onDeviceInfo(serial: String?, name: String?) {
        val next = PhoneSppPairing.cxrIdentity(serial, name)
        // Empty device info must not bypass the grace period either.
        if (next == PhoneSppPairing.cxrIdentity(null, null) || next == identity) return
        identity = next
        revision++
        cancelTimeout?.invoke()
        cancelTimeout = null
        if (connected) onReady(revision)
    }

    @Synchronized
    private fun onTimeout(expected: Long) {
        if (!connected || revision != expected || identity != null) return
        identity = PhoneSppPairing.cxrIdentity(null, null)
        cancelTimeout = null
        onReady(revision)
    }

    companion object {
        const val DEVICE_INFO_TIMEOUT_MS = 4_000L
    }
}
