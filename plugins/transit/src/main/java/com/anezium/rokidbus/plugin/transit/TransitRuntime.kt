package com.anezium.rokidbus.plugin.transit

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.coroutineContext

internal class TransitRuntime(
    private val host: TransitRuntimeHost,
    dependencies: TransitDependencies,
    private val refreshDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val refreshDelayMs: Long = REFRESH_MS,
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
) {
    private val repository = dependencies.repository
    private val locationProvider = dependencies.location
    private val favoritesStore = dependencies.favorites
    private val pager = BoardPager()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var screen = Screen.CHOOSER
    private var selectedMode = TransitMode.NEAR_ME
    private var activeMode = TransitMode.NEAR_ME
    private var lastSentKey: String? = null
    private var boardStops: List<TransitStop> = emptyList()
    private var boardsByStopId: Map<String, TransitBoard> = emptyMap()
    private var staleStopIds: Set<String> = emptySet()

    fun open() {
        stopRefreshLoop()
        host.setNearMeForeground(false)
        lastSentKey = null
        screen = Screen.CHOOSER
        selectedMode = favoritesStore.lastMode()
        activeMode = selectedMode
        clearBoardState()
        sendCard(TransitCards.chooser(selectedMode), forceShow = true)
    }

    fun close() {
        stopRefreshLoop()
        host.setNearMeForeground(false)
        clearBoardState()
        lastSentKey = null
        host.hideSurface()
    }

    fun input(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (screen) {
            Screen.CHOOSER -> handleChooserInput(event.keyCode)
            Screen.BOARD -> handleBoardInput(event.keyCode)
        }
    }

    private fun handleChooserInput(keyCode: Int) {
        when {
            keyCode == KeyEvent.KEYCODE_BACK -> close()
            keyCode in FORWARD_KEYS || keyCode in BACKWARD_KEYS -> {
                selectedMode = selectedMode.toggled()
                sendCard(TransitCards.chooser(selectedMode))
            }
            keyCode in TAP_KEYS -> enterMode(selectedMode)
        }
    }

    private fun handleBoardInput(keyCode: Int) {
        when {
            keyCode == KeyEvent.KEYCODE_BACK -> returnToChooser()
            keyCode in FORWARD_KEYS -> movePage(forward = true)
            keyCode in BACKWARD_KEYS -> movePage(forward = false)
            keyCode in TAP_KEYS -> {
                syncPager(Instant.now())
                pager.nextStop()
                renderCurrentBoard(Instant.now())
            }
        }
    }

    private fun enterMode(mode: TransitMode) {
        stopRefreshLoop()
        host.setNearMeForeground(false)
        activeMode = mode
        screen = Screen.BOARD
        clearBoardState()
        favoritesStore.setLastMode(mode)

        when (mode) {
            TransitMode.NEAR_ME -> enterNearMe()
            TransitMode.FAVORITES -> {
                if (favoritesStore.list().isEmpty()) {
                    sendCard(noFavoritesCard())
                } else {
                    sendCard(DepartureFormatter.message("Transit", "Loading favorites..."))
                }
                startFavoritesLoop()
            }
        }
    }

    private fun enterNearMe() {
        sendCard(DepartureFormatter.message("Transit", "Locating..."))
        when (locationProvider.access()) {
            TransitLocationAccess.MISSING_PRECISE -> {
                sendCard(DepartureFormatter.message("Transit", "Allow precise location in phone app."))
                return
            }
            TransitLocationAccess.MISSING_BACKGROUND -> {
                sendCard(
                    DepartureFormatter.message(
                        "Transit",
                        listOf("Allow location", "all the time on phone."),
                    ),
                )
                return
            }
            TransitLocationAccess.READY -> Unit
        }
        if (!host.setNearMeForeground(true)) {
            sendCard(DepartureFormatter.message("Transit", "Reopen Transit settings on phone."))
            return
        }
        startNearMeLoop()
    }

    private fun returnToChooser() {
        stopRefreshLoop()
        host.setNearMeForeground(false)
        clearBoardState()
        screen = Screen.CHOOSER
        selectedMode = activeMode
        sendCard(TransitCards.chooser(selectedMode))
    }

    private fun startNearMeLoop() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        val mode = TransitMode.NEAR_ME
        refreshJob = CoroutineScope(SupervisorJob() + refreshDispatcher).launch {
            val location = try {
                try {
                    withTimeoutOrNull(locationTimeoutMs) { locationProvider.currentLocation() }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    host.log("transit location failed: ${failure.javaClass.simpleName}")
                    null
                }
            } finally {
                releaseNearMeForeground(generation, mode)
            }
            if (location == null) {
                postRefreshCard(
                    generation,
                    mode,
                    DepartureFormatter.message("Transit", "Location unavailable. Reopen Near Me."),
                )
                return@launch
            }

            var loopState = RefreshLoopState(
                nearby = NearbySessionState(location = location, stops = null),
                previousBoards = emptyMap(),
            )
            while (isActive) {
                try {
                    loopState = refreshNearby(generation, mode, loopState)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    renderRefreshFailure(generation, mode, failure)
                }
                delay(refreshDelayMs)
            }
        }
    }

    private fun startFavoritesLoop() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        val mode = TransitMode.FAVORITES
        var loopState = RefreshLoopState(nearby = null, previousBoards = emptyMap())
        refreshJob = CoroutineScope(SupervisorJob() + refreshDispatcher).launch {
            while (isActive) {
                try {
                    loopState = refreshFavorites(generation, mode, loopState)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    renderRefreshFailure(generation, mode, failure)
                }
                delay(refreshDelayMs)
            }
        }
    }

    private suspend fun refreshNearby(
        generation: Long,
        mode: TransitMode,
        state: RefreshLoopState,
    ): RefreshLoopState {
        val nearby = checkNotNull(state.nearby)
        val fixedStops = nearby.stops ?: selectNearbyBoardStops(
            repository.nearbyStops(nearby.location)
                .take(MAX_CACHED_STOPS)
                .map { it.withDistanceFrom(nearby.location) },
            BOARD_STOP_COUNT,
        )
        coroutineContext.ensureActive()
        val nextNearby = nearby.copy(stops = fixedStops)
        if (fixedStops.isEmpty()) {
            postRefreshEmptyBoard(
                generation = generation,
                mode = mode,
                card = DepartureFormatter.message("Transit", "No nearby stops found."),
            )
            return RefreshLoopState(nearby = nextNearby, previousBoards = emptyMap())
        }
        val refresh = fetchDeparturesFor(fixedStops, state.previousBoards)
        postRefreshBoards(generation, mode, refresh)
        return RefreshLoopState(nearby = nextNearby, previousBoards = refresh.boardsByStopId)
    }

    private suspend fun refreshFavorites(
        generation: Long,
        mode: TransitMode,
        state: RefreshLoopState,
    ): RefreshLoopState {
        val stops = favoritesStore.list()
            .take(FAVORITE_BOARD_STOP_LIMIT)
            .map { it.withUnknownDistance() }
        if (stops.isEmpty()) {
            postRefreshEmptyBoard(
                generation = generation,
                mode = mode,
                card = noFavoritesCard(),
            )
            return state.copy(previousBoards = emptyMap())
        }

        coroutineContext.ensureActive()
        val refresh = fetchDeparturesFor(stops, state.previousBoards)
        postRefreshBoards(generation, mode, refresh)
        return state.copy(previousBoards = refresh.boardsByStopId)
    }

    private suspend fun fetchDeparturesFor(
        stops: List<TransitStop>,
        previousBoards: Map<String, TransitBoard>,
    ): BoardFetchResult {
        val nextBoards = linkedMapOf<String, TransitBoard>()
        val nextStale = linkedSetOf<String>()

        stops.forEach { stop ->
            coroutineContext.ensureActive()
            runCatching {
                TransitBoard(
                    stop = stop,
                    departures = repository.departures(stop.id),
                    fetchedAt = Instant.now(),
                )
            }.onSuccess { board ->
                nextBoards[stop.id] = board
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                host.log("transit stop refresh failed id=${stop.id}: ${failure.javaClass.simpleName}")
                val previous = previousBoards[stop.id]
                nextBoards[stop.id] = if (previous != null) {
                    previous.copy(stop = stop)
                } else {
                    TransitBoard(stop = stop, departures = emptyList(), fetchedAt = Instant.now())
                }
                nextStale += stop.id
            }
        }

        return BoardFetchResult(
            stops = stops,
            boardsByStopId = nextBoards,
            staleStopIds = nextStale,
        )
    }

    private fun releaseNearMeForeground(generation: Long, mode: TransitMode) {
        host.post {
            if (isRefreshCurrent(generation, mode)) host.setNearMeForeground(false)
        }
    }

    private fun movePage(forward: Boolean) {
        val now = Instant.now()
        syncPager(now)
        if (forward) {
            pager.forward()
        } else {
            pager.backward()
        }
        renderCurrentBoard(now)
    }

    private fun syncPager(now: Instant) {
        pager.updateStops(
            newStopIds = boardStops.map { it.id },
            newPageCounts = boardStops.map { stop ->
                boardsByStopId[stop.id]?.let { board -> DepartureFormatter.pageCount(board, now) } ?: 1
            },
        )
    }

    private fun renderCurrentBoard(now: Instant, staleOverride: Boolean = false) {
        if (boardStops.isEmpty()) return
        syncPager(now)
        val cursor = pager.cursor
        val stop = boardStops.getOrNull(cursor.stopIndex) ?: return
        val board = boardsByStopId[stop.id] ?: TransitBoard(stop, emptyList(), now)
        val stale = staleOverride || stop.id in staleStopIds
        sendCard(DepartureFormatter.format(board, now, stale = stale, page = cursor.pageIndex))
    }

    private fun renderRefreshFailure(generation: Long, mode: TransitMode, failure: Throwable) {
        postRefresh(generation, mode) {
            host.log("transit refresh failed: ${failure.javaClass.simpleName}")
            if (boardStops.isNotEmpty() && boardsByStopId.isNotEmpty()) {
                staleStopIds = boardStops.map { it.id }.toSet()
                renderCurrentBoard(Instant.now(), staleOverride = true)
            } else {
                sendCard(DepartureFormatter.message("Transit", "Transit fetch failed.", "stale ${localHourMinute()}"))
            }
        }
    }

    private fun stopRefreshLoop() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun postRefreshCard(generation: Long, mode: TransitMode, card: TransitCardContent) {
        postRefresh(generation, mode) {
            sendCard(card)
        }
    }

    private fun postRefreshEmptyBoard(
        generation: Long,
        mode: TransitMode,
        card: TransitCardContent,
    ) {
        postRefresh(generation, mode) {
            clearBoardState()
            sendCard(card)
        }
    }

    private fun postRefreshBoards(
        generation: Long,
        mode: TransitMode,
        refresh: BoardFetchResult,
    ) {
        postRefresh(generation, mode) {
            boardStops = refresh.stops
            boardsByStopId = refresh.boardsByStopId
            staleStopIds = refresh.staleStopIds
            val now = Instant.now()
            syncPager(now)
            renderCurrentBoard(now)
        }
    }

    private fun postRefresh(generation: Long, mode: TransitMode, action: () -> Unit) {
        host.post {
            if (!isRefreshCurrent(generation, mode)) return@post
            action()
        }
    }

    private fun isRefreshCurrent(generation: Long, mode: TransitMode): Boolean =
        refreshGeneration == generation && screen == Screen.BOARD && activeMode == mode

    private fun clearBoardState() {
        boardStops = emptyList()
        boardsByStopId = emptyMap()
        staleStopIds = emptySet()
        pager.reset()
    }

    private fun noFavoritesCard(): TransitCardContent =
        DepartureFormatter.message(
            title = "Transit",
            lines = listOf("No favorites yet.", "Add in phone app."),
        )

    private fun sendCard(card: TransitCardContent, forceShow: Boolean = false) {
        val contentKey = card.contentKey()
        if (!forceShow && contentKey == lastSentKey) return
        host.sendCard(card, show = forceShow || lastSentKey == null)
        lastSentKey = contentKey
    }

    private fun localHourMinute(): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(Instant.now())

    private enum class Screen {
        CHOOSER,
        BOARD,
    }

    private data class NearbySessionState(
        val location: TransitCoordinate,
        val stops: List<TransitStop>?,
    )

    private data class RefreshLoopState(
        val nearby: NearbySessionState?,
        val previousBoards: Map<String, TransitBoard>,
    )

    private data class BoardFetchResult(
        val stops: List<TransitStop>,
        val boardsByStopId: Map<String, TransitBoard>,
        val staleStopIds: Set<String>,
    )

    private companion object {
        private const val REFRESH_MS = 60_000L
        private const val LOCATION_TIMEOUT_MS = 15_000L
        private const val MAX_CACHED_STOPS = 5
        private const val BOARD_STOP_COUNT = 3
        // Bounds HTTP fan-out per refresh tick.
        private const val FAVORITE_BOARD_STOP_LIMIT = 5

        private val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        private val BACKWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        private val TAP_KEYS = setOf(
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
        )
    }
}
