package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOverlayCollisionTest {
    @Test
    fun preexistingSourceOverlapMayRemainDuringValidationButFinalPanelsAreDisjoint() {
        val obstacle = geometry("a", rect(0f, 0f, 100f, 40f), rect(0f, 0f, 100f, 40f))
        val source = rect(80f, 0f, 180f, 40f)
        assertTrue(doesNotIncreasePreexistingSourceOverlap(source, source, obstacle))

        val panels = listOf(
            obstacle,
            geometry("b", source, source),
        )
        val resolved = resolveAdaptivePanelCollisions(panels, 0f)
        val first = requireNotNull(resolved[0].panelRect)
        val second = requireNotNull(resolved[1].panelRect)
        assertFalse(overlaps(first, second))
    }

    @Test
    fun stablePriorityMakesInputPermutationStable() {
        val a = geometry("a", rect(0f, 0f, 80f, 40f), rect(0f, 0f, 120f, 40f))
        val b = geometry("b", rect(100f, 0f, 180f, 40f), rect(80f, 0f, 180f, 40f))
        val c = geometry("c", rect(160f, 0f, 240f, 40f), rect(140f, 0f, 240f, 40f))

        assertEquals(
            resolvedById(listOf(a, b, c)),
            resolvedById(listOf(c, a, b)),
        )
    }

    @Test
    fun compactPanelReservesGapBeforeLargeParagraph() {
        val large = geometry("a-large", rect(0f, 0f, 120f, 80f), rect(0f, 0f, 240f, 140f))
        val compact = geometry("z-compact", rect(40f, 100f, 200f, 120f), rect(40f, 100f, 200f, 120f))
        val result = resolveAdaptivePanelCollisions(listOf(large, compact), 0f)

        assertEquals(compact.panelRect, result[1].panelRect)
        val selectedLarge = requireNotNull(result[0].panelRect)
        assertFalse(overlaps(selectedLarge, compact.panelRect))
        assertEquals(100f, selectedLarge.bottom, 0f)
    }

    @Test
    fun duplicateStableIdSuppressesDeterministicStaleCopy() {
        val current = geometry("same", rect(0f, 0f, 80f, 40f), rect(0f, 0f, 80f, 40f))
        val stale = geometry("same", rect(100f, 0f, 180f, 40f), rect(100f, 0f, 180f, 40f))
        val result = resolveAdaptivePanelCollisions(listOf(stale, current), 0f)

        assertEquals(rect(0f, 0f, 80f, 40f), result[1].panelRect)
        assertNull(result[0].panelRect)
        assertEquals("same", result[0].suppressedByStableId)
    }

    @Test
    fun textCompleteSeparationBeatsLargerTruncatedAlternative() {
        val panels = listOf(
            geometry("a", rect(40f, 40f, 80f, 80f), rect(40f, 40f, 80f, 80f)),
            geometry("b", rect(0f, 0f, 120f, 120f), rect(0f, 0f, 120f, 120f)),
        )
        val result = resolveAdaptivePanelCollisions(panels, 0f) { panelIndex, candidate ->
            if (panelIndex == 0) {
                quality(complete = true, textSize = 12f)
            } else if (candidate.height >= 100f) {
                quality(complete = false, textSize = 12f, retained = 0.8f)
            } else {
                quality(complete = true, textSize = 10f)
            }
        }

        val selected = requireNotNull(result[1].panelRect)
        assertEquals(120f, selected.width, 0f)
        assertEquals(40f, selected.height, 0f)
    }

    @Test
    fun containingObstacleSuppressesPanelWhenNoReadableTrimExists() {
        val result = resolveAdaptivePanelCollisions(
            listOf(
                geometry("a", rect(0f, 0f, 100f, 100f), rect(0f, 0f, 100f, 100f)),
                geometry("b", rect(20f, 20f, 80f, 80f), rect(20f, 20f, 80f, 80f)),
            ),
            0f,
        ) { panelIndex, candidate ->
            if (panelIndex == 0 && candidate != rect(0f, 0f, 100f, 100f)) null else quality(true, 10f)
        }
        assertNull(result[0].panelRect)
        assertTrue(result[0].changed)
    }

    private fun resolvedById(panels: List<AdaptivePanelGeometry>): Map<String, ParagraphPanelRect?> =
        panels.zip(resolveAdaptivePanelCollisions(panels, 0f)).associate { (panel, result) ->
            panel.stableId to result.panelRect
        }

    private fun geometry(id: String, source: ParagraphPanelRect, panel: ParagraphPanelRect) =
        AdaptivePanelGeometry(id, source, panel)

    private fun quality(complete: Boolean, textSize: Float, retained: Float = 1f) =
        AdaptivePanelCandidateQuality(complete, textSize, retained)

    private fun overlaps(first: ParagraphPanelRect, second: ParagraphPanelRect): Boolean =
        minOf(first.right, second.right) > maxOf(first.left, second.left) &&
            minOf(first.bottom, second.bottom) > maxOf(first.top, second.top)

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        ParagraphPanelRect(left, top, right, bottom)
}
