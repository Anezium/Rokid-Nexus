package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FoodModelsTest {
    @Test
    fun normalizeBarcode_keepsDigitsAndEnforcesSupportedLengths() {
        assertEquals("3012345678901", normalizeBarcode(" 3-012345 678901 "))
        assertEquals("1234", normalizeBarcode("1 2 3 4"))
        assertEquals("12345678901234", normalizeBarcode("12345678901234"))
        assertNull(normalizeBarcode("123"))
        assertNull(normalizeBarcode("123456789012345"))
        assertNull(normalizeBarcode("not a barcode"))
    }

    @Test
    fun scaledValue_returnsUnknownWithoutPer100gValue() {
        assertEquals(84.0, scaledValue(210.0, 40.0)!!, 0.0)
        assertEquals(0.0, scaledValue(0.0, 250.0)!!, 0.0)
        assertNull(scaledValue(null, 40.0))
    }

    @Test
    fun aggregateNutrition_scalesSnapshotValuesAndMarksIncompleteTotals() {
        val complete = product(
            nutrients = NutrientsPer100g(200.0, 10.0, 30.0, 5.0, null, null, null),
        )
        val missingProtein = product(
            barcode = "12345679",
            nutrients = NutrientsPer100g(100.0, null, 20.0, 0.0, null, null, null),
        )

        val totals = aggregateNutrition(
            listOf(
                FoodEntry(1L, 10L, 150.0, complete),
                FoodEntry(2L, 20L, 50.0, missingProtein),
            ),
        )

        assertEquals(2, totals.entryCount)
        assertEquals(350.0, totals.caloriesKcal.knownValue, 0.0)
        assertTrue(totals.caloriesKcal.complete)
        assertEquals(15.0, totals.proteinGrams.knownValue, 0.0)
        assertFalse(totals.proteinGrams.complete)
        assertEquals(55.0, totals.carbohydrateGrams.knownValue, 0.0)
        assertEquals(7.5, totals.fatGrams.knownValue, 0.0)
        assertEquals("≥ 15 g", totals.proteinGrams.display("g"))
    }

    @Test
    fun aggregateNutrition_displaysAllUnknownNutrientsAsUnknownInsteadOfZero() {
        val product = product(
            nutrients = NutrientsPer100g(null, null, null, null, null, null, null),
        )

        val totals = aggregateNutrition(listOf(FoodEntry(1L, 10L, 80.0, product)))

        assertEquals(0.0, totals.caloriesKcal.knownValue, 0.0)
        assertFalse(totals.caloriesKcal.complete)
        assertEquals("unknown", totals.caloriesKcal.display("kcal"))
    }

    @Test
    fun dayBounds_usesTheLocalDayAcrossSpringDstChange() {
        val paris = ZoneId.of("Europe/Paris")
        val atMillis = Instant.parse("2026-03-29T12:00:00Z").toEpochMilli()

        val bounds = dayBounds(atMillis, paris)

        assertEquals(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), bounds.first)
        assertEquals(23L * 60L * 60L * 1000L, bounds.last - bounds.first + 1)
        assertTrue(Instant.parse("2026-03-29T21:59:59.999Z").toEpochMilli() in bounds)
        assertFalse(Instant.parse("2026-03-29T22:00:00Z").toEpochMilli() in bounds)
    }

    private fun product(
        barcode: String = "12345678",
        nutrients: NutrientsPer100g,
    ) = FoodProduct(
        barcode = barcode,
        name = "Snapshot product",
        brand = "Brand",
        servingLabel = "50 g",
        servingGrams = 50.0,
        nutritionGrade = null,
        novaGroup = null,
        nutrients = nutrients,
        fetchedAtMillis = 1L,
    )
}
