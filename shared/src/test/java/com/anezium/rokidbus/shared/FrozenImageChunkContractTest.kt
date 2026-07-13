package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrozenImageChunkContractTest {
    @Test
    fun `valid final chunk parses`() {
        val total = FrozenImageChunkContract.CHUNK_BYTES + 17
        val metadata = FrozenImageChunkContract.Metadata(
            sessionId = "session",
            requestId = 7,
            transferId = "transfer",
            chunkIndex = 1,
            chunkCount = 2,
            totalBytes = total,
            width = 2_048,
            height = 1_536,
            rotationDegrees = 270,
            sha256 = "a".repeat(64),
        )

        val parsed = FrozenImageChunkContract.parse(
            FrozenImageChunkContract.metadataJson(metadata),
            chunkSize = 17,
        )

        assertNotNull(parsed)
        assertEquals(metadata, parsed)
    }

    @Test
    fun `video mime and oversized chunks are rejected`() {
        val metadata = FrozenImageChunkContract.Metadata(
            "session", 7, "transfer", 0, 1, 10, 640, 480, 0, "b".repeat(64),
        )
        assertNull(
            FrozenImageChunkContract.parse(
                FrozenImageChunkContract.metadataJson(metadata).put("mimeType", "video/avc"),
                10,
            ),
        )
        assertNull(
            FrozenImageChunkContract.parse(
                FrozenImageChunkContract.metadataJson(metadata),
                FrozenImageChunkContract.CHUNK_BYTES + 1,
            ),
        )
    }

    @Test
    fun `hash is stable lowercase hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FrozenImageChunkContract.sha256("abc".toByteArray()),
        )
    }
}
