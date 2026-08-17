package com.anezium.rokidbus.plugin.foodlog

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal class FoodFactsClient(
    private val baseUrl: String = "https://world.openfoodfacts.org/api/v3",
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val http: (String) -> String? = ::getProductJson,
) {
    fun product(barcode: String): FoodProduct? {
        val normalized = normalizeBarcode(barcode) ?: return null
        val fields = PRODUCT_FIELDS.joinToString(",")
        val json = http("$baseUrl/product/$normalized?fields=$fields") ?: return null
        return parseProduct(json, normalized, nowMillis())
    }

    companion object {
        const val USER_AGENT =
            "RokidNexusFoodLog/0.1 (https://github.com/Anezium/Rokid-Nexus)"

        private val PRODUCT_FIELDS = listOf(
            "code",
            "product_name",
            "generic_name",
            "brands",
            "serving_size",
            "serving_quantity",
            "serving_quantity_unit",
            "nutrition_grades",
            "nova_group",
            "nutriments",
        )

        fun parseProduct(json: String, fallbackBarcode: String, fetchedAtMillis: Long): FoodProduct? {
            val root = JSONObject(json)
            val product = root.optJSONObject("product") ?: return null
            val barcode = normalizeBarcode(product.optString("code").ifBlank { fallbackBarcode })
                ?: return null
            val name = product.optString("product_name")
                .ifBlank { product.optString("generic_name") }
                .ifBlank { "Product $barcode" }
                .trim()
            val servingUnit = product.optString("serving_quantity_unit").lowercase(Locale.US)
            val servingGrams = product.optionalDouble("serving_quantity")
                ?.takeIf { servingUnit.isBlank() || servingUnit == "g" }
            val nutriments = product.optJSONObject("nutriments") ?: JSONObject()
            return FoodProduct(
                barcode = barcode,
                name = name,
                brand = product.optString("brands").trim(),
                servingLabel = product.optString("serving_size").trim().takeIf(String::isNotBlank),
                servingGrams = servingGrams,
                nutritionGrade = product.optString("nutrition_grades")
                    .trim()
                    .takeIf(String::isNotBlank),
                novaGroup = product.optionalInt("nova_group")?.takeIf { it in 1..4 },
                nutrients = NutrientsPer100g(
                    caloriesKcal = nutriments.optionalDouble("energy-kcal_100g"),
                    proteinGrams = nutriments.optionalDouble("proteins_100g"),
                    carbohydrateGrams = nutriments.optionalDouble("carbohydrates_100g"),
                    fatGrams = nutriments.optionalDouble("fat_100g"),
                    sugarsGrams = nutriments.optionalDouble("sugars_100g"),
                    fiberGrams = nutriments.optionalDouble("fiber_100g"),
                    saltGrams = nutriments.optionalDouble("salt_100g"),
                    saturatedFatGrams = nutriments.optionalDouble("saturated-fat_100g"),
                    sodiumMilligrams = nutriments.optionalGramsAsMilligrams("sodium_100g"),
                    cholesterolMilligrams = nutriments.optionalGramsAsMilligrams("cholesterol_100g"),
                    potassiumMilligrams = nutriments.optionalGramsAsMilligrams("potassium_100g"),
                    calciumMilligrams = nutriments.optionalGramsAsMilligrams("calcium_100g"),
                    ironMilligrams = nutriments.optionalGramsAsMilligrams("iron_100g"),
                    caffeineMilligrams = nutriments.optionalGramsAsMilligrams("caffeine_100g"),
                ),
                fetchedAtMillis = fetchedAtMillis,
            )
        }
    }
}

private fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return value.takeIf { it.isFinite() && it >= 0.0 }
}

private fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { it != 0 }
}

private fun JSONObject.optionalGramsAsMilligrams(name: String): Double? =
    optionalDouble(name)?.times(1_000.0)

private fun getProductJson(urlText: String): String? {
    val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", FoodFactsClient.USER_AGENT)
    }
    return try {
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw IOException("Open Food Facts HTTP $status")
        body
    } finally {
        connection.disconnect()
    }
}
