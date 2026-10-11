package com.anezium.rokidbus.shared.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * The documented JSON Schema subset skill operations use for their input and output.
 *
 * Everything is bounded and nothing executes: one `type` per node, objects never accept
 * unknown properties, every string and array declares its maximum, every number declares its
 * range, and there are no references, combinators, patterns, or formats. A keyword outside the
 * subset makes the whole catalog invalid rather than being ignored, so a provider cannot rely on
 * a rule the hub does not enforce.
 *
 * `nexusRef` is the one extension: a string property carrying an entity reference of the named
 * type. The hub swaps the provider's own identifier for an opaque, expiring handle on the way out
 * and resolves the handle back on the way in, so a model can only ever return a reference it was
 * given. Adapters remove the keyword before showing a schema to a model.
 */
sealed class SkillSchema {
    abstract val description: String?

    data class ObjectType(
        val properties: Map<String, SkillSchema>,
        val required: Set<String>,
        override val description: String? = null,
    ) : SkillSchema()

    data class StringType(
        val minLength: Int = 0,
        val maxLength: Int = 0,
        val enumValues: List<String>? = null,
        val referenceType: String? = null,
        override val description: String? = null,
    ) : SkillSchema()

    data class IntegerType(
        val minimum: Long,
        val maximum: Long,
        override val description: String? = null,
    ) : SkillSchema()

    data class NumberType(
        val minimum: Double,
        val maximum: Double,
        override val description: String? = null,
    ) : SkillSchema()

    data class BooleanType(override val description: String? = null) : SkillSchema()

    data class ArrayType(
        val items: SkillSchema,
        val minItems: Int,
        val maxItems: Int,
        override val description: String? = null,
    ) : SkillSchema()

    /** The subset serialization, `nexusRef` included, as the hub hands it to a caller. */
    fun toJson(): JSONObject = toJson(includeReferences = true)

    /**
     * Plain JSON Schema for a model's tool declaration: `nexusRef` is dropped and the property
     * says in words that it takes a reference from an earlier result.
     */
    fun toModelJson(): JSONObject = toJson(includeReferences = false)

    private fun toJson(includeReferences: Boolean): JSONObject {
        val json = JSONObject()
        when (this) {
            is ObjectType -> {
                json.put("type", "object")
                val properties = JSONObject()
                this.properties.forEach { (name, schema) ->
                    properties.put(name, schema.toJson(includeReferences))
                }
                json.put("properties", properties)
                json.put("required", JSONArray(required.sorted()))
                json.put("additionalProperties", false)
            }
            is StringType -> {
                json.put("type", "string")
                if (referenceType != null) {
                    if (includeReferences) json.put(KEY_REFERENCE, referenceType)
                } else {
                    enumValues?.let { json.put("enum", JSONArray(it)) }
                    if (enumValues == null) {
                        if (minLength > 0) json.put("minLength", minLength)
                        json.put("maxLength", maxLength)
                    }
                }
            }
            is IntegerType -> json.put("type", "integer").put("minimum", minimum).put("maximum", maximum)
            is NumberType -> json.put("type", "number").put("minimum", minimum).put("maximum", maximum)
            is BooleanType -> json.put("type", "boolean")
            is ArrayType -> {
                json.put("type", "array").put("items", items.toJson(includeReferences))
                if (minItems > 0) json.put("minItems", minItems)
                json.put("maxItems", maxItems)
            }
        }
        val text = when {
            this is StringType && referenceType != null && !includeReferences ->
                listOfNotNull(description, REFERENCE_MODEL_HINT).joinToString(" ")
            else -> description
        }
        text?.let { json.put("description", it) }
        return json
    }

    companion object {
        const val KEY_REFERENCE = "nexusRef"
        private const val REFERENCE_MODEL_HINT =
            "Pass back a reference exactly as an earlier result returned it; never make one up."
        val PROPERTY_NAME = Regex("[a-z][a-z0-9_]{0,47}")
        val REFERENCE_TYPE = Regex("[a-z][a-z0-9_]{1,31}")
    }
}

sealed interface SkillSchemaParseResult {
    data class Valid(val schema: SkillSchema.ObjectType) : SkillSchemaParseResult
    data class Invalid(val reason: String) : SkillSchemaParseResult
}

object SkillSchemaParser {
    /** Parses a root schema, which must be an object. */
    fun parseRoot(json: JSONObject): SkillSchemaParseResult {
        val counter = IntArray(1)
        return try {
            val schema = parseNode(json, depth = 1, counter = counter)
            if (schema !is SkillSchema.ObjectType) {
                SkillSchemaParseResult.Invalid("ROOT_NOT_OBJECT")
            } else {
                SkillSchemaParseResult.Valid(schema)
            }
        } catch (invalid: InvalidSchema) {
            SkillSchemaParseResult.Invalid(invalid.reason)
        }
    }

    private class InvalidSchema(val reason: String) : RuntimeException(reason)

    private fun fail(reason: String): Nothing = throw InvalidSchema(reason)

    private fun parseNode(json: JSONObject, depth: Int, counter: IntArray): SkillSchema {
        if (depth > SkillLimits.MAX_SCHEMA_DEPTH) fail("SCHEMA_TOO_DEEP")
        counter[0] += 1
        if (counter[0] > SkillLimits.MAX_SCHEMA_NODES) fail("SCHEMA_TOO_LARGE")
        if (!json.has("type")) fail("SCHEMA_TYPE_MISSING")
        val type = json.opt("type") as? String ?: fail("SCHEMA_TYPE_UNSUPPORTED")
        val allowed = COMMON_KEYS + when (type) {
            "object" -> OBJECT_KEYS
            "string" -> STRING_KEYS
            "integer", "number" -> RANGE_KEYS
            "boolean" -> emptySet()
            "array" -> ARRAY_KEYS
            else -> fail("SCHEMA_TYPE_UNSUPPORTED")
        }
        json.keys().forEach { key -> if (key !in allowed) fail("SCHEMA_KEYWORD_UNSUPPORTED") }
        val description = if (json.has("description")) {
            val text = json.opt("description") as? String ?: fail("SCHEMA_DESCRIPTION_INVALID")
            if (text.isBlank() || text.length > SkillLimits.MAX_SCHEMA_DESCRIPTION_CHARS) {
                fail("SCHEMA_DESCRIPTION_INVALID")
            }
            text.trim()
        } else {
            null
        }
        return when (type) {
            "object" -> parseObject(json, depth, counter, description)
            "string" -> parseString(json, description)
            "integer" -> {
                val minimum = json.integerKeyword("minimum")
                val maximum = json.integerKeyword("maximum")
                if (minimum > maximum) fail("SCHEMA_RANGE_INVALID")
                SkillSchema.IntegerType(minimum, maximum, description)
            }
            "number" -> {
                val minimum = json.numberKeyword("minimum")
                val maximum = json.numberKeyword("maximum")
                if (minimum > maximum) fail("SCHEMA_RANGE_INVALID")
                SkillSchema.NumberType(minimum, maximum, description)
            }
            "boolean" -> SkillSchema.BooleanType(description)
            else -> {
                val items = json.opt("items") as? JSONObject ?: fail("SCHEMA_ITEMS_MISSING")
                val minItems = if (json.has("minItems")) json.boundedInt("minItems", 0, SkillLimits.MAX_ARRAY_ITEMS) else 0
                val maxItems = json.boundedInt("maxItems", 1, SkillLimits.MAX_ARRAY_ITEMS)
                if (minItems > maxItems) fail("SCHEMA_RANGE_INVALID")
                SkillSchema.ArrayType(parseNode(items, depth + 1, counter), minItems, maxItems, description)
            }
        }
    }

    private fun parseObject(
        json: JSONObject,
        depth: Int,
        counter: IntArray,
        description: String?,
    ): SkillSchema.ObjectType {
        if (json.has("additionalProperties") && json.opt("additionalProperties") != false) {
            fail("SCHEMA_ADDITIONAL_PROPERTIES")
        }
        val propertiesJson = if (json.has("properties")) {
            json.opt("properties") as? JSONObject ?: fail("SCHEMA_PROPERTIES_INVALID")
        } else {
            JSONObject()
        }
        if (propertiesJson.length() > SkillLimits.MAX_OBJECT_PROPERTIES) fail("SCHEMA_TOO_MANY_PROPERTIES")
        val properties = linkedMapOf<String, SkillSchema>()
        propertiesJson.keys().asSequence().sorted().forEach { name ->
            if (!SkillSchema.PROPERTY_NAME.matches(name)) fail("SCHEMA_PROPERTY_NAME")
            val child = propertiesJson.opt(name) as? JSONObject ?: fail("SCHEMA_PROPERTY_INVALID")
            properties[name] = parseNode(child, depth + 1, counter)
        }
        val required = linkedSetOf<String>()
        if (json.has("required")) {
            val array = json.opt("required") as? JSONArray ?: fail("SCHEMA_REQUIRED_INVALID")
            for (index in 0 until array.length()) {
                val name = array.opt(index) as? String ?: fail("SCHEMA_REQUIRED_INVALID")
                if (name !in properties || !required.add(name)) fail("SCHEMA_REQUIRED_INVALID")
            }
        }
        return SkillSchema.ObjectType(properties, required, description)
    }

    private fun parseString(json: JSONObject, description: String?): SkillSchema.StringType {
        if (json.has(SkillSchema.KEY_REFERENCE)) {
            val type = json.opt(SkillSchema.KEY_REFERENCE) as? String ?: fail("SCHEMA_REFERENCE_INVALID")
            if (!SkillSchema.REFERENCE_TYPE.matches(type)) fail("SCHEMA_REFERENCE_INVALID")
            if (json.has("enum") || json.has("minLength") || json.has("maxLength")) {
                fail("SCHEMA_REFERENCE_INVALID")
            }
            return SkillSchema.StringType(referenceType = type, description = description)
        }
        if (json.has("enum")) {
            if (json.has("minLength") || json.has("maxLength")) fail("SCHEMA_ENUM_INVALID")
            val array = json.opt("enum") as? JSONArray ?: fail("SCHEMA_ENUM_INVALID")
            if (array.length() == 0 || array.length() > SkillLimits.MAX_ENUM_VALUES) fail("SCHEMA_ENUM_INVALID")
            val values = (0 until array.length()).map { index ->
                val value = array.opt(index) as? String ?: fail("SCHEMA_ENUM_INVALID")
                if (value.isEmpty() || value.length > MAX_ENUM_VALUE_CHARS) fail("SCHEMA_ENUM_INVALID")
                value
            }
            if (values.toSet().size != values.size) fail("SCHEMA_ENUM_INVALID")
            return SkillSchema.StringType(enumValues = values, description = description)
        }
        val maxLength = json.boundedInt("maxLength", 1, SkillLimits.MAX_STRING_LENGTH)
        val minLength = if (json.has("minLength")) json.boundedInt("minLength", 0, maxLength) else 0
        return SkillSchema.StringType(minLength = minLength, maxLength = maxLength, description = description)
    }

    private fun JSONObject.integerKeyword(key: String): Long {
        val value = opt(key) as? Number ?: fail("SCHEMA_RANGE_MISSING")
        val asDouble = value.toDouble()
        if (asDouble != Math.floor(asDouble) || asDouble.isInfinite()) fail("SCHEMA_RANGE_INVALID")
        if (asDouble < Long.MIN_VALUE.toDouble() || asDouble > Long.MAX_VALUE.toDouble()) fail("SCHEMA_RANGE_INVALID")
        return value.toLong()
    }

    private fun JSONObject.numberKeyword(key: String): Double {
        val value = opt(key) as? Number ?: fail("SCHEMA_RANGE_MISSING")
        val asDouble = value.toDouble()
        if (asDouble.isNaN() || asDouble.isInfinite()) fail("SCHEMA_RANGE_INVALID")
        return asDouble
    }

    private fun JSONObject.boundedInt(key: String, min: Int, max: Int): Int {
        val value = opt(key) as? Number ?: fail("SCHEMA_BOUND_MISSING")
        val asDouble = value.toDouble()
        if (asDouble != Math.floor(asDouble) || asDouble < min || asDouble > max) fail("SCHEMA_BOUND_INVALID")
        return asDouble.toInt()
    }

    private const val MAX_ENUM_VALUE_CHARS = 64
    private val COMMON_KEYS = setOf("type", "description")
    private val OBJECT_KEYS = setOf("properties", "required", "additionalProperties")
    private val STRING_KEYS = setOf("minLength", "maxLength", "enum", SkillSchema.KEY_REFERENCE)
    private val RANGE_KEYS = setOf("minimum", "maximum")
    private val ARRAY_KEYS = setOf("items", "minItems", "maxItems")
}

sealed interface SkillValidation {
    data object Valid : SkillValidation
    data class Invalid(val path: String, val reason: String) : SkillValidation
}

/**
 * Validates an instance against a schema of the subset, and rewrites the entity references it
 * carries. The hub runs this on every argument before dispatch and every result before release;
 * the SDK runs the same code as a local preflight.
 */
object SkillSchemaValidator {
    fun validate(instance: Any?, schema: SkillSchema): SkillValidation =
        validateAt(instance, schema, "$")

    private fun validateAt(instance: Any?, schema: SkillSchema, path: String): SkillValidation {
        if (instance == null || instance == JSONObject.NULL) return SkillValidation.Invalid(path, "NULL")
        return when (schema) {
            is SkillSchema.ObjectType -> {
                val json = instance as? JSONObject ?: return SkillValidation.Invalid(path, "TYPE")
                json.keys().forEach { key ->
                    if (key !in schema.properties) return SkillValidation.Invalid("$path.$key", "UNKNOWN_PROPERTY")
                }
                schema.required.forEach { name ->
                    if (!json.has(name)) return SkillValidation.Invalid("$path.$name", "REQUIRED")
                }
                schema.properties.forEach { (name, child) ->
                    if (json.has(name)) {
                        val result = validateAt(json.opt(name), child, "$path.$name")
                        if (result is SkillValidation.Invalid) return result
                    }
                }
                SkillValidation.Valid
            }
            is SkillSchema.StringType -> {
                val text = instance as? String ?: return SkillValidation.Invalid(path, "TYPE")
                when {
                    schema.referenceType != null ->
                        if (text.isEmpty() || text.length > MAX_REFERENCE_CHARS) {
                            SkillValidation.Invalid(path, "REFERENCE")
                        } else {
                            SkillValidation.Valid
                        }
                    schema.enumValues != null ->
                        if (text in schema.enumValues) SkillValidation.Valid else SkillValidation.Invalid(path, "ENUM")
                    text.length < schema.minLength || text.length > schema.maxLength ->
                        SkillValidation.Invalid(path, "LENGTH")
                    else -> SkillValidation.Valid
                }
            }
            is SkillSchema.IntegerType -> {
                val number = instance as? Number ?: return SkillValidation.Invalid(path, "TYPE")
                val value = number.toDouble()
                when {
                    value != Math.floor(value) || value.isInfinite() -> SkillValidation.Invalid(path, "TYPE")
                    value < schema.minimum.toDouble() || value > schema.maximum.toDouble() ->
                        SkillValidation.Invalid(path, "RANGE")
                    else -> SkillValidation.Valid
                }
            }
            is SkillSchema.NumberType -> {
                val number = instance as? Number ?: return SkillValidation.Invalid(path, "TYPE")
                val value = number.toDouble()
                when {
                    value.isNaN() || value.isInfinite() -> SkillValidation.Invalid(path, "TYPE")
                    value < schema.minimum || value > schema.maximum -> SkillValidation.Invalid(path, "RANGE")
                    else -> SkillValidation.Valid
                }
            }
            is SkillSchema.BooleanType ->
                if (instance is Boolean) SkillValidation.Valid else SkillValidation.Invalid(path, "TYPE")
            is SkillSchema.ArrayType -> {
                val array = instance as? JSONArray ?: return SkillValidation.Invalid(path, "TYPE")
                if (array.length() < schema.minItems || array.length() > schema.maxItems) {
                    return SkillValidation.Invalid(path, "LENGTH")
                }
                for (index in 0 until array.length()) {
                    val result = validateAt(array.opt(index), schema.items, "$path[$index]")
                    if (result is SkillValidation.Invalid) return result
                }
                SkillValidation.Valid
            }
        }
    }

    /**
     * A deep copy of a valid [instance] with every `nexusRef` value passed through [map]. A null
     * from [map] rejects the whole instance; the path of the first rejected reference is returned.
     */
    fun mapReferences(
        instance: JSONObject,
        schema: SkillSchema.ObjectType,
        map: (type: String, value: String) -> String?,
    ): ReferenceMapping {
        val failure = arrayOfNulls<String>(1)
        val copy = mapAt(instance, schema, "$", map, failure)
        val failedPath = failure[0]
        return if (failedPath != null || copy !is JSONObject) {
            ReferenceMapping.Rejected(failedPath ?: "$")
        } else {
            ReferenceMapping.Mapped(copy)
        }
    }

    sealed interface ReferenceMapping {
        data class Mapped(val instance: JSONObject) : ReferenceMapping
        data class Rejected(val path: String) : ReferenceMapping
    }

    private fun mapAt(
        instance: Any?,
        schema: SkillSchema,
        path: String,
        map: (String, String) -> String?,
        failure: Array<String?>,
    ): Any? {
        if (failure[0] != null) return null
        return when (schema) {
            is SkillSchema.ObjectType -> {
                val json = instance as? JSONObject ?: return null
                val copy = JSONObject()
                json.keys().forEach { key ->
                    val child = schema.properties[key]
                    val value = json.opt(key)
                    copy.put(key, if (child == null) value else mapAt(value, child, "$path.$key", map, failure))
                }
                copy
            }
            is SkillSchema.ArrayType -> {
                val array = instance as? JSONArray ?: return null
                JSONArray().also { copy ->
                    for (index in 0 until array.length()) {
                        copy.put(mapAt(array.opt(index), schema.items, "$path[$index]", map, failure))
                    }
                }
            }
            is SkillSchema.StringType -> {
                val type = schema.referenceType ?: return instance
                val mapped = (instance as? String)?.let { map(type, it) }
                if (mapped == null) {
                    failure[0] = path
                    ""
                } else {
                    mapped
                }
            }
            else -> instance
        }
    }

    /** Upper bound on any reference string, whether a hub handle or a provider identifier. */
    const val MAX_REFERENCE_CHARS = 512
}
