package com.anezium.rokidbus.lens

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

/** Uses the frozen paragraph segmenter verbatim while retaining live tracking identities. */
internal fun segmentLiveParagraphs(lines: List<LiveParagraphLine>): List<LiveParagraphGroup> {
    if (lines.isEmpty()) return emptyList()
    val canonicalLines = lines
        .groupBy { it.stableId }
        .values
        .map { duplicates -> duplicates.minWith(LIVE_PARAGRAPH_LINE_ORDER) }
    return segmentFrozenParagraphs(
        canonicalLines.map { line ->
            FrozenLayoutBlock(
                lines = listOf(
                    FrozenLayoutLine(
                        text = line.source,
                        bounds = line.bounds,
                        stableId = line.stableId,
                    ),
                ),
            )
        },
    ).map { paragraph ->
        val members = paragraph.lines.mapNotNull { line ->
            line.stableId?.let { stableId ->
                LiveParagraphLine(
                    stableId = stableId,
                    source = line.text,
                    bounds = line.bounds,
                )
            }
        }.distinctBy { it.stableId }
        LiveParagraphGroup(
            members = members,
            source = members.joinToString(" ") { it.source }.trim(),
            bounds = paragraph.bounds,
            columnIndex = paragraph.columnIndex,
            gapBelow = paragraph.gapBelow,
        )
    }.filter { it.members.isNotEmpty() }
}

/** Returns text only when every paragraph member is translated. */
internal fun completeLiveParagraphTranslation(memberTranslations: List<String?>): String? {
    if (memberTranslations.isEmpty() || memberTranslations.any { it.isNullOrBlank() }) return null
    return memberTranslations.filterNotNull().joinToString(" ").trim().takeIf { it.isNotEmpty() }
}

/**
 * Final live-only safety net for malformed/overlapping OCR anchors. Older stable identities win,
 * so a newly detected line cannot reshuffle surviving translations from frame to frame.
 */
internal fun selectNonOverlappingLiveBlockIds(blocks: List<LivePlacedBlock>): List<Long> {
    val selected = mutableListOf<LivePlacedBlock>()
    blocks
        .asSequence()
        .filter { it.bounds.width > 0f && it.bounds.height > 0f }
        .sortedWith(LIVE_PLACED_BLOCK_ORDER)
        .forEach { candidate ->
            if (selected.none { it.bounds.intersects(candidate.bounds) }) {
                selected += candidate
            }
        }
    return selected.map { it.stableId }
}

private val LIVE_PLACED_BLOCK_ORDER = compareBy<LivePlacedBlock> { it.stableId }
    .thenBy { it.bounds.top }
    .thenBy { it.bounds.left }
    .thenBy { it.bounds.bottom }
    .thenBy { it.bounds.right }

private val LIVE_PARAGRAPH_LINE_ORDER = compareBy<LiveParagraphLine> { it.bounds.top }
    .thenBy { it.bounds.left }
    .thenBy { it.bounds.bottom }
    .thenBy { it.bounds.right }
    .thenBy { it.stableId }
    .thenBy { it.source }
