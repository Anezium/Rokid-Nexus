package com.anezium.rokidbus.plugin.foodlog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A reminder can only be created for an explicit meal or hydration prompt. */
internal enum class FoodLogReminderKind { MEAL, HYDRATION }

internal data class FoodLogReminder(
    val id: String,
    val kind: FoodLogReminderKind,
    val label: String,
    val epochMillis: Long,
    val enabled: Boolean,
)

/** Thread-safe local persistence. Every mutation replaces the complete file atomically. */
internal class FoodLogReminderStore internal constructor(
    filesDir: File,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val fileOperations: FoodLogAtomicFileOperations = NioFoodLogAtomicFileOperations,
) {
    private val file = File(filesDir, FILE_NAME)

    constructor(context: Context) : this(context.applicationContext.filesDir)

    fun all(): List<FoodLogReminder> = synchronized(lock) { read().sortedBy(FoodLogReminder::epochMillis) }

    fun get(id: String): FoodLogReminder? = synchronized(lock) { read().firstOrNull { it.id == id } }

    fun create(kind: FoodLogReminderKind, label: String, epochMillis: Long, enabled: Boolean = true): FoodLogReminder = synchronized(lock) {
        require(epochMillis > 0L)
        val normalized = label.trim().take(MAX_LABEL_CHARS)
        require(normalized.isNotEmpty())
        val existing = read()
        val reminder = FoodLogReminder(uniqueId(existing), kind, normalized, epochMillis, enabled)
        persist(existing + reminder)
        reminder
    }

    fun update(reminder: FoodLogReminder): Boolean = synchronized(lock) {
        validate(reminder)
        val existing = read()
        if (existing.none { it.id == reminder.id }) return@synchronized false
        persist(existing.map { if (it.id == reminder.id) reminder else it })
        true
    }

    /** Merges a fully validated backup by stable UUID and returns the imported values. */
    fun merge(reminders: List<FoodLogReminder>): List<FoodLogReminder> = synchronized(lock) {
        require(reminders.size <= MAX_REMINDERS)
        reminders.forEach(::validate)
        require(reminders.map(FoodLogReminder::id).toSet().size == reminders.size)
        val existing = try {
            read()
        } catch (exception: Exception) {
            quarantineCorruptFile()
            emptyList()
        }
        val merged = existing.associateByTo(linkedMapOf(), FoodLogReminder::id)
        reminders.forEach { merged[it.id] = it }
        require(merged.size <= MAX_REMINDERS)
        persist(merged.values.toList())
        reminders
    }

    private fun quarantineCorruptFile() {
        if (!file.isFile) return
        val quarantine = generateSequence(0) { it + 1 }
            .map { suffix -> File(file.parentFile, "${file.name}.corrupt-$suffix") }
            .first { !it.exists() }
        check(file.renameTo(quarantine)) { "Corrupt reminder data could not be preserved" }
    }

    /** Removes exactly the supplied UUID after rereading the current persistent state. */
    fun delete(id: String): FoodLogReminder? = synchronized(lock) {
        val existing = read()
        val exact = existing.firstOrNull { it.id == id } ?: return@synchronized null
        persist(existing.filterNot { it.id == id })
        exact
    }

    /** Cancellation is deliberately UUID-only; labels are never used as destructive identities. */
    fun cancel(id: String): FoodLogReminder? = delete(id)

    /** Atomically claims exactly one enabled item so an alarm cannot deliver twice. */
    fun takeForDelivery(id: String): FoodLogReminder? = synchronized(lock) {
        val existing = read()
        val exact = existing.firstOrNull { it.id == id && it.enabled } ?: return@synchronized null
        persist(existing.filterNot { it.id == id })
        exact
    }

    private val lock: Any
        get() = LOCKS.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }

    private fun uniqueId(existing: List<FoodLogReminder>): String {
        repeat(100) {
            val candidate = idGenerator()
            if (UUID_PATTERN.matches(candidate) && existing.none { it.id == candidate }) return candidate
        }
        error("Unable to create a unique reminder UUID.")
    }

    private fun validate(value: FoodLogReminder) {
        require(UUID_PATTERN.matches(value.id))
        require(value.label.trim().isNotEmpty() && value.label.length <= MAX_LABEL_CHARS)
        require(value.epochMillis > 0L)
    }

    private fun read(): List<FoodLogReminder> {
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText())
        require(root.getInt("version") == FILE_VERSION) { "Unsupported reminder file" }
        val array = root.getJSONArray("reminders")
        require(array.length() <= MAX_REMINDERS) { "Too many reminders" }
        val reminders = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val kind = FoodLogReminderKind.valueOf(item.getString("kind"))
                val label = item.getString("label")
                add(FoodLogReminder(id, kind, label, item.getLong("epochMillis"), item.getBoolean("enabled")).also(::validate))
            }
        }
        require(reminders.map(FoodLogReminder::id).toSet().size == reminders.size) { "Duplicate reminder UUID" }
        return reminders
    }

    private fun persist(reminders: List<FoodLogReminder>) {
        val json = JSONObject().put("version", FILE_VERSION).put("reminders", JSONArray().apply {
            reminders.forEach { value -> put(JSONObject()
                .put("id", value.id).put("kind", value.kind.name).put("label", value.label)
                .put("epochMillis", value.epochMillis).put("enabled", value.enabled)) }
        })
        writeFoodLogJsonAtomically(file, json.toString(), fileOperations)
    }

    internal companion object {
        const val MAX_LABEL_CHARS = 80
        const val MAX_REMINDERS = 5_000
        private const val FILE_VERSION = 1
        private const val FILE_NAME = "food_log_reminders_v1.json"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

internal interface FoodLogAtomicFileOperations { fun atomicReplace(source: File, target: File); fun replace(source: File, target: File) }
internal object NioFoodLogAtomicFileOperations : FoodLogAtomicFileOperations {
    override fun atomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
internal fun writeFoodLogJsonAtomically(target: File, text: String, ops: FoodLogAtomicFileOperations = NioFoodLogAtomicFileOperations) {
    val parent = checkNotNull(target.parentFile); if (!parent.isDirectory && !parent.mkdirs()) error("Reminder directory unavailable.")
    val temporary = File(parent, ".${target.name}.tmp")
    try { FileOutputStream(temporary).use { it.write(text.toByteArray()); it.flush(); it.fd.sync() }; try { ops.atomicReplace(temporary, target) } catch (_: AtomicMoveNotSupportedException) { ops.replace(temporary, target) } } finally { temporary.delete() }
}
