package com.anezium.rokidbus.plugin.foodlog

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

internal data class FoodEntryPortion(val grams: Double, val consumedAtMillis: Long, val meal: MealType)

/** One explicit portion form is shared by barcode, catalog, favorites, recipes and journal edits. */
internal class FoodEntryEditor(
    private val context: Context,
    val product: FoodProduct,
    date: LocalDate,
    meal: MealType,
    val original: FoodEntry? = null,
    private val onSave: (FoodEntryPortion) -> Unit,
    private val onCancel: () -> Unit,
) {
    var entryUuid: String = original?.uuid ?: UUID.randomUUID().toString()
        private set
    private val zone = ZoneId.systemDefault()
    private val originalTime = original?.let { Instant.ofEpochMilli(it.consumedAtMillis).atZone(zone) }
    private var chosenDate = originalTime?.toLocalDate() ?: date
    private var chosenTime = originalTime?.toLocalTime() ?: LocalTime.now(zone).withSecond(0).withNano(0)
    private var chosenMeal = original?.mealType ?: meal
    private val serving = product.servingGrams?.takeIf { it.isFinite() && it > 0.0 }
    private var useServing = original == null && serving != null && serving in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS
    private val amount = NexusUi.field(context, "Amount").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(original?.quantityGrams?.let(::editableNutritionNumber) ?: if (useServing) "1" else "100")
    }
    private val unit = NexusUi.outlinePillButton(context, "")
    private val dateButton = NexusUi.outlinePillButton(context, "")
    private val timeButton = NexusUi.outlinePillButton(context, "")
    private val mealButton = NexusUi.outlinePillButton(context, "")
    private val preview = NexusUi.cardBody(context, "")
    private val error = NexusUi.statusLine(context).apply { visibility = View.GONE }
    private val saveButton = NexusUi.pillButton(context, if (original == null) "Add to journal" else "Save changes")
    private var saving = false

    val view: LinearLayout = NexusUi.contentColumn(context).apply {
        addView(NexusUi.sectionRow(context, if (original == null) "Log food" else "Edit entry"), NexusUi.block())
        addView(BusTheme.gap(context, 12))
        addView(NexusUi.rowTitle(context, product.name))
        if (product.brand.isNotBlank()) addView(NexusUi.rowSub(context, product.brand))
        addView(BusTheme.gap(context, 16))
        addView(NexusUi.cardBody(context, if (original == null) "Choose what you ate before saving." else "Nutrition uses the snapshot saved with this entry."))
        addView(BusTheme.gap(context, 12))
        addView(NexusUi.rowTitle(context, "Portion"))
        addView(amount, NexusUi.block())
        addView(unit, NexusUi.block())
        addView(BusTheme.gap(context, 8))
        addView(NexusUi.rowSub(context, serving?.let { "One serving weighs ${formatNutritionNumber(it)} g." }
            ?: "Serving weight is unknown. Enter the measured weight in grams."))
        addView(BusTheme.gap(context, 12))
        addView(preview, NexusUi.block())
        addView(BusTheme.gap(context, 18))
        addView(NexusUi.rowTitle(context, "Meal, date and time"))
        listOf(mealButton, dateButton, timeButton).forEach { addView(it, NexusUi.block()); addView(BusTheme.gap(context, 8)) }
        addView(error, NexusUi.block())
        addView(saveButton, NexusUi.block())
        addView(BusTheme.gap(context, 8))
        addView(NexusUi.textButton(context, "Cancel").apply { setOnClickListener { if (!saving) onCancel() } }, NexusUi.block())
    }

    init {
        unit.isEnabled = serving != null
        unit.setOnClickListener {
            val current = parsedAmount()
            val grams = if (useServing) current?.times(serving ?: 1.0) else current
            useServing = !useServing
            amount.setText(grams?.let { if (useServing) it / requireNotNull(serving) else it }?.let(::editableNutritionNumber).orEmpty())
            render()
        }
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        mealButton.setOnClickListener {
            val meals = MealType.entries
            AlertDialog.Builder(context).setTitle("Choose meal")
                .setSingleChoiceItems(meals.map(MealType::displayName).toTypedArray(), meals.indexOf(chosenMeal)) { dialog, index ->
                    chosenMeal = meals[index]; dialog.dismiss(); render()
                }.setNegativeButton("Cancel", null).show()
        }
        dateButton.setOnClickListener {
            DatePickerDialog(context, { _, year, month, day ->
                chosenDate = LocalDate.of(year, month + 1, day); render()
            }, chosenDate.year, chosenDate.monthValue - 1, chosenDate.dayOfMonth).show()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                chosenTime = LocalTime.of(hour, minute); render()
            }, chosenTime.hour, chosenTime.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }
        saveButton.setOnClickListener {
            if (!saving) runCatching {
                FoodEntryPortion(
                    portionGrams(parsedAmount() ?: throw IllegalArgumentException("Enter a portion."), if (useServing) serving else null),
                    consumptionTime(chosenDate, chosenTime, zone, original?.consumedAtMillis), chosenMeal,
                )
            }.fold(onSuccess = { setBusy(true); onSave(it) }, onFailure = { showError(it.message ?: "Check the portion, date and time.") })
        }
        render()
    }

    fun showError(message: String) { setBusy(false); error.text = message; error.visibility = View.VISIBLE }

    fun saveState() = Bundle().apply {
        putString("product", FoodLogBackup.productSnapshot(product))
        putString("entryUuid", entryUuid)
        putString("original", original?.let(FoodLogBackup::entrySnapshot))
        putString("date", chosenDate.toString()); putString("time", chosenTime.toString())
        putString("meal", chosenMeal.name); putString("amount", amount.text.toString())
        putBoolean("serving", useServing)
    }

    fun restoreState(state: Bundle) {
        entryUuid = requireNotNull(state.getString("entryUuid")).also { require(FOOD_ENTRY_ID_PATTERN.matches(it)) }
        chosenDate = LocalDate.parse(state.getString("date"))
        chosenTime = LocalTime.parse(state.getString("time"))
        chosenMeal = MealType.valueOf(requireNotNull(state.getString("meal")))
        useServing = state.getBoolean("serving") && serving != null
        amount.setText(state.getString("amount"))
        render()
    }

    private fun setBusy(value: Boolean) {
        saving = value
        saveButton.isEnabled = !value
        saveButton.text = if (value) "Saving…" else if (original == null) "Add to journal" else "Save changes"
    }

    private fun parsedAmount(): Double? = amount.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun render() {
        unit.text = if (useServing) "Servings · change to grams" else if (serving != null) "Grams · change to servings" else "Grams"
        dateButton.text = chosenDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        timeButton.text = "${chosenTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))} · ${zone.id}"
        mealButton.text = chosenMeal.displayName
        val grams = runCatching { portionGrams(parsedAmount() ?: 0.0, if (useServing) serving else null) }.getOrNull()
        preview.text = if (grams == null) "Enter a portion weighing 1–5,000 g." else {
            fun nutrient(value: Double?, unit: String) = scaledValue(value, grams).displayPer100g(unit)
            "${formatNutritionNumber(grams)} g · ${nutrient(product.nutrients.caloriesKcal, "kcal")}\n" +
                "Protein ${nutrient(product.nutrients.proteinGrams, "g")} · Carbs ${nutrient(product.nutrients.carbohydrateGrams, "g")} · Fat ${nutrient(product.nutrients.fatGrams, "g")}"
        }
    }
}
