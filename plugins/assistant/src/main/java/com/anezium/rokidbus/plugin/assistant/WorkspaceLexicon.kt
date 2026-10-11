package com.anezium.rokidbus.plugin.assistant

/**
 * The inverted index over retained chunk bodies, in deterministic document then chunk order. It is
 * derivable from the stored text with the versioned tokenizer: the index file may persist it as an
 * optional cache, and loading rebuilds it once when the cache is absent or from another version.
 * Chunk positions are private to one committed snapshot, never citations.
 */
internal class WorkspaceLexicalIndex private constructor(
    /** Sorted by [String.compareTo] and unique. */
    val terms: Array<String>,
    /** One flat (chunk position, occurrences) array per term, chunk positions strictly increasing. */
    val postings: Array<IntArray>,
    /** Every token of each chunk, including terms too long to query. */
    val tokenCounts: IntArray,
) {
    val pairCount: Int = postings.sumOf { it.size / 2 }
    val chunkCount: Int get() = tokenCounts.size

    /** Whether the cache stays within the structural ceilings the index file allows. */
    val fitsCache: Boolean get() = terms.size <= MAX_TERMS && pairCount <= MAX_PAIRS

    fun postingsOf(term: String): IntArray? {
        val position = terms.binarySearch(term)
        return if (position >= 0) postings[position] else null
    }

    companion object {
        /** Version 2 groups queries only on ";" and "?"; the tokenizer itself is unchanged. */
        const val VERSION = 2
        const val MAX_TERMS = 100_000
        const val MAX_PAIRS = 250_000

        // Test seam: how many chunk bodies were tokenized to build an index.
        @Volatile internal var bodyTokenizations = 0L

        fun build(texts: List<String>): WorkspaceLexicalIndex {
            val pairs = HashMap<String, IntArrayBuilder>()
            val counts = IntArray(texts.size)
            texts.forEachIndexed { position, text ->
                val tokens = WorkspaceTokenizer.tokens(text)
                bodyTokenizations++
                counts[position] = tokens.size
                val frequencies = LinkedHashMap<String, Int>()
                tokens.forEach { token ->
                    if (token.length <= WorkspaceLimits.MAX_QUERY_CHARS) frequencies[token] = (frequencies[token] ?: 0) + 1
                }
                frequencies.forEach { (term, count) ->
                    pairs.getOrPut(term) { IntArrayBuilder() }.add(position, count)
                }
            }
            val terms = pairs.keys.sorted().toTypedArray()
            return WorkspaceLexicalIndex(terms, Array(terms.size) { pairs.getValue(terms[it]).toArray() }, counts)
        }

        /**
         * Rebuilds a persisted cache after checking every reference, so a damaged cache fails closed
         * before any retriever trusts it. Sizes are checked by the caller before arrays are allocated.
         */
        fun restore(terms: Array<String>, postings: Array<IntArray>, tokenCounts: IntArray,
            chunkCount: Int): WorkspaceLexicalIndex {
            require(terms.size == postings.size && terms.size <= MAX_TERMS && tokenCounts.size == chunkCount)
            require(tokenCounts.all { it >= 0 })
            val perChunk = IntArray(chunkCount)
            var pairs = 0
            for (index in terms.indices) {
                val term = terms[index]
                require(term.isNotEmpty() && term.length <= WorkspaceLimits.MAX_QUERY_CHARS)
                require(index == 0 || terms[index - 1] < term)
                val list = postings[index]
                require(list.isNotEmpty() && list.size % 2 == 0)
                pairs += list.size / 2
                require(pairs <= MAX_PAIRS)
                var previous = -1
                for (offset in list.indices step 2) {
                    val chunk = list[offset]
                    val count = list[offset + 1]
                    require(chunk in 0 until chunkCount && chunk > previous && count > 0)
                    previous = chunk
                    perChunk[chunk] += count
                }
            }
            for (chunk in 0 until chunkCount) require(perChunk[chunk] <= tokenCounts[chunk])
            return WorkspaceLexicalIndex(terms, postings, tokenCounts)
        }
    }

    private class IntArrayBuilder {
        private var values = IntArray(4)
        private var size = 0
        fun add(first: Int, second: Int) {
            if (size + 2 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = first
            values[size++] = second
        }
        fun toArray(): IntArray = values.copyOf(size)
    }
}
