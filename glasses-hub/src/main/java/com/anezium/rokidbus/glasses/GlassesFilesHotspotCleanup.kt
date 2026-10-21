package com.anezium.rokidbus.glasses

/** Confined to the Wi-Fi executor; the shell owns the durable numeric cleanup record. */
internal class GlassesFilesHotspotCleanup {
    private var batch: String? = null

    fun onConnectSubmitted(batchId: String) {
        batch = batchId
    }

    fun finish(batchId: String, reconcile: () -> Unit, forget: () -> Unit) {
        val submitted = batch == batchId
        if (batch == batchId) {
            batch = null
        }
        reconcile()
        if (submitted) forget()
    }
}
