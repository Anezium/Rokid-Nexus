package com.anezium.rokidbus.plugin.foodlog

import java.text.Normalizer
import java.util.Locale

internal sealed interface FoodVoiceMatch {
    data class Matched(
        val product: FoodProduct,
        val quantityGrams: Double,
    ) : FoodVoiceMatch

    data class Ambiguous(val products: List<FoodProduct>) : FoodVoiceMatch
    data object NoProduct : FoodVoiceMatch
    data object InvalidQuantity : FoodVoiceMatch
}

/**
 * Resolves intentionally narrow phrases such as "200 grams of rice" or
 * "200 grammes de riz" against products already stored on the phone. It does
 * not invent nutrition data or send the transcript to another service.
 */
internal object FoodVoiceParser {
    private val quantityPrefix = Regex(
        pattern = """^\s*(\d{1,4}(?:[.,]\d)?)\s*(?:g|gr|gram|grams|gramme|grammes)\s+(?:(?:of|de|d')\s*)?(.+?)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val amountLessPrefix = Regex(
        pattern = """^\s*(?:add|log|eat|mange|manger|ajoute|ajouter)\s+(?:(?:some|du|de la|des|un|une)\s+)?(.+?)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun match(transcript: String, candidates: List<FoodProduct>): FoodVoiceMatch {
        if (candidates.isEmpty()) return FoodVoiceMatch.NoProduct
        val parsed = parsePhrase(transcript)
        if (parsed.quantityGrams != null && parsed.quantityGrams !in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS) {
            return FoodVoiceMatch.InvalidQuantity
        }
        val query = normalizeSearch(parsed.productQuery)
        if (query.isBlank()) return FoodVoiceMatch.NoProduct

        val ranked = candidates
            .distinctBy(FoodProduct::barcode)
            .map { product -> product to score(query, product) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<FoodProduct, Int>> { it.second }.thenBy { it.first.name })
        val bestScore = ranked.firstOrNull()?.second ?: return FoodVoiceMatch.NoProduct
        val best = ranked.filter { it.second == bestScore }.map(Pair<FoodProduct, Int>::first)
        if (best.size > 1) return FoodVoiceMatch.Ambiguous(best.take(MAX_AMBIGUOUS_RESULTS))

        val product = best.single()
        val quantity = parsed.quantityGrams
            ?: product.servingGrams?.takeIf { it in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS }
            ?: DEFAULT_VOICE_QUANTITY_GRAMS
        return FoodVoiceMatch.Matched(product, quantity)
    }

    private fun parsePhrase(transcript: String): ParsedPhrase {
        quantityPrefix.matchEntire(transcript)?.let { match ->
            return ParsedPhrase(
                quantityGrams = match.groupValues[1].replace(',', '.').toDoubleOrNull(),
                productQuery = match.groupValues[2],
            )
        }
        val query = amountLessPrefix.matchEntire(transcript)?.groupValues?.get(1) ?: transcript
        return ParsedPhrase(quantityGrams = null, productQuery = query)
    }

    private fun score(query: String, product: FoodProduct): Int {
        val name = normalizeSearch(product.name)
        val brand = normalizeSearch(product.brand)
        return when {
            name == query -> 100
            "$brand $name".trim() == query -> 95
            name.startsWith(query) -> 80
            name.contains(query) -> 70
            query.contains(name) -> 60
            brand.isNotBlank() && "$brand $name".contains(query) -> 50
            else -> 0
        }
    }

    private fun normalizeSearch(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private data class ParsedPhrase(
        val quantityGrams: Double?,
        val productQuery: String,
    )

    private const val DEFAULT_VOICE_QUANTITY_GRAMS = 100.0
    private const val MAX_AMBIGUOUS_RESULTS = 4
}
