package com.anezium.rokidbus.plugin.nav

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Writes small Mapbox Vector Tiles for tests, independently of the reader: protobuf by hand, the
 * MVT 2.1 command grammar, string, integer and boolean values. Coordinates are tile units.
 */
internal object MvtFixtures {
    class Feature(val tags: Map<String, Any>, val parts: List<List<Pair<Int, Int>>>, val type: Int = 2)

    class Layer(val name: String, val features: List<Feature>, val extent: Int = 4096, val version: Int = 2)

    fun line(vararg points: Pair<Int, Int>, tags: Map<String, Any>) = Feature(tags, listOf(points.toList()))

    fun tile(vararg layers: Layer): ByteArray = Proto().apply { layers.forEach { bytes(3, layer(it)) } }.toByteArray()

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** A `streets` and a `street_labels` layer in Shortbread's shape. */
    fun shortbread(streets: List<Feature>, labels: List<Feature> = emptyList()): ByteArray =
        tile(Layer(ShortbreadTiles.STREETS, streets), Layer(ShortbreadTiles.STREET_LABELS, labels))

    fun layer(layer: Layer): ByteArray {
        val keys = layer.features.flatMap { it.tags.keys }.distinct()
        val values = layer.features.flatMap { it.tags.values }.distinct()
        return Proto().apply {
            varint(15, layer.version.toLong())
            string(1, layer.name)
            layer.features.forEach { feature ->
                bytes(2, Proto().apply {
                    packed(2, feature.tags.flatMap { (key, value) -> listOf(keys.indexOf(key).toLong(), values.indexOf(value).toLong()) })
                    varint(3, feature.type.toLong())
                    packed(4, geometry(feature.parts))
                }.toByteArray())
            }
            keys.forEach { string(3, it) }
            values.forEach { value ->
                bytes(4, Proto().apply {
                    when (value) {
                        is String -> string(1, value)
                        is Boolean -> varint(7, if (value) 1 else 0)
                        is Int -> varint(4, value.toLong())
                        is Long -> varint(4, value)
                        else -> error("unsupported value $value")
                    }
                }.toByteArray())
            }
            varint(5, layer.extent.toLong())
        }.toByteArray()
    }

    fun geometry(parts: List<List<Pair<Int, Int>>>): List<Long> {
        val commands = ArrayList<Long>()
        var x = 0
        var y = 0
        fun point(px: Int, py: Int) {
            commands += zigzag(px - x)
            commands += zigzag(py - y)
            x = px
            y = py
        }
        parts.forEach { part ->
            commands += command(1, 1)
            point(part[0].first, part[0].second)
            if (part.size > 1) {
                commands += command(2, part.size - 1)
                part.drop(1).forEach { (px, py) -> point(px, py) }
            }
        }
        return commands
    }

    fun command(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

    fun zigzag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL

    class Proto {
        private val out = ByteArrayOutputStream()

        fun key(field: Int, wire: Int) = raw((field.toLong() shl 3) or wire.toLong())

        fun raw(value: Long) {
            var rest = value
            while (true) {
                if (rest and 0x7FL.inv() == 0L) {
                    out.write(rest.toInt())
                    return
                }
                out.write(((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
        }

        fun varint(field: Int, value: Long) {
            key(field, 0)
            raw(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            key(field, 2)
            raw(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun packed(field: Int, values: List<Long>) {
            bytes(field, Proto().apply { values.forEach(::raw) }.toByteArray())
        }

        fun write(bytes: ByteArray) = out.write(bytes)

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
