package com.anezium.rokidbus.plugin.transit

import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.coroutineContext

class TransitPlugin : NexusPlugin {
    override val id: String = SURFACE_ID
    override val displayName: String = "Transit"
    override val handlesBack: Boolean = true

    private lateinit var host: NexusPluginHost
    private lateinit var repository: TransitRepository
    private lateinit var locationProvider: TransitLocationProvider
    private lateinit var favoritesStore: TransitFavoritesStore
    private val pager = BoardPager()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var screen = Screen.CHOOSER
    private var selectedMode = TransitMode.NEAR_ME
    private var activeMode = TransitMode.NEAR_ME
    private var lastSentKey: String? = null
    private var cachedLocation: TransitCoordinate? = null
    private var cachedStops: List<TransitStop> = emptyList()
    private var stopDiscoveryAtMs = 0L
    private var boardStops: List<TransitStop> = emptyList()
    private var boardsByStopId: Map<String, TransitBoard> = emptyMap()
    private var staleStopIds: Set<String> = emptySet()

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        repository = TransitRepository()
        locationProvider = TransitLocationProvider(host.context)
        favoritesStore = TransitFavoritesStore(host.context)
    }

    override fun onOpen() {
        stopRefreshLoop()
        lastSentKey = null
        screen = Screen.CHOOSER
        selectedMode = favoritesStore.lastMode()
        activeMode = selectedMode
        boardStops = emptyList()
        boardsByStopId = emptyMap()
        staleStopIds = emptySet()
        pager.reset()
        sendCard(TransitCards.chooser(selectedMode), forceShow = true)
    }

    override fun onClose() {
        stopRefreshLoop()
        lastSentKey = null
        host.send(
            BusPaths.SURFACE_HIDE,
            JSONObject()
                .put("surfaceId", SURFACE_ID),
        )
    }

    override fun onInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (screen) {
            Screen.CHOOSER -> handleChooserInput(event.keyCode)
            Screen.BOARD -> handleBoardInput(event.keyCode)
        }
    }

    private fun handleChooserInput(keyCode: Int) {
        when {
            keyCode == KeyEvent.KEYCODE_BACK -> onClose()
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
        activeMode = mode
        screen = Screen.BOARD
        boardStops = emptyList()
        boardsByStopId = emptyMap()
        staleStopIds = emptySet()
        pager.reset()
        favoritesStore.setLastMode(mode)

        when (mode) {
            TransitMode.NEAR_ME -> {
                sendCard(DepartureFormatter.message("Transit", "Locating..."))
                if (!locationProvider.hasLocationPermission()) {
                    sendCard(DepartureFormatter.message("Transit", "Grant location in phone app."))
                    return
                }
            }
            TransitMode.FAVORITES -> {
                if (favoritesStore.list().isEmpty()) {
                    sendCard(noFavoritesCard())
                } else {
                    sendCard(DepartureFormatter.message("Transit", "Loading favorites..."))
                }
            }
        }
        startRefreshLoop()
    }

    private fun returnToChooser() {
        stopRefreshLoop()
        screen = Screen.CHOOSER
        selectedMode = activeMode
        sendCard(TransitCards.chooser(selectedMode))
    }

    private suspend fun refreshOnce(
        generation: Long,
        mode: TransitMode,
        state: RefreshLoopState,
    ): RefreshLoopState {
        return when (mode) {
            TransitMode.NEAR_ME -> refreshNearby(generation, mode, state)
            TransitMode.FAVORITES -> refreshFavorites(generation, mode, state)
        }
    }

    private suspend fun refreshNearby(
        generation: Long,
        mode: TransitMode,
        state: RefreshLoopState,
    ): RefreshLoopState {
        if (!locationProvider.hasLocationPermission()) {
            postRefreshCard(generation, mode, DepartureFormatter.message("Transit", "Grant location in phone app."))
            return state
        }
        val location = locationProvider.currentLocation()
        if (location == null) {
            postRefreshCard(generation, mode, DepartureFormatter.message("Transit", "Waiting for phone location."))
            return state
        }

        val nowMs = System.currentTimeMillis()
        val nearby = state.nearby
        val shouldDiscover = nearby.cachedStops.isEmpty() ||
            nearby.stopDiscoveryAtMs == 0L ||
            nowMs - nearby.stopDiscoveryAtMs > STOP_DISCOVERY_MAX_AGE_MS ||
            nearby.cachedLocation?.let { haversineMeters(it, location) > STOP_MOVE_METERS } == true
        val nextCachedStops: List<TransitStop>
        val nextDiscoveryAtMs: Long
        if (shouldDiscover) {
            nextCachedStops = repository.nearbyStops(location)
                .take(MAX_CACHED_STOPS)
                .map { it.withDistanceFrom(location) }
            nextDiscoveryAtMs = nowMs
        } else {
            nextCachedStops = nearby.cachedStops
                .map { it.withDistanceFrom(location) }
                .sortedBy { it.distanceMeters }
            nextDiscoveryAtMs = nearby.stopDiscoveryAtMs
        }
        coroutineContext.ensureActive()

        val nextNearby = NearbyLoopState(
            cachedLocation = location,
            cachedStops = nextCachedStops,
            stopDiscoveryAtMs = nextDiscoveryAtMs,
        )
        val stops = selectNearbyBoardStops(nextCachedStops, BOARD_STOP_COUNT)
        if (stops.isEmpty()) {
            postRefreshEmptyBoard(
                generation = generation,
                mode = mode,
                nearby = nextNearby,
                card = DepartureFormatter.message("Transit", "No nearby stops found."),
            )
            return RefreshLoopState(nearby = nextNearby, previousBoards = emptyMap())
        }
        val refresh = fetchDeparturesFor(stops, state.previousBoards)
        postRefreshBoards(generation, mode, nearby = nextNearby, refresh = refresh)
        return RefreshLoopState(nearby = nextNearby, previousBoards = refresh.boardsByStopId)
    }

    private suspend fun refreshFavorites(
        generation: Long,
        mode: TransitMode,
        state: RefreshLoopState,
    ): RefreshLoopState {
        val stored = favoritesStore.list()
        if (stored.isEmpty()) {
            postRefreshEmptyBoard(
                generation = generation,
                mode = mode,
                nearby = null,
                card = noFavoritesCard(),
            )
            return state.copy(previousBoards = emptyMap())
        }

        val location = locationProvider.currentLocation()
        val stops = if (location != null) {
            stored.map { it.withDistanceFrom(location) }
                .sortedBy { it.distanceMeters }
                .take(FAVORITE_BOARD_STOP_LIMIT)
        } else {
            stored.take(FAVORITE_BOARD_STOP_LIMIT)
                .map { it.withUnknownDistance() }
        }
        coroutineContext.ensureActive()
        val refresh = fetchDeparturesFor(stops, state.previousBoards)
        postRefreshBoards(generation, mode, nearby = null, refresh = refresh)
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
                host.log("transit stop refresh failed id=${stop.id}: ${failure.javaClass.simpleName}: ${failure.message}")
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
            host.log("transit refresh failed: ${failure.javaClass.simpleName}: ${failure.message}")
            if (boardStops.isNotEmpty() && boardsByStopId.isNotEmpty()) {
                staleStopIds = boardStops.map { it.id }.toSet()
                renderCurrentBoard(Instant.now(), staleOverride = true)
            } else {
                sendCard(DepartureFormatter.message("Transit", "Transit fetch failed.", "stale ${localHourMinute()}"))
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        val mode = activeMode
        var loopState = RefreshLoopState(
            nearby = NearbyLoopState(
                cachedLocation = cachedLocation,
                cachedStops = cachedStops,
                stopDiscoveryAtMs = stopDiscoveryAtMs,
            ),
            previousBoards = boardsByStopId,
        )
        refreshJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                try {
                    loopState = refreshOnce(generation, mode, loopState)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    renderRefreshFailure(generation, mode, failure)
                }
                delay(REFRESH_MS)
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
        nearby: NearbyLoopState?,
        card: TransitCardContent,
    ) {
        postRefresh(generation, mode) {
            nearby?.let(::applyNearbyState)
            clearBoardState()
            sendCard(card)
        }
    }

    private fun postRefreshBoards(
        generation: Long,
        mode: TransitMode,
        nearby: NearbyLoopState?,
        refresh: BoardFetchResult,
    ) {
        postRefresh(generation, mode) {
            nearby?.let(::applyNearbyState)
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

    private fun applyNearbyState(nearby: NearbyLoopState) {
        cachedLocation = nearby.cachedLocation
        cachedStops = nearby.cachedStops
        stopDiscoveryAtMs = nearby.stopDiscoveryAtMs
    }

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
        val lines = JSONArray()
        card.lines.forEach { line ->
            if (line.isStructured) {
                lines.put(
                    JSONObject()
                        .put("text", line.text)
                        .put("badge", line.badge)
                        .put("trail", JSONArray(line.trail)),
                )
            } else {
                lines.put(line.text)
            }
        }
        val payload = JSONObject()
            .put("surfaceId", SURFACE_ID)
            .put("kind", "card")
            .put("contentKey", contentKey)
            .put("title", card.title)
            .put("lines", lines)
            .put("footer", card.footer)
        host.send(
            if (forceShow || lastSentKey == null) BusPaths.SURFACE_SHOW else BusPaths.SURFACE_UPDATE,
            payload,
        )
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

    private data class NearbyLoopState(
        val cachedLocation: TransitCoordinate?,
        val cachedStops: List<TransitStop>,
        val stopDiscoveryAtMs: Long,
    )

    private data class RefreshLoopState(
        val nearby: NearbyLoopState,
        val previousBoards: Map<String, TransitBoard>,
    )

    private data class BoardFetchResult(
        val stops: List<TransitStop>,
        val boardsByStopId: Map<String, TransitBoard>,
        val staleStopIds: Set<String>,
    )

    private companion object {
        private const val SURFACE_ID = "transit"
        private const val REFRESH_MS = 60_000L
        private const val STOP_DISCOVERY_MAX_AGE_MS = 5 * 60_000L
        private const val STOP_MOVE_METERS = 200
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
