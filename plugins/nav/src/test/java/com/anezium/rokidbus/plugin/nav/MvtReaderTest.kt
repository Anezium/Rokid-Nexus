package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.plugin.nav.MvtFixtures.Feature
import com.anezium.rokidbus.plugin.nav.MvtFixtures.Layer
import com.anezium.rokidbus.plugin.nav.MvtFixtures.line
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vector tile reader: the line grammar, tags, layer selection, and every bound that refuses a tile whole. */
class MvtReaderTest {
    @Test
    fun `lines keep their parts, their tags of every value type, and negative deltas`() {
        val bytes = MvtFixtures.tile(
            Layer(
                "streets",
                listOf(
                    Feature(
                        mapOf("kind" to "residential", "bridge" to true, "layer" to -2),
                        listOf(listOf(10 to 20, 4000 to 20, 4000 to 4100), listOf(-50 to 30, 10 to 5)),
                    ),
                ),
            ),
        )
        val layer = MvtReader.read(bytes, setOf("streets")).getValue("streets")
        assertEquals(4096, layer.extent)
        val street = layer.lines.single()
        assertEquals("residential", street.tags["kind"])
        assertEquals(true, street.tags["bridge"])
        assertEquals(-2L, street.tags["layer"])
        assertEquals(2, street.parts.size)
        assertArrayEquals(intArrayOf(10, 20, 4000, 20, 4000, 4100), street.parts[0])
        assertArrayEquals(intArrayOf(-50, 30, 10, 5), street.parts[1])
    }

    @Test
    fun `only the layers asked for are decoded, and points and polygons are skipped`() {
        val bytes = MvtFixtures.tile(
            Layer("pois", listOf(Feature(mapOf("amenity" to "cafe"), listOf(listOf(5 to 5)), type = 1))),
            Layer(
                "streets",
                listOf(
                    line(0 to 0, 10 to 10, tags = mapOf("kind" to "footway")),
                    Feature(mapOf("kind" to "pedestrian"), listOf(listOf(0 to 0, 10 to 0, 10 to 10)), type = 3),
                ),
            ),
        )
        val layers = MvtReader.read(bytes, setOf("streets"))
        assertEquals(setOf("streets"), layers.keys)
        assertEquals(listOf("footway"), layers.getValue("streets").lines.map { it.tags["kind"] })
    }

    @Test
    fun `a part of one point is not a line`() {
        val bytes = MvtFixtures.tile(Layer("streets", listOf(Feature(mapOf("kind" to "path"), listOf(listOf(1 to 1), listOf(2 to 2, 3 to 3))))))
        val lines = MvtReader.read(bytes, setOf("streets")).getValue("streets").lines
        assertEquals(1, lines.single().parts.size)
        assertArrayEquals(intArrayOf(2, 2, 3, 3), lines.single().parts.single())
    }

    @Test
    fun `malformed tiles fail as a whole`() {
        val good = MvtFixtures.tile(Layer("streets", listOf(line(0 to 0, 10 to 10, tags = mapOf("kind" to "footway")))))
        val cases = listOf(
            // Cut in the middle of the layer's length-delimited body.
            good.copyOf(good.size - 3),
            // A varint that never ends.
            byteArrayOf(0x1a, 0x05, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            // A length beyond the end of the tile.
            byteArrayOf(0x1a, 0x7f, 0x01),
            // Wire type 3 (groups) has no place in a vector tile.
            byteArrayOf(0x1b),
            // A tag index past the key table.
            MvtFixtures.Proto().apply {
                bytes(3, MvtFixtures.Proto().apply {
                    string(1, "streets")
                    bytes(2, MvtFixtures.Proto().apply {
                        packed(2, listOf(5, 0))
                        varint(3, 2)
                        packed(4, MvtFixtures.geometry(listOf(listOf(0 to 0, 1 to 1))))
                    }.toByteArray())
                    string(3, "kind")
                    bytes(4, MvtFixtures.Proto().apply { string(1, "path") }.toByteArray())
                }.toByteArray())
            }.toByteArray(),
            // ClosePath in a line, and LineTo before any MoveTo.
            lineWithGeometry(listOf(MvtFixtures.command(1, 1), 0, 0, MvtFixtures.command(7, 1))),
            lineWithGeometry(listOf(MvtFixtures.command(2, 1), 2, 2)),
            // A count claiming more parameters than the geometry holds.
            lineWithGeometry(listOf(MvtFixtures.command(1, 1), 0, 0, MvtFixtures.command(2, 1_000_000), 2, 2)),
            // A coordinate far beyond the tile's buffer.
            lineWithGeometry(listOf(MvtFixtures.command(1, 1), MvtFixtures.zigzag(4096 * 9), 0, MvtFixtures.command(2, 1), 2, 2)),
            // An unsupported version and an extent of zero.
            MvtFixtures.tile(Layer("streets", listOf(line(0 to 0, 1 to 1, tags = mapOf("kind" to "path"))), version = 3)),
            MvtFixtures.tile(Layer("streets", listOf(line(0 to 0, 1 to 1, tags = mapOf("kind" to "path"))), extent = 0)),
            // The same layer twice.
            MvtFixtures.tile(Layer("streets", emptyList()), Layer("streets", emptyList())),
        )
        cases.forEachIndexed { index, bytes ->
            assertThrows("case $index", MvtFormatException::class.java) { MvtReader.read(bytes, setOf("streets")) }
        }
    }

    @Test
    fun `feature, vertex and string bounds refuse a tile instead of truncating it`() {
        val many = MvtFixtures.tile(Layer("streets", List(20) { line(0 to 0, it to it + 1, tags = mapOf("kind" to "path")) }))
        assertEquals(20, MvtReader.read(many, setOf("streets"), MvtLimits(maxFeatures = 20)).getValue("streets").lines.size)
        assertThrows(MvtFormatException::class.java) { MvtReader.read(many, setOf("streets"), MvtLimits(maxFeatures = 19)) }
        assertThrows(MvtFormatException::class.java) { MvtReader.read(many, setOf("streets"), MvtLimits(maxVertices = 39)) }
        val longName = MvtFixtures.tile(Layer("street_labels", listOf(line(0 to 0, 1 to 1, tags = mapOf("name" to "x".repeat(100))))))
        assertThrows(MvtFormatException::class.java) { MvtReader.read(longName, setOf("street_labels"), MvtLimits(maxStringBytes = 99)) }
        // An unrequested layer is skipped by length, its contents never checked against the bounds.
        assertTrue(MvtReader.read(longName, setOf("streets"), MvtLimits(maxStringBytes = 99)).isEmpty())
    }

    @Test
    fun `the public Shortbread sample decodes with its street layers intact`() {
        val bytes = ShortbreadTiles.inflate(sample())
        val layers = MvtReader.read(bytes, setOf(ShortbreadTiles.STREETS, ShortbreadTiles.STREET_LABELS))
        val streets = layers.getValue(ShortbreadTiles.STREETS)
        val labels = layers.getValue(ShortbreadTiles.STREET_LABELS)
        // Counted independently of this reader when the fixture was made (see its README).
        assertEquals(4096, streets.extent)
        assertEquals(1635, streets.lines.size)
        assertEquals(1328, labels.lines.size)
        assertEquals(185, labels.lines.count { (it.tags["name"] as? String)?.isNotBlank() == true })
        assertEquals(925, streets.lines.count { it.tags["kind"] == "footway" })
        assertEquals(18, streets.lines.count { it.tags["kind"] == "primary" })
        assertTrue(labels.lines.any { it.tags["name"] == "Unter den Linden" })
        assertTrue(labels.lines.any { it.tags["name"] == "Ebertstraße" })
    }

    private fun lineWithGeometry(commands: List<Long>): ByteArray = MvtFixtures.Proto().apply {
        bytes(3, MvtFixtures.Proto().apply {
            string(1, "streets")
            bytes(2, MvtFixtures.Proto().apply {
                varint(3, 2)
                packed(4, commands)
            }.toByteArray())
        }.toByteArray())
    }.toByteArray()

    companion object {
        /** OpenStreetMap Shortbread 1 tile 14/8800/5373, its two street layers only. */
        fun sample(): ByteArray = MvtReaderTest::class.java.classLoader!!
            .getResourceAsStream("osm-shortbread-v1-14-8800-5373-streets.mvt.gz")!!.use { it.readBytes() }
    }
}
