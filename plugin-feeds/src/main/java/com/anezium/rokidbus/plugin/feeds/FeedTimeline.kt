package com.anezium.rokidbus.plugin.feeds

class FeedTimeline(
    private val maxPosts: Int = MAX_POSTS,
) {
    var posts: List<FeedPost> = emptyList()
        private set
    var nextCursor: String? = null
        private set
    var hasFetched: Boolean = false
        private set

    fun reset() {
        posts = emptyList()
        nextCursor = null
        hasFetched = false
    }

    fun append(page: FeedPage) {
        val knownIds = posts.asSequence().map(FeedPost::id).toMutableSet()
        val additions = page.posts.filter { knownIds.add(it.id) }
        posts = (posts + additions).take(maxPosts)
        nextCursor = page.nextCursor
            ?.takeIf { posts.size < maxPosts }
            ?.takeIf(String::isNotBlank)
        hasFetched = true
    }

    fun shouldFetchNext(position: Int, threshold: Int = PREFETCH_THRESHOLD): Boolean =
        hasFetched &&
            nextCursor != null &&
            posts.isNotEmpty() &&
            position >= (posts.size - threshold).coerceAtLeast(0)

    companion object {
        const val MAX_POSTS = 200
        const val PREFETCH_THRESHOLD = 5
    }
}
