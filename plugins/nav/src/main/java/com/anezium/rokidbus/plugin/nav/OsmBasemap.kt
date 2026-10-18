package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RoutePathRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** One Web Mercator (XYZ) tile. */
internal data class TileKey(val z: Int, val x: Int, val y: Int) {
    init {
        require(z in 0..MAX_ZOOM && x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "Tile outside the grid" }
    }

    companion object {
        const val MAX_ZOOM = 22
    }
}

/**
 * Where street tiles come from. The address is built here from integers only, so nothing a
 * response or a setting says can point a request anywhere else; another provider of the same
 * schema is one more implementation, the rest of the map is unchanged.
 */
internal interface BasemapSource {
    /** Names the source's tiles in the local cache. */
    val id: String

    /** Drawn on every map that shows this source's streets. */
    val attribution: String

    /** The deepest zoom the source serves; the map never asks for a deeper one. */
    val maxZoom: Int

    fun url(key: TileKey): String
}

/**
 * The OpenStreetMap Foundation's vector tiles (https://operations.osmfoundation.org/policies/vector/),
 * in the Shortbread 1 schema (https://shortbread-tiles.org/schema/1.0/). The versioned path is the
 * one the service's own `shortbread_v1/tilejson.json` and demo style name; the schema's streets go
 * to zoom 14.
 */
internal object OsmShortbreadSource : BasemapSource {
    override val id = "osm-shortbread-v1"
    override val attribution = "© OpenStreetMap"
    override val maxZoom = 14

    private const val TILES = "https://vector.openstreetmap.org/shortbread_v1/"

    override fun url(key: TileKey): String {
        require(key.z <= maxZoom)
        return "$TILES${key.z}/${key.x}/${key.y}.mvt"
    }
}

/**
 * What a street is to someone walking: [MAJOR] roads are drawn wider, [STREET]s are the ordinary
 * mesh, [PATH]s (footways, steps, service roads) are the finest and the first left out when room
 * is short. [weight] scales a street's distance when choosing what fits, so a major road a little
 * farther away still comes before a footway close by.
 */
internal enum class StreetClass(val role: RoutePathRole, val weight: Double) {
    MAJOR(RoutePathRole.MAJOR, 0.5),
    STREET(RoutePathRole.STREET, 1.0),
    PATH(RoutePathRole.STREET, 1.8),
    ;

    companion object {
        /**
         * The Shortbread `kind` of a `streets` or `street_labels` feature, or null for what is not
         * drawn: railways, runways and taxiways. A road in a tunnel is underground, not a street the
         * wearer sees, so only walkable underpasses are kept.
         */
        fun of(kind: Any?, rail: Any? = null, tunnel: Any? = null): StreetClass? {
            if (rail == true) return null
            val streetClass = when (kind) {
                "motorway", "trunk", "primary", "secondary", "tertiary" -> MAJOR
                "unclassified", "residential", "living_street", "pedestrian", "busway" -> STREET
                "service", "footway", "path", "cycleway", "steps", "track" -> PATH
                else -> return null
            }
            if (tunnel == true && streetClass != PATH && kind != "pedestrian") return null
            return streetClass
        }
    }
}

/** A street, clipped to its own tile: `x0, y0, …` in tile units. */
internal class BasemapLine(val streetClass: StreetClass, val points: IntArray)

/** A street's name, on the street's own line, clipped like it. */
internal class BasemapName(val name: String, val streetClass: StreetClass, val points: IntArray)

/**
 * One decoded tile, only what the map draws: walkable streets by class and street names. Every
 * line is clipped to the tile's own square, so the buffer a tile repeats from its neighbours is
 * gone and a street crossing two tiles is drawn once, its two halves meeting on the shared edge.
 */
internal class BasemapTile(val key: TileKey, val extent: Int, val streets: List<BasemapLine>, val names: List<BasemapName>) {
    /** Kept vertices, the measure of what the tile costs in memory. */
    val vertices: Int = streets.sumOf { it.points.size / 2 } + names.sumOf { it.points.size / 2 }
}

/** Reads a Shortbread 1 tile body (gzip or plain) into a [BasemapTile]; every failure is [MvtFormatException]. */
internal object ShortbreadTiles {
    const val STREETS = "streets"
    const val STREET_LABELS = "street_labels"

    /** The most a tile may inflate to. The densest city tiles measured are a fraction of it. */
    const val MAX_TILE_BYTES = 4 * 1024 * 1024
    private const val MAX_NAME_CHARS = 120

    fun decode(key: TileKey, body: ByteArray, limits: MvtLimits = MvtLimits()): BasemapTile {
        val layers = MvtReader.read(inflate(body), setOf(STREETS, STREET_LABELS), limits)
        val streets = layers[STREETS]
        val labels = layers[STREET_LABELS]
        val extent = streets?.extent ?: labels?.extent ?: DEFAULT_EXTENT
        if (labels != null && streets != null && labels.extent != streets.extent) throw MvtFormatException("layers disagree on the tile extent")
        val lines = streets?.lines.orEmpty().flatMap { line ->
            val streetClass = StreetClass.of(line.tags["kind"], line.tags["rail"], line.tags["tunnel"]) ?: return@flatMap emptyList()
            line.parts.flatMap { clipToTile(it, extent) }.map { BasemapLine(streetClass, it) }
        }
        val names = labels?.lines.orEmpty().flatMap { line ->
            val name = (line.tags["name"] as? String)?.trim()?.take(MAX_NAME_CHARS)?.takeIf(String::isNotEmpty) ?: return@flatMap emptyList()
            val streetClass = StreetClass.of(line.tags["kind"], tunnel = line.tags["tunnel"]) ?: return@flatMap emptyList()
            line.parts.flatMap { clipToTile(it, extent) }.map { BasemapName(name, streetClass, it) }
        }
        return BasemapTile(key, extent, lines, names)
    }

    /** The body as the tile's protobuf bytes, inflated if it is gzip, never beyond [MAX_TILE_BYTES]. */
    fun inflate(body: ByteArray): ByteArray {
        if (body.size < 2 || body[0] != 0x1f.toByte() || body[1] != 0x8b.toByte()) {
            if (body.size > MAX_TILE_BYTES) throw MvtFormatException("tile larger than $MAX_TILE_BYTES bytes")
            return body
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(body)).use { input ->
                val output = ByteArrayOutputStream(body.size * 3)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_TILE_BYTES) throw MvtFormatException("tile inflates beyond $MAX_TILE_BYTES bytes")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (invalid: java.io.IOException) {
            throw MvtFormatException("tile is not valid gzip")
        }
    }

    /**
     * The runs of [points] inside the tile's square, each segment clipped with Liang-Barsky. Both
     * tiles compute a shared edge's crossing from the same segment on the same grid, so the two
     * halves of a street meet exactly.
     */
    internal fun clipToTile(points: IntArray, extent: Int): List<IntArray> {
        val runs = ArrayList<IntArray>()
        var run = ArrayList<Int>()
        fun close() {
            if (run.size >= 4) runs += run.toIntArray()
            run = ArrayList()
        }
        val size = extent.toDouble()
        for (index in 2 until points.size step 2) {
            val ax = points[index - 2].toDouble()
            val ay = points[index - 1].toDouble()
            val bx = points[index].toDouble()
            val by = points[index + 1].toDouble()
            val clipped = clipSquare(ax, ay, bx, by, size)
            if (clipped == null) {
                close()
                continue
            }
            val sx = clipped[0].roundToInt()
            val sy = clipped[1].roundToInt()
            val ex = clipped[2].roundToInt()
            val ey = clipped[3].roundToInt()
            if (run.size < 2 || run[run.size - 2] != sx || run[run.size - 1] != sy) {
                close()
                run.add(sx)
                run.add(sy)
            }
            if (run[run.size - 2] != ex || run[run.size - 1] != ey) {
                run.add(ex)
                run.add(ey)
            }
            // Leaving the square ends the run; the next visible segment starts another.
            if (clipped[2] != bx || clipped[3] != by) close()
        }
        close()
        return runs
    }

    private fun clipSquare(ax: Double, ay: Double, bx: Double, by: Double, size: Double): DoubleArray? {
        val dx = bx - ax
        val dy = by - ay
        var enter = 0.0
        var leave = 1.0
        val ps = doubleArrayOf(-dx, dx, -dy, dy)
        val qs = doubleArrayOf(ax, size - ax, ay, size - ay)
        for (index in 0 until 4) {
            val p = ps[index]
            val q = qs[index]
            if (p == 0.0) {
                if (q < 0.0) return null
            } else {
                val ratio = q / p
                if (p < 0.0) enter = max(enter, ratio) else leave = min(leave, ratio)
                if (enter > leave) return null
            }
        }
        val sx = if (enter == 0.0) ax else ax + enter * dx
        val sy = if (enter == 0.0) ay else ay + enter * dy
        val ex = if (leave == 1.0) bx else ax + leave * dx
        val ey = if (leave == 1.0) by else ay + leave * dy
        return doubleArrayOf(sx, sy, ex, ey)
    }

    private const val DEFAULT_EXTENT = 4096
}

/** Web Mercator, as the XYZ tile grid uses it. */
internal object WebMercator {
    /** Beyond this latitude the square grid has no tiles. */
    const val MAX_LATITUDE = 85.0511287798066

    fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    fun worldY(lat: Double): Double {
        val clamped = Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return (1.0 - ln(tan(clamped) + 1.0 / kotlin.math.cos(clamped)) / PI) / 2.0
    }

    fun lon(worldX: Double): Double = worldX * 360.0 - 180.0

    fun lat(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))

    /**
     * The tiles of zoom [z] covering the box from [west] to [east] longitude (crossing the
     * antimeridian when [west] is east of [east]) and [south] to [north] latitude, or null when
     * there are more than [maxTiles].
     */
    fun cover(z: Int, west: Double, south: Double, east: Double, north: Double, maxTiles: Int): List<TileKey>? {
        val n = 1 shl z
        fun column(lon: Double) = floor(worldX(lon) * n).toInt().coerceIn(0, n - 1)
        fun row(lat: Double) = floor(worldY(lat) * n).toInt().coerceIn(0, n - 1)
        val left = column(west)
        val right = column(east)
        val columns = if (west <= east) (left..right).toList() else (left until n).toList() + (0..right).toList()
        val rows = row(north)..row(south)
        if (columns.size.toLong() * (rows.last - rows.first + 1) > maxTiles) return null
        return rows.flatMap { y -> columns.map { x -> TileKey(z, x, y) } }
    }
}
