package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.FrozenImageChunkContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrozenImageChunkAssemblerTest {
    @Test
    fun `assembles chunks arriving in reverse order`() {
        val jpeg = ByteArray(FrozenImageChunkContract.CHUNK_BYTES + 17) { (it % 251).toByte() }
        val sha = FrozenImageChunkContract.sha256(jpeg)
        val count = FrozenImageChunkContract.chunkCount(jpeg.size)
        val assembler = FrozenImageChunkAssembler()

        val last = metadata(jpeg, sha, count, 1)
        assertNull(assembler.accept(last, jpeg.copyOfRange(FrozenImageChunkContract.CHUNK_BYTES, jpeg.size)))
        val completed = assembler.accept(
            metadata(jpeg, sha, count, 0),
            jpeg.copyOfRange(0, FrozenImageChunkContract.CHUNK_BYTES),
        )!!

        assertArrayEquals(jpeg, completed.jpeg)
    }

    @Test
    fun `rejects completed transfer with wrong checksum`() {
        val jpeg = byteArrayOf(1, 2, 3)
        val assembler = FrozenImageChunkAssembler()
        assertNull(
            assembler.accept(
                metadata(jpeg, "0".repeat(64), 1, 0),
                jpeg,
            ),
        )
    }

    private fun metadata(jpeg: ByteArray, sha: String, count: Int, index: Int) =
        FrozenImageChunkContract.metadataJson(
            FrozenImageChunkContract.Metadata(
                sessionId = "session",
                requestId = 7L,
                transferId = "transfer",
                chunkIndex = index,
                chunkCount = count,
                totalBytes = jpeg.size,
                width = 2048,
                height = 1536,
                rotationDegrees = 90,
                sha256 = sha,
            ),
        )
}
