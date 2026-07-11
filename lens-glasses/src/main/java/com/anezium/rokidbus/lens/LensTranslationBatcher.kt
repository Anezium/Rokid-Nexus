package com.anezium.rokidbus.lens

import com.anezium.rokidbus.shared.LensWireContract

internal data class LensTranslationCandidate<K>(
    val key: K,
    val source: String,
)

internal data class LensTranslationBatch<K>(
    val selected: List<LensTranslationCandidate<K>>,
    val skipped: List<LensTranslationCandidate<K>>,
    val deferred: List<LensTranslationCandidate<K>>,
)

/** Selects one request that is guaranteed to satisfy the phone parser's source limits. */
internal fun <K> selectLensTranslationBatch(
    candidates: Iterable<LensTranslationCandidate<K>>,
    maxStringCount: Int = LensWireContract.MAX_STRING_COUNT,
    maxStringChars: Int = LensWireContract.MAX_STRING_CHARS,
    maxTotalSourceBytes: Int = LensWireContract.MAX_TOTAL_SOURCE_BYTES,
): LensTranslationBatch<K> {
    require(maxStringCount > 0)
    require(maxStringChars > 0)
    require(maxTotalSourceBytes > 0)

    val selected = mutableListOf<LensTranslationCandidate<K>>()
    val skipped = mutableListOf<LensTranslationCandidate<K>>()
    val deferred = mutableListOf<LensTranslationCandidate<K>>()
    var selectedBytes = 0

    candidates.forEach { candidate ->
        val sourceBytes = candidate.source.toByteArray(Charsets.UTF_8).size
        if (candidate.source.isBlank() ||
            candidate.source.length > maxStringChars ||
            sourceBytes > maxTotalSourceBytes
        ) {
            skipped += candidate
            return@forEach
        }

        if (selected.size >= maxStringCount || selectedBytes + sourceBytes > maxTotalSourceBytes) {
            deferred += candidate
            return@forEach
        }

        selected += candidate
        selectedBytes += sourceBytes
    }

    return LensTranslationBatch(
        selected = selected,
        skipped = skipped,
        deferred = deferred,
    )
}
