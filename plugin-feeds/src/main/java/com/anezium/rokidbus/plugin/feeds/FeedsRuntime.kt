package com.anezium.rokidbus.plugin.feeds

import android.content.Context
import android.view.KeyEvent
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

internal interface FeedsRuntimeHost {
    fun sendCard(card: FeedCardContent, show: Boolean)
    fun sendImage(payload: JSONObject, bytes: ByteArray)
    fun supportsImage(): Boolean
    fun hideSurface()
    fun post(action: () -> Unit)
    fun log(message: String)
}

internal class FeedsRuntime(
    context: Context?,
    private val host: FeedsRuntimeHost,
    private val settings: () -> FeedsSettings,
    private val sourceFactory: (FeedsSettings) -> FeedSource = {
        defaultSource(requireNotNull(context).applicationContext, it)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val imageLoader: FeedImageLoader = FeedImagePipeline(),
    private val now: () -> Instant = Instant::now,
) {
    private val timeline = FeedTimeline()
    private var source: FeedSource? = null
    private var sourceKind = FeedSourceKind.BLUESKY
    private var position = 0
    private var level = NavigationLevel.FEED
    private var threadState: ThreadState? = null
    private var galleryIndex = 0
    private var pageFetchJob: Job? = null
    private var threadFetchJob: Job? = null
    private var imageFetchJob: Job? = null
    private var generation = 0L
    private var threadGeneration = 0L
    private var imageGeneration = 0L
    private var visible = false

    fun open() {
        closeFetch()
        closeSource()
        val currentSettings = settings()
        sourceKind = currentSettings.source
        source = sourceFactory(currentSettings)
        timeline.reset()
        position = 0
        level = NavigationLevel.FEED
        threadState = null
        galleryIndex = 0
        visible = true
        host.sendCard(PostCardLayout.message("Loading ${sourceKind.displayName}...", sourceKind.tag), show = true)
        fetchNextPage()
    }

    fun close() {
        visible = false
        closeFetch()
        closeSource()
        host.hideSurface()
    }

    fun input(event: NexusInputEvent) {
        if (!visible || event.action != KeyEvent.ACTION_DOWN) return
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK -> navigateBack()
            event.keyCode in FORWARD_KEYS -> moveForward()
            event.keyCode in BACKWARD_KEYS -> moveBackward()
            event.keyCode in TAP_KEYS -> tap()
        }
    }

    private fun moveForward() {
        when (level) {
            NavigationLevel.FEED -> {
                if (position + 1 < timeline.posts.size) position++
                renderCurrent()
                maybeFetchNext()
            }
            NavigationLevel.THREAD -> {
                val state = threadState ?: return
                val card = currentThreadCard() ?: return
                when {
                    state.textPage + 1 < card.pageCount -> state.textPage++
                    state.position + 1 < state.posts.size -> {
                        state.position++
                        state.textPage = 0
                    }
                }
                renderCurrent()
            }
            NavigationLevel.GALLERY -> {
                val post = currentThreadPost() ?: return
                if (galleryIndex + 1 < post.media.size) galleryIndex++
                renderCurrent()
            }
        }
    }

    private fun moveBackward() {
        when (level) {
            NavigationLevel.FEED -> {
                if (position > 0) position--
                renderCurrent()
            }
            NavigationLevel.THREAD -> {
                val state = threadState ?: return
                when {
                    state.textPage > 0 -> state.textPage--
                    state.position > 0 -> {
                        state.position--
                        state.textPage = 0
                    }
                }
                renderCurrent()
            }
            NavigationLevel.GALLERY -> {
                if (galleryIndex > 0) galleryIndex--
                renderCurrent()
            }
        }
    }

    private fun tap() {
        when (level) {
            NavigationLevel.FEED -> openThread()
            NavigationLevel.THREAD -> {
                val post = currentThreadPost() ?: return
                if (!post.hasMedia) return
                galleryIndex = 0
                level = NavigationLevel.GALLERY
                renderCurrent()
            }
            NavigationLevel.GALLERY -> Unit
        }
    }

    private fun navigateBack() {
        when (level) {
            NavigationLevel.FEED -> close()
            NavigationLevel.THREAD -> {
                threadGeneration++
                threadFetchJob?.cancel()
                threadFetchJob = null
                threadState = null
                level = NavigationLevel.FEED
                renderCurrent()
            }
            NavigationLevel.GALLERY -> {
                level = NavigationLevel.THREAD
                galleryIndex = 0
                renderCurrent()
            }
        }
    }

    private fun openThread() {
        val focalPost = timeline.posts.getOrNull(position) ?: return
        level = NavigationLevel.THREAD
        threadState = null
        galleryIndex = 0
        host.sendCard(PostCardLayout.message("Loading thread...", sourceKind.tag), show = false)
        fetchThread(focalPost)
    }

    private fun maybeFetchNext() {
        if (timeline.shouldFetchNext(position)) fetchNextPage()
    }

    private fun fetchNextPage() {
        if (pageFetchJob?.isActive == true) return
        val activeSource = source ?: return
        val cursor = if (timeline.hasFetched) timeline.nextCursor ?: return else null
        val fetchGeneration = generation
        pageFetchJob = CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                val page = activeSource.fetchPage(cursor)
                host.post {
                    if (!visible || generation != fetchGeneration) return@post
                    timeline.append(page)
                    if (timeline.posts.isEmpty()) {
                        host.sendCard(PostCardLayout.message("No posts found.", sourceKind.tag), show = false)
                    } else {
                        position = position.coerceIn(0, timeline.posts.lastIndex)
                        if (level == NavigationLevel.FEED) renderCurrent()
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                host.post {
                    if (!visible || generation != fetchGeneration) return@post
                    host.log("feeds fetch failed source=${sourceKind.tag}: ${failure.javaClass.simpleName}")
                    if (timeline.posts.isEmpty()) {
                        host.sendCard(
                            PostCardLayout.message("Feed fetch failed. Check phone network and settings.", sourceKind.tag),
                            show = false,
                        )
                    }
                }
            }
        }
    }

    private fun fetchThread(focalPost: FeedPost) {
        val activeSource = source ?: return
        val fetchGeneration = generation
        val requestGeneration = ++threadGeneration
        threadFetchJob = CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                val fetched = activeSource.fetchThread(focalPost)
                host.post {
                    if (
                        !visible || generation != fetchGeneration ||
                        threadGeneration != requestGeneration || level != NavigationLevel.THREAD
                    ) return@post
                    val posts = fetched.posts.ifEmpty { listOf(focalPost) }
                    threadState = ThreadState(
                        posts = posts,
                        position = fetched.focusIndex.coerceIn(posts.indices),
                    )
                    renderCurrent()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                host.post {
                    if (
                        !visible || generation != fetchGeneration ||
                        threadGeneration != requestGeneration || level != NavigationLevel.THREAD
                    ) return@post
                    host.log("thread fetch failed source=${sourceKind.tag}: ${failure.javaClass.simpleName}")
                    host.sendCard(
                        PostCardLayout.message("Thread fetch failed. Check phone network and settings.", sourceKind.tag),
                        show = false,
                    )
                }
            }
        }
    }

    private fun renderCurrent() {
        if (level == NavigationLevel.GALLERY) {
            renderGallery()
            return
        }
        closeImageFetch()
        val card = currentCard() ?: return
        host.sendCard(card, show = false)
    }

    private fun renderGallery() {
        val post = currentThreadPost() ?: return
        val media = post.media.getOrNull(galleryIndex) ?: return
        closeImageFetch()
        if (!host.supportsImage() || !media.hasDisplayableImageUrl()) {
            host.sendCard(PostCardLayout.renderGalleryItem(post, galleryIndex, now()), show = false)
            return
        }

        val requestGeneration = imageGeneration
        val runtimeGeneration = generation
        val requestIndex = galleryIndex
        val requestPostId = post.id
        host.sendCard(
            PostCardLayout.message(
                "Loading photo… (${requestIndex + 1}/${post.media.size})",
                "${post.source} media ${requestIndex + 1}/${post.media.size}",
            ),
            show = false,
        )
        imageFetchJob = CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                val frame = imageLoader.load(media)
                host.post {
                    if (!isCurrentGalleryRequest(runtimeGeneration, requestGeneration, requestPostId, requestIndex)) {
                        return@post
                    }
                    if (frame == null || !host.supportsImage()) {
                        host.sendCard(PostCardLayout.renderGalleryItem(post, requestIndex, now()), show = false)
                        return@post
                    }
                    val payload = frame.payload()
                        .put("caption", media.altText.trim().take(ImageSurfaceContract.MAX_TEXT_CHARS))
                        .put("footer", galleryFooter(post, media, requestIndex))
                        .put("handlesBack", true)
                    if (ImageSurfaceContract.validate(payload, frame.bytes) is ImageSurfaceValidationResult.Valid) {
                        host.sendImage(payload, frame.bytes)
                    } else {
                        host.sendCard(PostCardLayout.renderGalleryItem(post, requestIndex, now()), show = false)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                host.post {
                    if (!isCurrentGalleryRequest(runtimeGeneration, requestGeneration, requestPostId, requestIndex)) {
                        return@post
                    }
                    host.log(
                        "gallery image failed source=${post.source} type=${media.type.name.lowercase()}: " +
                            failure.javaClass.simpleName,
                    )
                    host.sendCard(PostCardLayout.renderGalleryItem(post, requestIndex, now()), show = false)
                }
            }
        }
    }

    private fun isCurrentGalleryRequest(
        runtimeGeneration: Long,
        requestGeneration: Long,
        postId: String,
        mediaIndex: Int,
    ): Boolean = visible &&
        generation == runtimeGeneration &&
        imageGeneration == requestGeneration &&
        level == NavigationLevel.GALLERY &&
        galleryIndex == mediaIndex &&
        currentThreadPost()?.id == postId

    private fun currentCard(): FeedCardContent? = when (level) {
        NavigationLevel.FEED -> currentFeedCard()
        NavigationLevel.THREAD -> currentThreadCard()
        NavigationLevel.GALLERY -> currentGalleryCard()
    }

    private fun currentFeedCard(): FeedCardContent? = timeline.posts.getOrNull(position)?.let { post ->
        PostCardLayout.layout(
            post = post,
            now = now(),
            position = position,
            total = timeline.posts.size,
        )
    }

    private fun currentThreadCard(): FeedCardContent? {
        val state = threadState ?: return null
        val post = state.posts.getOrNull(state.position) ?: return null
        return PostCardLayout.layout(
            post = post,
            now = now(),
            position = state.position,
            total = state.posts.size,
            expanded = true,
            requestedPage = state.textPage,
            footerOverride = "${post.source} thread ${state.position + 1}/${state.posts.size}",
        )
    }

    private fun currentGalleryCard(): FeedCardContent? = currentThreadPost()?.let { post ->
        PostCardLayout.renderGalleryItem(post, galleryIndex, now())
    }

    private fun currentThreadPost(): FeedPost? =
        threadState?.let { state -> state.posts.getOrNull(state.position) }

    private fun closeFetch() {
        generation++
        threadGeneration++
        closeImageFetch()
        pageFetchJob?.cancel()
        pageFetchJob = null
        threadFetchJob?.cancel()
        threadFetchJob = null
    }

    private fun closeImageFetch() {
        imageGeneration++
        imageFetchJob?.cancel()
        imageFetchJob = null
    }

    private fun closeSource() {
        (source as? AutoCloseable)?.close()
        source = null
    }

    private companion object {
        fun FeedMedia.hasDisplayableImageUrl(): Boolean {
            val candidate = previewUrl.trim().ifBlank { url.trim() }
            return candidate.startsWith("https://", ignoreCase = true)
        }

        fun galleryFooter(post: FeedPost, media: FeedMedia, index: Int): String {
            val type = when (media.type) {
                FeedMediaType.PHOTO -> "photo"
                FeedMediaType.GIF -> "GIF"
                FeedMediaType.VIDEO -> buildString {
                    append("video")
                    duration(media.durationMs)?.let { append(" $it") }
                }
            }
            return "${post.source} $type ${index + 1}/${post.media.size}"
                .take(ImageSurfaceContract.MAX_TEXT_CHARS)
        }

        fun duration(durationMs: Long?): String? = durationMs
            ?.coerceAtLeast(0L)
            ?.div(1_000L)
            ?.let { seconds -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" }

        fun defaultSource(context: Context, settings: FeedsSettings): FeedSource = when (settings.source) {
            FeedSourceKind.BLUESKY -> BlueskyFeedSource(settings.blueskyFeedGeneratorUri)
            FeedSourceKind.X_ACCOUNT -> XAccountFeedSource(settings.xAccountCookies)
            FeedSourceKind.X_WEBVIEW -> XWebViewFeedSource(context, settings.xAccountCookies)
            FeedSourceKind.X_OFFICIAL -> XFeedSource(settings.xBearerToken, settings.xUserId)
            FeedSourceKind.DEMO -> MockFeedSource()
        }

        val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        val BACKWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        val TAP_KEYS = setOf(
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
        )
    }

    private enum class NavigationLevel { FEED, THREAD, GALLERY }

    private data class ThreadState(
        val posts: List<FeedPost>,
        var position: Int,
        var textPage: Int = 0,
    )
}
