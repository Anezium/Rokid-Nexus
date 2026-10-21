package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesHotspotPolicy

/** Confined to the Wi-Fi executor; the shell owns the durable numeric cleanup record. */
internal class GlassesFilesHotspotCleanup {
    companion object {
        const val WAIT_MS = GlassesFilesHotspotPolicy.IDLE_MS + 10_000L
    }
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
