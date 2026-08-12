package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FoodVoiceParserTest {
    private val rice = product("Riz", servingGrams = 180.0)
    private val yogurt = product("Greek yogurt", brand = "Example", barcode = "12345679")

    @Test
    fun `matches French phrase with an explicit amount`() {
        val result = FoodVoiceParser.match("200 grammes de riz", listOf(rice))

        require(result is FoodVoiceMatch.Matched)
        assertSame(rice, result.product)
        assertEquals(200.0, result.quantityGrams, 0.0)
    }

    @Test
    fun `matches English phrase without accents or case sensitivity`() {
        val result = FoodVoiceParser.match("ADD some GREEK YOGURT", listOf(rice, yogurt))

        require(result is FoodVoiceMatch.Matched)
        assertSame(yogurt, result.product)
        assertEquals(100.0, result.quantityGrams, 0.0)
    }

    @Test
    fun `uses the stored serving when amount is omitted`() {
        val result = FoodVoiceParser.match("mange du riz", listOf(rice))

        require(result is FoodVoiceMatch.Matched)
        assertEquals(180.0, result.quantityGrams, 0.0)
    }

    @Test
    fun `rejects amounts outside journal bounds`() {
        assertSame(
            FoodVoiceMatch.InvalidQuantity,
            FoodVoiceParser.match("9000 g riz", listOf(rice)),
        )
    }

    private fun product(
        name: String,
        brand: String = "",
        barcode: String = "12345678",
        servingGrams: Double? = null,
    ) = FoodProduct(
        barcode = barcode,
        name = name,
        brand = brand,
        servingLabel = null,
        servingGrams = servingGrams,
        nutritionGrade = null,
        novaGroup = null,
        nutrients = NutrientsPer100g(null, null, null, null, null, null, null),
        fetchedAtMillis = 0L,
    )
}
