package com.anezium.rokidbus.plugin.foodlog

import android.app.DatePickerDialog
import android.content.Context
import android.widget.LinearLayout
import android.view.View
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal class FoodJournalView(
    private val context: Context,
    initialDate: LocalDate,
    private val onDate: (LocalDate) -> Unit,
    private val onAdd: (MealType) -> Unit,
    private val onEdit: (FoodEntry) -> Unit,
    private val onDelete: (FoodEntry) -> Unit,
) {
    var date: LocalDate = initialDate
        private set
    private val dateButton = NexusUi.outlinePillButton(context, "Choose date")
    private val summary = NexusUi.cardBody(context, "Loading journal…")
    private val details = NexusUi.cardBody(context, "").apply { visibility = View.GONE }
    private val meals = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val view = NexusUi.contentColumn(context).apply {
        addView(dateButton, NexusUi.block())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("Previous" to -1L, "Today" to 0L, "Next" to 1L).forEach { (title, offset) ->
                addView(NexusUi.textButton(context, title).apply {
                    setOnClickListener { choose(if (offset == 0L) LocalDate.now() else date.plusDays(offset)) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, NexusUi.block())
        addView(BusTheme.gap(context, 16))
        addView(summary, NexusUi.block())
        addView(NexusUi.textButton(context, "Nutrition details").apply {
            setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, NexusUi.block())
        addView(details, NexusUi.block())
        addView(BusTheme.gap(context, 16))
        addView(NexusUi.pillButton(context, "Add food").apply {
            setOnClickListener { onAdd(inferredMealType(System.currentTimeMillis())) }
        }, NexusUi.block())
        addView(meals, NexusUi.block())
    }

    init {
        dateButton.setOnClickListener {
            DatePickerDialog(context, { _, year, month, day -> choose(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        renderDate()
    }

    fun choose(value: LocalDate) { date = value; renderDate(); summary.text = "Loading journal…"; details.text = ""; meals.removeAllViews(); onDate(value) }

    fun showError() { summary.text = "Journal could not be loaded. Select the date again to retry."; meals.removeAllViews() }

    fun render(entries: List<FoodEntry>, goals: NutritionGoals?) {
        val totals = aggregateNutrition(entries)
        fun other(selector: (NutrientsPer100g) -> Double?): NutritionTotal {
            val values = entries.map { scaledValue(selector(it.product.nutrients), it.quantityGrams) }
            return NutritionTotal(values.filterNotNull().sum(), values.all { it != null })
        }
        details.text = listOf(
            "Sugars ${other { it.sugarsGrams }.display("g")} · Fiber ${other { it.fiberGrams }.display("g")}",
            "Saturated fat ${totals.saturatedFatGrams.display("g")} · Salt ${other { it.saltGrams }.display("g")}",
            "Sodium ${totals.sodiumMilligrams.display("mg")} · Cholesterol ${totals.cholesterolMilligrams.display("mg")}",
            "Potassium ${totals.potassiumMilligrams.display("mg")} · Calcium ${totals.calciumMilligrams.display("mg")}",
            "Iron ${totals.ironMilligrams.display("mg")} · Caffeine ${totals.caffeineMilligrams.display("mg")}",
        ).joinToString("\n")
        summary.text = buildString {
            append("${entries.size} ${if (entries.size == 1) "entry" else "entries"}\n")
            append(progress("Energy", totals.caloriesKcal, goals?.caloriesKcal, "kcal"))
            append("\n${progress("Protein", totals.proteinGrams, goals?.proteinGrams, "g")}")
            append("\n${progress("Carbs", totals.carbohydrateGrams, goals?.carbohydrateGrams, "g")}")
            append("\n${progress("Fat", totals.fatGrams, goals?.fatGrams, "g")}")
            if (!totals.caloriesKcal.complete || !totals.proteinGrams.complete || !totals.carbohydrateGrams.complete || !totals.fatGrams.complete) {
                append("\nSome nutrition is unknown. ≥ means the known amount only.")
            }
        }
        meals.removeAllViews()
        journalMeals(entries).forEach { group ->
            meals.addView(BusTheme.gap(context, 24))
            meals.addView(NexusUi.sectionRow(context, if (group.meal == MealType.SNACK) "Snacks" else group.meal.displayName, group.totals.caloriesKcal.display("kcal")), NexusUi.block())
            if (group.entries.isEmpty()) meals.addView(NexusUi.rowSub(context, "No food logged."))
            group.entries.forEach { entry ->
                meals.addView(BusTheme.gap(context, 10))
                meals.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(context, entry.product.name))
                    val time = Instant.ofEpochMilli(entry.consumedAtMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                    val calories = scaledValue(entry.product.nutrients.caloriesKcal, entry.quantityGrams).displayPer100g("kcal")
                    addView(NexusUi.rowSub(context, "$time · ${formatNutritionNumber(entry.quantityGrams)} g · $calories"))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(NexusUi.textButton(context, "Edit").apply { setOnClickListener { onEdit(entry) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(NexusUi.textButton(context, "Delete", true).apply { setOnClickListener { onDelete(entry) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    }, NexusUi.block())
                }, NexusUi.block())
            }
            meals.addView(NexusUi.textButton(context, "Add ${group.meal.displayName.lowercase()} food").apply { setOnClickListener { onAdd(group.meal) } }, NexusUi.block())
        }
    }

    private fun renderDate() {
        val prefix = if (date == LocalDate.now()) "Today · " else ""
        dateButton.text = prefix + date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    private fun progress(label: String, total: NutritionTotal, goal: Double?, unit: String): String =
        "$label ${total.display(unit)}" + (goal?.let { " / ${formatNutritionNumber(it)} $unit goal" } ?: "")
}
