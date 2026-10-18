package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RouteLabel
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A street in the journey's local metres ([RouteLine.toMeters]), with its bounding box. */
internal class LocalLine(val streetClass: StreetClass, val xs: DoubleArray, val ys: DoubleArray, val name: String? = null) {
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
}

/**
 * A tile converted once into the journey's projection, off the main thread, so drawing it is only
 * an affine step: the same projection the route uses, vertex by vertex, from the tile's exact Web
 * Mercator coordinates.
 */
internal class LocalTile(val key: TileKey, val streets: List<LocalLine>, val names: List<LocalLine>) {
    // Computed on the decoding worker. The view rejects excess work before clipping or allocating.
    val vertices: Long = streets.sumOf { it.xs.size.toLong() } + names.sumOf { it.xs.size.toLong() }
    val simplifyWork: Long = streets.sumOf { 4L * it.xs.size * it.xs.size }

    companion object {
        fun of(tile: BasemapTile, line: RouteLine): LocalTile {
            val n = (1 shl tile.key.z).toDouble()
            val extent = tile.extent.toDouble()
            fun convert(points: IntArray, streetClass: StreetClass, name: String?): LocalLine {
                val count = points.size / 2
                val xs = DoubleArray(count)
                val ys = DoubleArray(count)
                for (index in 0 until count) {
                    val worldX = (tile.key.x + points[index * 2] / extent) / n
                    val worldY = (tile.key.y + points[index * 2 + 1] / extent) / n
                    val meters = line.toMeters(WebMercator.lat(worldY), WebMercator.lon(worldX))
                    xs[index] = meters.x
                    ys[index] = meters.y
                }
                return LocalLine(streetClass, xs, ys, name)
            }
            return LocalTile(
                tile.key,
                tile.streets.map { convert(it.points, it.streetClass, null) },
                tile.names.map { convert(it.points, it.streetClass, it.name) },
            )
        }
    }
}

/** The tiles one view draws: their zoom and keys. */
internal data class TileCover(val zoom: Int, val keys: List<TileKey>)

/**
 * The street tiles a journey can draw right now, already in its projection. Immutable: the loader
 * hands out a new one each time a tile arrives or leaves, and a geometry built from it never
 * changes under its feet.
 */
internal class NavBasemapTiles(val attribution: String, val maxZoom: Int, val tiles: Map<TileKey, LocalTile>) {
    companion object {
        val NONE = NavBasemapTiles("", 0, emptyMap())
    }
}

/**
 * Puts the real streets around a planned walk under its route, within what is left of the route
 * geometry's budget once the route, the marks and the planner's labels are in.
 *
 * Only streets OpenStreetMap has are drawn, each where it is: clipped with Liang-Barsky to the
 * frame, simplified by at most [SIMPLIFY_UNITS] of a unit, never joined to a street it does not
 * touch. Which streets fit is decided by distance to the route and the wearer, scaled by class,
 * so the nearest streets and the major roads come before footways farther off.
 *
 * Twenty-four paths would hold only a handful of streets one by one, so the chosen street
 * segments are drawn as walks: each connected group of them is one path that goes along every
 * segment and back over drawn ones where it must. Every stroke of such a path lies on a real
 * street; nothing is drawn between streets that do not meet.
 */
internal object NavBasemapLayer {
    /** Follow views stay within four tiles; a whole route may take nine. */
    const val FOLLOW_MAX_TILES = 4
    const val OVERVIEW_MAX_TILES = 9

    /** Below this zoom Shortbread drops the residential streets, so a wider view is route only. */
    const val MIN_ZOOM = 12

    /** The most the streets may add to a geometry: the link carries one a second, and two per page revision. */
    const val MAX_BASEMAP_BYTES = 8 * 1024

    const val MAX_PREPARE_VERTICES = 16_000L
    const val MAX_SIMPLIFY_WORK = 2_000_000L
    private const val MAX_NAME_VERTICES = 256

    internal fun withinWorkBudget(tiles: List<LocalTile>): Boolean =
        tiles.sumOf { it.vertices } <= MAX_PREPARE_VERTICES &&
            tiles.sumOf { it.simplifyWork } <= MAX_SIMPLIFY_WORK

    const val SIMPLIFY_UNITS = 1.2
    private const val EXTENT = RouteGeometryContract.EXTENT
    private const val COVER_MARGIN = 1.02
    private const val MAX_CANDIDATE_EDGES = 512
    private const val LABEL_RESERVE_BYTES = 320
    private const val PATH_JSON_CHARS = 23
    private const val LABEL_MARGIN = 40
    private const val LABEL_SPACING = 160.0
    private const val LABEL_ON_STREET_UNITS = 6.0
    private const val FIELD_CELLS = 50
    private const val CELL_UNITS = EXTENT.toDouble() / FIELD_CELLS

    /** The tiles [window] needs at the deepest zoom where it stays within the view's tile count, or null. */
    fun cover(window: NavMapWindow, line: RouteLine, view: NavMapView, maxZoom: Int): TileCover? {
        val half = window.spanMeters / 2.0 * COVER_MARGIN
        val (north, west) = line.latLonAt(MapMeters(window.center.x - half, window.center.y - half))
        val (south, east) = line.latLonAt(MapMeters(window.center.x + half, window.center.y + half))
        val maxTiles = if (view == NavMapView.FOLLOW) FOLLOW_MAX_TILES else OVERVIEW_MAX_TILES
        for (zoom in maxZoom downTo MIN_ZOOM) {
            WebMercator.cover(zoom, west, south, east, north, maxTiles)?.let { return TileCover(zoom, it) }
        }
        return null
    }

    /** [route] with the streets of [basemap] that fit beneath it, or [route] itself when none do. */
    fun draw(route: RouteGeometry, window: NavMapWindow, line: RouteLine, view: NavMapView, budget: NavMapBudget, basemap: NavBasemapTiles): RouteGeometry {
        if (basemap.tiles.isEmpty()) return route
        val cover = cover(window, line, view, basemap.maxZoom) ?: return route
        val tiles = cover.keys.mapNotNull(basemap.tiles::get)
        if (tiles.isEmpty()) return route
        if (!withinWorkBudget(tiles)) return route
        val field = FocusField(route)
        val edges = edges(tiles, window, field)
        if (edges.isEmpty()) return route

        val pathsLeft = budget.maxPaths - route.paths.size
        val pointsLeft = budget.maxPoints - route.paths.sumOf { it.points.size }
        if (pathsLeft <= 0 || pointsLeft < RouteGeometryContract.MIN_PATH_POINTS) return route
        val credited = route.copy(attribution = basemap.attribution)
        val creditedBytes = bytes(credited)
        val bytesLimit = min(budget.maxBytes, bytes(route) + MAX_BASEMAP_BYTES)

        // Sized without encoding: each street path adds its own JSON and the comma before it.
        fun attempt(count: Int): Pair<List<RoutePath>, List<Edge>>? {
            val packed = pack(edges.subList(0, count), pathsLeft)
            val paths = packed.first
            if (paths.sumOf { it.points.size } > pointsLeft) return null
            val added = paths.sumOf { pathBytes(it) + 1 } - if (route.paths.isEmpty() && paths.isNotEmpty()) 1 else 0
            return packed.takeIf { creditedBytes + added + LABEL_RESERVE_BYTES <= bytesLimit }
        }

        // The largest count of the best edges that fits: more edges only ever cost more points and bytes.
        var low = 0
        var high = min(edges.size, pointsLeft)
        var best: Pair<List<RoutePath>, List<Edge>>? = null
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = attempt(middle)
            if (candidate != null) {
                best = candidate
                low = middle
            } else {
                high = middle - 1
            }
        }
        val (paths, drawn) = best ?: return route
        if (drawn.isEmpty()) return route
        val geometry = credited.copy(paths = route.paths + paths)
        val named = geometry.copy(labels = route.labels + names(tiles, window, field, drawn, route.labels))
        return listOf(named, geometry).firstOrNull { bytes(it) <= bytesLimit } ?: route
    }

    /** The JSON one path adds to the encoded geometry: `{"role":"...","points":[x,y,...]}`. */
    private fun pathBytes(path: RoutePath): Int =
        PATH_JSON_CHARS + path.role.wireValue.length + 2 * path.points.size - 1 + path.points.sumOf { digits(it.x) + digits(it.y) }

    private fun digits(value: Int): Int = if (value >= 1000) 4 else if (value >= 100) 3 else if (value >= 10) 2 else 1

    /** One street segment in frame units, [a] and [b] packed as `x * 1001 + y`. */
    private class Edge(val a: Int, val b: Int, val streetClass: StreetClass, val score: Double)

    private fun vertexOf(x: Int, y: Int): Int = x * (EXTENT + 1) + y

    private fun pointOf(vertex: Int): RoutePoint = RoutePoint(vertex / (EXTENT + 1), vertex % (EXTENT + 1))

    /** Every visible street segment once, best first: duplicates from shared ways or tile edges merged. */
    private fun edges(tiles: List<LocalTile>, window: NavMapWindow, field: FocusField): List<Edge> {
        val half = window.spanMeters / 2.0
        val left = window.center.x - half
        val right = window.center.x + half
        val top = window.center.y - half
        val bottom = window.center.y + half
        // Each visible run as a chain of whole frame units: the vertices the wire will carry.
        val chains = ArrayList<Pair<StreetClass, IntArray>>()
        for (tile in tiles) {
            for (street in tile.streets) {
                if (street.maxX < left || street.minX > right || street.maxY < top || street.minY > bottom) continue
                for (run in runs(street, window)) {
                    val chain = ArrayList<Int>(run.size)
                    for (point in run) {
                        val vertex = vertexOf(point.x.roundToInt().coerceIn(0, EXTENT), point.y.roundToInt().coerceIn(0, EXTENT))
                        if (chain.lastOrNull() != vertex) chain += vertex
                    }
                    if (chain.size >= 2) chains += street.streetClass to chain.toIntArray()
                }
            }
        }
        // A vertex two runs share is a junction: simplification never moves it, or streets that meet would part.
        val uses = HashMap<Int, Int>()
        chains.forEach { (_, chain) -> chain.forEach { uses[it] = (uses[it] ?: 0) + 1 } }
        val byKey = HashMap<Long, Edge>()
        fun add(streetClass: StreetClass, from: Int, to: Int) {
            if (from == to) return
            val key = min(from, to).toLong() shl 32 or max(from, to).toLong()
            val existing = byKey[key]
            if (existing != null && existing.streetClass.ordinal <= streetClass.ordinal) return
            val a = pointOf(from)
            val b = pointOf(to)
            val distance = field.distance((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
            byKey[key] = Edge(from, to, streetClass, (distance + 15.0) * streetClass.weight)
        }
        for ((streetClass, chain) in chains) {
            var start = 0
            for (index in 1 until chain.size) {
                if (index != chain.lastIndex && (uses[chain[index]] ?: 0) < 2) continue
                val stretch = (start..index).map { pointOf(chain[it]).let { point -> MapMeters(point.x.toDouble(), point.y.toDouble()) } }
                NavMapProjection.simplify(stretch, SIMPLIFY_UNITS).zipWithNext().forEach { (a, b) ->
                    add(streetClass, vertexOf(a.x.toInt(), a.y.toInt()), vertexOf(b.x.toInt(), b.y.toInt()))
                }
                start = index
            }
        }
        return byKey.values.sortedWith(compareBy<Edge>({ it.score }, { it.a }, { it.b })).take(MAX_CANDIDATE_EDGES)
    }

    /** The parts of [street] inside the frame, as frame-unit polylines. */
    private fun runs(street: LocalLine, window: NavMapWindow): List<List<MapMeters>> {
        val runs = ArrayList<List<MapMeters>>()
        var run = ArrayList<MapMeters>()
        for (index in 1 until street.xs.size) {
            val a = window.normalized(MapMeters(street.xs[index - 1], street.ys[index - 1]))
            val b = window.normalized(MapMeters(street.xs[index], street.ys[index]))
            val clipped = NavMapProjection.clip(a, b)
            if (clipped == null) {
                if (run.size >= 2) runs += run
                run = ArrayList()
                continue
            }
            if (run.isEmpty() || run.last() != clipped.first) {
                if (run.size >= 2) runs += run
                run = arrayListOf(clipped.first)
            }
            run += clipped.second
            if (clipped.second != b) {
                runs += run
                run = ArrayList()
            }
        }
        if (run.size >= 2) runs += run
        return runs
    }

    /** One path's walk: the best score among its segments, the path, and the segments it draws. */
    private class Walk(val score: Double, val path: RoutePath, val drawn: List<Edge>)

    /**
     * The chosen edges as few paths as possible: each connected group is walked depth first from
     * its best edge, going back over drawn segments to reach the next. Ordinary streets may also go
     * along chosen major roads to reach each other: drawn thinner under the major road's own wider
     * stroke, that adds nothing to the picture. Walks are kept best first, up to [maxPaths]; the
     * edges actually drawn come back too.
     */
    private fun pack(edges: List<Edge>, maxPaths: Int): Pair<List<RoutePath>, List<Edge>> {
        val majors = edges.filter { it.streetClass.role == RoutePathRole.MAJOR }
        val minors = edges.filter { it.streetClass.role == RoutePathRole.STREET }
        val kept = (walks(RoutePathRole.MAJOR, majors, emptyList()) + walks(RoutePathRole.STREET, minors, majors))
            .sortedBy { it.score }
            .take(maxPaths)
        return kept.map { it.path } to kept.flatMap { it.drawn }
    }

    private class Frame(val vertex: Int, val entry: Int, val drawnAtEntry: Int, val viaConnector: Boolean) {
        var cursor = 0
    }

    private fun walks(role: RoutePathRole, own: List<Edge>, connectors: List<Edge>): List<Walk> {
        val all = own + connectors
        val adjacency = HashMap<Int, MutableList<Int>>()
        all.forEachIndexed { index, edge ->
            adjacency.getOrPut(edge.a) { ArrayList() } += index
            adjacency.getOrPut(edge.b) { ArrayList() } += index
        }
        val used = BooleanArray(own.size)
        // The walk that last went along each connector, so no walk goes round one twice.
        val crossed = IntArray(connectors.size) { -1 }
        val result = ArrayList<Walk>()
        for ((first, seed) in own.withIndex()) {
            if (used[first]) continue
            val id = result.size
            val walk = arrayListOf(seed.a)
            val drawn = ArrayList<Edge>()
            val stack = ArrayDeque<Frame>().apply { addLast(Frame(seed.a, 0, 0, false)) }
            var forwardEnd = 1
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val incident = adjacency.getValue(frame.vertex)
                var next = -1
                while (next < 0 && frame.cursor < incident.size) {
                    val index = incident[frame.cursor++]
                    val free = if (index < own.size) !used[index] else crossed[index - own.size] != id
                    if (free) next = index
                }
                if (next >= 0) {
                    val edge = all[next]
                    val other = if (edge.a == frame.vertex) edge.b else edge.a
                    val connector = next >= own.size
                    if (connector) {
                        crossed[next - own.size] = id
                    } else {
                        used[next] = true
                        drawn += edge
                    }
                    walk += other
                    if (!connector) forwardEnd = walk.size
                    stack.addLast(Frame(other, walk.size - 1, drawn.size, connector))
                } else {
                    stack.removeLast()
                    if (frame.viaConnector && drawn.size == frame.drawnAtEntry) {
                        // A detour along a major road that reached no new street is taken back out.
                        walk.subList(frame.entry, walk.size).clear()
                    } else if (stack.isNotEmpty()) {
                        walk += stack.last().vertex
                    }
                }
            }
            // The way back after the last new segment draws nothing new.
            result += Walk(seed.score, RoutePath(role, walk.subList(0, forwardEnd).map(::pointOf)), drawn)
        }
        return result
    }

    /**
     * A few real street names, where the named street is drawn, near the route, and away from the
     * labels already there. They come last: the hub keeps the route's own labels first.
     */
    private fun names(tiles: List<LocalTile>, window: NavMapWindow, field: FocusField, drawn: List<Edge>, existing: List<RouteLabel>): List<RouteLabel> {
        val room = RouteGeometryContract.MAX_LABELS - existing.size
        if (room <= 0) return emptyList()
        val streets = SegmentIndex(drawn)
        val candidates = HashMap<String, Triple<Double, RoutePoint, String>>()
        val low = LABEL_MARGIN.toDouble()
        val high = (EXTENT - LABEL_MARGIN).toDouble()
        var visited = 0
        for (tile in tiles) {
            for (name in tile.names) {
                if (visited + name.xs.size > MAX_NAME_VERTICES) continue
                visited += name.xs.size
                val text = NavText.fit(name.name ?: continue, RouteGeometryContract.MAX_LABEL_CHARS)
                if (text.isBlank()) continue
                val points = name.xs.indices.map { window.normalized(MapMeters(name.xs[it], name.ys[it])) }
                for ((index, point) in points.withIndex()) {
                    if (point.x !in low..high || point.y !in low..high) continue
                    if (!streets.near(point.x, point.y, LABEL_ON_STREET_UNITS)) continue
                    // Along a drawn street, not merely across one: a name's line can pass under a street in a tunnel.
                    val neighbour = points.getOrNull(index + 1) ?: points.getOrNull(index - 1) ?: continue
                    if (!streets.near((point.x + neighbour.x) / 2, (point.y + neighbour.y) / 2, LABEL_ON_STREET_UNITS)) continue
                    val score = (field.distance(point.x, point.y) + 15.0) * name.streetClass.weight
                    val key = text.lowercase()
                    if ((candidates[key]?.first ?: Double.MAX_VALUE) > score) {
                        candidates[key] = Triple(score, RoutePoint(point.x.roundToInt(), point.y.roundToInt()), text)
                    }
                }
            }
        }
        val taken = existing.map { it.text.lowercase() }.toMutableSet()
        val placed = existing.map { RoutePoint(it.x, it.y) }.toMutableList()
        val result = ArrayList<RouteLabel>()
        for ((_, point, text) in candidates.values.sortedWith(compareBy<Triple<Double, RoutePoint, String>>({ it.first }, { it.third }))) {
            if (result.size >= room) break
            if (text.lowercase() in taken) continue
            if (placed.any { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) < LABEL_SPACING }) continue
            taken += text.lowercase()
            placed += point
            result += RouteLabel(text, point.x, point.y, RouteGeometryContract.MAX_LABEL_PRIORITY)
        }
        return result
    }

    private fun bytes(geometry: RouteGeometry): Int = RouteGeometryContract.encode(geometry).toString().toByteArray(Charsets.UTF_8).size

    /** The drawn segments, bucketed so a label can ask whether it sits on one. */
    private class SegmentIndex(edges: List<Edge>) {
        private val buckets = HashMap<Int, MutableList<Pair<RoutePoint, RoutePoint>>>()

        init {
            for (edge in edges) {
                val a = pointOf(edge.a)
                val b = pointOf(edge.b)
                val segment = a to b
                val steps = max(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)) / BUCKET_UNITS + 1
                var previous = -1
                // Queries include neighbouring cells: sample the line once per cell width,
                // rather than allocating every cell of a diagonal's bounding rectangle.
                for (step in 0..steps) {
                    val column = cell(a.x + (b.x - a.x) * step / steps)
                    val row = cell(a.y + (b.y - a.y) * step / steps)
                    val key = column * BUCKETS + row
                    if (key != previous) buckets.getOrPut(key) { ArrayList() } += segment
                    previous = key
                }
            }
        }

        fun near(x: Double, y: Double, radius: Double): Boolean {
            val column = cell(x.toInt())
            val row = cell(y.toInt())
            for (dc in -1..1) for (dr in -1..1) {
                val bucket = buckets[(column + dc) * BUCKETS + row + dr] ?: continue
                if (bucket.any { (a, b) -> segmentDistance(x, y, a, b) <= radius }) return true
            }
            return false
        }

        private fun cell(value: Int): Int = (value / BUCKET_UNITS).coerceIn(0, BUCKETS - 1)

        private companion object {
            const val BUCKET_UNITS = 25
            const val BUCKETS = EXTENT / BUCKET_UNITS + 1
        }
    }

    /**
     * How far each part of the frame is from what the wearer follows: the route, walked or ahead,
     * their position, the next turn and the destination. A chamfer distance over a coarse grid,
     * in frame units; plenty to rank streets, and cheap enough for every revision.
     */
    private class FocusField(route: RouteGeometry) {
        private val cells = DoubleArray(FIELD_CELLS * FIELD_CELLS) { Double.MAX_VALUE }

        init {
            fun seed(x: Double, y: Double) {
                val column = (x / CELL_UNITS).toInt().coerceIn(0, FIELD_CELLS - 1)
                val row = (y / CELL_UNITS).toInt().coerceIn(0, FIELD_CELLS - 1)
                cells[row * FIELD_CELLS + column] = 0.0
            }
            route.paths.filter { it.role == RoutePathRole.ROUTE || it.role == RoutePathRole.COMPLETED }.forEach { path ->
                path.points.zipWithNext().forEach { (a, b) ->
                    val steps = max(1, (hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) / (CELL_UNITS / 2)).toInt())
                    for (step in 0..steps) {
                        val t = step.toDouble() / steps
                        seed(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    }
                }
            }
            listOfNotNull(route.position?.point, route.turn, route.destination).forEach { seed(it.x.toDouble(), it.y.toDouble()) }
            if (cells.none { it == 0.0 }) seed(EXTENT / 2.0, EXTENT / 2.0)
            val diagonal = sqrt(2.0)
            fun relax(row: Int, column: Int, otherRow: Int, otherColumn: Int, cost: Double) {
                if (otherRow !in 0 until FIELD_CELLS || otherColumn !in 0 until FIELD_CELLS) return
                val candidate = cells[otherRow * FIELD_CELLS + otherColumn] + cost
                if (candidate < cells[row * FIELD_CELLS + column]) cells[row * FIELD_CELLS + column] = candidate
            }
            for (row in 0 until FIELD_CELLS) for (column in 0 until FIELD_CELLS) {
                relax(row, column, row, column - 1, 1.0)
                relax(row, column, row - 1, column, 1.0)
                relax(row, column, row - 1, column - 1, diagonal)
                relax(row, column, row - 1, column + 1, diagonal)
            }
            for (row in FIELD_CELLS - 1 downTo 0) for (column in FIELD_CELLS - 1 downTo 0) {
                relax(row, column, row, column + 1, 1.0)
                relax(row, column, row + 1, column, 1.0)
                relax(row, column, row + 1, column + 1, diagonal)
                relax(row, column, row + 1, column - 1, diagonal)
            }
        }

        fun distance(x: Double, y: Double): Double {
            val column = (x / CELL_UNITS).toInt().coerceIn(0, FIELD_CELLS - 1)
            val row = (y / CELL_UNITS).toInt().coerceIn(0, FIELD_CELLS - 1)
            return cells[row * FIELD_CELLS + column] * CELL_UNITS
        }
    }

    private fun segmentDistance(x: Double, y: Double, a: RoutePoint, b: RoutePoint): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = dx * dx + dy * dy
        if (length == 0.0) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }
}
