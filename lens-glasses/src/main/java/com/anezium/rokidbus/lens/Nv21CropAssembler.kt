package com.anezium.rokidbus.lens

import java.nio.ByteBuffer

internal data class YuvPlaneData(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

internal data class Nv21AssemblyResult(
    val byteCount: Int,
    val usedInterleavedFastPath: Boolean,
)

/** Copies an even raw YUV_420_888 crop into a caller-owned NV21 byte array. */
internal object Nv21CropAssembler {
    fun copy(
        yPlane: YuvPlaneData,
        uPlane: YuvPlaneData,
        vPlane: YuvPlaneData,
        crop: LiveCropRect,
        destination: ByteArray,
    ): Nv21AssemblyResult {
        require(crop.left >= 0 && crop.top >= 0)
        require(crop.width > 0 && crop.height > 0)
        require((crop.left or crop.top or crop.width or crop.height) and 1 == 0) {
            "NV21 crop coordinates and dimensions must be even: $crop"
        }
        require(yPlane.rowStride > 0 && uPlane.rowStride > 0 && vPlane.rowStride > 0)
        require(yPlane.pixelStride > 0 && uPlane.pixelStride > 0 && vPlane.pixelStride > 0)

        val ySize = crop.width * crop.height
        val byteCount = ySize + ySize / 2
        require(destination.size >= byteCount) {
            "destination too small: ${destination.size} < $byteCount"
        }

        copyYRows(yPlane, crop, destination)
        val fastPath = canBulkCopyInterleavedVu(uPlane, vPlane, crop)
        if (fastPath) {
            copyInterleavedVuRows(vPlane, crop, destination, ySize)
        } else {
            copyGenericVuRows(uPlane, vPlane, crop, destination, ySize)
        }
        return Nv21AssemblyResult(byteCount, fastPath)
    }

    private fun copyYRows(
        plane: YuvPlaneData,
        crop: LiveCropRect,
        destination: ByteArray,
    ) {
        val source = plane.buffer.asReadOnlyBuffer()
        val base = source.position()
        for (row in 0 until crop.height) {
            val sourceRow = base + (crop.top + row) * plane.rowStride
            val destinationRow = row * crop.width
            if (plane.pixelStride == 1) {
                bulkCopy(source, sourceRow + crop.left, destination, destinationRow, crop.width)
            } else {
                for (column in 0 until crop.width) {
                    destination[destinationRow + column] =
                        source.get(sourceRow + (crop.left + column) * plane.pixelStride)
                }
            }
        }
    }

    private fun canBulkCopyInterleavedVu(
        uPlane: YuvPlaneData,
        vPlane: YuvPlaneData,
        crop: LiveCropRect,
    ): Boolean {
        if (uPlane.pixelStride != 2 || vPlane.pixelStride != 2 ||
            uPlane.rowStride != vPlane.rowStride
        ) {
            return false
        }

        val u = uPlane.buffer.asReadOnlyBuffer()
        val v = vPlane.buffer.asReadOnlyBuffer()
        val uBase = u.position()
        val vBase = v.position()
        val sharedByteCount = minOf(u.limit() - uBase, v.limit() - vBase - 1)
        if (sharedByteCount <= 0) return false

        val shiftedV = v.duplicate().apply {
            position(vBase + 1)
            limit(vBase + 1 + sharedByteCount)
        }.slice()
        val uBytes = u.duplicate().apply {
            position(uBase)
            limit(uBase + sharedByteCount)
        }.slice()
        if (shiftedV != uBytes) return false

        val chromaTop = crop.top / 2
        val chromaLeftBytes = crop.left
        val chromaHeight = crop.height / 2
        return (0 until chromaHeight).all { row ->
            val relativeStart = (chromaTop + row) * vPlane.rowStride + chromaLeftBytes
            val start = vBase + (chromaTop + row) * vPlane.rowStride + chromaLeftBytes
            start >= vBase &&
                start + crop.width <= v.limit() &&
                relativeStart + crop.width - 1 <= sharedByteCount
        }
    }

    private fun copyInterleavedVuRows(
        vPlane: YuvPlaneData,
        crop: LiveCropRect,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        val source = vPlane.buffer.asReadOnlyBuffer()
        val base = source.position()
        val chromaTop = crop.top / 2
        val chromaLeftBytes = crop.left
        for (row in 0 until crop.height / 2) {
            val sourceOffset = base + (chromaTop + row) * vPlane.rowStride + chromaLeftBytes
            bulkCopy(
                source = source,
                sourceOffset = sourceOffset,
                destination = destination,
                destinationOffset = destinationOffset + row * crop.width,
                byteCount = crop.width,
            )
        }
    }

    private fun copyGenericVuRows(
        uPlane: YuvPlaneData,
        vPlane: YuvPlaneData,
        crop: LiveCropRect,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        val u = uPlane.buffer.asReadOnlyBuffer()
        val v = vPlane.buffer.asReadOnlyBuffer()
        val uBase = u.position()
        val vBase = v.position()
        val chromaTop = crop.top / 2
        val chromaLeft = crop.left / 2
        val chromaWidth = crop.width / 2
        for (row in 0 until crop.height / 2) {
            val uRow = uBase + (chromaTop + row) * uPlane.rowStride
            val vRow = vBase + (chromaTop + row) * vPlane.rowStride
            val destinationRow = destinationOffset + row * crop.width
            for (column in 0 until chromaWidth) {
                val destinationIndex = destinationRow + column * 2
                destination[destinationIndex] =
                    v.get(vRow + (chromaLeft + column) * vPlane.pixelStride)
                destination[destinationIndex + 1] =
                    u.get(uRow + (chromaLeft + column) * uPlane.pixelStride)
            }
        }
    }

    private fun bulkCopy(
        source: ByteBuffer,
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ) {
        source.position(sourceOffset)
        source.get(destination, destinationOffset, byteCount)
    }
}
