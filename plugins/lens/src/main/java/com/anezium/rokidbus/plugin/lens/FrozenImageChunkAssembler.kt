package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.FrozenImageChunkContract
import org.json.JSONObject

internal data class AssembledFrozenImage(
    val metadata: FrozenImageChunkContract.Metadata,
    val jpeg: ByteArray,
)

internal class FrozenImageChunkAssembler {
    private data class Transfer(
        val first: FrozenImageChunkContract.Metadata,
        val chunks: Array<ByteArray?>,
        var receivedBytes: Int = 0,
    )

    private val transfers = linkedMapOf<String, Transfer>()

    @Synchronized
    fun accept(payload: JSONObject, data: ByteArray): AssembledFrozenImage? {
        val metadata = FrozenImageChunkContract.parse(payload, data.size) ?: return null
        var transfer = transfers[metadata.transferId]
        if (transfer == null) {
            // Abandoned transfers never complete on their own; keep only the most
            // recent few so a lossy link cannot accumulate partial images forever.
            while (transfers.size >= MAX_PENDING_TRANSFERS) {
                transfers.remove(transfers.keys.first())
            }
            transfer = Transfer(metadata, arrayOfNulls(metadata.chunkCount))
            transfers[metadata.transferId] = transfer
        }
        if (!matches(transfer.first, metadata)) {
            transfers.remove(metadata.transferId)
            return null
        }
        val previous = transfer.chunks[metadata.chunkIndex]
        if (previous != null) {
            if (!previous.contentEquals(data)) transfers.remove(metadata.transferId)
            return null
        }
        transfer.chunks[metadata.chunkIndex] = data.copyOf()
        transfer.receivedBytes += data.size
        if (transfer.receivedBytes != metadata.totalBytes || transfer.chunks.any { it == null }) return null

        val jpeg = ByteArray(metadata.totalBytes)
        var offset = 0
        transfer.chunks.forEach { chunk ->
            val bytes = chunk ?: return null
            bytes.copyInto(jpeg, offset)
            offset += bytes.size
        }
        transfers.remove(metadata.transferId)
        if (FrozenImageChunkContract.sha256(jpeg) != metadata.sha256) return null
        return AssembledFrozenImage(metadata, jpeg)
    }

    @Synchronized
    fun clear() {
        transfers.clear()
    }

    private companion object {
        const val MAX_PENDING_TRANSFERS = 4
    }

    private fun matches(
        first: FrozenImageChunkContract.Metadata,
        next: FrozenImageChunkContract.Metadata,
    ): Boolean = first.sessionId == next.sessionId && first.requestId == next.requestId &&
        first.transferId == next.transferId && first.chunkCount == next.chunkCount &&
        first.totalBytes == next.totalBytes && first.width == next.width &&
        first.height == next.height && first.rotationDegrees == next.rotationDegrees &&
        first.sha256 == next.sha256
}
