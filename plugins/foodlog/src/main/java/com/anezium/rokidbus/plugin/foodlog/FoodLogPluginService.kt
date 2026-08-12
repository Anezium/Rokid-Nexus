package com.anezium.rokidbus.plugin.foodlog

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class FoodLogPluginService : NexusPluginService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val store by lazy { FoodLogStore(applicationContext) }
    private val foodFacts = FoodFactsClient()
    private val barcodeScanner by lazy { FoodBarcodeScanner(ioExecutor) }

    private var surface: NexusSurfaceSession? = null
    private var snapshotSession: NexusSnapshotSession? = null
    private var generation = 0L
    private var screen = Screen.HOME
    private var selectedIndex = 0
    private var selectedProduct: FoodProduct? = null
    private var quantityGrams = DEFAULT_QUANTITY_GRAMS
    private var todayEntries: List<FoodEntry> = emptyList()
    private var recentProducts: List<FoodProduct> = emptyList()
    private var undoCandidate: FoodEntry? = null
    private var messageTitle = "Food Log"
    private var messageLines = emptyList<String>()

    override fun onNexusOpen() {
        generation += 1
        screen = Screen.HOME
        selectedIndex = 0
        selectedProduct = null
        undoCandidate = null
        refreshLocalState()
        surface = nexusSurfaceSession(SURFACE_ID)
        render(show = true)
    }

    override fun onNexusClose() {
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        surface?.hide()
        surface = null
        selectedProduct = null
        undoCandidate = null
        screen = Screen.HOME
    }

    override fun onDestroy() {
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        barcodeScanner.close()
        ioExecutor.shutdownNow()
        store.close()
        super.onDestroy()
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> activate()
            KeyEvent.KEYCODE_BACK -> back()
        }
    }

    private fun move(delta: Int) {
        when (screen) {
            Screen.HOME -> {
                selectedIndex = Math.floorMod(selectedIndex + delta, HOME_ITEMS.size)
                render(show = false)
            }
            Screen.RECENTS -> if (recentProducts.isNotEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex + delta, recentProducts.size)
                render(show = false)
            }
            Screen.PORTION -> {
                quantityGrams = (quantityGrams + delta * QUANTITY_STEP_GRAMS)
                    .coerceIn(MIN_QUANTITY_GRAMS, MAX_QUANTITY_GRAMS)
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun activate() {
        when (screen) {
            Screen.HOME -> when (selectedIndex) {
                0 -> startSnapshot()
                1 -> showToday()
                2 -> showRecents()
                3 -> confirmUndo()
            }
            Screen.PRODUCT -> {
                screen = Screen.PORTION
                render(show = false)
            }
            Screen.PORTION -> addSelectedProduct()
            Screen.RECENTS -> recentProducts.getOrNull(selectedIndex)?.let(::showProduct)
            Screen.CONFIRM_UNDO -> deleteUndoCandidate()
            Screen.MESSAGE -> showHome()
            Screen.TODAY,
            Screen.SCANNING,
            -> Unit
        }
    }

    private fun back() {
        if (screen == Screen.HOME) {
            surface?.hide()
            return
        }
        generation += 1
        snapshotSession?.cancel()
        snapshotSession = null
        showHome()
    }

    private fun showHome() {
        refreshLocalState()
        screen = Screen.HOME
        selectedIndex = 0
        selectedProduct = null
        undoCandidate = null
        render(show = false)
    }

    private fun showToday() {
        refreshLocalState()
        screen = Screen.TODAY
        render(show = false)
    }

    private fun showRecents() {
        refreshLocalState()
        if (recentProducts.isEmpty()) {
            showMessage("Recent foods", "Nothing logged yet.")
            return
        }
        selectedIndex = 0
        screen = Screen.RECENTS
        render(show = false)
    }

    private fun confirmUndo() {
        val candidate = store.latestEntryForDay()
        if (candidate == null) {
            showMessage("Undo last", "Nothing logged today.")
            return
        }
        undoCandidate = candidate
        screen = Screen.CONFIRM_UNDO
        render(show = false)
    }

    private fun deleteUndoCandidate() {
        val candidate = undoCandidate ?: return showHome()
        val removed = store.deleteEntry(candidate.id)
        undoCandidate = null
        refreshLocalState()
        showMessage(
            "Undo last",
            if (removed) "Removed ${candidate.product.name}." else "That entry no longer exists.",
        )
    }

    private fun startSnapshot() {
        generation += 1
        val operationGeneration = generation
        screen = Screen.SCANNING
        messageTitle = "Scan barcode"
        messageLines = listOf("Point at one EAN or UPC barcode.", "Hold still while the photo is taken.")
        render(show = false)
        val callbacks = object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) {
                snapshotSession = null
                if (!isCurrent(operationGeneration)) return
                messageLines = listOf("Reading barcode…")
                render(show = false)
                barcodeScanner.scan(jpeg) { result ->
                    mainHandler.post {
                        if (!isCurrent(operationGeneration)) return@post
                        when (result) {
                            is FoodBarcodeScanner.Result.Found -> loadProduct(result.code, operationGeneration)
                            FoodBarcodeScanner.Result.NotFound -> showMessage(
                                "No barcode found",
                                "Move closer, keep the label sharp, then try again.",
                            )
                            is FoodBarcodeScanner.Result.Ambiguous -> showMessage(
                                "Several barcodes found",
                                "Frame a single product and try again.",
                            )
                            is FoodBarcodeScanner.Result.Failure -> showMessage(
                                "Scan failed",
                                "The image could not be read. Try again.",
                            )
                        }
                    }
                }
            }

            override fun onSnapshotError(error: NexusSnapshotError) {
                snapshotSession = null
                if (!isCurrent(operationGeneration)) return
                val message = when (error) {
                    NexusSnapshotError.BUSY -> "The glasses camera is busy."
                    NexusSnapshotError.LINK_DOWN -> "The glasses link is unavailable."
                    NexusSnapshotError.TIMEOUT -> "The camera did not answer in time."
                    NexusSnapshotError.CANCELLED -> "Capture cancelled."
                    NexusSnapshotError.CAPTURE_FAILED,
                    NexusSnapshotError.ERROR,
                    -> "The photo could not be captured."
                }
                showMessage("Scan unavailable", message)
            }
        }
        val session = nexusSnapshotSession(callbacks)
        snapshotSession = session
        val result = session?.capture()
        if (result != NexusSdkResult.SENT) {
            snapshotSession = null
            session?.cancel()
            val message = when (result) {
                NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                    "Grant Glasses camera access to Food Log in Nexus settings."
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> "Camera snapshots are not supported by this hub."
                else -> "The glasses camera is not ready."
            }
            showMessage("Scan unavailable", message)
        }
    }

    private fun loadProduct(barcode: String, operationGeneration: Long) {
        store.product(barcode)?.let {
            showProduct(it)
            return
        }
        messageTitle = "Open Food Facts"
        messageLines = listOf("Looking up product…")
        render(show = false)
        ioExecutor.execute {
            val result = runCatching { foodFacts.product(barcode) }
            result.getOrNull()?.let(store::upsertProduct)
            mainHandler.post {
                if (!isCurrent(operationGeneration)) return@post
                result.fold(
                    onSuccess = { product ->
                        if (product == null) {
                            showMessage(
                                "Product not found",
                                "Add it manually from Food Log settings on the phone.",
                            )
                        } else {
                            showProduct(product)
                        }
                    },
                    onFailure = {
                        showMessage(
                            "Lookup failed",
                            "Check the phone network connection and try again.",
                        )
                    },
                )
            }
        }
    }

    private fun showProduct(product: FoodProduct) {
        selectedProduct = product
        quantityGrams = product.servingGrams
            ?.takeIf { it in MIN_QUANTITY_GRAMS..MAX_QUANTITY_GRAMS }
            ?: DEFAULT_QUANTITY_GRAMS
        screen = Screen.PRODUCT
        render(show = false)
    }

    private fun addSelectedProduct() {
        val product = selectedProduct ?: return showHome()
        store.addEntry(product, quantityGrams)
        refreshLocalState()
        val totals = aggregateNutrition(todayEntries)
        showMessage(
            "Added",
            "${product.name} · ${formatNutritionNumber(quantityGrams)} g",
            "Today: ${totals.caloriesKcal.display("kcal")}",
        )
    }

    private fun showMessage(title: String, vararg lines: String) {
        messageTitle = title
        messageLines = lines.toList()
        screen = Screen.MESSAGE
        render(show = false)
    }

    private fun refreshLocalState() {
        todayEntries = store.entriesForDay()
        recentProducts = store.recentProducts()
    }

    private fun isCurrent(operationGeneration: Long): Boolean =
        operationGeneration == generation && isNexusSessionOpen

    private fun render(show: Boolean) {
        val card = when (screen) {
            Screen.HOME -> homeCard()
            Screen.SCANNING -> messageCard(messageTitle, messageLines, "back to cancel")
            Screen.PRODUCT -> productCard(selectedProduct)
            Screen.PORTION -> portionCard(selectedProduct)
            Screen.TODAY -> todayCard()
            Screen.RECENTS -> recentsCard()
            Screen.CONFIRM_UNDO -> undoCard(undoCandidate)
            Screen.MESSAGE -> messageCard(messageTitle, messageLines, "tap or back")
        }
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private fun homeCard(): NexusCard {
        val totals = aggregateNutrition(todayEntries)
        return NexusCard(
            title = "Food Log",
            subtitle = "Today · ${totals.entryCount} ${if (totals.entryCount == 1) "entry" else "entries"}",
            lines = emptyList(),
            richLines = HOME_ITEMS.mapIndexed { index, item ->
                NexusCardLine(
                    text = item.first,
                    sub = if (index == 1) {
                        "${totals.caloriesKcal.display("kcal")} · P ${totals.proteinGrams.display("g")}"
                    } else {
                        item.second
                    },
                    selected = index == selectedIndex,
                )
            },
            footer = "swipe · tap · back",
            contentKey = "foodlog-home-v1",
        )
    }

    private fun productCard(product: FoodProduct?): NexusCard {
        if (product == null) return messageCard("Product", listOf("Product unavailable."), "back")
        val nutrients = product.nutrients
        return NexusCard(
            title = product.name.hud(120),
            subtitle = product.brand.hud(240).ifBlank { "Open Food Facts" },
            lines = listOf(
                "Energy · ${nutrients.caloriesKcal.displayPer100g("kcal / 100 g")}",
                "Protein · ${nutrients.proteinGrams.displayPer100g("g / 100 g")}",
                "Carbs · ${nutrients.carbohydrateGrams.displayPer100g("g / 100 g")}",
                "Fat · ${nutrients.fatGrams.displayPer100g("g / 100 g")}",
                productBadges(product),
            ),
            footer = "tap to choose amount · back",
            contentKey = "foodlog-product",
            handlesBack = true,
        )
    }

    private fun portionCard(product: FoodProduct?): NexusCard {
        if (product == null) return messageCard("Amount", listOf("Product unavailable."), "back")
        val calories = scaledValue(product.nutrients.caloriesKcal, quantityGrams)
        return NexusCard(
            title = "How much?",
            subtitle = product.name.hud(240),
            lines = emptyList(),
            richLines = listOf(
                NexusCardLine(
                    text = "${formatNutritionNumber(quantityGrams)} g",
                    sub = calories?.let { "${formatNutritionNumber(it)} kcal" }
                        ?: "Energy unknown",
                    tone = NexusRowTone.ALERT,
                    selected = true,
                ),
            ),
            footer = "swipe ±10 g · tap to add · back",
            contentKey = "foodlog-portion",
            handlesBack = true,
        )
    }

    private fun todayCard(): NexusCard {
        val totals = aggregateNutrition(todayEntries)
        val rows = buildList {
            add(
                NexusCardLine(
                    text = totals.caloriesKcal.display("kcal"),
                    sub = "P ${totals.proteinGrams.display("g")} · C ${totals.carbohydrateGrams.display("g")} · F ${totals.fatGrams.display("g")}",
                    tone = NexusRowTone.ALERT,
                ),
            )
            todayEntries.take(MAX_HUD_ENTRIES).forEach { entry ->
                val calories = scaledValue(entry.product.nutrients.caloriesKcal, entry.quantityGrams)
                add(
                    NexusCardLine(
                        text = entry.product.name.hud(240),
                        badge = "${formatNutritionNumber(entry.quantityGrams)}g".hud(24),
                        sub = "${entry.consumedTime()} · ${calories?.let(::formatNutritionNumber) ?: "?"} kcal",
                    ),
                )
            }
            if (todayEntries.isEmpty()) {
                add(NexusCardLine("Nothing logged today.", tone = NexusRowTone.DIM))
            } else if (todayEntries.size > MAX_HUD_ENTRIES) {
                add(NexusCardLine("${todayEntries.size - MAX_HUD_ENTRIES} more on phone", tone = NexusRowTone.DIM))
            }
        }
        return NexusCard(
            title = "Today",
            subtitle = "${todayEntries.size} ${if (todayEntries.size == 1) "entry" else "entries"}",
            lines = emptyList(),
            richLines = rows,
            footer = "back",
            contentKey = "foodlog-today",
            handlesBack = true,
        )
    }

    private fun recentsCard(): NexusCard = NexusCard(
        title = "Recent foods",
        lines = emptyList(),
        richLines = recentProducts.mapIndexed { index, product ->
            NexusCardLine(
                text = product.name.hud(240),
                sub = product.brand.hud(240).ifBlank { product.barcode },
                selected = index == selectedIndex,
            )
        },
        footer = "swipe · tap · back",
        contentKey = "foodlog-recents",
        handlesBack = true,
    )

    private fun undoCard(entry: FoodEntry?): NexusCard {
        if (entry == null) return messageCard("Undo last", listOf("Nothing to remove."), "back")
        return NexusCard(
            title = "Remove last entry?",
            subtitle = entry.consumedTime(),
            lines = listOf(
                entry.product.name.hud(240),
                "${formatNutritionNumber(entry.quantityGrams)} g",
                "This removes exactly this log entry.",
            ),
            footer = "tap to remove · back to keep",
            contentKey = "foodlog-undo",
            handlesBack = true,
        )
    }

    private fun messageCard(title: String, lines: List<String>, footer: String): NexusCard = NexusCard(
        title = title.hud(120),
        lines = lines.map { it.hud(240) },
        footer = footer,
        contentKey = "foodlog-message",
        handlesBack = true,
    )

    private fun productBadges(product: FoodProduct): String = buildList {
        product.nutritionGrade?.uppercase()?.let { add("Nutri-Score $it") }
        product.novaGroup?.let { add("NOVA $it") }
        if (isEmpty()) add("Community data may be incomplete")
    }.joinToString(" · ").hud(240)

    private enum class Screen {
        HOME,
        SCANNING,
        PRODUCT,
        PORTION,
        TODAY,
        RECENTS,
        CONFIRM_UNDO,
        MESSAGE,
    }

    private companion object {
        const val SURFACE_ID = "foodlog-main"
        const val DEFAULT_QUANTITY_GRAMS = 100.0
        const val QUANTITY_STEP_GRAMS = 10.0
        const val MAX_HUD_ENTRIES = 8
        val HOME_ITEMS = listOf(
            "Scan product" to "EAN or UPC with the glasses camera",
            "Today" to "Daily nutrition and entries",
            "Recent foods" to "Log something again",
            "Undo last" to "Exact last entry from today",
        )
    }
}

private fun String.hud(limit: Int): String = trim().take(limit)

private fun FoodEntry.consumedTime(): String =
    Instant.ofEpochMilli(consumedAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
