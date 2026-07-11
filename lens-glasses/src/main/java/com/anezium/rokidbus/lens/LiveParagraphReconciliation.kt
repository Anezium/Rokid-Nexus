package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LensWireContract
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class LiveParagraphRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal data class LiveParagraphObservation(
    val sourceText: String,
    val bounds: LiveParagraphRect,
    val memberBounds: List<LiveParagraphRect>,
    val columnIndex: Int,
    val gapBelow: Float,
)

internal data class LiveParagraphAnchor(
    val stableId: Long,
    val sourceText: String,
    val bounds: LiveParagraphRect,
    val memberBounds: List<LiveParagraphRect>,
    val columnIndex: Int,
    val gapBelow: Float,
    val lastSeenCredibleSerial: Long,
    val lastSeenAtMs: Long,
    val candidateSourceText: String? = null,
    val candidateSourceObservations: Int = 0,
    val candidateLastSeenCredibleSerial: Long = -1L,
    val visible: Boolean = false,
)

internal data class LiveParagraphGraveyardEntry(
    val anchor: LiveParagraphAnchor,
    val droppedAtMs: Long,
)

internal data class LiveParagraphReconciliationState(
    val anchors: List<LiveParagraphAnchor> = emptyList(),
    val graveyard: List<LiveParagraphGraveyardEntry> = emptyList(),
    val nextStableId: Long = 1L,
    val credibleFrameSerial: Long = 0L,
)

internal data class LiveParagraphReconciliationStats(
    val matchedCount: Int = 0,
    val revivedCount: Int = 0,
    val newCount: Int = 0,
    val droppedCount: Int = 0,
    val paragraphAnchorCount: Int = 0,
)

internal data class LiveParagraphReconciliationResult(
    val state: LiveParagraphReconciliationState,
    val visibleAnchors: List<LiveParagraphAnchor>,
    val stats: LiveParagraphReconciliationStats,
)

/** Pure paragraph-anchor state transition used only on the serialized OCR result path. */
internal object LiveParagraphReconciler {
    fun reconcile(
        state: LiveParagraphReconciliationState,
        observations: List<LiveParagraphObservation>,
        nowMs: Long,
    ): LiveParagraphReconciliationResult {
        val canonicalObservations = observations
            .asSequence()
            .mapNotNull(::sanitizeObservation)
            .sortedWith(OBSERVATION_ORDER)
            .take(LIVE_PARAGRAPH_MAX_ANCHORS)
            .toList()
        val hardExpiredAnchors = state.anchors.filter { anchor ->
            nowMs - anchor.lastSeenAtMs >= LIVE_TRACK_STALE_HARD_MS
        }
        var droppedCount = hardExpiredAnchors.size
        val active = state.anchors
            .asSequence()
            .filter { nowMs - it.lastSeenAtMs < LIVE_TRACK_STALE_HARD_MS }
            .sortedBy { it.stableId }
            .take(LIVE_PARAGRAPH_MAX_ANCHORS)
            .toMutableList()
        var graveyard = (
            state.graveyard.filter { nowMs - it.droppedAtMs < LIVE_TRACK_GRAVEYARD_MS } +
                hardExpiredAnchors.map { LiveParagraphGraveyardEntry(anchor = it, droppedAtMs = nowMs) }
            )
            .asSequence()
            .sortedWith(GRAVEYARD_ORDER)
            .toList()
            .takeLast(LIVE_TRACK_GRAVEYARD_CAPACITY)
            .toMutableList()

        if (canonicalObservations.isEmpty()) {
            val nextState = state.copy(
                anchors = active,
                graveyard = graveyard,
            )
            return LiveParagraphReconciliationResult(
                state = nextState,
                visibleAnchors = active.filter { it.visible },
                stats = LiveParagraphReconciliationStats(
                    droppedCount = droppedCount,
                    paragraphAnchorCount = active.size,
                ),
            )
        }

        val credibleSerial = state.credibleFrameSerial + 1L
        val activeAssignments = minimumCostAssignments(
            oldTexts = Array(active.size) { active[it].sourceText },
            oldRects = Array(active.size) { active[it].bounds },
            observations = canonicalObservations,
        )
        val observationMatched = BooleanArray(canonicalObservations.size)
        val nextAnchors = mutableListOf<LiveParagraphAnchor>()
        var matchedCount = 0

        active.forEachIndexed { anchorIndex, anchor ->
            val observationIndex = activeAssignments.getOrElse(anchorIndex) { -1 }
            if (observationIndex >= 0) {
                observationMatched[observationIndex] = true
                nextAnchors += updateAnchor(
                    anchor = anchor,
                    observation = canonicalObservations[observationIndex],
                    credibleSerial = credibleSerial,
                    nowMs = nowMs,
                    forceVisible = false,
                )
                matchedCount += 1
            } else if (credibleSerial - anchor.lastSeenCredibleSerial >= LIVE_TRACK_STALE_FRAMES) {
                graveyard += LiveParagraphGraveyardEntry(anchor = anchor, droppedAtMs = nowMs)
                droppedCount += 1
            } else {
                nextAnchors += anchor
            }
        }
        graveyard = graveyard
            .sortedWith(GRAVEYARD_ORDER)
            .takeLast(LIVE_TRACK_GRAVEYARD_CAPACITY)
            .toMutableList()

        val remainingObservationIndices = canonicalObservations.indices
            .filterNot { observationMatched[it] }
        val remainingObservations = remainingObservationIndices.map(canonicalObservations::get)
        val graveAssignments = minimumCostAssignments(
            oldTexts = Array(graveyard.size) { graveyard[it].anchor.sourceText },
            oldRects = Array(graveyard.size) { graveyard[it].anchor.bounds },
            observations = remainingObservations,
        )
        val retainedGraveyard = mutableListOf<LiveParagraphGraveyardEntry>()
        var revivedCount = 0
        graveyard.forEachIndexed { graveIndex, entry ->
            val remainingIndex = graveAssignments.getOrElse(graveIndex) { -1 }
            if (remainingIndex >= 0) {
                observationMatched[remainingObservationIndices[remainingIndex]] = true
                nextAnchors += updateAnchor(
                    anchor = entry.anchor,
                    observation = remainingObservations[remainingIndex],
                    credibleSerial = credibleSerial,
                    nowMs = nowMs,
                    forceVisible = true,
                )
                revivedCount += 1
            } else {
                retainedGraveyard += entry
            }
        }

        var nextStableId = state.nextStableId
        var newCount = 0
        canonicalObservations.forEachIndexed { observationIndex, observation ->
            if (observationMatched[observationIndex]) return@forEachIndexed
            nextAnchors += LiveParagraphAnchor(
                stableId = nextStableId++,
                sourceText = observation.sourceText,
                bounds = observation.bounds,
                memberBounds = observation.memberBounds,
                columnIndex = observation.columnIndex,
                gapBelow = observation.gapBelow,
                lastSeenCredibleSerial = credibleSerial,
                lastSeenAtMs = nowMs,
            )
            newCount += 1
        }

        val canonicalAnchors = nextAnchors
            .sortedBy { it.stableId }
            .take(LIVE_PARAGRAPH_MAX_ANCHORS)
        val nextState = LiveParagraphReconciliationState(
            anchors = canonicalAnchors,
            graveyard = retainedGraveyard.sortedWith(GRAVEYARD_ORDER),
            nextStableId = nextStableId,
            credibleFrameSerial = credibleSerial,
        )
        return LiveParagraphReconciliationResult(
            state = nextState,
            // Once visible, an anchor stays rendered until the drop policy retires it: a
            // single missed match must not blink the panel off (field 2026-07-11, "le mode
            // live clignote"). Stability wins over one-frame freshness.
            visibleAnchors = canonicalAnchors.filter { it.visible },
            stats = LiveParagraphReconciliationStats(
                matchedCount = matchedCount,
                revivedCount = revivedCount,
                newCount = newCount,
                droppedCount = droppedCount,
                paragraphAnchorCount = canonicalAnchors.size,
            ),
        )
    }

    private fun sanitizeObservation(observation: LiveParagraphObservation): LiveParagraphObservation? {
        val source = LensWireContract.normalizeText(observation.sourceText)
        if (source.isEmpty() || observation.bounds.width <= 0f || observation.bounds.height <= 0f) return null
        val members = observation.memberBounds.filter { it.width > 0f && it.height > 0f }
        return observation.copy(
            sourceText = source,
            memberBounds = members.ifEmpty { listOf(observation.bounds) },
            gapBelow = observation.gapBelow.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f,
        )
    }

    private fun updateAnchor(
        anchor: LiveParagraphAnchor,
        observation: LiveParagraphObservation,
        credibleSerial: Long,
        nowMs: Long,
        forceVisible: Boolean,
    ): LiveParagraphAnchor {
        val wasConsecutive = anchor.lastSeenCredibleSerial == credibleSerial - 1L
        if (observation.sourceText == anchor.sourceText) {
            return anchor.copy(
                bounds = observation.bounds,
                memberBounds = observation.memberBounds,
                columnIndex = observation.columnIndex,
                gapBelow = observation.gapBelow,
                lastSeenCredibleSerial = credibleSerial,
                lastSeenAtMs = nowMs,
                candidateSourceText = null,
                candidateSourceObservations = 0,
                candidateLastSeenCredibleSerial = -1L,
                visible = forceVisible || anchor.visible || wasConsecutive,
            )
        }

        val sameConsecutiveCandidate = anchor.candidateSourceText == observation.sourceText &&
            anchor.candidateLastSeenCredibleSerial == credibleSerial - 1L
        val candidateObservations = if (sameConsecutiveCandidate) {
            anchor.candidateSourceObservations + 1
        } else {
            1
        }
        val commitCandidate = candidateObservations >= LIVE_PARAGRAPH_SOURCE_DEBOUNCE_OBSERVATIONS
        return anchor.copy(
            sourceText = if (commitCandidate) observation.sourceText else anchor.sourceText,
            bounds = observation.bounds,
            memberBounds = observation.memberBounds,
            columnIndex = observation.columnIndex,
            gapBelow = observation.gapBelow,
            lastSeenCredibleSerial = credibleSerial,
            lastSeenAtMs = nowMs,
            candidateSourceText = if (commitCandidate) null else observation.sourceText,
            candidateSourceObservations = if (commitCandidate) 0 else candidateObservations,
            candidateLastSeenCredibleSerial = if (commitCandidate) -1L else credibleSerial,
            visible = forceVisible || anchor.visible || wasConsecutive,
        )
    }

    /** Rectangular Hungarian assignment over a spatially capped primitive cost matrix. */
    private fun minimumCostAssignments(
        oldTexts: Array<String>,
        oldRects: Array<LiveParagraphRect>,
        observations: List<LiveParagraphObservation>,
    ): IntArray {
        val rowCount = oldTexts.size
        if (rowCount == 0 || observations.isEmpty()) return IntArray(rowCount) { -1 }
        val observationCount = observations.size
        val columnCount = observationCount + rowCount
        val costs = IntArray(rowCount * columnCount) { INVALID_MATCH_COST }
        val maximumTextLength = max(
            oldTexts.maxOfOrNull { it.length } ?: 0,
            observations.maxOfOrNull { it.sourceText.length } ?: 0,
        )
        val editScratch = IntArray(maximumTextLength + 1)
        val nearestIndices = IntArray(LIVE_PARAGRAPH_MAX_CANDIDATES)
        val nearestScores = DoubleArray(LIVE_PARAGRAPH_MAX_CANDIDATES)

        for (row in 0 until rowCount) {
            nearestIndices.fill(-1)
            nearestScores.fill(Double.POSITIVE_INFINITY)
            for (observationIndex in 0 until observationCount) {
                val observation = observations[observationIndex]
                val spatialScore = preliminarySpatialScore(oldRects[row], observation.bounds)
                if (!spatialScore.isFinite()) continue
                insertNearestCandidate(
                    candidateIndex = observationIndex,
                    candidateScore = spatialScore,
                    indices = nearestIndices,
                    scores = nearestScores,
                )
            }
            for (candidateSlot in nearestIndices.indices) {
                val observationIndex = nearestIndices[candidateSlot]
                if (observationIndex < 0) continue
                val cost = compositeMatchCost(
                    oldText = oldTexts[row],
                    oldRect = oldRects[row],
                    observation = observations[observationIndex],
                    editScratch = editScratch,
                )
                if (cost < UNMATCHED_COST) costs[row * columnCount + observationIndex] = cost
            }
            for (dummy in 0 until rowCount) {
                costs[row * columnCount + observationCount + dummy] =
                    UNMATCHED_COST + if (dummy == row) 0 else DUMMY_TIE_COST
            }
        }

        val assignedColumns = hungarianMinimumAssignment(costs, rowCount, columnCount)
        return IntArray(rowCount) { row ->
            val column = assignedColumns[row]
            if (column in 0 until observationCount && costs[row * columnCount + column] < UNMATCHED_COST) {
                column
            } else {
                -1
            }
        }
    }

    private fun preliminarySpatialScore(oldRect: LiveParagraphRect, newRect: LiveParagraphRect): Double {
        if (oldRect.width <= 0f || oldRect.height <= 0f || newRect.width <= 0f || newRect.height <= 0f) {
            return Double.POSITIVE_INFINITY
        }
        val iou = rectIou(oldRect, newRect)
        if (iou >= LIVE_PARAGRAPH_STRONG_IOU) return -iou.toDouble()
        val maximumWidth = max(oldRect.width, newRect.width).coerceAtLeast(1f)
        val maximumHeight = max(oldRect.height, newRect.height).coerceAtLeast(1f)
        val heightRatio = max(oldRect.height, newRect.height) / min(oldRect.height, newRect.height)
        val normalizedDx = abs(oldRect.centerX - newRect.centerX) / maximumWidth
        val normalizedDy = abs(oldRect.centerY - newRect.centerY) / maximumHeight
        if (heightRatio > LIVE_PARAGRAPH_MAX_HEIGHT_RATIO ||
            normalizedDx > LIVE_PARAGRAPH_MAX_CENTER_X_WIDTHS ||
            normalizedDy > LIVE_PARAGRAPH_MAX_CENTER_Y_HEIGHTS
        ) {
            return Double.POSITIVE_INFINITY
        }
        return (normalizedDx + normalizedDy + (heightRatio - 1f)).toDouble()
    }

    private fun compositeMatchCost(
        oldText: String,
        oldRect: LiveParagraphRect,
        observation: LiveParagraphObservation,
        editScratch: IntArray,
    ): Int {
        val newRect = observation.bounds
        val iou = rectIou(oldRect, newRect)
        val maximumWidth = max(oldRect.width, newRect.width).coerceAtLeast(1f)
        val maximumHeight = max(oldRect.height, newRect.height).coerceAtLeast(1f)
        val normalizedDx = abs(oldRect.centerX - newRect.centerX) / maximumWidth
        val normalizedDy = abs(oldRect.centerY - newRect.centerY) / maximumHeight
        val centerDistance = hypot(normalizedDx.toDouble(), normalizedDy.toDouble())
        val widthChange = abs(oldRect.width - newRect.width) / maximumWidth
        val heightChange = abs(oldRect.height - newRect.height) / maximumHeight
        val oldAspect = oldRect.width / oldRect.height.coerceAtLeast(1f)
        val newAspect = newRect.width / newRect.height.coerceAtLeast(1f)
        val aspectChange = abs(oldAspect - newAspect) / max(oldAspect, newAspect).coerceAtLeast(1f)
        val strongGeometry = iou >= LIVE_PARAGRAPH_STRONG_IOU
        val editDistance = if (strongGeometry) {
            0.0
        } else {
            normalizedLevenshteinDistance(oldText, observation.sourceText, editScratch)
        }
        if (!strongGeometry && editDistance > LIVE_PARAGRAPH_MAX_EDIT_DISTANCE) return INVALID_MATCH_COST

        val cost =
            (1.0 - iou.toDouble()) * IOU_COST_WEIGHT +
                centerDistance * CENTER_COST_WEIGHT +
                widthChange * SIZE_COST_WEIGHT +
                heightChange * SIZE_COST_WEIGHT +
                aspectChange * ASPECT_COST_WEIGHT +
                editDistance * TEXT_COST_WEIGHT
        return cost.toInt().coerceIn(0, MAX_VALID_MATCH_COST)
    }

    private fun insertNearestCandidate(
        candidateIndex: Int,
        candidateScore: Double,
        indices: IntArray,
        scores: DoubleArray,
    ) {
        var insertAt = -1
        for (index in indices.indices) {
            if (candidateScore < scores[index] ||
                (candidateScore == scores[index] && candidateIndex < indices[index])
            ) {
                insertAt = index
                break
            }
        }
        if (insertAt < 0) return
        for (index in indices.lastIndex downTo insertAt + 1) {
            indices[index] = indices[index - 1]
            scores[index] = scores[index - 1]
        }
        indices[insertAt] = candidateIndex
        scores[insertAt] = candidateScore
    }

    private fun hungarianMinimumAssignment(
        costs: IntArray,
        rowCount: Int,
        columnCount: Int,
    ): IntArray {
        val rowPotential = LongArray(rowCount + 1)
        val columnPotential = LongArray(columnCount + 1)
        val columnRow = IntArray(columnCount + 1)
        val path = IntArray(columnCount + 1)
        val minimum = LongArray(columnCount + 1)
        val used = BooleanArray(columnCount + 1)

        for (row in 1..rowCount) {
            columnRow[0] = row
            var column = 0
            minimum.fill(HUNGARIAN_INFINITY)
            used.fill(false)
            do {
                used[column] = true
                val currentRow = columnRow[column]
                var delta = HUNGARIAN_INFINITY
                var nextColumn = 0
                for (candidateColumn in 1..columnCount) {
                    if (used[candidateColumn]) continue
                    val reducedCost = costs[(currentRow - 1) * columnCount + candidateColumn - 1].toLong() -
                        rowPotential[currentRow] - columnPotential[candidateColumn]
                    if (reducedCost < minimum[candidateColumn]) {
                        minimum[candidateColumn] = reducedCost
                        path[candidateColumn] = column
                    }
                    if (minimum[candidateColumn] < delta ||
                        (minimum[candidateColumn] == delta && candidateColumn < nextColumn)
                    ) {
                        delta = minimum[candidateColumn]
                        nextColumn = candidateColumn
                    }
                }
                for (candidateColumn in 0..columnCount) {
                    if (used[candidateColumn]) {
                        rowPotential[columnRow[candidateColumn]] += delta
                        columnPotential[candidateColumn] -= delta
                    } else {
                        minimum[candidateColumn] -= delta
                    }
                }
                column = nextColumn
            } while (columnRow[column] != 0)

            do {
                val previousColumn = path[column]
                columnRow[column] = columnRow[previousColumn]
                column = previousColumn
            } while (column != 0)
        }

        val assignment = IntArray(rowCount) { -1 }
        for (column in 1..columnCount) {
            val row = columnRow[column]
            if (row in 1..rowCount) assignment[row - 1] = column - 1
        }
        return assignment
    }

    private fun normalizedLevenshteinDistance(a: String, b: String, scratch: IntArray): Double {
        if (a == b) return 0.0
        val maximumLength = max(a.length, b.length)
        if (maximumLength == 0) return 0.0
        if (abs(a.length - b.length).toDouble() / maximumLength.toDouble() >
            LIVE_PARAGRAPH_MAX_EDIT_DISTANCE
        ) {
            return 1.0
        }
        for (index in 0..b.length) scratch[index] = index
        for (aIndex in 1..a.length) {
            var diagonal = scratch[0]
            scratch[0] = aIndex
            for (bIndex in 1..b.length) {
                val previousRowValue = scratch[bIndex]
                val substitutionCost = if (a[aIndex - 1] == b[bIndex - 1]) 0 else 1
                scratch[bIndex] = minOf(
                    previousRowValue + 1,
                    scratch[bIndex - 1] + 1,
                    diagonal + substitutionCost,
                )
                diagonal = previousRowValue
            }
        }
        return scratch[b.length].toDouble() / maximumLength.toDouble()
    }

    private fun rectIou(first: LiveParagraphRect, second: LiveParagraphRect): Float {
        val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersectionArea = intersectionWidth * intersectionHeight
        if (intersectionArea <= 0f) return 0f
        val unionArea = first.width * first.height + second.width * second.height - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    private val OBSERVATION_ORDER = compareBy<LiveParagraphObservation> { it.bounds.top }
        .thenBy { it.bounds.left }
        .thenBy { it.bounds.bottom }
        .thenBy { it.bounds.right }
        .thenBy { it.sourceText }

    private val GRAVEYARD_ORDER = compareBy<LiveParagraphGraveyardEntry> { it.droppedAtMs }
        .thenBy { it.anchor.stableId }

    private const val LIVE_PARAGRAPH_MAX_ANCHORS = 64
    private const val LIVE_PARAGRAPH_MAX_CANDIDATES = 8
    private const val LIVE_PARAGRAPH_SOURCE_DEBOUNCE_OBSERVATIONS = 2
    private const val LIVE_PARAGRAPH_STRONG_IOU = 0.55f
    private const val LIVE_PARAGRAPH_MAX_EDIT_DISTANCE = 0.25
    private const val LIVE_PARAGRAPH_MAX_HEIGHT_RATIO = 3.5f
    private const val LIVE_PARAGRAPH_MAX_CENTER_X_WIDTHS = 0.85f
    private const val LIVE_PARAGRAPH_MAX_CENTER_Y_HEIGHTS = 1.5f
    private const val IOU_COST_WEIGHT = 220_000.0
    private const val CENTER_COST_WEIGHT = 170_000.0
    private const val SIZE_COST_WEIGHT = 90_000.0
    private const val ASPECT_COST_WEIGHT = 70_000.0
    private const val TEXT_COST_WEIGHT = 260_000.0
    private const val UNMATCHED_COST = 65_000_000
    private const val INVALID_MATCH_COST = 100_000_000
    private const val MAX_VALID_MATCH_COST = 999_999
    private const val DUMMY_TIE_COST = 1
    private const val HUNGARIAN_INFINITY = Long.MAX_VALUE / 4L
}
