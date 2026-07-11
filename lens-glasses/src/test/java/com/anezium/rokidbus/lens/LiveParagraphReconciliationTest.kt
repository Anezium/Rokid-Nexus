package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveParagraphReconciliationTest {
    @Test
    fun ninePixelVerticalJitterKeepsParagraphId() {
        val first = reconcile(observations = listOf(observation("stable article", top = 0f)), nowMs = 0L)
        val stableId = first.state.anchors.single().stableId

        val second = reconcile(
            state = first.state,
            observations = listOf(observation("stable article", top = 9f)),
            nowMs = 100L,
        )

        assertEquals(stableId, second.state.anchors.single().stableId)
        assertEquals(1, second.stats.matchedCount)
        assertEquals(9f, second.visibleAnchors.single().bounds.top, 0f)
    }

    @Test
    fun twoAndThreeWayMemberLineSplitsKeepOneParagraphId() {
        val unsplit = frameParagraph(
            frameLine("alpha beta gamma", left = 10, right = 190),
        )
        val twoWay = frameParagraph(
            frameLine("alpha", left = 10, right = 190, top = 0),
            frameLine("beta gamma", left = 10, right = 190, top = 20),
        )
        val threeWay = frameParagraph(
            frameLine("alpha", left = 10, right = 190, top = 0),
            frameLine("beta", left = 10, right = 190, top = 20),
            frameLine("gamma", left = 10, right = 190, top = 40),
        )

        val first = reconcile(observations = listOf(unsplit), nowMs = 0L)
        val stableId = first.state.anchors.single().stableId
        val second = reconcile(first.state, listOf(twoWay), nowMs = 100L)
        val third = reconcile(second.state, listOf(threeWay), nowMs = 200L)
        val directThreeWay = reconcile(first.state, listOf(threeWay), nowMs = 100L)

        assertEquals(stableId, second.state.anchors.single().stableId)
        assertEquals(stableId, third.state.anchors.single().stableId)
        assertEquals(stableId, directThreeWay.state.anchors.single().stableId)
        assertEquals(2, second.state.anchors.single().memberBounds.size)
        assertEquals(3, third.state.anchors.single().memberBounds.size)
        assertEquals("alpha beta gamma", third.state.anchors.single().sourceText)
    }

    @Test
    fun emptyAcceptedFramesPreserveArticleUntilFourSecondBackstop() {
        val first = reconcile(observations = listOf(observation("stable article")), nowMs = 0L)
        val visible = reconcile(first.state, listOf(observation("stable article")), nowMs = 100L)
        val emptyOne = reconcile(visible.state, emptyList(), nowMs = 200L)
        val emptyTwo = reconcile(emptyOne.state, emptyList(), nowMs = 1_000L)
        val justBeforeBackstop = reconcile(emptyTwo.state, emptyList(), nowMs = 4_099L)
        val atBackstop = reconcile(justBeforeBackstop.state, emptyList(), nowMs = 4_100L)

        assertEquals(visible.state.credibleFrameSerial, justBeforeBackstop.state.credibleFrameSerial)
        assertEquals(1, justBeforeBackstop.visibleAnchors.size)
        assertTrue(atBackstop.state.anchors.isEmpty())
        assertTrue(atBackstop.visibleAnchors.isEmpty())
        assertEquals(1, atBackstop.state.graveyard.size)
    }

    @Test
    fun repeatedIdenticalTextAtDifferentPositionsNeverSwapsIds() {
        val first = reconcile(
            observations = listOf(
                observation("repeated", left = 20f),
                observation("repeated", left = 300f),
            ),
            nowMs = 0L,
        )
        val leftId = first.state.anchors.minBy { it.bounds.left }.stableId
        val rightId = first.state.anchors.maxBy { it.bounds.left }.stableId

        val second = reconcile(
            state = first.state,
            observations = listOf(
                observation("repeated", left = 304f),
                observation("repeated", left = 24f),
            ).reversed(),
            nowMs = 100L,
        )

        assertEquals(leftId, second.state.anchors.minBy { it.bounds.left }.stableId)
        assertEquals(rightId, second.state.anchors.maxBy { it.bounds.left }.stableId)
    }

    @Test
    fun assignmentMaximizesValidMatchesBeforeMinimizingCompositeCost() {
        val leftBounds = LiveParagraphRect(0f, 20f, 100f, 40f)
        val rightBounds = LiveParagraphRect(80f, 20f, 180f, 40f)
        val state = LiveParagraphReconciliationState(
            anchors = listOf(
                anchor(1L, leftBounds),
                anchor(2L, rightBounds),
            ),
            nextStableId = 3L,
            credibleFrameSerial = 1L,
        )

        val result = reconcile(
            state = state,
            observations = listOf(
                observation("same", left = 80f, width = 100f),
                observation("same", left = -80f, width = 100f),
            ),
            nowMs = 100L,
        )

        assertEquals(2, result.stats.matchedCount)
        assertEquals(-80f, result.state.anchors.single { it.stableId == 1L }.bounds.left, 0f)
        assertEquals(80f, result.state.anchors.single { it.stableId == 2L }.bounds.left, 0f)
    }

    @Test
    fun paragraphSourceChangeRequiresTwoCredibleObservations() {
        val first = reconcile(observations = listOf(observation("Sentence")), nowMs = 0L)
        val stable = reconcile(first.state, listOf(observation("Sentence")), nowMs = 100L)
        val jitter = reconcile(stable.state, listOf(observation("Sentence.")), nowMs = 200L)
        val committed = reconcile(jitter.state, listOf(observation("Sentence.")), nowMs = 300L)

        assertEquals("Sentence", jitter.state.anchors.single().sourceText)
        assertEquals("Sentence.", jitter.state.anchors.single().candidateSourceText)
        assertEquals("Sentence.", committed.state.anchors.single().sourceText)
        assertNull(committed.state.anchors.single().candidateSourceText)
    }

    @Test
    fun nearbyDroppedParagraphRevivesItsStableId() {
        var result = reconcile(observations = listOf(observation("article", left = 20f)), nowMs = 0L)
        result = reconcile(result.state, listOf(observation("article", left = 20f)), nowMs = 100L)
        val stableId = result.state.anchors.single().stableId
        repeat(3) { miss ->
            result = reconcile(
                result.state,
                listOf(observation("other", left = 300f)),
                nowMs = 200L + miss * 100L,
            )
        }

        val revived = reconcile(
            result.state,
            listOf(
                observation("other", left = 300f),
                observation("article", left = 24f),
            ),
            nowMs = 600L,
        )

        assertEquals(1, revived.stats.revivedCount)
        assertEquals(stableId, revived.state.anchors.single { it.sourceText == "article" }.stableId)
    }

    @Test
    fun exactTextRevivalStillRequiresGeometry() {
        var result = reconcile(observations = listOf(observation("article", left = 20f)), nowMs = 0L)
        result = reconcile(result.state, listOf(observation("article", left = 20f)), nowMs = 100L)
        val oldId = result.state.anchors.single().stableId
        repeat(3) { miss ->
            result = reconcile(
                result.state,
                listOf(observation("other", left = 300f)),
                nowMs = 200L + miss * 100L,
            )
        }

        val farReappearance = reconcile(
            result.state,
            listOf(
                observation("other", left = 300f),
                observation("article", left = 700f),
            ),
            nowMs = 600L,
        )

        assertEquals(0, farReappearance.stats.revivedCount)
        assertNotEquals(oldId, farReappearance.state.anchors.single { it.sourceText == "article" }.stableId)
    }

    private fun reconcile(
        state: LiveParagraphReconciliationState = LiveParagraphReconciliationState(),
        observations: List<LiveParagraphObservation>,
        nowMs: Long,
    ): LiveParagraphReconciliationResult =
        LiveParagraphReconciler.reconcile(state, observations, nowMs)

    private fun observation(
        text: String,
        left: Float = 20f,
        top: Float = 20f,
        width: Float = 180f,
        height: Float = 20f,
    ): LiveParagraphObservation {
        val bounds = LiveParagraphRect(left, top, left + width, top + height)
        return LiveParagraphObservation(
            sourceText = text,
            bounds = bounds,
            memberBounds = listOf(bounds),
            columnIndex = 0,
            gapBelow = 0f,
        )
    }

    private fun anchor(stableId: Long, bounds: LiveParagraphRect): LiveParagraphAnchor =
        LiveParagraphAnchor(
            stableId = stableId,
            sourceText = "same",
            bounds = bounds,
            memberBounds = listOf(bounds),
            columnIndex = 0,
            gapBelow = 0f,
            lastSeenCredibleSerial = 1L,
            lastSeenAtMs = 0L,
            visible = true,
        )

    private fun frameParagraph(vararg lines: LiveFrameParagraphLine): LiveParagraphObservation {
        val paragraph = segmentLiveFrameParagraphs(
            listOf(LiveFrameParagraphBlock(lines.toList())),
        ).single()
        return LiveParagraphObservation(
            sourceText = paragraph.source,
            bounds = paragraph.bounds.toLiveParagraphRect(),
            memberBounds = paragraph.lineBounds.map { it.toLiveParagraphRect() },
            columnIndex = paragraph.columnIndex,
            gapBelow = paragraph.gapBelow,
        )
    }

    private fun frameLine(source: String, left: Int, right: Int, top: Int = 0): LiveFrameParagraphLine =
        LiveFrameParagraphLine(source, FrozenLayoutRect(left, top, right, top + 20))

    private fun FrozenLayoutRect.toLiveParagraphRect(): LiveParagraphRect =
        LiveParagraphRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
