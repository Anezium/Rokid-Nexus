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
}
