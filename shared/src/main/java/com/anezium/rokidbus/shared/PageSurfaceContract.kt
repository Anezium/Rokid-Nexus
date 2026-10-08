package com.anezium.rokidbus.shared

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

sealed interface PageSurfaceValidationResult<out T> {
    data class Valid<T>(val value: T) : PageSurfaceValidationResult<T>
    data class Invalid(val error: String, val reason: String) : PageSurfaceValidationResult<Nothing>
}

data class PageCorrelation(
    val requestId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val pageId: String,
)

data class PageSurfaceRequest(
    val correlation: PageCorrelation,
    val reason: String,
    val paramsJson: String? = null,
) {
    fun toPayload(): JSONObject = PageSurfaceContract.correlationPayload(correlation)
        .put("reason", reason)
        .apply { paramsJson?.let { put("params", JSONObject(it)) } }
}

data class PageSurfaceAction(val id: String, val label: String, val kind: String, val confirm: Boolean)

sealed interface PageSurfaceResponse {
    val correlation: PageCorrelation

    /** Serialized copies prevent a caller mutating a retained, validated JSON object. */
    data class Page(
        override val correlation: PageCorrelation,
        val revision: Long,
        val template: String,
        val title: String,
        val bodyJson: String,
        val actions: List<PageSurfaceAction>,
        val live: Boolean,
        val payloadJson: String,
        val byteSize: Int,
    ) : PageSurfaceResponse

    data class Error(override val correlation: PageCorrelation, val code: String) : PageSurfaceResponse
}

data class PageSurfaceInvocation(
    val invocationId: String,
    val sessionGeneration: Long,
    val frameIndex: Int,
    val pageId: String,
    val revision: Long,
    val actionId: String,
) {
    fun toPayload(): JSONObject = JSONObject()
        .put("invocationId", invocationId)
        .put("sessionGeneration", sessionGeneration)
        .put("frameIndex", frameIndex)
        .put("pageId", pageId)
        .put("revision", revision)
        .put("actionId", actionId)
}

data class PageSurfaceResult(
    val invocationId: String,
    val status: String,
    val message: String?,
    val replacement: PageSurfaceResponse.Page?,
)

data class PageSurfaceVisibility(val pageId: String, val visible: Boolean, val leaseUntilMs: Long?)
data class PageSurfaceClosed(val pageId: String, val reason: String)

/** Reserved page v1 contract. No running hub announces or dispatches it yet. */
object PageSurfaceContract {
    const val VERSION = 1
    const val GATE_MS = 800L
    const val LOADING_HINT_MS = 2_000L
    const val PAGE_TIMEOUT_MS = 8_000L
    const val COLD_REGISTRATION_BUDGET_MS = 5_000L
    const val LEASE_MS = 120_000L
    const val LEASE_RENEW_MS = 30_000L
    const val MAX_FRAMES = 6
    const val MAX_ROOT_STOPS = 6
    const val MAX_ID_CHARS = 128
    const val MAX_ACTIONS = 64
    const val MAX_TITLE_CHARS = 48
    const val MAX_ACTION_LABEL_CHARS = 24
    const val MAX_MESSAGE_CHARS = 64
    const val MAX_PARAMS_BYTES = 2_048
    const val MAX_DATASET_BYTES = 2_048
    const val MAX_PAGE_BYTES = 65_536
    const val MAX_SNAPSHOT_TOTAL_BYTES = 524_288
    /** Container depth of any validated JSON value; the payload object itself is level 1. */
    const val MAX_NESTING_DEPTH = 8
    /** Longest accepted text form of a JSON number. */
    const val MAX_NUMBER_CHARS = 32
    // 2^128 has 39 digits, so any integer part wider than this exceeds MAX_NUMBER_CHARS.
    private const val MAX_NUMBER_BITS = 128
    const val META_PLUGIN_PAGES = "rokidbus.plugin.pages"
    const val CAPABILITY_FIELD_PAGE_SESSION_VERSION = "pageSessionVersion"

    const val TEMPLATE_SUMMARY = "summary"
    const val TEMPLATE_SELECTABLE_LIST = "selectableList"
    const val TEMPLATE_DOCUMENT = "document"
    const val TEMPLATE_COMMANDS = "commands"
    const val TEMPLATE_CONVERSATION = "conversation"
    const val TEMPLATE_MEDIA = "media"
    const val TEMPLATE_ROUTE = "route"
    const val TEMPLATE_INK = "ink"

    const val ERROR_INVALID_PAGE_REQUEST = "INVALID_PAGE_REQUEST"
    const val ERROR_INVALID_PAGE = "INVALID_PAGE"
    const val ERROR_PAGE_TOO_LARGE = "PAGE_TOO_LARGE"
    const val ERROR_PAGE_TIMEOUT = "PAGE_TIMEOUT"
    const val ERROR_PAGE_UNAVAILABLE = "PAGE_UNAVAILABLE"
    const val ERROR_FRAME_LIMIT = "FRAME_LIMIT"
    const val ERROR_STALE_GENERATION = "STALE_GENERATION"
    const val ERROR_UNCONFIRMED_ACTION = "UNCONFIRMED_ACTION"

    val templates: Set<String> = setOf(
        TEMPLATE_SUMMARY, TEMPLATE_SELECTABLE_LIST, TEMPLATE_DOCUMENT, TEMPLATE_COMMANDS,
        TEMPLATE_CONVERSATION, TEMPLATE_MEDIA, TEMPLATE_ROUTE, TEMPLATE_INK,
    )
    val errors: Set<String> = setOf(
        ERROR_INVALID_PAGE_REQUEST, ERROR_INVALID_PAGE, ERROR_PAGE_TOO_LARGE, ERROR_PAGE_TIMEOUT,
        ERROR_PAGE_UNAVAILABLE, ERROR_FRAME_LIMIT, ERROR_STALE_GENERATION, ERROR_UNCONFIRMED_ACTION,
    )
    private val requestReasons = setOf("open", "refresh", "retry")
    private val actionKinds = setOf("hub", "plugin", "immersion")
    private val resultStatuses = setOf("done", "rejected", "stale")
    private val closeReasons = setOf("back", "session_closed", "timeout", "link_lost", "replaced", "frame_limit")
    private val pageFields = setOf("revision", "template", "title", "body", "actions", "live", "data")

    fun isValidId(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_ID_CHARS && value.all { it.code in 33..126 }

    fun serializedBytes(payload: JSONObject): Int = payload.toString().toByteArray(Charsets.UTF_8).size

    fun validateRequest(payload: JSONObject): PageSurfaceValidationResult<PageSurfaceRequest> =
        validate(ERROR_INVALID_PAGE_REQUEST) {
            val params = if (payload.has("params")) {
                boundedJson(objectValue(payload, "params"), MAX_PARAMS_BYTES, "params")
            } else null
            PageSurfaceRequest(readCorrelation(payload), enumValue(payload, "reason", requestReasons), params)
        }

    fun validateResponse(
        payload: JSONObject,
        pending: PageSurfaceRequest? = null,
    ): PageSurfaceValidationResult<PageSurfaceResponse> = validate(ERROR_INVALID_PAGE) {
        val serialized = boundedJson(payload, MAX_PAGE_BYTES, "response", ERROR_PAGE_TOO_LARGE)
        val correlation = readCorrelation(payload)
        if (pending != null && correlation != pending.correlation) {
            fail("response does not match the pending request", ERROR_STALE_GENERATION)
        }
        if (payload.has("error")) {
            checkShape(pageFields.none(payload::has), "error cannot also contain a page")
            PageSurfaceResponse.Error(correlation, enumValue(payload, "error", errors))
        } else {
            val body = objectValue(payload, "body")
            if (body.has("data")) requireSize(objectValue(body, "data"), MAX_DATASET_BYTES, "body.data")
            if (payload.has("data")) requireSize(objectValue(payload, "data"), MAX_DATASET_BYTES, "data")
            val array = payload.opt("actions") as? JSONArray ?: fail("actions must be an array")
            checkShape(array.length() <= MAX_ACTIONS, "too many actions")
            val actions = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("action must be an object")
                PageSurfaceAction(
                    id(entry, "id"), text(entry, "label", MAX_ACTION_LABEL_CHARS),
                    enumValue(entry, "kind", actionKinds), boolean(entry, "confirm"),
                )
            }
            checkShape(actions.map { it.id }.distinct().size == actions.size, "action ids must be unique")
            PageSurfaceResponse.Page(
                correlation, long(payload, "revision"), enumValue(payload, "template", templates),
                text(payload, "title", MAX_TITLE_CHARS), boundedJson(body, MAX_PAGE_BYTES, "body"), actions,
                boolean(payload, "live"), serialized, serialized.toByteArray(Charsets.UTF_8).size,
            )
        }
    }

    fun validateAction(payload: JSONObject): PageSurfaceValidationResult<PageSurfaceInvocation> =
        validate(ERROR_INVALID_PAGE_REQUEST) {
            PageSurfaceInvocation(
                id(payload, "invocationId"), long(payload, "sessionGeneration"), frameIndex(payload),
                id(payload, "pageId"), long(payload, "revision"), id(payload, "actionId"),
            )
        }

    fun validateResult(payload: JSONObject): PageSurfaceValidationResult<PageSurfaceResult> =
        validate(ERROR_INVALID_PAGE_REQUEST) {
            val replacement = if (payload.has("replacement")) {
                when (val result = validateResponse(objectValue(payload, "replacement"))) {
                    is PageSurfaceValidationResult.Invalid -> fail(result.reason, result.error)
                    is PageSurfaceValidationResult.Valid -> result.value as? PageSurfaceResponse.Page
                        ?: fail("replacement must be a successful page")
                }
            } else null
            PageSurfaceResult(
                id(payload, "invocationId"), enumValue(payload, "status", resultStatuses),
                if (payload.has("message")) text(payload, "message", MAX_MESSAGE_CHARS) else null,
                replacement,
            )
        }

    fun validateVisibility(payload: JSONObject): PageSurfaceValidationResult<PageSurfaceVisibility> =
        validate(ERROR_INVALID_PAGE_REQUEST) {
            val visible = boolean(payload, "visible")
            checkShape(visible || !payload.has("leaseUntilMs"), "covered visibility cannot carry a lease")
            PageSurfaceVisibility(id(payload, "pageId"), visible, if (visible) long(payload, "leaseUntilMs") else null)
        }

    fun validateClosed(payload: JSONObject): PageSurfaceValidationResult<PageSurfaceClosed> =
        validate(ERROR_INVALID_PAGE_REQUEST) {
            PageSurfaceClosed(id(payload, "pageId"), enumValue(payload, "reason", closeReasons))
        }

    fun correlationPayload(value: PageCorrelation): JSONObject = JSONObject()
        .put("requestId", value.requestId)
        .put("sessionGeneration", value.sessionGeneration)
        .put("frameIndex", value.frameIndex)
        .put("pageId", value.pageId)

    private fun readCorrelation(payload: JSONObject) = PageCorrelation(
        id(payload, "requestId"), long(payload, "sessionGeneration"), frameIndex(payload), id(payload, "pageId"),
    )

    private fun frameIndex(payload: JSONObject): Int {
        val index = long(payload, "frameIndex")
        checkShape(index < MAX_FRAMES, "frameIndex must include root and fit the frame limit")
        return index.toInt()
    }

    private fun id(payload: JSONObject, key: String): String {
        val value = payload.opt(key) as? String ?: fail("$key must be a string")
        checkShape(isValidId(value), "$key must be a safe id")
        return value
    }

    private fun text(payload: JSONObject, key: String, limit: Int): String {
        val value = payload.opt(key) as? String ?: fail("$key must be a string")
        checkShape(value.length <= limit, "$key exceeds $limit characters")
        return value
    }

    private fun enumValue(payload: JSONObject, key: String, values: Set<String>): String {
        val value = payload.opt(key) as? String ?: fail("$key must be a string")
        checkShape(value in values, "$key is unknown")
        return value
    }

    private fun boolean(payload: JSONObject, key: String): Boolean =
        payload.opt(key) as? Boolean ?: fail("$key must be a boolean")

    private fun objectValue(payload: JSONObject, key: String): JSONObject =
        payload.opt(key) as? JSONObject ?: fail("$key must be an object")

    private fun long(payload: JSONObject, key: String): Long {
        val number = payload.opt(key) as? Number ?: fail("$key must be an integer")
        val value = try {
            number.toString().toBigDecimal().longValueExact()
        } catch (_: NumberFormatException) {
            fail("$key must be a signed 64-bit integer")
        } catch (_: ArithmeticException) {
            fail("$key must be a signed 64-bit integer")
        }
        checkShape(value >= 0, "$key must be nonnegative")
        return value
    }

    /**
     * The text of a finite number of a type a JSON parser produces, or null when
     * it is unsupported or longer than [MAX_NUMBER_CHARS]. Arbitrary-precision
     * values are measured by bit length first so a huge one is never rendered.
     */
    private fun numberText(value: Number): String? {
        val text = when (value) {
            is Int, is Long, is Short, is Byte -> value.toString()
            is Double -> value.takeIf(Double::isFinite)?.toString()
            is Float -> value.takeIf(Float::isFinite)?.toString()
            is BigInteger -> value.takeIf { it.bitLength() <= MAX_NUMBER_BITS }?.toString()
            is BigDecimal -> value.takeIf { it.unscaledValue().bitLength() <= MAX_NUMBER_BITS }?.toString()
            else -> null
        }
        return text?.takeIf { it.length <= MAX_NUMBER_CHARS }
    }

    private fun requireSize(payload: JSONObject, limit: Int, key: String) {
        boundedJson(payload, limit, key)
    }

    /**
     * Serializes [payload] only after an iterative walk has bounded it, so a
     * hostile value can neither exhaust the stack nor force an oversized copy:
     * nesting beyond [MAX_NESTING_DEPTH], any string longer than [MAX_PAGE_BYTES],
     * any number longer than [MAX_NUMBER_CHARS], any scalar that is not a JSON
     * string, number, boolean or null, and any value whose minimum serialized
     * size already exceeds [limit] fail first. The exact UTF-8 size of the
     * serialized text is then enforced.
     */
    private fun boundedJson(payload: JSONObject, limit: Int, key: String, tooLarge: String? = null): String {
        var minimumBytes = 0L
        fun count(bytes: Long) {
            minimumBytes += bytes
            if (minimumBytes > limit) fail("$key exceeds $limit UTF-8 bytes", tooLarge)
        }
        fun countText(value: String) {
            if (value.length > MAX_PAGE_BYTES) fail("$key contains a string above $MAX_PAGE_BYTES characters", tooLarge)
            count(value.length + 2L)
        }

        val pending = ArrayDeque<Pair<Any?, Int>>()
        pending.addLast(payload to 1)
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            when (value) {
                is JSONObject -> {
                    checkShape(depth <= MAX_NESTING_DEPTH, "$key nests deeper than $MAX_NESTING_DEPTH levels")
                    count(2L + maxOf(value.length() - 1, 0))
                    for (name in value.keys()) {
                        countText(name)
                        count(1)
                        pending.addLast(value.opt(name) to depth + 1)
                    }
                }
                is JSONArray -> {
                    checkShape(depth <= MAX_NESTING_DEPTH, "$key nests deeper than $MAX_NESTING_DEPTH levels")
                    count(2L + maxOf(value.length() - 1, 0))
                    for (index in 0 until value.length()) pending.addLast(value.opt(index) to depth + 1)
                }
                is String -> countText(value)
                is Number -> {
                    checkShape(numberText(value) != null, "$key contains a number above $MAX_NUMBER_CHARS characters")
                    count(1)
                }
                is Boolean, JSONObject.NULL, null -> count(1)
                else -> fail("$key contains an unsupported ${value.javaClass.simpleName} value")
            }
        }
        val serialized = try {
            payload.toString()
        } catch (_: Exception) {
            null
        } ?: fail("$key cannot be serialized")
        if (serialized.toByteArray(Charsets.UTF_8).size > limit) fail("$key exceeds $limit UTF-8 bytes", tooLarge)
        return serialized
    }

    private class InvalidPayload(val detail: String, val code: String?) : RuntimeException()
    private fun fail(reason: String, error: String? = null): Nothing = throw InvalidPayload(reason, error)
    private fun checkShape(condition: Boolean, reason: String) { if (!condition) fail(reason) }

    private inline fun <T> validate(error: String, read: () -> T): PageSurfaceValidationResult<T> = try {
        PageSurfaceValidationResult.Valid(read())
    } catch (invalid: InvalidPayload) {
        PageSurfaceValidationResult.Invalid(invalid.code ?: error, invalid.detail)
    } catch (_: Exception) {
        // A validator at the provider boundary reports malformed input; it never throws it.
        PageSurfaceValidationResult.Invalid(error, "payload could not be read")
    }
}
