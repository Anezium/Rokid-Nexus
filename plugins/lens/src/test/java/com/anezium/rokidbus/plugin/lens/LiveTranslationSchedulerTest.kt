package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslationSchedulerTest {
    @Test
    fun `25 live misses cover every source in subsequent batches`() {
        val provider = RecordingProvider()
        val updates = mutableListOf<LiveTranslationUpdate>()
        val scheduler = LiveTranslationScheduler(provider, {}, updates::addAll)

        scheduler.submit(candidates(25))
        assertEquals(24, provider.requests.single().strings.size)
        provider.succeedNext()
        assertEquals(2, provider.requests.size)
        assertEquals(listOf("source-24"), provider.requests.last().strings)
        provider.succeedNext()

        assertEquals((0 until 25).map { "source-$it" }.toSet(), updates.map { it.candidate.sourceText }.toSet())
        assertEquals(25, updates.size)
    }

    @Test
    fun `64 live misses preserve age and do not starve the suffix`() {
        val provider = RecordingProvider()
        val updates = mutableListOf<LiveTranslationUpdate>()
        val scheduler = LiveTranslationScheduler(provider, {}, updates::addAll)

        scheduler.submit(candidates(64))
        while (provider.hasPending()) provider.succeedNext()

        assertEquals(listOf(24, 24, 16), provider.requests.map { it.strings.size })
        assertEquals((0 until 64).map { "source-$it" }, updates.map { it.candidate.sourceText })
    }

    @Test
    fun `duplicate sources share one provider item and fan out`() {
        val provider = RecordingProvider()
        val updates = mutableListOf<LiveTranslationUpdate>()
        val scheduler = LiveTranslationScheduler(provider, {}, updates::addAll)
        val duplicate = candidate(1, "same")

        scheduler.submit(listOf(duplicate, duplicate.copy(stableId = 2), candidate(3, "other")))
        assertEquals(listOf("same", "other"), provider.requests.single().strings)
        provider.succeedNext()

        assertEquals(listOf(1L, 2L, 3L), updates.map { it.candidate.stableId })
    }

    @Test
    fun `callback errors become explicit per-source failures`() {
        val provider = RecordingProvider()
        val updates = mutableListOf<LiveTranslationUpdate>()
        val scheduler = LiveTranslationScheduler(provider, {}, updates::addAll)

        scheduler.submit(candidates(2))
        provider.failNext(TranslationErrorCode.TIMEOUT)

        assertTrue(updates.all { it.result.fallback })
        assertTrue(updates.all { it.result.failure == TranslationErrorCode.TIMEOUT })
    }

    @Test
    fun `frozen 128 sources run in deterministic sequential batches`() {
        val provider = RecordingProvider()
        var completed: Map<String, TranslationResult>? = null
        FrozenTranslationBatch(
            translator = provider,
            requestPrefix = "freeze-1",
            targetLanguage = "fr",
            mode = LensRecognizerMode.LATIN,
            sources = (0 until 128).map { "source-$it" },
            log = {},
            onComplete = { completed = it },
        ).start()

        while (provider.hasPending()) provider.succeedNext()

        assertEquals(listOf(24, 24, 24, 24, 24, 8), provider.requests.map { it.strings.size })
        assertEquals(128, completed!!.size)
    }

    @Test
    fun `frozen 25 sources use two batches and complete once`() {
        val provider = RecordingProvider()
        var completions = 0
        FrozenTranslationBatch(
            translator = provider,
            requestPrefix = "freeze-2",
            targetLanguage = "fr",
            mode = LensRecognizerMode.LATIN,
            sources = (0 until 25).map { "source-$it" },
            log = {},
            onComplete = { completions += 1 },
        ).start()

        while (provider.hasPending()) provider.succeedNext()

        assertEquals(listOf(24, 1), provider.requests.map { it.strings.size })
        assertEquals(1, completions)
    }

    @Test
    fun `request batching observes item and byte limits`() {
        val provider = RecordingProvider()
        val scheduler = LiveTranslationScheduler(provider, {}, {})
        val source = "x".repeat(1_000)
        scheduler.submit((0 until 24).map { candidate(it.toLong(), "$it-$source") })

        val request = provider.requests.single()
        assertTrue(request.strings.size <= LensWireContract.MAX_STRING_COUNT)
        assertTrue(request.strings.sumOf { it.toByteArray(Charsets.UTF_8).size } <= LensWireContract.MAX_TOTAL_SOURCE_BYTES)
        assertTrue(translationRequestJsonBytes(request) > 3 * 1024)
    }

    private fun candidates(count: Int): List<LiveTranslationCandidate> =
        (0 until count).map { candidate(it.toLong(), "source-$it") }

    private fun candidate(id: Long, source: String) = LiveTranslationCandidate(
        sessionGeneration = 1,
        composerGeneration = 2,
        script = OcrScript.LATIN,
        targetLanguage = "fr",
        stableId = id,
        sourceText = source,
    )

    private class RecordingProvider : TranslationProvider {
        data class Pending(
            val request: TranslationRequest,
            val callback: TranslationProvider.Callback,
            var canceled: Boolean = false,
        )

        val requests = mutableListOf<TranslationRequest>()
        private val pending = ArrayDeque<Pending>()

        override fun translate(
            request: TranslationRequest,
            callback: TranslationProvider.Callback,
        ): TranslationCall {
            requests += request
            val work = Pending(request, callback)
            pending += work
            return TranslationCall { work.canceled = true }
        }

        fun hasPending(): Boolean = pending.any { !it.canceled }

        fun succeedNext() {
            val work = pending.removeFirst()
            assertFalse(work.canceled)
            work.callback.onSuccess(
                work.request.strings.map { source ->
                    TranslationResult(source, "translated-$source", "en")
                },
            )
        }

        fun failNext(error: TranslationErrorCode) {
            val work = pending.removeFirst()
            assertFalse(work.canceled)
            work.callback.onError(error)
        }

        override fun close() = Unit
    }
}
