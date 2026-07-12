package com.anezium.rokidbus.plugin.lens

internal data class LiveLayoutRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: LiveLayoutRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class LivePlacedBlock(
    val stableId: Long,
    val bounds: LiveLayoutRect,
)

internal data class LiveParagraphLine(
    val stableId: Long,
    val source: String,
    val bounds: FrozenLayoutRect,
)

internal data class LiveParagraphGroup(
    val members: List<LiveParagraphLine>,
    val source: String,
    val bounds: FrozenLayoutRect,
    val columnIndex: Int,
    val gapBelow: Float,
) {
    val memberStableIds: List<Long> get() = members.map { it.stableId }.sorted()
    val collisionStableId: Long get() = members.minOf { it.stableId }
}

internal data class LiveFrameParagraphLine(
    val source: String,
    val bounds: FrozenLayoutRect,
)

internal data class LiveFrameParagraphBlock(
    val lines: List<LiveFrameParagraphLine>,
)

internal data class LiveFrameParagraph(
    val source: String,
    val bounds: FrozenLayoutRect,
    val lineBounds: List<FrozenLayoutRect>,
    val columnIndex: Int,
    val gapBelow: Float,
)

/** Segments current-frame OCR blocks before any stable paragraph identity is assigned. */
internal fun segmentLiveFrameParagraphs(
    blocks: List<LiveFrameParagraphBlock>,
): List<LiveFrameParagraph> =
    segmentFrozenParagraphs(
        blocks.map { block ->
            FrozenLayoutBlock(
                lines = block.lines.map { line ->
                    FrozenLayoutLine(text = line.source, bounds = line.bounds)
                },
            )
        },
    ).map { paragraph ->
        LiveFrameParagraph(
            source = paragraph.source,
            bounds = paragraph.bounds,
            lineBounds = paragraph.lines.map { it.bounds },
            columnIndex = paragraph.columnIndex,
            gapBelow = paragraph.gapBelow,
        )
    }

