package com.anezium.rokidbus.plugin.lens

internal data class FrozenOverlayLine(
    val source: String,
    val bounds: FrozenLayoutRect,
    val medianLineHeight: Float,
    val growDown: Float,
    val column: Int,
)

/** Restores the old Lens freeze path's paragraph segmentation before translation/rendering. */
internal fun aggregateFrozenOverlayLines(
    blocks: List<FrozenLayoutBlock>,
): List<FrozenOverlayLine> = segmentFrozenParagraphs(blocks).map { paragraph ->
    FrozenOverlayLine(
        source = paragraph.source,
        bounds = paragraph.bounds,
        medianLineHeight = medianLineHeight(paragraph.lines),
        growDown = paragraph.gapBelow,
        column = paragraph.columnIndex,
    )
}

private fun medianLineHeight(lines: List<FrozenLayoutLine>): Float {
    val values = lines.map { it.bounds.height.toFloat() }.filter { it > 0f }.sorted()
    if (values.isEmpty()) return 0f
    val middle = values.size / 2
    return if (values.size % 2 == 0) {
        (values[middle - 1] + values[middle]) / 2f
    } else {
        values[middle]
    }
}
