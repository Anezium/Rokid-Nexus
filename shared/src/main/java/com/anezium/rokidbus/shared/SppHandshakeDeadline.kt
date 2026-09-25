package com.anezium.rokidbus.shared

/** Completion and expiry race for ownership of one socket, never its replacement. */
class SppHandshakeDeadline(
    schedule: (Long, () -> Unit) -> (() -> Unit),
    close: () -> Unit,
) {
    private val lock = Any()
    private var finished = false
    private var expired = false
    private val cancel = schedule(SppAuthProtocol.HANDSHAKE_TIMEOUT_MS) {
        synchronized(lock) {
            if (!finished) {
                finished = true
                expired = true
                close()
            }
        }
    }

    fun complete(): Boolean = synchronized(lock) {
        finished = true
        cancel()
        !expired
    }
}
