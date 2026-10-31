package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.plugin.nav.MvtFixtures.line
import com.anezium.rokidbus.shared.RoutePathRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shortbread tiles as the map uses them: which streets, clipped to their tile, and the Web Mercator grid. */
class OsmBasemapTest {
    @Test
    fun `dense address and POI layers do not crowd streets out of the tile budget`() {
        val point = MvtFixtures.Proto().apply {
            varint(3, 1)
            packed(4, listOf(MvtFixtures.command(1, 1), 0, 0))
        }.toByteArray()
        fun unusedLayer(name: String) = MvtFixtures.Proto().apply {
            repeat(8_000) { bytes(2, point) }
            string(3, "name")
            repeat(8_000) { bytes(4, MvtFixtures.Proto().apply { string(1, "Unused $it") }.toByteArray()) }
            // Legal protobuf ordering must not make unused readers consume the retained budget.
            string(1, name)
        }.toByteArray()
        val streets = MvtFixtures.shortbread(
            listOf(line(0 to 100, 4096 to 100, tags = mapOf("kind" to "residential"))),
            listOf(line(0 to 100, 4096 to 100, tags = mapOf("kind" to "residential", "name" to "Test Street"))),
        )
        val body = MvtFixtures.Proto().apply {
            bytes(3, unusedLayer("addresses"))
            write(streets)
            bytes(3, unusedLayer("pois"))
        }.toByteArray()
        // 16,002 features and over 16,000 table entries, all synthetic; only two features are retained.
        for (limits in listOf(MvtLimits(), MvtLimits(maxFeatures = 2, maxKeysOrValues = 6))) {
            for (encoded in listOf(body, MvtFixtures.gzip(body))) {
                val tile = ShortbreadTiles.decode(TileKey(14, 1, 1), encoded, limits)
                assertEquals(StreetClass.STREET, tile.streets.single().streetClass)
                assertArrayEquals(intArrayOf(0, 100, 4096, 100), tile.streets.single().points)
                assertEquals("Test Street", tile.names.single().name)
            }
        }
    }

    @Test
    fun `walkable streets are kept by class, railways and road tunnels are not`() {
        assertEquals(StreetClass.MAJOR, StreetClass.of("primary"))
        assertEquals(StreetClass.MAJOR, StreetClass.of("tertiary"))
        assertEquals(StreetClass.STREET, StreetClass.of("residential"))
        assertEquals(StreetClass.STREET, StreetClass.of("pedestrian", tunnel = true))
        assertEquals(StreetClass.PATH, StreetClass.of("footway"))
        assertEquals(StreetClass.PATH, StreetClass.of("steps", tunnel = true))
        assertEquals(RoutePathRole.MAJOR, StreetClass.MAJOR.role)
        assertEquals(RoutePathRole.STREET, StreetClass.PATH.role)
        assertNull(StreetClass.of("rail"))
        assertNull(StreetClass.of("primary", rail = true))
        assertNull(StreetClass.of("subway"))
        assertNull(StreetClass.of("runway"))
        assertNull(StreetClass.of("trunk", tunnel = true))
        assertNull(StreetClass.of("residential", tunnel = true))
        assertNull(StreetClass.of(null))
    }

    @Test
    fun `a street is clipped to its own tile, so neighbours meet on the shared edge without overlap`() {
        // From tile A into the buffer of its east neighbour, and the same street as B sees it.
        val inA = ShortbreadTiles.clipToTile(intArrayOf(4000, 100, 4160, 300), 4096)
        val inB = ShortbreadTiles.clipToTile(intArrayOf(4000 - 4096, 100, 4160 - 4096, 300), 4096)
        assertArrayEquals(intArrayOf(4000, 100, 4096, 220), inA.single())
        assertArrayEquals(intArrayOf(0, 220, 64, 300), inB.single())
        // A line that leaves and comes back is two runs, never one joined across the outside.
        val bent = ShortbreadTiles.clipToTile(intArrayOf(100, 100, 100, -100, 200, -100, 200, 100), 4096)
        assertEquals(2, bent.size)
        assertArrayEquals(intArrayOf(100, 100, 100, 0), bent[0])
        assertArrayEquals(intArrayOf(200, 0, 200, 100), bent[1])
        assertTrue(ShortbreadTiles.clipToTile(intArrayOf(-10, -10, -5, -20), 4096).isEmpty())
    }

    @Test
    fun `a decoded tile keeps walkable streets and their names, nothing else`() {
        val body = MvtFixtures.shortbread(
            streets = listOf(
                line(0 to 0, 100 to 0, tags = mapOf("kind" to "residential")),
                line(0 to 10, 100 to 10, tags = mapOf("kind" to "rail", "rail" to true)),
                line(0 to 20, 100 to 20, tags = mapOf("kind" to "primary", "tunnel" to true)),
                line(0 to 30, 100 to 30, tags = mapOf("kind" to "footway")),
            ),
            labels = listOf(
                line(0 to 0, 100 to 0, tags = mapOf("kind" to "residential", "name" to "Rue Exemple")),
                line(0 to 10, 100 to 10, tags = mapOf("kind" to "rail", "name" to "Railway")),
                line(0 to 30, 100 to 30, tags = mapOf("kind" to "footway")),
            ),
        )
        val tile = ShortbreadTiles.decode(TileKey(14, 1, 1), MvtFixtures.gzip(body))
        assertEquals(listOf(StreetClass.STREET, StreetClass.PATH), tile.streets.map { it.streetClass })
        assertEquals(listOf("Rue Exemple"), tile.names.map { it.name })
        assertEquals(4096, tile.extent)
    }

    @Test
    fun `the public sample keeps its walkable streets and real names`() {
        val tile = ShortbreadTiles.decode(TileKey(14, 8800, 5373), MvtReaderTest.sample())
        assertTrue(tile.streets.size > 1_000)
        assertTrue(tile.streets.any { it.streetClass == StreetClass.MAJOR })
        assertTrue(tile.streets.all { line -> line.points.all { it in 0..4096 } })
        val names = tile.names.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("Unter den Linden", "Ebertstraße", "Straße des 17. Juni")))
    }

    @Test
    fun `a body that is not a tile, or inflates too far, is refused`() {
        assertThrows(MvtFormatException::class.java) { ShortbreadTiles.decode(TileKey(14, 1, 1), byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)) }
        val bomb = MvtFixtures.gzip(ByteArray(ShortbreadTiles.MAX_TILE_BYTES + 1))
        assertTrue(bomb.size < 64 * 1024)
        assertThrows(MvtFormatException::class.java) { ShortbreadTiles.inflate(bomb) }
        // An empty tile is valid and draws nothing.
        val empty = ShortbreadTiles.decode(TileKey(14, 1, 1), ByteArray(0))
        assertTrue(empty.streets.isEmpty() && empty.names.isEmpty())
    }

    @Test
    fun `the OSM source builds its versioned address from integers only and stops at zoom 14`() {
        assertEquals("https://vector.openstreetmap.org/shortbread_v1/14/8800/5373.mvt", OsmShortbreadSource.url(TileKey(14, 8800, 5373)))
        assertEquals("© OpenStreetMap", OsmShortbreadSource.attribution)
        assertThrows(IllegalArgumentException::class.java) { OsmShortbreadSource.url(TileKey(15, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { TileKey(14, 16384, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileKey(14, 0, -1) }
    }

    @Test
    fun `Web Mercator matches the tile grid, wraps the antimeridian and clamps the poles`() {
        // The fixture's tile holds the Brandenburg Gate.
        val n = 1 shl 14
        assertEquals(8800, (WebMercator.worldX(13.3777) * n).toInt())
        assertEquals(5373, (WebMercator.worldY(52.5163) * n).toInt())
        assertEquals(13.359375, WebMercator.lon(8800.0 / n), 1e-9)
        assertEquals(52.5229059, WebMercator.lat(5373.0 / n), 1e-6)

        val across = WebMercator.cover(14, 179.99, -0.01, -179.99, 0.01, 9)!!
        assertEquals(setOf(0, n - 1), across.map { it.x }.toSet())
        assertEquals(4, across.size)
        val polar = WebMercator.cover(12, -0.01, 89.0, 0.01, 89.5, 9)!!
        assertTrue(polar.all { it.y == 0 })
        assertNull(WebMercator.cover(14, 2.0, 48.0, 2.2, 48.2, 9))
    }
}
