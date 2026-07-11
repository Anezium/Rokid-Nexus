package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslationLayoutTest {
    @Test
    fun progressiveParagraphIsOutlineOnlyWithZeroTranslatedMembers() {
        assertEquals(null, progressiveLiveParagraphTranslation(listOf(null, null)))
    }

    @Test
    fun progressiveParagraphJoinsAvailableMembersAndMarksMissingMembers() {
        assertEquals(
            "first third …",
            progressiveLiveParagraphTranslation(listOf("first", null, "third")),
        )
    }

    @Test
    fun progressiveParagraphJoinsAllMembersWithoutSuffix() {
        assertEquals(
            "first second third",
            progressiveLiveParagraphTranslation(listOf("first", "second", "third")),
        )
    }

    @Test
    fun separatedParagraphPanelsRemainIndependentlySelectable() {
        val panels = listOf(
            placed(1, top = 10f),
            placed(2, top = 34f),
            placed(3, top = 58f),
            placed(4, top = 82f),
        )

        val selected = selectNonOverlappingLiveBlockIds(panels)

        assertEquals(listOf(1L, 2L, 3L, 4L), selected)
        assertEquals(4, selected.size)
        assertPairwiseNonOverlapping(panels.map { it.bounds })
    }

    @Test
    fun residualOverlapSelectionIsDeterministicAndOlderStableIdWins() {
        val blocks = listOf(
            LivePlacedBlock(4, LiveLayoutRect(10f, 10f, 110f, 70f)),
            LivePlacedBlock(2, LiveLayoutRect(10f, 10f, 110f, 70f)),
            LivePlacedBlock(9, LiveLayoutRect(10f, 90f, 110f, 140f)),
        )

        val selected = selectNonOverlappingLiveBlockIds(blocks)
        val selectedAgain = selectNonOverlappingLiveBlockIds(blocks.reversed())
        val selectedBounds = blocks.filter { it.stableId in selected }.map { it.bounds }

        assertEquals(listOf(2L, 9L), selected)
        assertEquals(selected, selectedAgain)
        assertPairwiseNonOverlapping(selectedBounds)
    }

    @Test
    fun sameTrackedSetProducesIdenticalGroupsAndMemberIdentity() {
        val tracked = listOf(
            liveLine(7L, "alpha", left = 10, top = 0),
            liveLine(4L, "duplicate", left = 10, top = 20),
            liveLine(2L, "duplicate", left = 10, top = 20),
            liveLine(9L, "right column", left = 220, top = 0),
        )

        val first = segmentLiveParagraphs(tracked)
        val repeated = segmentLiveParagraphs(tracked)
        val reversed = segmentLiveParagraphs(tracked.reversed())

        assertEquals(first, repeated)
        assertEquals(first, reversed)
        assertEquals(listOf(listOf(7L, 2L, 4L), listOf(9L)), first.map { group ->
            group.members.map { it.stableId }
        })
        assertEquals(listOf(listOf(2L, 4L, 7L), listOf(9L)), first.map { it.memberStableIds })
        assertEquals(listOf(2L, 9L), first.map { it.collisionStableId })
    }

    @Test
    fun liveGroupingUsesFrozenGapAndIndentBoundaries() {
        val groups = segmentLiveParagraphs(
            listOf(
                liveLine(1L, "first line", left = 10, top = 0),
                liveLine(2L, "continues", left = 10, top = 20),
                liveLine(3L, "after gap", left = 10, top = 58),
                liveLine(4L, "indented", left = 26, top = 78),
            ),
        )

        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L)), groups.map { group ->
            group.members.map { it.stableId }
        })
        assertTrue(groups.all { it.gapBelow >= 0f })
    }

    private fun placed(id: Long, top: Float): LivePlacedBlock =
        LivePlacedBlock(id, LiveLayoutRect(10f, top, 210f, top + 20f))

    private fun liveLine(
        id: Long,
        source: String,
        left: Int,
        top: Int,
        width: Int = 180,
        height: Int = 20,
    ): LiveParagraphLine =
        LiveParagraphLine(
            stableId = id,
            source = source,
            bounds = FrozenLayoutRect(left, top, left + width, top + height),
        )

    private fun assertPairwiseNonOverlapping(rects: List<LiveLayoutRect>) {
        rects.forEachIndexed { leftIndex, left ->
            for (rightIndex in leftIndex + 1 until rects.size) {
                assertFalse("$left overlaps ${rects[rightIndex]}", left.intersects(rects[rightIndex]))
            }
        }
    }
}
