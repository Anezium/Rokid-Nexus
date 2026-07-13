package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class FrozenOverlayAggregationTest {
    @Test
    fun `ordinary OCR lines become one paragraph overlay`() {
        val result = aggregateFrozenOverlayLines(
            listOf(
                FrozenLayoutBlock(
                    listOf(
                        FrozenLayoutLine("This is the first sentence", FrozenLayoutRect(40, 100, 600, 140)),
                        FrozenLayoutLine("and this is its continuation", FrozenLayoutRect(40, 150, 580, 190)),
                        FrozenLayoutLine("on a third line.", FrozenLayoutRect(40, 200, 360, 240)),
                    ),
                ),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(
            "This is the first sentence and this is its continuation on a third line.",
            result.single().source,
        )
        assertEquals(FrozenLayoutRect(40, 100, 600, 240), result.single().bounds)
        assertEquals(40f, result.single().medianLineHeight, 0f)
        assertEquals(49f, result.single().growDown, 0.001f)
        assertEquals(0, result.single().column)
    }

    @Test
    fun `separate columns remain separate overlay panels`() {
        val result = aggregateFrozenOverlayLines(
            listOf(
                FrozenLayoutBlock(listOf(FrozenLayoutLine("Left", FrozenLayoutRect(20, 20, 180, 60)))),
                FrozenLayoutBlock(listOf(FrozenLayoutLine("Right", FrozenLayoutRect(420, 20, 620, 60)))),
            ),
        )

        assertEquals(listOf("Left", "Right"), result.map(FrozenOverlayLine::source))
        assertEquals(listOf(0, 1), result.map(FrozenOverlayLine::column))
    }
}
