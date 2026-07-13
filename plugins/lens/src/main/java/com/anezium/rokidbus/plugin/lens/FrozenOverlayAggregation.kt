package com.anezium.rokidbus.plugin.lens

internal data class FrozenOverlayLine(
    val source: String,
    val bounds: FrozenLayoutRect,
)

/** Restores the old Lens freeze path's paragraph segmentation before translation/rendering. */
internal fun aggregateFrozenOverlayLines(
    blocks: List<FrozenLayoutBlock>,
): List<FrozenOverlayLine> = segmentFrozenParagraphs(blocks).map { paragraph ->
    FrozenOverlayLine(paragraph.source, paragraph.bounds)
}
