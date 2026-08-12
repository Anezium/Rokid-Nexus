package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.client.plugin.NexusBulkChannel
import com.anezium.rokidbus.shared.BulkLinkPurpose
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkProtocol
import java.util.concurrent.Executors

/** Camera feature endpoint over the hub-owned Bulk Link; no Wi-Fi credentials enter Lens. */
internal class CorePhoneLensImageLink(
    private val openChannel: (String, BulkLinkPurpose) -> NexusBulkChannel?,
    private val onPacket: (CameraLinkPacket) -> Unit,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lens-core-link").apply { isDaemon = true } }
    private var channel: NexusBulkChannel? = null
    @Volatile private var closed = false
    fun open(sessionId: String) {
        if (closed) return
        executor.execute {
            val active = openChannel(sessionId, BulkLinkPurpose.CAMERA) ?: return@execute log("camera bulk channel unavailable")
            channel = active
            try { while (!closed) CameraLinkProtocol.read(active.input)?.let(onPacket) ?: break }
            catch (failure: Throwable) { if (!closed) log("camera bulk link ended ${failure.javaClass.simpleName}") }
            finally { runCatching { active.close() }; if (channel === active) channel = null }
        }
    }
    override fun close() { closed = true; runCatching { channel?.close() }; channel = null; executor.shutdownNow() }
}
