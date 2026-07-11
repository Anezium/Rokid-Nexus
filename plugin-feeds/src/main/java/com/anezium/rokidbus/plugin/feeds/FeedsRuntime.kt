package com.anezium.rokidbus.plugin.feeds

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

internal interface FeedsRuntimeHost {
    fun sendCard(card: FeedCardContent, show: Boolean)
    fun hideSurface()
    fun post(action: () -> Unit)
    fun log(message: String)
}

internal class FeedsRuntime(
    private val host: FeedsRuntimeHost,
    private val settings: () -> FeedsSettings,
    private val sourceFactory: (FeedsSettings) -> FeedSource = ::defaultSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) {
    private val timeline = FeedTimeline()
    private var source: FeedSource? = null
    private var sourceKind = FeedSourceKind.BLUESKY
    private var position = 0
    private var expanded = false
    private var textPage = 0
    private var fetchJob: Job? = null
    private var generation = 0L
    private var visible = false

    fun open() {
        closeFetch()
        val currentSettings = settings()
        sourceKind = currentSettings.source
        source = sourceFactory(currentSettings)
        timeline.reset()
        position = 0
        expanded = false
        textPage = 0
        visible = true
        host.sendCard(PostCardLayout.message("Loading ${sourceKind.displayName}...", sourceKind.tag), show = true)
        fetchNextPage()
    }

    fun close() {
        visible = false
        closeFetch()
        host.hideSurface()
    }

    fun input(event: NexusInputEvent) {
        if (!visible || event.action != KeyEvent.ACTION_DOWN) return
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK -> close()
            event.keyCode in FORWARD_KEYS -> moveForward()
            event.keyCode in BACKWARD_KEYS -> moveBackward()
            event.keyCode in TAP_KEYS -> toggleExpanded()
        }
    }

    private fun moveForward() {
        val card = currentCard() ?: return
        when {
            expanded && textPage + 1 < card.pageCount -> textPage++
            position + 1 < timeline.posts.size -> {
                position++
                expanded = false
                textPage = 0
            }
            else -> Unit
        }
        renderCurrent()
        maybeFetchNext()
    }

    private fun moveBackward() {
        when {
            expanded && textPage > 0 -> textPage--
            position > 0 -> {
                position--
                expanded = false
                textPage = 0
            }
            else -> Unit
        }
        renderCurrent()
    }

    private fun toggleExpanded() {
        val card = currentCard() ?: return
        if (!card.truncated) return
        expanded = !expanded
        textPage = 0
        renderCurrent()
    }

    private fun maybeFetchNext() {
        if (timeline.shouldFetchNext(position)) fetchNextPage()
    }

    private fun fetchNextPage() {
        if (fetchJob?.isActive == true) return
        val activeSource = source ?: return
        val cursor = if (timeline.hasFetched) timeline.nextCursor ?: return else null
        val fetchGeneration = generation
        fetchJob = CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                val page = activeSource.fetchPage(cursor)
                host.post {
                    if (!visible || generation != fetchGeneration) return@post
                    timeline.append(page)
                    if (timeline.posts.isEmpty()) {
                        host.sendCard(PostCardLayout.message("No posts found.", sourceKind.tag), show = false)
                    } else {
                        position = position.coerceIn(0, timeline.posts.lastIndex)
                        renderCurrent()
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

    private fun renderCurrent() {
        val card = currentCard() ?: return
        host.sendCard(card, show = false)
    }

    private fun currentCard(): FeedCardContent? = timeline.posts.getOrNull(position)?.let { post ->
        PostCardLayout.layout(
            post = post,
            now = now(),
            position = position,
            total = timeline.posts.size,
            expanded = expanded,
            requestedPage = textPage,
        )
    }

    private fun closeFetch() {
        generation++
        fetchJob?.cancel()
        fetchJob = null
    }

    private companion object {
        fun defaultSource(settings: FeedsSettings): FeedSource = when (settings.source) {
            FeedSourceKind.BLUESKY -> BlueskyFeedSource(settings.blueskyFeedGeneratorUri)
            FeedSourceKind.X_ACCOUNT -> XAccountFeedSource(settings.xAccountCookies)
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
}
