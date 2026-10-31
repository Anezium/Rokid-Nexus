package com.anezium.rokidbus.plugin.nav

/** A tile's drawable input, prepared on the decoding worker rather than trimmed on every fix. */
internal class LocalTileDetail(val streets: List<LocalLine>, val names: List<LocalLine>) {
    val vertices: Long = streets.sumOf { it.xs.size.toLong() } + names.sumOf { it.xs.size.toLong() }
    val simplifyWork: Long = streets.sumOf { work(it) }

    /** Equal shares for every possible cover size keep the aggregate preparation bound unchanged. */
    fun boundedDetails(): List<LocalTileDetail> {
        val counts = 1..NavBasemapLayer.OVERVIEW_MAX_TILES
        if (fits(counts.last)) return counts.map { this }
        val roads = spread(streets)
        val labels = spread(names)
        val nameVertices = names.sumOf { it.xs.size.toLong() }
        return counts.map { count ->
            if (fits(count)) return@map this
            val vertexLimit = NavBasemapLayer.MAX_PREPARE_VERTICES / count
            val workLimit = NavBasemapLayer.MAX_SIMPLIFY_WORK / count
            val roadLimit = vertexLimit - minOf(nameVertices, vertexLimit / 4)
            var vertices = 0L
            var work = 0L
            val keptRoads = roads.filter { road ->
                if (vertices + road.xs.size > roadLimit || work + work(road) > workLimit) {
                    false
                } else {
                    vertices += road.xs.size
                    work += work(road)
                    true
                }
            }
            val keptNames = labels.filter { name ->
                if (vertices + name.xs.size > vertexLimit) {
                    false
                } else {
                    vertices += name.xs.size
                    true
                }
            }
            LocalTileDetail(keptRoads, keptNames)
        }
    }

    private fun fits(count: Int): Boolean =
        vertices <= NavBasemapLayer.MAX_PREPARE_VERTICES / count &&
            simplifyWork <= NavBasemapLayer.MAX_SIMPLIFY_WORK / count

    private companion object {
        fun work(line: LocalLine): Long = 4L * line.xs.size * line.xs.size

        /** Round-robin spatial cells, major roads first within each, so no tile corner monopolizes detail. */
        fun spread(lines: List<LocalLine>): List<LocalLine> {
            if (lines.isEmpty()) return lines
            val left = lines.minOf { it.minX }
            val top = lines.minOf { it.minY }
            val width = (lines.maxOf { it.maxX } - left).coerceAtLeast(1.0)
            val height = (lines.maxOf { it.maxY } - top).coerceAtLeast(1.0)
            val order = compareBy<LocalLine>({ it.streetClass }, { it.minX }, { it.minY }, { it.maxX }, { it.maxY }, { it.name.orEmpty() })
            val cells = lines.groupBy { line ->
                val column = (((line.minX + line.maxX) / 2 - left) / width * 8).toInt().coerceIn(0, 7)
                val row = (((line.minY + line.maxY) / 2 - top) / height * 8).toInt().coerceIn(0, 7)
                row * 8 + column
            }.toSortedMap()
            val pending = ArrayDeque(cells.values.map { it.sortedWith(order).iterator() })
            return buildList(lines.size) {
                while (pending.isNotEmpty()) {
                    val cell = pending.removeFirst()
                    add(cell.next())
                    if (cell.hasNext()) pending.addLast(cell)
                }
            }
        }
    }
}
