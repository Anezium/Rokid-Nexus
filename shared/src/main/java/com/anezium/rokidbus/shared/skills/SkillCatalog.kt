package com.anezium.rokidbus.shared.skills

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class SkillEffect(val wireValue: String) {
    /** Observes state; retrying within a turn is allowed. */
    READ("read"),

    /** Changes something outside the provider; never replayed automatically. */
    ACTION("action"),
    ;

    companion object {
        fun fromWire(value: String?): SkillEffect? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Plugin-owned Android access an operation needs; shown to the wearer before approval. */
enum class SkillPrerequisite(val wireValue: String, val label: String) {
    NETWORK("network", "Internet access"),
    LOCATION("location", "Location, while the operation runs"),
    NOTIFICATION_ACCESS("notification_access", "Notification access"),
    ;

    companion object {
        fun fromWire(value: String?): SkillPrerequisite? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The kinds of data a result may return to the caller. The fixed vocabulary is what lets the
 * approval screen say, in the hub's words rather than the provider's, what could reach the AI
 * provider chosen in Assistant.
 */
enum class SkillDataCategory(val wireValue: String, val label: String) {
    PLACE_NAMES("place_names", "Stop and place names"),
    SCHEDULES("schedules", "Departure and arrival times"),
    ITINERARY("itinerary", "Journey itineraries"),
    MEDIA_METADATA("media_metadata", "Titles and artists of what is playing"),
    PLAYBACK_STATE("playback_state", "Whether something is playing"),
    STATUS("status", "Whether the operation succeeded"),
    TEXT("text", "Text you asked the plugin to process"),
    ;

    companion object {
        fun fromWire(value: String?): SkillDataCategory? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One operation a provider declares. Identity is the plugin-local [id] with its major contract
 * [version]; the hub supplies the provider. [digest] covers everything the wearer approves, so
 * any change to the contract, however small, needs a fresh approval.
 */
data class SkillOperation(
    val id: String,
    val version: Int,
    val label: String,
    val description: String,
    val examples: List<String>,
    val effect: SkillEffect,
    val cancellable: Boolean,
    val deduplicates: Boolean,
    val requires: Set<PluginCapability>,
    val prerequisites: Set<SkillPrerequisite>,
    val dataCategories: Set<SkillDataCategory>,
    val input: SkillSchema.ObjectType,
    val output: SkillSchema.ObjectType,
    val digest: String,
) {
    /** Entity types this operation can hand back to its caller. */
    val outputReferenceTypes: Set<String>
        get() = referenceTypes(output)

    companion object {
        val ID = Regex("[a-z][a-z0-9_]{1,47}")
        const val MAX_VERSION = 999

        /** Nexus access an operation may declare it needs from its provider's ordinary grant. */
        val REQUIRABLE_CAPABILITIES = setOf(PluginCapability.SURFACES)

        private fun referenceTypes(schema: SkillSchema): Set<String> = when (schema) {
            is SkillSchema.ObjectType -> schema.properties.values.flatMapTo(linkedSetOf(), ::referenceTypes)
            is SkillSchema.ArrayType -> referenceTypes(schema.items)
            is SkillSchema.StringType -> setOfNotNull(schema.referenceType)
            else -> emptySet()
        }
    }
}

data class SkillCatalog(
    val version: Int,
    val operations: List<SkillOperation>,
    val digest: String,
) {
    fun operation(id: String): SkillOperation? = operations.firstOrNull { it.id == id }
}

sealed interface SkillCatalogParseResult {
    data class Valid(val catalog: SkillCatalog) : SkillCatalogParseResult
    data class Invalid(val reason: String) : SkillCatalogParseResult
}

/**
 * Parses a provider's catalog resource. The hub runs it during ordinary discovery, without
 * starting the provider, and the SDK runs it on the provider's own catalog as a self-check. Any
 * violation rejects the whole catalog with a stable reason; it never invalidates the plugin.
 *
 * ```json
 * {
 *   "version": 1,
 *   "operations": [{
 *     "id": "search_stops", "version": 1,
 *     "label": "Search stops", "description": "…", "examples": ["Departures from Central"],
 *     "effect": "read", "cancellable": true, "deduplicates": true,
 *     "requires": [], "prerequisites": ["network"], "data": ["place_names"],
 *     "input": { "type": "object", "properties": { … }, "required": [ … ] },
 *     "output": { "type": "object", "properties": { … } }
 *   }]
 * }
 * ```
 */
object SkillCatalogParser {
    const val VERSION = 1

    fun parse(bytes: ByteArray): SkillCatalogParseResult {
        if (bytes.size > SkillLimits.MAX_CATALOG_BYTES) return SkillCatalogParseResult.Invalid("CATALOG_TOO_LARGE")
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return SkillCatalogParseResult.Invalid("CATALOG_NOT_UTF8")
        return parse(text)
    }

    fun parse(text: String): SkillCatalogParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > SkillLimits.MAX_CATALOG_BYTES) {
            return SkillCatalogParseResult.Invalid("CATALOG_TOO_LARGE")
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return SkillCatalogParseResult.Invalid("CATALOG_NOT_JSON")
        root.keys().forEach { key ->
            if (key !in ROOT_KEYS) return SkillCatalogParseResult.Invalid("CATALOG_KEY_UNSUPPORTED")
        }
        if (root.opt("version") != VERSION) return SkillCatalogParseResult.Invalid("CATALOG_VERSION")
        val array = root.opt("operations") as? JSONArray
            ?: return SkillCatalogParseResult.Invalid("CATALOG_OPERATIONS_MISSING")
        if (array.length() == 0) return SkillCatalogParseResult.Invalid("CATALOG_EMPTY")
        if (array.length() > SkillLimits.MAX_OPERATIONS_PER_PROVIDER) {
            return SkillCatalogParseResult.Invalid("CATALOG_TOO_MANY_OPERATIONS")
        }
        val operations = mutableListOf<SkillOperation>()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject
                ?: return SkillCatalogParseResult.Invalid("OPERATION_INVALID")
            when (val parsed = parseOperation(item)) {
                is OperationResult.Valid -> operations += parsed.operation
                is OperationResult.Invalid -> return SkillCatalogParseResult.Invalid(parsed.reason)
            }
        }
        if (operations.map(SkillOperation::id).toSet().size != operations.size) {
            return SkillCatalogParseResult.Invalid("OPERATION_ID_DUPLICATE")
        }
        val digest = sha256Hex(operations.joinToString("\n") { "${it.id}:${it.digest}" })
        return SkillCatalogParseResult.Valid(SkillCatalog(VERSION, operations, digest))
    }

    private sealed interface OperationResult {
        data class Valid(val operation: SkillOperation) : OperationResult
        data class Invalid(val reason: String) : OperationResult
    }

    private fun parseOperation(item: JSONObject): OperationResult {
        fun invalid(reason: String) = OperationResult.Invalid(reason)
        item.keys().forEach { key -> if (key !in OPERATION_KEYS) return invalid("OPERATION_KEY_UNSUPPORTED") }
        val id = item.opt("id") as? String ?: return invalid("OPERATION_ID")
        if (!SkillOperation.ID.matches(id)) return invalid("OPERATION_ID")
        val version = (item.opt("version") as? Int)?.takeIf { it in 1..SkillOperation.MAX_VERSION }
            ?: return invalid("OPERATION_VERSION")
        val label = (item.opt("label") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= SkillLimits.MAX_LABEL_CHARS }
            ?: return invalid("OPERATION_LABEL")
        val description = (item.opt("description") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= SkillLimits.MAX_DESCRIPTION_BYTES }
            ?: return invalid("OPERATION_DESCRIPTION")
        val examples = stringList(item, "examples") ?: return invalid("OPERATION_EXAMPLES")
        if (examples.size > SkillLimits.MAX_EXAMPLES ||
            examples.any { it.isBlank() || it.length > SkillLimits.MAX_EXAMPLE_CHARS }
        ) return invalid("OPERATION_EXAMPLES")
        val effect = SkillEffect.fromWire(item.opt("effect") as? String) ?: return invalid("OPERATION_EFFECT")
        val cancellable = item.opt("cancellable") as? Boolean ?: return invalid("OPERATION_CANCELLABLE")
        val deduplicates = item.opt("deduplicates") as? Boolean ?: return invalid("OPERATION_DEDUPLICATES")
        val requires = (stringList(item, "requires") ?: return invalid("OPERATION_REQUIRES")).map { value ->
            PluginCapability.fromWireValue(value)?.takeIf { it in SkillOperation.REQUIRABLE_CAPABILITIES }
                ?: return invalid("OPERATION_REQUIRES")
        }.toSet()
        val prerequisites = (stringList(item, "prerequisites") ?: return invalid("OPERATION_PREREQUISITES"))
            .map { SkillPrerequisite.fromWire(it) ?: return invalid("OPERATION_PREREQUISITES") }
            .toSet()
        val data = (stringList(item, "data") ?: return invalid("OPERATION_DATA"))
            .map { SkillDataCategory.fromWire(it) ?: return invalid("OPERATION_DATA") }
            .toSet()
        if (data.isEmpty()) return invalid("OPERATION_DATA")
        val inputJson = item.opt("input") as? JSONObject ?: return invalid("INPUT_SCHEMA_MISSING")
        val input = when (val parsed = SkillSchemaParser.parseRoot(inputJson)) {
            is SkillSchemaParseResult.Valid -> parsed.schema
            is SkillSchemaParseResult.Invalid -> return invalid("INPUT_${parsed.reason}")
        }
        val outputJson = item.opt("output") as? JSONObject ?: return invalid("OUTPUT_SCHEMA_MISSING")
        val output = when (val parsed = SkillSchemaParser.parseRoot(outputJson)) {
            is SkillSchemaParseResult.Valid -> parsed.schema
            is SkillSchemaParseResult.Invalid -> return invalid("OUTPUT_${parsed.reason}")
        }
        val canonical = JSONObject()
            .put("id", id)
            .put("version", version)
            .put("label", label)
            .put("description", description)
            .put("examples", JSONArray(examples))
            .put("effect", effect.wireValue)
            .put("cancellable", cancellable)
            .put("deduplicates", deduplicates)
            .put("requires", JSONArray(requires.map { it.wireValue }.sorted()))
            .put("prerequisites", JSONArray(prerequisites.map { it.wireValue }.sorted()))
            .put("data", JSONArray(data.map { it.wireValue }.sorted()))
            .put("input", input.toJson())
            .put("output", output.toJson())
        return OperationResult.Valid(
            SkillOperation(
                id = id,
                version = version,
                label = label,
                description = description,
                examples = examples,
                effect = effect,
                cancellable = cancellable,
                deduplicates = deduplicates,
                requires = requires,
                prerequisites = prerequisites,
                dataCategories = data,
                input = input,
                output = output,
                digest = sha256Hex(CanonicalJson.write(canonical)),
            ),
        )
    }

    private fun stringList(item: JSONObject, key: String): List<String>? {
        if (!item.has(key)) return emptyList()
        val array = item.opt(key) as? JSONArray ?: return null
        return (0 until array.length()).map { index -> array.opt(index) as? String ?: return null }
    }

    internal fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val ROOT_KEYS = setOf("version", "operations")
    private val OPERATION_KEYS = setOf(
        "id", "version", "label", "description", "examples", "effect", "cancellable",
        "deduplicates", "requires", "prerequisites", "data", "input", "output",
    )
}

/**
 * JSON with sorted keys and no insignificant whitespace, so a digest does not depend on which
 * org.json implementation produced the text: Android keeps insertion order, the JVM one does not.
 */
object CanonicalJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> {
                out.append('{')
                value.keys().asSequence().sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    out.append(JSONObject.quote(key)).append(':')
                    append(out, value.opt(key))
                }
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) out.append(',')
                    append(out, value.opt(index))
                }
                out.append(']')
            }
            is String -> out.append(JSONObject.quote(value))
            is Boolean -> out.append(value)
            is Number -> out.append(JSONObject.numberToString(value))
            else -> out.append(JSONObject.quote(value.toString()))
        }
    }
}
