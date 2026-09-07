package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class FoodJournalTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val food = FoodProduct("12345678", "Oats", "", null, 40.0, null, null,
        NutrientsPer100g(200.0, 10.0, null, 5.0, null, null, null), 1)

    @Test fun mealsKeepStableOrderAndUnknownNutrition() {
        val entries = listOf(
            FoodEntry(1, 20, 50.0, food, mealType = MealType.LUNCH),
            FoodEntry(2, 10, 100.0, food, mealType = MealType.BREAKFAST),
            FoodEntry(3, 30, 25.0, food, mealType = MealType.LUNCH),
            FoodEntry(4, 40, 10.0, food, mealType = MealType.UNKNOWN),
        )
        val meals = journalMeals(entries)
        assertEquals(MealType.entries, meals.map(MealNutrition::meal))
        assertEquals(listOf(1L, 3L), meals[1].entries.map(FoodEntry::id))
        assertEquals(150.0, meals[1].totals.caloriesKcal.knownValue, 0.0)
        assertFalse(meals[1].totals.carbohydrateGrams.complete)
        assertEquals(370.0, aggregateNutrition(entries).caloriesKcal.knownValue, 0.0)
        assertEquals(4, journalMeals(emptyList()).size)
    }

    @Test fun gramAndKnownServingPortionsScaleExactly() {
        assertEquals(75.25, portionGrams(75.25), 0.0)
        assertEquals(60.0, portionGrams(1.5, 40.0), 0.0)
        assertEquals("75.25", editableNutritionNumber(75.25))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 5_001.0).forEach {
            assertTrue(runCatching { portionGrams(it) }.isFailure)
        }
        assertTrue(runCatching { portionGrams(2.0, 3_000.0) }.isFailure)
    }

    @Test fun autumnDayIncludesBothRepeatedHoursButNotNextMidnight() {
        val bounds = dayBounds(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli(), paris)
        assertEquals(25 * 60 * 60 * 1_000L, bounds.last - bounds.first + 1)
        assertTrue(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli() in bounds)
        assertTrue(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli() in bounds)
        assertFalse(Instant.parse("2026-10-25T23:00:00Z").toEpochMilli() in bounds)
    }

    @Test fun editingRepeatedTimeKeepsOriginalOffsetAndNewEntriesChooseEarlierOffset() {
        val date = LocalDate.of(2026, 10, 25)
        val time = LocalTime.of(2, 30)
        val later = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        assertEquals(later, consumptionTime(date, time, paris, later))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), consumptionTime(date, time, paris))
    }

    @Test(expected = IllegalArgumentException::class)
    fun springGapDoesNotSilentlyMoveTheEnteredTime() {
        consumptionTime(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), paris)
    }

    @Test fun customIdentityCannotTurnIntoABarcode() {
        val id = "custom-aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
        assertNull(normalizeBarcode(id))
        assertEquals(id, productId(id))
        assertNull(normalizeBarcode("food12345678"))
        assertNull(normalizeBarcode("１２３４"))
    }
}
