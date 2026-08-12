package com.anezium.rokidbus.plugin.foodlog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Phone-side daily journal and manual barcode fallback for Food Log. */
class FoodLogActivity : Activity() {
    private lateinit var store: FoodLogStore
    private val factsClient = FoodFactsClient()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var barcodeField: EditText
    private lateinit var quantityField: EditText
    private lateinit var addButton: View
    private lateinit var lookupStatus: TextView
    private lateinit var summary: TextView
    private lateinit var entriesList: LinearLayout
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FoodLogStore(applicationContext)
        buildUi()
        refreshDay()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        barcodeField = NexusUi.field(this, "Barcode").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        quantityField = NexusUi.field(this, "Quantity in grams").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(DEFAULT_QUANTITY_GRAMS)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addFromBarcode()
                    true
                } else {
                    false
                }
            }
        }
        lookupStatus = NexusUi.statusLine(this).apply { visibility = View.GONE }
        summary = NexusUi.cardBody(this, "Loading today’s journal…")
        entriesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addButton = NexusUi.pillButton(this, "Look up and add").apply {
            setOnClickListener { addFromBarcode() }
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@FoodLogActivity,
                    "Log packaged food by barcode. Product data comes from Open Food Facts and can be incomplete.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@FoodLogActivity, 18))
            addView(NexusUi.sectionRow(this@FoodLogActivity, "Today"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(NexusUi.card(this@FoodLogActivity).apply { addView(summary) }, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 22))
            addView(NexusUi.sectionRow(this@FoodLogActivity, "Add food"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(barcodeField, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 8))
            addView(quantityField, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(addButton, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 8))
            addView(lookupStatus, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 22))
            addView(NexusUi.sectionRow(this@FoodLogActivity, "Entries"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(entriesList, NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 24))
            addView(NexusUi.sectionRow(this@FoodLogActivity, "Data"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(
                NexusUi.cardBody(
                    this@FoodLogActivity,
                    "Open Food Facts is a collaborative database. Check the label when nutrition data matters.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@FoodLogActivity, 24))
            addView(NexusUi.sectionRow(this@FoodLogActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@FoodLogActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@FoodLogActivity,
                    NexusPluginIcons.drawableFor("heart"),
                    "Food Log",
                    "Daily nutrition journal · v0.1",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@FoodLogActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        })
    }

    private fun addFromBarcode() {
        val barcode = normalizeBarcode(barcodeField.text.toString())
        val quantity = quantityField.text.toString().replace(',', '.').toDoubleOrNull()
        when {
            barcode == null -> showLookupStatus("Enter a 4–14 digit barcode.")
            quantity == null || quantity !in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS ->
                showLookupStatus("Quantity must be between 1 and 5,000 g.")
            else -> {
                addButton.isEnabled = false
                showLookupStatus("Looking up product…")
                worker.execute {
                    val product = store.product(barcode) ?: factsClient.product(barcode)?.also(store::upsertProduct)
                    if (product == null) {
                        post { finishLookup("No product found for this barcode.") }
                        return@execute
                    }
                    store.addEntry(product, quantity)
                    post {
                        barcodeField.text?.clear()
                        finishLookup("Added ${product.name}.")
                        refreshDay()
                    }
                }
            }
        }
    }

    private fun refreshDay() {
        worker.execute {
            val entries = store.entriesForDay()
            val totals = aggregateNutrition(entries)
            post {
                summary.text = buildString {
                    append("${totals.entryCount} ${if (totals.entryCount == 1) "entry" else "entries"} today\n")
                    append("${totals.caloriesKcal.display("kcal")} · ")
                    append("Protein ${totals.proteinGrams.display("g")} · ")
                    append("Carbs ${totals.carbohydrateGrams.display("g")} · ")
                    append("Fat ${totals.fatGrams.display("g")}")
                }
                renderEntries(entries)
            }
        }
    }

    private fun renderEntries(entries: List<FoodEntry>) {
        entriesList.removeAllViews()
        if (entries.isEmpty()) {
            entriesList.addView(NexusUi.cardBody(this, "No food logged today."), NexusUi.block())
            return
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) entriesList.addView(BusTheme.gap(this, 8))
            entriesList.addView(entryCard(entry), NexusUi.block())
        }
    }

    private fun entryCard(entry: FoodEntry): LinearLayout = NexusUi.card(this).apply {
        val top = LinearLayout(this@FoodLogActivity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(this@FoodLogActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@FoodLogActivity, entry.product.name))
                    addView(
                        NexusUi.rowSub(
                            this@FoodLogActivity,
                            listOfNotNull(
                                formatEntryTime(entry.consumedAtMillis),
                                entry.product.brand.takeIf(String::isNotBlank),
                                "${formatNutritionNumber(entry.quantityGrams)} g",
                            ).joinToString(" · "),
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(NexusUi.textButton(this@FoodLogActivity, "Delete", danger = true).apply {
                setOnClickListener { deleteEntry(entry.id) }
            })
        }
        addView(top, NexusUi.block())
        addView(BusTheme.gap(this@FoodLogActivity, 8))
        addView(
            NexusUi.metaLabel(
                this@FoodLogActivity,
                "${scaledValue(entry.product.nutrients.caloriesKcal, entry.quantityGrams).displayPer100g("kcal")} · " +
                    "P ${scaledValue(entry.product.nutrients.proteinGrams, entry.quantityGrams).displayPer100g("g")} · " +
                    "C ${scaledValue(entry.product.nutrients.carbohydrateGrams, entry.quantityGrams).displayPer100g("g")} · " +
                    "F ${scaledValue(entry.product.nutrients.fatGrams, entry.quantityGrams).displayPer100g("g")}",
            ),
        )
    }

    private fun deleteEntry(id: Long) {
        worker.execute {
            val deleted = store.deleteEntry(id)
            post {
                showLookupStatus(if (deleted) "Entry deleted." else "Entry was already removed.")
                refreshDay()
            }
        }
    }

    private fun finishLookup(message: String) {
        addButton.isEnabled = true
        showLookupStatus(message)
    }

    private fun showLookupStatus(message: String) {
        lookupStatus.text = message
        lookupStatus.visibility = View.VISIBLE
    }

    private fun post(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    private fun formatEntryTime(millis: Long): String =
        ENTRY_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Food Log") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val DEFAULT_QUANTITY_GRAMS = "100"
        val ENTRY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
}
