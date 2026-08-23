package com.anezium.rokidbus.phone

/**
 * Monotonic occupancy counter for the one foreground surface slot.
 *
 * [assign] increments when the occupying plugin changes (including idle to
 * occupied after [release]) and otherwise returns the live epoch so updates
 * from the current owner keep the same value.
 */
class ForegroundSurfaceEpoch(
    seedMs: Long = System.currentTimeMillis(),
) {
    private val lock = Any()
    // Wall-clock seed so a hub process restart never replays epoch values the glasses already saw.
    private var liveEpoch = seedMs
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
