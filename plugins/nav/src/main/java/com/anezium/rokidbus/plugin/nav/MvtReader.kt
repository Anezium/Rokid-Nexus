package com.anezium.rokidbus.plugin.nav

/** A vector tile that breaks its format or one of [MvtLimits]: the whole tile is dropped, never partly trusted. */
internal class MvtFormatException(message: String) : Exception(message)

/** What one tile may cost to read. A tile over any of them is refused rather than truncated. */
internal data class MvtLimits(
    val maxLayers: Int = 64,
    /** Line features kept across the requested layers. */
    val maxFeatures: Int = 12_000,
    /** Vertices kept across the requested layers. */
    val maxVertices: Int = 300_000,
    val maxKeysOrValues: Int = 8_192,
    val maxStringBytes: Int = 4_096,
)

/** One line feature of a layer: its tags, and its parts as `x0, y0, x1, y1, …` in tile units. */
internal class MvtLine(val tags: Map<String, Any>, val parts: List<IntArray>)

/** A decoded layer: [extent] tile units span the tile's side; coordinates may reach into its buffer. */
internal class MvtLayer(val name: String, val extent: Int, val lines: List<MvtLine>)

/**
 * The Mapbox Vector Tile 2.1 encoding (a protobuf `Tile` of `Layer`s), read only as far as
 * Navigation needs it: the line features of the layers asked for, with their tags. Other layers
 * are skipped by length without being decoded, and points and polygons are skipped too.
 *
 * It is deliberately small and strict: every length is checked against what is left, every tag
 * index against its table, every geometry command against the line grammar, and the counts
 * against [MvtLimits], so a malformed or hostile tile costs a bounded amount of work and then
 * fails as a whole with [MvtFormatException].
 */
internal object MvtReader {
    private const val LINESTRING = 2L
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val DEFAULT_EXTENT = 4096L
    private const val MAX_EXTENT = 1L shl 16

    /** Coordinates beyond this many extents from the tile are not a buffer, they are an error. */
    private const val MAX_REACH_EXTENTS = 8

    fun read(bytes: ByteArray, layers: Set<String>, limits: MvtLimits = MvtLimits()): Map<String, MvtLayer> {
        val tile = ProtoReader(bytes, 0, bytes.size)
        val result = LinkedHashMap<String, MvtLayer>()
        val budget = Budget(limits)
        var layerCount = 0
        while (tile.hasMore()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == ProtoReader.LENGTH) {
                if (++layerCount > limits.maxLayers) fail("more than ${limits.maxLayers} layers")
                readLayer(tile.message(), layers, limits, budget)?.let { layer ->
                    // Two layers of one name are not a tile anyone wrote on purpose.
                    if (result.put(layer.name, layer) != null) fail("layer ${layer.name} appears twice")
                }
            } else {
                tile.skip(wire)
            }
        }
        return result
    }

    private class Budget(val limits: MvtLimits) {
        var features = 0
        var vertices = 0
    }

    private fun readLayer(layer: ProtoReader, wanted: Set<String>, limits: MvtLimits, budget: Budget): MvtLayer? {
        var name: String? = null
        var version = 1L
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<ProtoReader>()
        val values = ArrayList<ProtoReader>()
        val features = ArrayList<ProtoReader>()
        // One pass records where everything is; only a wanted layer is decoded afterwards.
        while (layer.hasMore()) {
            val (field, wire) = layer.key()
            when {
                field == 15 && wire == ProtoReader.VARINT -> version = layer.varint()
                field == 1 && wire == ProtoReader.LENGTH -> name = layer.string(limits.maxStringBytes)
                field == 2 && wire == ProtoReader.LENGTH -> features += layer.message()
                field == 3 && wire == ProtoReader.LENGTH -> keys += layer.message()
                field == 4 && wire == ProtoReader.LENGTH -> values += layer.message()
                field == 5 && wire == ProtoReader.VARINT -> extent = layer.varint()
                else -> layer.skip(wire)
            }
            if (keys.size > limits.maxKeysOrValues || values.size > limits.maxKeysOrValues) fail("too many keys or values")
        }
        val layerName = name ?: fail("layer without a name")
        if (layerName !in wanted) return null
        if (version != 1L && version != 2L) fail("unsupported layer version $version")
        if (extent !in 1L..MAX_EXTENT) fail("layer extent $extent is out of range")
        val keyNames = keys.map { it.remainingString(limits.maxStringBytes) }
        val valueTable = values.map { readValue(it, limits.maxStringBytes) }
        val lines = ArrayList<MvtLine>()
        for (feature in features) {
            readLine(feature, keyNames, valueTable, extent.toInt(), budget)?.let(lines::add)
        }
        return MvtLayer(layerName, extent.toInt(), lines)
    }

    private fun readValue(value: ProtoReader, maxStringBytes: Int): Any? {
        var result: Any? = null
        while (value.hasMore()) {
            val (field, wire) = value.key()
            result = when {
                field == 1 && wire == ProtoReader.LENGTH -> value.string(maxStringBytes)
                field == 2 && wire == ProtoReader.FIXED32 -> java.lang.Float.intBitsToFloat(value.fixed32()).toDouble()
                field == 3 && wire == ProtoReader.FIXED64 -> java.lang.Double.longBitsToDouble(value.fixed64())
                field == 4 && wire == ProtoReader.VARINT -> value.varint()
                field == 5 && wire == ProtoReader.VARINT -> value.varint()
                field == 6 && wire == ProtoReader.VARINT -> value.varint().let { (it ushr 1) xor -(it and 1L) }
                field == 7 && wire == ProtoReader.VARINT -> value.varint() != 0L
                else -> {
                    value.skip(wire)
                    result
                }
            }
        }
        return result
    }

    private fun readLine(feature: ProtoReader, keys: List<String>, values: List<Any?>, extent: Int, budget: Budget): MvtLine? {
        var type = 0L
        var tags: ProtoReader? = null
        var geometry: ProtoReader? = null
        while (feature.hasMore()) {
            val (field, wire) = feature.key()
            when {
                field == 2 && wire == ProtoReader.LENGTH -> tags = feature.message()
                field == 3 && wire == ProtoReader.VARINT -> type = feature.varint()
                field == 4 && wire == ProtoReader.LENGTH -> geometry = feature.message()
                else -> feature.skip(wire)
            }
        }
        if (type != LINESTRING) return null
        val limits = budget.limits
        if (++budget.features > limits.maxFeatures) fail("more than ${limits.maxFeatures} line features")
        val properties = HashMap<String, Any>()
        tags?.let { packed ->
            while (packed.hasMore()) {
                val key = packed.varint()
                if (!packed.hasMore()) fail("feature tags are not key/value pairs")
                val value = packed.varint()
                if (key !in keys.indices.toLongRange() || value !in values.indices.toLongRange()) fail("feature tag index out of range")
                values[value.toInt()]?.let { properties[keys[key.toInt()]] = it }
            }
        }
        val parts = geometry?.let { readLineGeometry(it, extent, budget) }.orEmpty()
        if (parts.isEmpty()) return null
        return MvtLine(properties, parts)
    }

    /** MoveTo (one point) then LineTo (one or more) per part; ClosePath has no place in a line. */
    private fun readLineGeometry(geometry: ProtoReader, extent: Int, budget: Budget): List<IntArray> {
        val reach = extent.toLong() * MAX_REACH_EXTENTS
        val parts = ArrayList<IntArray>()
        var x = 0L
        var y = 0L
        var current: IntArrayBuilder? = null
        fun finish() {
            current?.takeIf { it.size >= 4 }?.let { parts += it.toArray() }
            current = null
        }
        while (geometry.hasMore()) {
            val command = geometry.varint()
            val id = (command and 0x7).toInt()
            val count = command ushr 3
            when (id) {
                MOVE_TO -> {
                    if (count != 1L) fail("a line part starts with one MoveTo point")
                    finish()
                    current = IntArrayBuilder()
                }
                LINE_TO -> {
                    if (current == null) fail("LineTo without a MoveTo")
                    if (count < 1L) fail("LineTo without a point")
                }
                else -> fail("command $id is not part of a line")
            }
            // Each parameter takes at least one byte: a count beyond what is left is a lie.
            if (count > geometry.remaining() / 2) fail("command count beyond the end of the geometry")
            repeat(count.toInt()) {
                x += zigzag(geometry.varint())
                y += zigzag(geometry.varint())
                if (x !in -reach..reach || y !in -reach..reach) fail("coordinate outside the tile's reach")
                if (++budget.vertices > budget.limits.maxVertices) fail("more than ${budget.limits.maxVertices} vertices")
                current!!.add(x.toInt(), y.toInt())
            }
        }
        finish()
        return parts
    }

    private fun zigzag(value: Long): Long = (value ushr 1) xor -(value and 1L)

    private fun IntRange.toLongRange(): LongRange = first.toLong()..last.toLong()

    private fun fail(reason: String): Nothing = throw MvtFormatException(reason)

    private class IntArrayBuilder {
        private var data = IntArray(16)
        var size = 0
            private set

        fun add(x: Int, y: Int) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    /** Protobuf wire reading over [bytes] from [position] to [end], every read checked against [end]. */
    private class ProtoReader(private val bytes: ByteArray, private var position: Int, private val end: Int) {
        fun hasMore(): Boolean = position < end

        fun remaining(): Int = end - position

        fun key(): Pair<Int, Int> {
            val key = varint()
            val field = key ushr 3
            if (field < 1 || field > Int.MAX_VALUE) fail("invalid field number")
            return field.toInt() to (key and 0x7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= end) fail("truncated varint")
                if (shift >= 64) fail("varint longer than ten bytes")
                val byte = bytes[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fixed32(): Int {
            take(4)
            return (bytes[position - 4].toInt() and 0xFF) or
                ((bytes[position - 3].toInt() and 0xFF) shl 8) or
                ((bytes[position - 2].toInt() and 0xFF) shl 16) or
                ((bytes[position - 1].toInt() and 0xFF) shl 24)
        }

        fun fixed64(): Long {
            val low = fixed32().toLong() and 0xFFFFFFFFL
            val high = fixed32().toLong() and 0xFFFFFFFFL
            return low or (high shl 32)
        }

        /** The next length-delimited field as its own reader, without copying. */
        fun message(): ProtoReader {
            val length = varint()
            if (length < 0 || length > end - position) fail("length beyond the end of its message")
            val start = position
            position += length.toInt()
            return ProtoReader(bytes, start, position)
        }

        fun string(maxBytes: Int): String = message().remainingString(maxBytes)

        fun remainingString(maxBytes: Int): String {
            val length = end - position
            if (length > maxBytes) fail("string longer than $maxBytes bytes")
            val text = String(bytes, position, length, Charsets.UTF_8)
            position = end
            return text
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> take(8)
                LENGTH -> message()
                FIXED32 -> take(4)
                else -> fail("unsupported wire type $wire")
            }
        }

        private fun take(count: Int) {
            if (count > end - position) fail("truncated fixed-size field")
            position += count
        }

        companion object {
            const val VARINT = 0
            const val FIXED64 = 1
            const val LENGTH = 2
            const val FIXED32 = 5
        }
    }
}
