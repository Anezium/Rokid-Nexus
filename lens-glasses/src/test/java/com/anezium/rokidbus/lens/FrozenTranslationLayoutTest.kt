package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenTranslationLayoutTest {
    @Test
    fun paragraphGapGreaterThanPointEightFiveMedianLineHeightSplits() {
        val blocks = listOf(
            block(line("first", left = 10, top = 0, width = 180, height = 20)),
            block(line("second", left = 10, top = 38, width = 180, height = 20)),
        )

        val paragraphs = segmentFrozenParagraphs(blocks)

        assertEquals(listOf("first", "second"), paragraphs.map { it.source })
    }

    @Test
    fun changedFirstLineIndentSplitsParagraph() {
        val blocks = listOf(
            block(
                line("paragraph one", left = 10, top = 0),
                line("continues", left = 10, top = 20),
            ),
            block(
                line("indented start", left = 24, top = 40),
                line("continues again", left = 10, top = 60),
            ),
        )

        val paragraphs = segmentFrozenParagraphs(blocks)

        assertEquals(2, paragraphs.size)
        assertEquals("paragraph one continues", paragraphs[0].source)
        assertEquals("indented start continues again", paragraphs[1].source)
    }

    @Test
    fun headingFontJumpGreaterThanOnePointThreeSplits() {
        val blocks = listOf(
            block(line("Heading", left = 10, top = 0, height = 28)),
            block(line("body copy", left = 10, top = 28, height = 20)),
        )

        val paragraphs = segmentFrozenParagraphs(blocks)

        assertEquals(listOf("Heading", "body copy"), paragraphs.map { it.source })
    }

    @Test
    fun lineAndCharacterCapsSplitOnlyAtSourceLineBoundaries() {
        val lines = (1..10).map { index -> line("line$index", left = 10, top = (index - 1) * 20) }

        val lineCapped = segmentFrozenParagraphs(
            blocks = listOf(FrozenLayoutBlock(lines)),
            maxLines = 3,
            maxChars = 1_000,
        )
        val charCapped = segmentFrozenParagraphs(
            blocks = listOf(
                FrozenLayoutBlock(
                    listOf(
                        line("aaaaa", left = 10, top = 0),
                        line("bbbbb", left = 10, top = 20),
                        line("ccccc", left = 10, top = 40),
                        line("ddddd", left = 10, top = 60),
                    ),
                ),
            ),
            maxLines = 20,
            maxChars = 11,
        )

        assertEquals(listOf(3, 3, 3, 1), lineCapped.map { it.lines.size })
        assertEquals(lines.map { it.text }, lineCapped.flatMap { it.lines }.map { it.text })
        assertTrue(lineCapped.all { it.lines.size <= 3 })
        assertEquals(listOf(2, 2), charCapped.map { it.lines.size })
        assertTrue(charCapped.all { it.source.length <= 11 })
        assertEquals(listOf("aaaaa", "bbbbb", "ccccc", "ddddd"), charCapped.flatMap { it.lines }.map { it.text })
    }

    @Test
    fun pathologicalSingleLineStillRespectsCharacterCapWithoutDroppingText() {
        val source = "alpha beta gamma delta epsilon"

        val paragraphs = segmentFrozenParagraphs(
            blocks = listOf(block(line(source, left = 10, top = 0, width = 300))),
            maxLines = 8,
            maxChars = 10,
        )

        assertTrue(paragraphs.all { it.source.length <= 10 })
        assertEquals(source.split(' '), paragraphs.flatMap { it.source.split(' ') })
        assertEquals(10, paragraphs.first().bounds.left)
        assertEquals(310, paragraphs.last().bounds.right)
    }

    @Test
    fun columnsAndResultsAreDeterministicAcrossInputPermutation() {
        val input = listOf(
            block(line("right one", left = 220, top = 0)),
            block(line("left two", left = 10, top = 20)),
            block(line("right two", left = 220, top = 20)),
            block(line("left one", left = 10, top = 0)),
        )

        val first = segmentFrozenParagraphs(input)
        val repeated = segmentFrozenParagraphs(input)
        val reversed = segmentFrozenParagraphs(input.reversed())

        assertEquals(first, repeated)
        assertEquals(first, reversed)
        assertEquals(listOf("left one left two", "right one right two"), first.map { it.source })
    }

    @Test
    fun paragraphGrowthBudgetStopsBeforeNextPanelInSameColumn() {
        val paragraphs = segmentFrozenParagraphs(
            listOf(
                block(
                    line("one", left = 10, top = 0),
                    line("two", left = 10, top = 20),
                    line("three", left = 10, top = 40),
                ),
                block(line("next", left = 10, top = 78)),
            ),
        )

        assertEquals(2, paragraphs.size)
        assertEquals(16f, paragraphs[0].gapBelow, 0.01f)
        assertEquals(7f, paragraphs[1].gapBelow, 0.01f)
        assertEquals(paragraphs[0].columnIndex, paragraphs[1].columnIndex)
    }

    private fun block(vararg lines: FrozenLayoutLine): FrozenLayoutBlock =
        FrozenLayoutBlock(lines.toList())

    private fun line(
        text: String,
        left: Int,
        top: Int,
        width: Int = 180,
        height: Int = 20,
    ): FrozenLayoutLine =
        FrozenLayoutLine(text, FrozenLayoutRect(left, top, left + width, top + height))
}
