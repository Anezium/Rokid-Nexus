package com.anezium.rokidbus.plugin.foodlog

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.UUID

internal class FoodRecipeEditor(
    private val context: Context,
    private val original: FoodRecipe? = null,
    private val search: (String, (List<FoodProduct>) -> Unit) -> Unit,
    private val onSave: (FoodRecipe) -> Unit,
    private val onCancel: () -> Unit,
) {
    private var recipeUuid = original?.uuid ?: UUID.randomUUID().toString()
    private data class IngredientField(val product: FoodProduct, val grams: EditText)
    private val fields = mutableListOf<IngredientField>()
    private val name = NexusUi.field(context, "Recipe name").apply { setText(original?.name.orEmpty()) }
    private val servings = NexusUi.field(context, "Number of servings").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(original?.servings?.let(::editableNutritionNumber) ?: "2")
    }
    private val query = NexusUi.field(context, "Search saved foods for an ingredient")
    private val ingredients = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = NexusUi.cardBody(context, "Add ingredients to calculate nutrition per serving.")
    private val error = NexusUi.statusLine(context).apply { visibility = View.GONE }
    private val save = NexusUi.pillButton(context, "Save recipe")
    private var generation = 0
    private var saving = false
    val isSaving: Boolean get() = saving

    val view = NexusUi.contentColumn(context).apply {
        addView(NexusUi.sectionRow(context, if (original == null) "Create recipe" else "Edit recipe"), NexusUi.block())
        addView(BusTheme.gap(context, 12))
        addView(name, NexusUi.block())
        addView(BusTheme.gap(context, 8))
        addView(servings, NexusUi.block())
        addView(NexusUi.rowSub(context, "Use the ingredient weights before cooking. A serving divides those weights and nutrition equally."))
        addView(BusTheme.gap(context, 16))
        addView(NexusUi.rowTitle(context, "Ingredients"))
        addView(ingredients, NexusUi.block())
        addView(BusTheme.gap(context, 12))
        addView(query, NexusUi.block())
        addView(results, NexusUi.block())
        addView(BusTheme.gap(context, 16))
        addView(preview, NexusUi.block())
        addView(error, NexusUi.block())
        addView(BusTheme.gap(context, 12))
        addView(save, NexusUi.block())
        addView(NexusUi.textButton(context, "Cancel").apply { setOnClickListener { if (!saving) onCancel() } }, NexusUi.block())
    }

    init {
        original?.ingredients?.forEach { addIngredient(it.product, it.grams) }
        servings.watch { renderPreview() }
        query.watch { searchIngredients() }
        save.setOnClickListener {
            if (!saving) runCatching { recipe() }.fold(
                onSuccess = { saving = true; save.isEnabled = false; save.text = "Saving…"; onSave(it) },
                onFailure = { showError(it.message ?: "Check the recipe ingredients.") },
            )
        }
        searchIngredients()
        renderPreview()
    }

    fun showError(message: String) {
        saving = false; save.isEnabled = true; save.text = "Save recipe"
        error.text = message; error.visibility = View.VISIBLE
    }

    fun saveState() = Bundle().apply {
        putString("original", original?.let(FoodLogBackup::recipeSnapshot))
        putString("recipeUuid", recipeUuid)
        putString("name", name.text.toString()); putString("servings", servings.text.toString())
        putString("query", query.text.toString())
        putStringArrayList("foods", ArrayList(fields.map { FoodLogBackup.productSnapshot(it.product) }))
        putStringArrayList("weights", ArrayList(fields.map { it.grams.text.toString() }))
    }

    fun restoreState(state: Bundle) {
        recipeUuid = requireNotNull(state.getString("recipeUuid")).also { require(FOOD_UUID_PATTERN.matches(it)) }
        name.setText(state.getString("name")); servings.setText(state.getString("servings"))
        fields.clear(); ingredients.removeAllViews()
        val weights = state.getStringArrayList("weights").orEmpty()
        state.getStringArrayList("foods").orEmpty().forEachIndexed { index, json ->
            addIngredient(FoodLogBackup.readProductSnapshot(json))
            fields.last().grams.setText(weights.getOrNull(index).orEmpty())
        }
        query.setText(state.getString("query")); renderPreview(); searchIngredients()
    }

    private fun recipe(): FoodRecipe {
        val title = name.text.toString().trim()
        require(title.isNotBlank() && title.length <= MAX_RECIPE_NAME_CHARS) { "Enter a recipe name of 1–120 characters." }
        val count = servings.number()
        require(count != null && count in 0.25..100.0) { "Servings must be between 0.25 and 100." }
        require(fields.size in 1..MAX_RECIPE_INGREDIENTS) { "Choose at least one ingredient, up to 64." }
        val chosen = fields.map { field ->
            val grams = field.grams.number()
            require(grams != null && grams in MIN_QUANTITY_GRAMS..MAX_RECIPE_INGREDIENT_GRAMS) {
                "${field.product.name}: enter a weight of 1–20,000 g."
            }
            RecipeIngredient(field.product, grams)
        }
        require(chosen.sumOf(RecipeIngredient::grams) / count <= MAX_RECIPE_INGREDIENT_GRAMS) { "One serving must weigh no more than 20,000 g. Increase the number of servings." }
        return FoodRecipe(recipeUuid, title, count, chosen, original?.createdAtMillis ?: System.currentTimeMillis())
    }

    private fun addIngredient(product: FoodProduct, grams: Double = 100.0) {
        if (fields.size >= MAX_RECIPE_INGREDIENTS || fields.any { it.product.barcode == product.barcode }) return
        val field = NexusUi.field(context, "${product.name}: grams").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(editableNutritionNumber(grams))
        }
        val item = IngredientField(product, field)
        fields += item
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 12))
            addView(NexusUi.rowTitle(context, product.name))
            addView(field, NexusUi.block())
            addView(NexusUi.textButton(context, "Remove ingredient").apply {
                setOnClickListener { fields.remove(item); ingredients.removeView(this.parent as View); renderPreview(); searchIngredients() }
            }, NexusUi.block())
        }
        ingredients.addView(row, NexusUi.block())
        field.watch { renderPreview() }
        renderPreview()
    }

    private fun searchIngredients() {
        val request = ++generation
        search(query.text.toString()) { products ->
            if (request != generation) return@search
            results.removeAllViews()
            val candidates = products.filter { product ->
                !product.barcode.startsWith("recipe-") && fields.none { it.product.barcode == product.barcode }
            }
            candidates.take(8).forEach { product ->
                results.addView(NexusUi.outlinePillButton(context, product.name).apply {
                    setOnClickListener { addIngredient(product); searchIngredients() }
                }, NexusUi.block())
            }
            if (candidates.isEmpty()) results.addView(NexusUi.rowSub(context, "No matching ingredients. Save a barcode product or custom food in Foods first."))
            else if (candidates.size > 8) results.addView(NexusUi.rowSub(context, "Type more of the food name to narrow these results."))
        }
    }

    private fun renderPreview() {
        val count = servings.number()?.takeIf { it in 0.25..100.0 }
        val chosen = fields.mapNotNull { field -> field.grams.number()?.takeIf { it in MIN_QUANTITY_GRAMS..MAX_RECIPE_INGREDIENT_GRAMS }?.let { RecipeIngredient(field.product, it) } }
        preview.text = if (count == null || chosen.isEmpty() || chosen.size != fields.size) "Enter valid ingredient weights and servings to see nutrition." else {
            val nutrients = nutrientsForIngredients(chosen, count)
            "Per serving · ${formatNutritionNumber(chosen.sumOf(RecipeIngredient::grams) / count)} g ingredients\n" +
                "${nutrients.caloriesKcal.displayPer100g("kcal")} · Protein ${nutrients.proteinGrams.displayPer100g("g")} · Carbs ${nutrients.carbohydrateGrams.displayPer100g("g")} · Fat ${nutrients.fatGrams.displayPer100g("g")}"
        }
    }
}

internal fun EditText.watch(onChange: () -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange()
    override fun afterTextChanged(s: Editable?) = Unit
})

private fun EditText.number() = text.toString().trim().replace(',', '.').toDoubleOrNull()
