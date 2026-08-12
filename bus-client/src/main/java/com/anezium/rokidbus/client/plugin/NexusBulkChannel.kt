package com.anezium.rokidbus.client.plugin

import android.os.ParcelFileDescriptor
import com.anezium.rokidbus.shared.BulkLinkPurpose
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A hub-authorized local endpoint for a single live Bulk Link lease. */
class NexusBulkChannel internal constructor(
    val sessionId: String,
    val purpose: BulkLinkPurpose,
    private val descriptor: ParcelFileDescriptor,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val input = FileInputStream(descriptor.fileDescriptor)
    val output = FileOutputStream(descriptor.fileDescriptor)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
    }
}
