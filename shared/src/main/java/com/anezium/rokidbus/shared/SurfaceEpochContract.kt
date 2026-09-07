package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Hub-owned occupancy counter for the foreground surface slot.
 *
 * Plugins never send a trusted [FIELD]; the phone hub overwrites it the way it
 * overwrites `ownerPluginId`. Glasses drop a frame whose epoch is older than
 * the live epoch for that slot even if `seq` is newer.
 */
object SurfaceEpochContract {
    const val FIELD = "epoch"

    fun stamp(payload: JSONObject, epoch: Long): JSONObject =
        JSONObject(payload.toString()).put(FIELD, epoch)

    fun read(payload: JSONObject): Long = payload.optLong(FIELD, 0L)

    fun strip(payload: JSONObject): JSONObject {
        val copy = JSONObject(payload.toString())
        copy.remove(FIELD)
        return copy
    }

    fun isStale(incomingEpoch: Long, liveEpoch: Long): Boolean = incomingEpoch < liveEpoch
}
