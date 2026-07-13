package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOverlayComposerTest {
    @Test
    fun stableIdAppearsAfterTwoFramesAndTextDebouncesForTwoObservations() {
        val composer = LiveOverlayComposer()
        val first = composer.observe(blocks("Sentence"), OcrScript.LATIN, 1_000, 1_000, 0L)
        assertNull(composer.complete(first, emptyMap()))

        val visible = composer.observe(blocks("Sentence"), OcrScript.LATIN, 1_000, 1_000, 100L)
        val initial = composer.complete(visible, emptyMap())!!.single()
        assertEquals("lens-live-1", initial.id)
        assertEquals("Sentence", initial.text)
        assertEquals(0.02f, initial.layout.medianLineHeight, 0f)
        assertEquals(0.007f, initial.layout.growDown, 0.0001f)
        assertEquals(0, initial.layout.column)

        val candidate = composer.observe(blocks("Sentence."), OcrScript.LATIN, 1_000, 1_000, 200L)
        assertNull(composer.complete(candidate, emptyMap()))
        val committed = composer.observe(blocks("Sentence."), OcrScript.LATIN, 1_000, 1_000, 300L)
        assertEquals("Sentence.", composer.complete(committed, emptyMap())!!.single().text)
    }

    @Test
    fun boxJitterIsDampedAndSubEpsilonEmissionIsSuppressed() {
        val composer = LiveOverlayComposer()
        composer.complete(composer.observe(blocks("stable"), OcrScript.LATIN, 1_000, 1_000, 0L), emptyMap())
        val visible = composer.observe(blocks("stable"), OcrScript.LATIN, 1_000, 1_000, 100L)
        val baseline = composer.complete(visible, emptyMap())!!.single()

        val tinyJitter = composer.observe(
            blocks("stable", left = 21),
            OcrScript.LATIN,
            1_000,
            1_000,
            200L,
        )
        assertNull(composer.complete(tinyJitter, emptyMap()))

        val largerJitter = composer.observe(
            blocks("stable", left = 60),
            OcrScript.LATIN,
            1_000,
            1_000,
            300L,
        )
        val moved = composer.complete(largerJitter, emptyMap())!!.single()
        assertTrue(moved.box.left > baseline.box.left)
        assertTrue(moved.box.left < 0.06f)
    }

    @Test
    fun continuousParagraphMetricsAreDampedAndCanTriggerMetricOnlyUpdates() {
        val composer = LiveOverlayComposer()
        val initialBlocks = multilineBlocks(lineHeights = listOf(5, 10, 5))
        composer.complete(composer.observe(initialBlocks, OcrScript.LATIN, 1_000, 1_000, 0L), emptyMap())
        val visible = composer.observe(initialBlocks, OcrScript.LATIN, 1_000, 1_000, 100L)
        val baseline = composer.complete(visible, emptyMap())!!.single()
        assertEquals(0.005f, baseline.layout.medianLineHeight, 0f)

        val changedMetrics = composer.observe(
            multilineBlocks(lineHeights = listOf(2, 16, 2)),
            OcrScript.LATIN,
            1_000,
            1_000,
            200L,
        )
        val changed = composer.complete(changedMetrics, emptyMap())!!.single()

        assertEquals(baseline.box, changed.box)
        assertTrue(changed.layout.medianLineHeight < baseline.layout.medianLineHeight)
        assertTrue(changed.layout.medianLineHeight > 0.002f)
        assertEquals(baseline.layout.growDown, changed.layout.growDown, 0f)
    }

    @Test
    fun translationIsRetainedWhenARepeatedRequestFails() {
        val composer = LiveOverlayComposer()
        composer.complete(composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 0L), emptyMap())
        val visible = composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 100L)
        val translated = composer.complete(
            visible,
            mapOf("hello" to TranslationResult("hello", "bonjour", "en")),
        )!!.single()
        assertEquals("bonjour", translated.text)

        val repeated = composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 200L)
        assertNull(composer.complete(repeated, emptyMap()))
        val providerVariation = composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 300L)
        assertNull(
            composer.complete(
                providerVariation,
                mapOf("hello" to TranslationResult("hello", "salut", "en")),
            ),
        )
    }

    @Test
    fun `stale result cannot overwrite newer exact source`() {
        val composer = LiveOverlayComposer()
        composer.complete(composer.observe(blocks("old"), OcrScript.LATIN, 1_000, 1_000, 0L, "fr"), emptyMap())
        val old = composer.observe(blocks("old"), OcrScript.LATIN, 1_000, 1_000, 100L, "fr")
        composer.complete(old, emptyMap())
        val staleCandidate = composer.translationCandidates(old, 7L).single()

        composer.observe(blocks("new"), OcrScript.LATIN, 1_000, 1_000, 200L, "fr")
        val current = composer.observe(blocks("new"), OcrScript.LATIN, 1_000, 1_000, 300L, "fr")
        composer.complete(current, emptyMap())
        val application = composer.applyTranslations(
            listOf(
                LiveTranslationUpdate(
                    staleCandidate,
                    TranslationResult("old", "ancien", "en"),
                ),
            ),
        )

        assertEquals(0, application.applied)
        assertEquals(1, application.rejected)
        assertEquals("new", composer.complete(current, emptyMap())?.single()?.text ?: "new")
    }

    @Test
    fun `oversized translation falls back explicitly without prefix truncation`() {
        val display = overlayDisplay(
            "complete source",
            TranslationResult("complete source", "x".repeat(1_025), "en"),
        )

        assertEquals("complete source", display.text)
        assertEquals(ROLE_TRANSLATION_FALLBACK, display.role)
        assertEquals("translation_too_large", display.reason)
        assertTrue(display.fallback)
    }

    @Test
    fun `pending source remains readable while translation is outstanding`() {
        val composer = LiveOverlayComposer()
        composer.complete(composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 0L, "fr"), emptyMap())
        val pending = composer.observe(blocks("hello"), OcrScript.LATIN, 1_000, 1_000, 100L, "fr")

        val item = composer.complete(pending, emptyMap())!!.single()
        assertEquals("hello", item.text)
        assertEquals(ROLE_PENDING, item.role)
    }

    private fun blocks(text: String, left: Int = 20): List<LiveFrameParagraphBlock> =
        listOf(
            LiveFrameParagraphBlock(
                listOf(
                    LiveFrameParagraphLine(
                        source = text,
                        bounds = FrozenLayoutRect(left, 20, left + 180, 40),
                    ),
                ),
            ),
        )

    private fun multilineBlocks(lineHeights: List<Int>): List<LiveFrameParagraphBlock> {
        var top = 20
        val lines = lineHeights.mapIndexed { index, height ->
            LiveFrameParagraphLine(
                source = "line-$index",
                bounds = FrozenLayoutRect(20, top, 200, top + height),
            ).also { top += height }
        }
        return listOf(LiveFrameParagraphBlock(lines))
    }
}
