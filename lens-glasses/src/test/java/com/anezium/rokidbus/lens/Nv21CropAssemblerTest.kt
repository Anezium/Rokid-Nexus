package com.anezium.rokidbus.lens

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Nv21CropAssemblerTest {
    @Test
    fun copiesPaddedYAndSharedInterleavedVuWithBulkRows() {
        val yRowStride = 10
        val y = ByteArray(yRowStride * 6)
        for (row in 0 until 6) {
            for (column in 0 until 8) y[row * yRowStride + column] = (row * 10 + column).toByte()
        }

        val uvRowStride = 10
        val vu = ByteArray(uvRowStride * 3)
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                vu[row * uvRowStride + column * 2] = (60 + row * 10 + column).toByte()
                vu[row * uvRowStride + column * 2 + 1] = (100 + row * 10 + column).toByte()
            }
        }
        val v = slice(vu, 0, vu.size)
        val u = slice(vu, 1, vu.size - 1)
        val destination = ByteArray(24)

        val result = Nv21CropAssembler.copy(
            yPlane = YuvPlaneData(ByteBuffer.wrap(y), yRowStride, 1),
            uPlane = YuvPlaneData(u, uvRowStride, 2),
            vPlane = YuvPlaneData(v, uvRowStride, 2),
            crop = LiveCropRect(2, 2, 6, 6),
            destination = destination,
        )

        assertEquals(Nv21AssemblyResult(byteCount = 24, usedInterleavedFastPath = true), result)
        assertArrayEquals(
            bytes(
                22, 23, 24, 25,
                32, 33, 34, 35,
                42, 43, 44, 45,
                52, 53, 54, 55,
                71, 111, 72, 112,
                81, 121, 82, 122,
            ),
            destination,
        )
    }

    @Test
    fun copiesPaddedStridedYAndPlanarChromaWithGenericSampling() {
        val yRowStride = 18
        val y = ByteArray(yRowStride * 6)
        for (row in 0 until 6) {
            for (column in 0 until 8) y[row * yRowStride + column * 2] = (row * 10 + column).toByte()
        }

        val uRowStride = 6
        val vRowStride = 7
        val u = ByteArray(uRowStride * 3)
        val v = ByteArray(vRowStride * 3)
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                u[row * uRowStride + column] = (100 + row * 10 + column).toByte()
                v[row * vRowStride + column] = (60 + row * 10 + column).toByte()
            }
        }
        val destination = ByteArray(24)

        val result = Nv21CropAssembler.copy(
            yPlane = YuvPlaneData(ByteBuffer.wrap(y), yRowStride, 2),
            uPlane = YuvPlaneData(ByteBuffer.wrap(u), uRowStride, 1),
            vPlane = YuvPlaneData(ByteBuffer.wrap(v), vRowStride, 1),
            crop = LiveCropRect(2, 2, 6, 6),
            destination = destination,
        )

        assertEquals(Nv21AssemblyResult(byteCount = 24, usedInterleavedFastPath = false), result)
        assertArrayEquals(
            bytes(
                22, 23, 24, 25,
                32, 33, 34, 35,
                42, 43, 44, 45,
                52, 53, 54, 55,
                71, 111, 72, 112,
                81, 121, 82, 122,
            ),
            destination,
        )
    }

    private fun slice(bytes: ByteArray, offset: Int, length: Int): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, length).slice()

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
