package com.anezium.rokidbus.lens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class FrozenLayoutRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f

    fun union(other: FrozenLayoutRect): FrozenLayoutRect =
        FrozenLayoutRect(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom),
        )
}

internal data class FrozenLayoutLine(
    val text: String,
    val bounds: FrozenLayoutRect,
    val stableId: Long? = null,
)

internal data class FrozenLayoutBlock(
    val lines: List<FrozenLayoutLine>,
)

internal data class FrozenLayoutParagraph(
    val source: String,
    val bounds: FrozenLayoutRect,
    val lines: List<FrozenLayoutLine>,
    val columnIndex: Int,
    val gapBelow: Float,
)

internal const val FROZEN_PARAGRAPH_MAX_LINES = 8
internal const val FROZEN_PARAGRAPH_MAX_CHARS = 512
internal const val FROZEN_PARAGRAPH_GAP_LINE_HEIGHTS = 0.85f
internal const val FROZEN_PARAGRAPH_INDENT_LINE_HEIGHTS = 0.60f
internal const val FROZEN_PARAGRAPH_HEADING_HEIGHT_RATIO = 1.3f

/**
 * Reconstructs paragraphs from ML Kit line geometry without losing the original line geometry.
 * Blocks are first assigned to deterministic columns, then split on visual paragraph cues and
 * conservative size caps. Each result also carries a same-column downward-growth budget capped
 * by the next paragraph and [PARAGRAPH_PANEL_MAX_HEIGHT_FACTOR]. A pathological frozen OCR line
 * over [maxChars] is split into contiguous horizontal pieces so the cap remains absolute without
 * discarding source text. Stable live lines remain atomic because their translations are cached
 * and requested per line.
 */
internal fun segmentFrozenParagraphs(
    blocks: List<FrozenLayoutBlock>,
    maxLines: Int = FROZEN_PARAGRAPH_MAX_LINES,
    maxChars: Int = FROZEN_PARAGRAPH_MAX_CHARS,
): List<FrozenLayoutParagraph> {
    require(maxLines > 0) { "maxLines must be positive" }
    require(maxChars > 0) { "maxChars must be positive" }

    val sanitized = blocks.mapNotNull(::sanitizeBlock).map { splitOversizedLines(it, maxChars) }
    if (sanitized.isEmpty()) return emptyList()

    return assignFrozenColumns(sanitized)
        .sortedWith(FROZEN_COLUMN_ORDER)
        .flatMapIndexed { columnIndex, column ->
            addFrozenParagraphGrowthBudgets(
                paragraphs = segmentFrozenColumn(column.blocks, maxLines, maxChars),
                columnIndex = columnIndex,
            )
        }
}

private data class SanitizedFrozenBlock(
    val lines: List<FrozenLayoutLine>,
    val bounds: FrozenLayoutRect,
)

private data class FrozenColumn(
    val blocks: MutableList<SanitizedFrozenBlock>,
    var bounds: FrozenLayoutRect,
)

private fun sanitizeBlock(block: FrozenLayoutBlock): SanitizedFrozenBlock? {
    val lines = block.lines
        .asSequence()
        .map { it.copy(text = it.text.trim()) }
        .filter { it.text.isNotEmpty() && it.bounds.width > 0 && it.bounds.height > 0 }
        .sortedWith(FROZEN_LINE_ORDER)
        .toList()
    if (lines.isEmpty()) return null
    return SanitizedFrozenBlock(
        lines = lines,
        bounds = lines.drop(1).fold(lines.first().bounds) { bounds, line -> bounds.union(line.bounds) },
    )
}

private fun splitOversizedLines(
    block: SanitizedFrozenBlock,
    maxChars: Int,
): SanitizedFrozenBlock {
    val lines = block.lines.flatMap { line ->
        if (line.text.length <= maxChars || line.stableId != null) {
            listOf(line)
        } else {
            splitOversizedLine(line, maxChars)
        }
    }
    return SanitizedFrozenBlock(
        lines = lines.sortedWith(FROZEN_LINE_ORDER),
        bounds = lines.drop(1).fold(lines.first().bounds) { bounds, line -> bounds.union(line.bounds) },
    )
}

private fun splitOversizedLine(line: FrozenLayoutLine, maxChars: Int): List<FrozenLayoutLine> {
    val chunks = mutableListOf<String>()
    var remaining = line.text.trim()
    while (remaining.length > maxChars) {
        val whitespaceBreak = remaining.lastIndexOf(' ', startIndex = maxChars)
        val breakAt = whitespaceBreak.takeIf { it > 0 } ?: maxChars
        chunks += remaining.substring(0, breakAt).trim()
        remaining = remaining.substring(breakAt).trimStart()
    }
    if (remaining.isNotEmpty()) chunks += remaining
    if (chunks.size <= 1) return listOf(line.copy(text = chunks.firstOrNull() ?: line.text))

    val totalWeight = chunks.sumOf { it.length }.coerceAtLeast(1)
    var cumulativeWeight = 0
    var left = line.bounds.left
    return chunks.mapIndexed { index, chunk ->
        cumulativeWeight += chunk.length
        val right = if (index == chunks.lastIndex) {
            line.bounds.right
        } else {
            val proportional = line.bounds.left +
                (line.bounds.width.toLong() * cumulativeWeight.toLong() / totalWeight.toLong()).toInt()
            val remainingChunks = chunks.lastIndex - index
            val canKeepPositiveWidths = line.bounds.width >= chunks.size
            val minimumRight = if (canKeepPositiveWidths) left + 1 else left
            val maximumRight = if (canKeepPositiveWidths) {
                line.bounds.right - remainingChunks
            } else {
                line.bounds.right
            }
            proportional.coerceIn(minimumRight, maximumRight)
        }
        FrozenLayoutLine(
            text = chunk,
            bounds = FrozenLayoutRect(left, line.bounds.top, right, line.bounds.bottom),
            stableId = line.stableId,
        ).also { left = right }
    }
}

private fun addFrozenParagraphGrowthBudgets(
    paragraphs: List<FrozenLayoutParagraph>,
    columnIndex: Int,
): List<FrozenLayoutParagraph> =
    paragraphs.mapIndexed { index, paragraph ->
        val sourceHeight = paragraph.bounds.height.toFloat().coerceAtLeast(0f)
        val factorBudget = sourceHeight * (PARAGRAPH_PANEL_MAX_HEIGHT_FACTOR - 1f)
        val nextTop = paragraphs.getOrNull(index + 1)?.bounds?.top?.toFloat()
        val medianLineHeight = median(
            paragraph.lines.map { it.bounds.height.toFloat() }.filter { it > 0f },
        )
        val clearance = max(
            1f,
            medianLineHeight * PARAGRAPH_PANEL_GAP_CLEARANCE_LINE_HEIGHTS,
        )
        val availableGap = nextTop
            ?.let { it - paragraph.bounds.bottom.toFloat() - clearance }
            ?.coerceAtLeast(0f)
            ?: factorBudget
        paragraph.copy(
            columnIndex = columnIndex,
            gapBelow = min(factorBudget, availableGap).coerceAtLeast(0f),
        )
    }

private fun assignFrozenColumns(blocks: List<SanitizedFrozenBlock>): List<FrozenColumn> {
    val columns = mutableListOf<FrozenColumn>()
    blocks.sortedWith(FROZEN_BLOCK_ORDER).forEach { block ->
        val bestColumn = columns
            .mapNotNull { column ->
                val overlap = horizontalOverlapFraction(block.bounds, column.bounds)
                if (overlap < FROZEN_COLUMN_MIN_HORIZONTAL_OVERLAP) {
                    null
                } else {
                    Triple(column, overlap, abs(block.bounds.centerX - column.bounds.centerX))
                }
            }
            .sortedWith(
                compareByDescending<Triple<FrozenColumn, Float, Float>> { it.second }
                    .thenBy { it.third }
                    .thenBy { it.first.bounds.left }
                    .thenBy { it.first.bounds.top },
            )
            .firstOrNull()
            ?.first

        if (bestColumn == null) {
            columns += FrozenColumn(mutableListOf(block), block.bounds)
        } else {
            bestColumn.blocks += block
            bestColumn.bounds = bestColumn.bounds.union(block.bounds)
        }
    }
    return columns
}

private fun segmentFrozenColumn(
    blocks: List<SanitizedFrozenBlock>,
    maxLines: Int,
    maxChars: Int,
): List<FrozenLayoutParagraph> {
    val sortedBlocks = blocks.sortedWith(FROZEN_BLOCK_ORDER)
    val medianLineHeight = median(
        sortedBlocks.flatMap { block -> block.lines.map { it.bounds.height.toFloat() } },
    ).coerceAtLeast(1f)
    val paragraphs = mutableListOf<FrozenLayoutParagraph>()
    var paragraphLines = mutableListOf<FrozenLayoutLine>()
    var paragraphChars = 0

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) return
        paragraphs += FrozenLayoutParagraph(
            source = paragraphLines.joinToString(" ") { it.text }.trim(),
            bounds = paragraphLines.drop(1).fold(paragraphLines.first().bounds) { bounds, line ->
                bounds.union(line.bounds)
            },
            lines = paragraphLines.toList(),
            columnIndex = -1,
            gapBelow = 0f,
        )
        paragraphLines = mutableListOf()
        paragraphChars = 0
    }

    sortedBlocks.forEach { block ->
        block.lines.forEachIndexed { lineIndex, line ->
            val previous = paragraphLines.lastOrNull()
            val joinedChars = paragraphChars + if (paragraphLines.isEmpty()) line.text.length else line.text.length + 1
            val exceedsCaps = paragraphLines.isNotEmpty() &&
                (paragraphLines.size >= maxLines || joinedChars > maxChars)
            val visualBoundary = previous != null && (
                hasFrozenParagraphGap(previous, line, medianLineHeight) ||
                    hasFrozenHeadingJump(previous, line) ||
                    (lineIndex == 0 && hasFrozenIndentChange(paragraphLines, line, medianLineHeight))
                )
            if (exceedsCaps || visualBoundary) flushParagraph()
            paragraphLines += line
            paragraphChars += line.text.length + if (paragraphLines.size == 1) 0 else 1
        }
    }
    flushParagraph()
    return paragraphs
}

private fun hasFrozenParagraphGap(
    previous: FrozenLayoutLine,
    next: FrozenLayoutLine,
    medianLineHeight: Float,
): Boolean =
    next.bounds.top - previous.bounds.bottom > medianLineHeight * FROZEN_PARAGRAPH_GAP_LINE_HEIGHTS

private fun hasFrozenIndentChange(
    paragraphLines: List<FrozenLayoutLine>,
    next: FrozenLayoutLine,
    medianLineHeight: Float,
): Boolean {
    val baselineLeft = median(paragraphLines.map { it.bounds.left.toFloat() })
    return abs(next.bounds.left - baselineLeft) > medianLineHeight * FROZEN_PARAGRAPH_INDENT_LINE_HEIGHTS
}

private fun hasFrozenHeadingJump(previous: FrozenLayoutLine, next: FrozenLayoutLine): Boolean {
    val shorter = min(previous.bounds.height, next.bounds.height).toFloat()
    if (shorter <= 0f) return true
    return max(previous.bounds.height, next.bounds.height) / shorter > FROZEN_PARAGRAPH_HEADING_HEIGHT_RATIO
}

private fun horizontalOverlapFraction(a: FrozenLayoutRect, b: FrozenLayoutRect): Float {
    val narrowerWidth = min(a.width, b.width)
    if (narrowerWidth <= 0) return 0f
    val overlap = min(a.right, b.right) - max(a.left, b.left)
    return max(0, overlap).toFloat() / narrowerWidth.toFloat()
}

private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}

private val FROZEN_LINE_ORDER = compareBy<FrozenLayoutLine> { it.bounds.top }
    .thenBy { it.bounds.left }
    .thenBy { it.bounds.bottom }
    .thenBy { it.bounds.right }
    .thenBy { it.stableId ?: Long.MAX_VALUE }
    .thenBy { it.text }

private val FROZEN_BLOCK_ORDER = compareBy<SanitizedFrozenBlock> { it.bounds.top }
    .thenBy { it.bounds.left }
    .thenBy { it.bounds.bottom }
    .thenBy { it.bounds.right }
    .thenBy { block -> block.lines.joinToString("\u0000") { (it.stableId ?: Long.MAX_VALUE).toString() } }
    .thenBy { block -> block.lines.joinToString("\u0000") { it.text } }

private val FROZEN_COLUMN_ORDER = compareBy<FrozenColumn> { it.bounds.left }
    .thenBy { it.bounds.top }
    .thenBy { it.bounds.right }
    .thenBy { it.bounds.bottom }

private const val FROZEN_COLUMN_MIN_HORIZONTAL_OVERLAP = 0.5f
