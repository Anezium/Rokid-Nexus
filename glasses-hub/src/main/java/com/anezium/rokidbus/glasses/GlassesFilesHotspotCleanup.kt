package com.anezium.rokidbus.glasses

/** Confined to the hub's Wi-Fi executor; identifiers live only until this batch ends. */
internal class GlassesFilesHotspotCleanup {
    private var batch: String? = null
    private var ssid: String? = null

    fun onConnectSubmitted(batchId: String, joinedSsid: String) {
        batch = batchId
        ssid = joinedSsid
    }

    fun finish(batchId: String, reconcile: () -> Unit, forget: (String) -> Unit) {
        val joinedSsid = ssid.takeIf { batch == batchId }
        if (batch == batchId) {
            batch = null
            ssid = null
        }
        reconcile()
        joinedSsid?.let(forget)
    }
}
