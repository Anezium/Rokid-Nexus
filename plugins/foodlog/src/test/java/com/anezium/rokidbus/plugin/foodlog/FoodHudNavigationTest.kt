package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.*
import org.junit.Test

class FoodHudNavigationTest {
    @Test fun everyFavoriteRemainsReachablePastTheCardRowLimit() {
        val foods = (0 until 137).map { "Food $it" }
        foods.indices.forEach { selected ->
            val rows = foodHudWindow(foods, selected)
            assertTrue(rows.size <= 20)
            assertEquals(foods[selected], rows.single { it.index == selected }.value)
        }
        assertEquals(136, foodHudWindow(foods, 136).last().index)
        assertEquals(0, foodHudWindow(foods, 0).first().index)
    }

    @Test fun emptyListAndSelectionPastARefreshedListAreBounded() {
        assertTrue(foodHudWindow(emptyList<String>(), 10).isEmpty())
        assertEquals(listOf(IndexedValue(0, "Only food")), foodHudWindow(listOf("Only food"), 500))
    }
}
