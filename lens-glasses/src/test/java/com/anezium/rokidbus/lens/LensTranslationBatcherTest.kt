package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTranslationBatcherTest {
    @Test
    fun limitsBatchToTwentyFourStrings() {
        val batch = selectLensTranslationBatch(
            (0 until 30).map { LensTranslationCandidate(it, "source-$it") },
        )

        assertEquals((0 until LensWireContract.MAX_STRING_COUNT).toList(), batch.selected.map { it.key })
        assertEquals((LensWireContract.MAX_STRING_COUNT until 30).toList(), batch.deferred.map { it.key })
        assertTrue(batch.skipped.isEmpty())
    }

    @Test
    fun respectsUtf8ByteLimitAndStillPacksLaterCandidate() {
        val batch = selectLensTranslationBatch(
            candidates = listOf(
                LensTranslationCandidate("first", "é".repeat(8)),
                LensTranslationCandidate("does-not-fit", "a".repeat(5)),
                LensTranslationCandidate("later-fit", "b".repeat(4)),
            ),
            maxStringCount = 24,
            maxStringChars = 1_024,
            maxTotalSourceBytes = 20,
        )

        assertEquals(listOf("first", "later-fit"), batch.selected.map { it.key })
        assertEquals(listOf("does-not-fit"), batch.deferred.map { it.key })
    }

    @Test
    fun skipsOversizedStringWithoutBlockingValidCandidates() {
        val batch = selectLensTranslationBatch(
            listOf(
                LensTranslationCandidate("too-long", "x".repeat(1_025)),
                LensTranslationCandidate("valid", "bonjour"),
            ),
        )

        assertEquals(listOf("valid"), batch.selected.map { it.key })
        assertEquals(listOf("too-long"), batch.skipped.map { it.key })
        assertTrue(batch.deferred.isEmpty())
    }

    @Test
    fun neverExceedsSixteenKibibytesOfUtf8Source() {
        val source = "界".repeat(1_000)
        val batch = selectLensTranslationBatch(
            (0 until 8).map { LensTranslationCandidate(it, source) },
        )

        val selectedBytes = batch.selected.sumOf { it.source.toByteArray(Charsets.UTF_8).size }
        assertTrue(selectedBytes <= LensWireContract.MAX_TOTAL_SOURCE_BYTES)
        assertTrue(batch.deferred.isNotEmpty())
    }
}
