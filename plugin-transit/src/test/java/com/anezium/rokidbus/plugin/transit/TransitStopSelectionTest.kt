package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitStopSelectionTest {
    @Test
    fun selectNearbyBoardStops_noRailUsesNearestThree() {
        val selected = selectNearbyBoardStops(
            listOf(
                stop("a", 10, "BUS"),
                stop("b", 20, "BUS"),
                stop("c", 30, "BUS"),
                stop("d", 40, "BUS"),
            ),
        )

        assertEquals(listOf("a", "b", "c"), selected.map { it.id })
    }

    @Test
    fun selectNearbyBoardStops_appendsNearestLaterRailStop() {
        val selected = selectNearbyBoardStops(
            listOf(
                stop("a", 10, "BUS"),
                stop("b", 20, "BUS"),
                stop("c", 30, "BUS"),
                stop("d", 40, "BUS"),
                stop("e", 50, "TRAIN"),
                stop("f", 60, "TRAM"),
            ),
        )

        assertEquals(listOf("a", "b", "c", "e"), selected.map { it.id })
    }

    @Test
    fun selectNearbyBoardStops_keepsNearestThreeWhenRailAlreadyVisible() {
        val selected = selectNearbyBoardStops(
            listOf(
                stop("a", 10, "BUS"),
                stop("b", 20, "SUBWAY"),
                stop("c", 30, "BUS"),
                stop("d", 40, "TRAIN"),
            ),
        )

        assertEquals(listOf("a", "b", "c"), selected.map { it.id })
    }

    @Test
    fun selectNearbyBoardStops_returnsAllWhenFewerThanThreeStopsExist() {
        val selected = selectNearbyBoardStops(
            listOf(
                stop("a", 10, "BUS"),
                stop("b", 20, "FERRY"),
            ),
        )

        assertEquals(listOf("a", "b"), selected.map { it.id })
    }

    private fun stop(id: String, distanceMeters: Int, vararg modes: String): TransitStop =
        TransitStop(
            id = id,
            name = id,
            lat = 0.0,
            lon = 0.0,
            distanceMeters = distanceMeters,
            modes = modes.toList(),
        )
}
