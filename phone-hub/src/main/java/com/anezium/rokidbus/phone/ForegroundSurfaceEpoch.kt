package com.anezium.rokidbus.phone

/**
 * Monotonic occupancy counter for the one foreground surface slot.
 *
 * [assign] increments when the occupying plugin changes (including idle to
 * occupied after [release]) and otherwise returns the live epoch so updates
 * from the current owner keep the same value.
 */
internal class ForegroundSurfaceEpoch {
    private val lock = Any()
    private var liveEpoch = 0L
    private var ownerPluginId: String? = null

    fun assign(ownerPluginId: String): Long = synchronized(lock) {
        if (this.ownerPluginId != ownerPluginId) {
            liveEpoch += 1L
            this.ownerPluginId = ownerPluginId
        }
        liveEpoch
    }

    fun release(ownerPluginId: String) {
        synchronized(lock) {
            if (this.ownerPluginId == ownerPluginId) {
                this.ownerPluginId = null
            }
        }
    }
}
