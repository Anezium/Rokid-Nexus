package com.anezium.rokidbus.glasses

import android.os.ParcelFileDescriptor
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.shared.BulkLinkPurpose
import com.anezium.rokidbus.shared.CameraLinkEndpointOffer
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketFlags
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface CameraDataLink : AutoCloseable {
    val isAuthenticated: Boolean
    fun enqueue(packet: CameraLinkPacket): Boolean
    fun resendOfferIfDisconnected()
    fun beginFrozenTransfer(requestId: Long): Boolean
    fun cancelFrozenTransfer(requestId: Long): Boolean
    fun acceptReverseOffer(offer: CameraLinkEndpointOffer) = Unit
}

/** Core camera endpoint with the same bounded writer semantics as the legacy network link. */
internal class CoreCameraDataLink(
    client: BusClient?,
    sessionId: String,
    private val onFrozenTransferFinished: (Long) -> Unit,
) : CameraDataLink {
    private val channel: ParcelFileDescriptor? = client?.openBulkChannel(sessionId, BulkLinkPurpose.CAMERA)
    private val output = channel?.let { FileOutputStream(it.fileDescriptor) }
    private val packets = ArrayBlockingQueue<CameraLinkPacket>(NETWORK_QUEUE_CAPACITY)
    private val packetQueueLock = Any()
    private val transferPolicy = CameraLinkTransferPolicy()
    private val running = AtomicBoolean(output != null)
    private val writer = Executors.newSingleThreadExecutor {
        Thread(it, "camera-bulk-writer").apply { isDaemon = true }
    }.also { executor ->
        if (output != null) executor.execute(::writerLoop)
    }

    override val isAuthenticated: Boolean get() = running.get()

    override fun enqueue(packet: CameraLinkPacket): Boolean {
        synchronized(packetQueueLock) {
            if (!running.get()) return false
            if (packet.type == CameraLinkPacketType.VIDEO_FRAME &&
                !transferPolicy.shouldAdmitVideo(packet.isKeyFrame())
            ) {
                return false
            }
            if (packet.type == CameraLinkPacketType.FROZEN_IMAGE) {
                transferPolicy.beginFrozenMode(packet.requestId)
                dropQueuedVideoFrames()
            }
            if (packets.offer(packet)) return true
            return if (packet.type == CameraLinkPacketType.VIDEO_FRAME) {
                dropOneVideoFrame() && packets.offer(packet)
            } else {
                while (packets.remainingCapacity() == 0 && dropOneVideoFrame()) Unit
                packets.offer(packet)
            }
        }
    }

    override fun resendOfferIfDisconnected() = Unit

    override fun beginFrozenTransfer(requestId: Long): Boolean = synchronized(packetQueueLock) {
        if (!running.get()) return false
        transferPolicy.beginFrozenMode(requestId)
        dropQueuedVideoFrames()
        true
    }

    override fun cancelFrozenTransfer(requestId: Long): Boolean {
        val shouldResume = synchronized(packetQueueLock) {
            packets.removeIf {
                it.type == CameraLinkPacketType.FROZEN_IMAGE && it.requestId == requestId
            }
            transferPolicy.endFrozenMode(requestId)
        }
        if (shouldResume) onFrozenTransferFinished(requestId)
        return shouldResume
    }

    private fun writerLoop() {
        val target = output ?: return
        while (running.get()) {
            val packet = runCatching { packets.poll(250L, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (packet.type == CameraLinkPacketType.VIDEO_FRAME) {
                val admitted = synchronized(packetQueueLock) {
                    transferPolicy.shouldAdmitVideo(packet.isKeyFrame()).also { accepted ->
                        if (accepted) transferPolicy.onVideoWriteStarted(packet.isKeyFrame())
                    }
                }
                if (!admitted) continue
            }
            if (runCatching { CameraLinkProtocol.write(target, packet) }.isFailure) {
                running.set(false)
                packets.clear()
            }
        }
    }

    private fun dropOneVideoFrame(): Boolean {
        val stale = packets.firstOrNull { it.type == CameraLinkPacketType.VIDEO_FRAME } ?: return false
        return packets.remove(stale)
    }

    private fun dropQueuedVideoFrames() {
        packets.removeIf { it.type == CameraLinkPacketType.VIDEO_FRAME }
    }

    private fun CameraLinkPacket.isKeyFrame(): Boolean =
        flags and CameraLinkPacketFlags.KEY_FRAME != 0

    override fun close() {
        running.set(false)
        synchronized(packetQueueLock) {
            packets.clear()
            transferPolicy.reset()
        }
        runCatching { channel?.close() }
        runCatching { output?.close() }
        writer.shutdownNow()
    }

    private companion object {
        const val NETWORK_QUEUE_CAPACITY = 12
    }
}
