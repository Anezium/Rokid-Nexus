package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodFactsClientTest {
    @Test
    fun parseProduct_keepsTheProductNutritionSnapshot() {
        val product = FoodFactsClient.parseProduct(
            json = """
                {
                  "product": {
                    "code": "3 012345 678901",
                    "product_name": "  Oat bar  ",
                    "brands": " Example Foods ",
                    "serving_size": "1 bar (40 g)",
                    "serving_quantity": 40,
                    "serving_quantity_unit": "g",
                    "nutrition_grades": "b",
                    "nova_group": 3,
                    "nutriments": {
                      "energy-kcal_100g": 425,
                      "proteins_100g": 9.5,
                      "carbohydrates_100g": 61,
                      "fat_100g": 16,
                      "sugars_100g": 21,
                      "fiber_100g": 7.2,
                      "salt_100g": 0.42
                    }
                  }
                }
            """.trimIndent(),
            fallbackBarcode = "0000000000000",
            fetchedAtMillis = 1_700_000_000_000L,
        )

        requireNotNull(product)
        assertEquals("3012345678901", product.barcode)
        assertEquals("Oat bar", product.name)
        assertEquals("Example Foods", product.brand)
        assertEquals("1 bar (40 g)", product.servingLabel)
        assertEquals(40.0, product.servingGrams!!, 0.0)
        assertEquals("b", product.nutritionGrade)
        assertEquals(3, product.novaGroup)
        assertEquals(425.0, product.nutrients.caloriesKcal!!, 0.0)
        assertEquals(1_700_000_000_000L, product.fetchedAtMillis)
    }

    @Test
    fun parseProduct_preservesMissingNutrientsAsUnknown() {
        val product = FoodFactsClient.parseProduct(
            json = """{"product":{"code":"12345678","nutriments":{"fat_100g":0}}}""",
            fallbackBarcode = "87654321",
            fetchedAtMillis = 42L,
        )

        requireNotNull(product)
        assertEquals("Product 12345678", product.name)
        assertNull(product.nutrients.caloriesKcal)
        assertNull(product.nutrients.proteinGrams)
        assertNull(product.nutrients.carbohydrateGrams)
        assertEquals(0.0, product.nutrients.fatGrams!!, 0.0)
        assertNull(product.servingGrams)
        assertNull(product.nutritionGrade)
        assertNull(product.novaGroup)
    }

    @Test
    fun parseProduct_usesFallbackBarcodeAndRejectsAbsentProduct() {
        val fallback = FoodFactsClient.parseProduct(
            json = """{"product":{"generic_name":"Plain yogurt"}}""",
            fallbackBarcode = " 978-0201379624 ",
            fetchedAtMillis = 12L,
        )

        requireNotNull(fallback)
        assertEquals("9780201379624", fallback.barcode)
        assertEquals("Plain yogurt", fallback.name)
        assertNull(
            FoodFactsClient.parseProduct(
                json = """{"status":0,"status_verbose":"product not found"}""",
                fallbackBarcode = "12345678",
                fetchedAtMillis = 12L,
            ),
        )
    }

    @Test
    fun parseProduct_rejectsInvalidAndNonGramServingValues() {
        val product = FoodFactsClient.parseProduct(
            json = """
                {"product":{"code":"12345678","serving_quantity":250,
                "serving_quantity_unit":"ml","nova_group":7,
                "nutriments":{"energy-kcal_100g":-3,"proteins_100g":"not a number"}}}
            """.trimIndent(),
            fallbackBarcode = "00000000",
            fetchedAtMillis = 12L,
        )

        requireNotNull(product)
        assertNull(product.servingGrams)
        assertNull(product.novaGroup)
        assertNull(product.nutrients.caloriesKcal)
        assertNull(product.nutrients.proteinGrams)
    }
}
