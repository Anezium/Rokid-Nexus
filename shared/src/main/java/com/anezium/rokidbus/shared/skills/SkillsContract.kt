package com.anezium.rokidbus.shared.skills

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject

enum class SkillStatus(val wireValue: String) {
    /** The read finished, or the requested postcondition was observed. An empty read is valid. */
    COMPLETED("completed"),

    /** Dispatch is known to have happened but completion was not observed. Terminal in v1. */
    ACCEPTED("accepted"),

    /** Nothing happened; a bounded choice is needed before a new invocation. */
    NEEDS_INPUT("needs_input"),

    /** A known error, with a stable code and what is known about dispatch. */
    FAILED("failed"),

    /** Neither execution nor completion can be established. Never success, never proof of none. */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): SkillStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class SkillDispatch(val wireValue: String) {
    NONE("none"),
    DISPATCHED("dispatched"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): SkillDispatch? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Whether a declared operation can be used right now, as distinct from whether it is valid. */
enum class SkillAvailability(val wireValue: String) {
    ABSENT("absent"),
    DISABLED("disabled"),
    INCOMPATIBLE("incompatible"),
    SETUP_REQUIRED("setup_required"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    READY("ready"),
    ;

    companion object {
        fun fromWire(value: String?): SkillAvailability? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Stable error codes. The hub produces only these; a provider may add its own domain codes
 * matching [PROVIDER_CODE] (Transit's `no_route`, for example). An error's code is the branch a
 * caller takes; provider prose, if any, is never a command to anyone.
 */
object SkillErrorCodes {
    const val PERMISSION_REQUIRED = "permission_required"
    const val SETUP_REQUIRED = "setup_required"
    const val UNSUPPORTED_OPERATION = "unsupported_operation"
    const val INVALID_ARGUMENTS = "invalid_arguments"
    const val BUSY = "busy"
    const val UNAVAILABLE = "unavailable"
    const val STALE_REFERENCE = "stale_reference"
    const val DEADLINE_EXCEEDED = "deadline_exceeded"
    const val CANCELLED = "cancelled"

    /** A request key reused within a session with different arguments. */
    const val OPERATION_CONFLICT = "operation_conflict"

    /** The provider answered outside its declared output contract. */
    const val INVALID_RESULT = "invalid_result"

    val PROVIDER_CODE = Regex("[a-z][a-z0-9_]{1,47}")
}

data class SkillError(
    val code: String,
    val dispatch: SkillDispatch,
)

/** A provider-side reference: the provider's own identifier, which never reaches the caller. */
data class SkillProviderReference(val type: String, val value: String)

data class SkillProviderChoice(
    val label: String,
    val detail: String? = null,
    val reference: SkillProviderReference,
)

/** What a provider asks back with `needs_input`. [reason] is a stable code. */
data class SkillProviderInput(
    val reason: String,
    val prompt: String? = null,
    val choices: List<SkillProviderChoice> = emptyList(),
)

data class SkillChoice(
    val label: String,
    val detail: String?,
    /** An opaque hub handle the caller passes back in a new invocation. */
    val reference: String,
)

data class SkillInputRequest(
    val reason: String,
    val prompt: String?,
    val choices: List<SkillChoice>,
)

/** Hub to provider: one invocation to execute. [arguments] carry the provider's own identifiers. */
data class SkillProviderInvocation(
    val invocationId: String,
    val operationId: String,
    val contractVersion: Int,
    val arguments: JSONObject,
    val deadlineMs: Long,
)

/** Provider to hub: the one answer to [invocationId]. */
data class SkillProviderResult(
    val invocationId: String,
    val status: SkillStatus,
    val data: JSONObject? = null,
    val input: SkillProviderInput? = null,
    val error: SkillError? = null,
    val observedAtMs: Long? = null,
)

/** Caller to hub. [requestKey] identifies the request for duplicate handling within [session]. */
data class SkillInvokeRequest(
    val session: String,
    val requestKey: String,
    val alias: String,
    val arguments: JSONObject,
)

/** Hub to caller: every invocation ends in exactly one of these. */
data class SkillResultEnvelope(
    val session: String,
    val requestKey: String,
    val invocationId: String,
    val providerId: String,
    val operationId: String,
    val alias: String,
    val contractVersion: Int,
    val status: SkillStatus,
    val data: JSONObject? = null,
    val input: SkillInputRequest? = null,
    val error: SkillError? = null,
    val observedAtMs: Long,
)

/** One operation as the hub exposes it to an authorized caller. */
data class SkillCatalogEntry(
    val alias: String,
    val providerId: String,
    val providerName: String,
    val operationId: String,
    val version: Int,
    val label: String,
    val description: String,
    val examples: List<String>,
    val effect: SkillEffect,
    val cancellable: Boolean,
    val availability: SkillAvailability,
    val input: SkillSchema.ObjectType,
    val dataCategories: Set<SkillDataCategory>,
)

/**
 * Wire messages of the `/skills/` family. Every builder and parser is version-checked and fails
 * closed: a parser returns null on anything it does not fully understand. Plugins use the typed
 * SDK rather than these builders; the hub is the only producer of delivery messages, and it
 * stamps `pluginId` on each so the SDK's owner filter applies.
 */
object SkillsContract {
    const val VERSION = 1

    /**
     * Registration metadata field a phone hub that routes skills adds, carrying [VERSION]. The
     * SDK gates every skills helper on it, so a plugin built with this SDK degrades cleanly on
     * an older hub instead of sending traffic nobody routes.
     */
    const val REGISTRATION_FIELD = "skillsVersion"

    val SESSION_ID = Regex("[A-Za-z0-9_-]{8,64}")
    val REQUEST_KEY = Regex("[A-Za-z0-9_.:-]{1,96}")
    val INVOCATION_ID = Regex("[A-Za-z0-9_-]{8,64}")
    val ALIAS = Regex("[a-z][a-z0-9_]{0,63}")

    /** Opaque reference handles the hub issues; nothing else resolves. */
    val HANDLE = Regex("r_[A-Za-z0-9_-]{22}")

    /** Every hub-generated alias starts with this, and no built-in Assistant tool may. */
    const val ALIAS_PREFIX = "sk_"

    // Caller to hub.

    fun catalogRequest(): JSONObject = JSONObject().put("version", VERSION)

    fun invokeRequest(request: SkillInvokeRequest): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("session", request.session)
        .put("requestKey", request.requestKey)
        .put("alias", request.alias)
        .put("arguments", request.arguments)

    fun parseInvokeRequest(payload: JSONObject): SkillInvokeRequest? {
        if (payload.opt("version") != VERSION) return null
        val session = (payload.opt("session") as? String)?.takeIf(SESSION_ID::matches) ?: return null
        val requestKey = (payload.opt("requestKey") as? String)?.takeIf(REQUEST_KEY::matches) ?: return null
        val alias = (payload.opt("alias") as? String)?.takeIf(ALIAS::matches) ?: return null
        val arguments = payload.opt("arguments") as? JSONObject ?: return null
        return SkillInvokeRequest(session, requestKey, alias, arguments)
    }

    fun cancelRequest(session: String, requestKey: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("session", session)
        .put("requestKey", requestKey)

    fun parseCancelRequest(payload: JSONObject): Pair<String, String>? {
        if (payload.opt("version") != VERSION) return null
        val session = (payload.opt("session") as? String)?.takeIf(SESSION_ID::matches) ?: return null
        val requestKey = (payload.opt("requestKey") as? String)?.takeIf(REQUEST_KEY::matches) ?: return null
        return session to requestKey
    }

    fun sessionClose(session: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("session", session)

    fun parseSessionClose(payload: JSONObject): String? {
        if (payload.opt("version") != VERSION) return null
        return (payload.opt("session") as? String)?.takeIf(SESSION_ID::matches)
    }

    // Hub to caller.

    fun catalogReply(pluginId: String, entries: List<SkillCatalogEntry>): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        val operations = JSONArray()
        entries.take(SkillLimits.MAX_EXPOSED_OPERATIONS).forEach { entry ->
            operations.put(
                JSONObject()
                    .put("alias", entry.alias)
                    .put("providerId", entry.providerId)
                    .put("providerName", entry.providerName)
                    .put("operation", entry.operationId)
                    .put("version", entry.version)
                    .put("label", entry.label)
                    .put("description", entry.description)
                    .put("examples", JSONArray(entry.examples))
                    .put("effect", entry.effect.wireValue)
                    .put("cancellable", entry.cancellable)
                    .put("availability", entry.availability.wireValue)
                    .put("input", entry.input.toJson())
                    .put("data", JSONArray(entry.dataCategories.map { it.wireValue }.sorted())),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("operations", operations)
    }

    fun parseCatalogReply(payload: JSONObject): List<SkillCatalogEntry>? {
        if (payload.opt("version") != VERSION) return null
        val array = payload.opt("operations") as? JSONArray ?: return null
        return (0 until minOf(array.length(), SkillLimits.MAX_EXPOSED_OPERATIONS)).mapNotNull { index ->
            val item = array.opt(index) as? JSONObject ?: return@mapNotNull null
            val alias = (item.opt("alias") as? String)?.takeIf(ALIAS::matches) ?: return@mapNotNull null
            val providerId = (item.opt("providerId") as? String)?.takeIf(PluginDescriptor::isValidId)
                ?: return@mapNotNull null
            val operation = (item.opt("operation") as? String)?.takeIf(SkillOperation.ID::matches)
                ?: return@mapNotNull null
            val input = (item.opt("input") as? JSONObject)
                ?.let { (SkillSchemaParser.parseRoot(it) as? SkillSchemaParseResult.Valid)?.schema }
                ?: return@mapNotNull null
            SkillCatalogEntry(
                alias = alias,
                providerId = providerId,
                providerName = (item.opt("providerName") as? String).orEmpty().take(80),
                operationId = operation,
                version = item.optInt("version", 0).takeIf { it in 1..SkillOperation.MAX_VERSION }
                    ?: return@mapNotNull null,
                label = (item.opt("label") as? String).orEmpty().take(SkillLimits.MAX_LABEL_CHARS),
                description = (item.opt("description") as? String).orEmpty(),
                examples = (item.opt("examples") as? JSONArray)?.let { examples ->
                    (0 until examples.length()).mapNotNull { examples.opt(it) as? String }
                }.orEmpty().take(SkillLimits.MAX_EXAMPLES),
                effect = SkillEffect.fromWire(item.opt("effect") as? String) ?: return@mapNotNull null,
                cancellable = item.opt("cancellable") == true,
                availability = SkillAvailability.fromWire(item.opt("availability") as? String)
                    ?: return@mapNotNull null,
                input = input,
                dataCategories = (item.opt("data") as? JSONArray)?.let { data ->
                    (0 until data.length()).mapNotNull { SkillDataCategory.fromWire(data.opt(it) as? String) }
                }.orEmpty().toSet(),
            )
        }
    }

    fun resultPayload(pluginId: String, result: SkillResultEnvelope): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("session", result.session)
            .put("requestKey", result.requestKey)
            .put("invocationId", result.invocationId)
            .put("providerId", result.providerId)
            .put("operation", result.operationId)
            .put("alias", result.alias)
            .put("contractVersion", result.contractVersion)
            .put("status", result.status.wireValue)
            .put("observedAt", result.observedAtMs)
            .apply {
                result.data?.let { put("data", it) }
                result.input?.let { put("input", inputJson(it)) }
                result.error?.let { put("error", errorJson(it)) }
            }
    }

    fun parseResult(payload: JSONObject): SkillResultEnvelope? {
        if (payload.opt("version") != VERSION) return null
        val status = SkillStatus.fromWire(payload.opt("status") as? String) ?: return null
        val error = (payload.opt("error") as? JSONObject)?.let { parseError(it) ?: return null }
        val input = (payload.opt("input") as? JSONObject)?.let { json ->
            val reason = (json.opt("reason") as? String)?.takeIf(SkillErrorCodes.PROVIDER_CODE::matches)
                ?: return null
            val choices = (json.opt("choices") as? JSONArray)?.let { array ->
                (0 until minOf(array.length(), SkillLimits.MAX_CHOICES)).map { index ->
                    val item = array.opt(index) as? JSONObject ?: return null
                    SkillChoice(
                        label = (item.opt("label") as? String)?.take(SkillLimits.MAX_CHOICE_LABEL_CHARS)
                            ?: return null,
                        detail = (item.opt("detail") as? String)?.take(SkillLimits.MAX_CHOICE_DETAIL_CHARS),
                        reference = (item.opt("ref") as? String)?.takeIf(HANDLE::matches) ?: return null,
                    )
                }
            }.orEmpty()
            SkillInputRequest(reason, (json.opt("prompt") as? String)?.take(SkillLimits.MAX_PROMPT_CHARS), choices)
        }
        return SkillResultEnvelope(
            session = (payload.opt("session") as? String)?.takeIf(SESSION_ID::matches) ?: return null,
            requestKey = (payload.opt("requestKey") as? String)?.takeIf(REQUEST_KEY::matches) ?: return null,
            invocationId = (payload.opt("invocationId") as? String)?.takeIf(INVOCATION_ID::matches) ?: return null,
            providerId = (payload.opt("providerId") as? String).orEmpty(),
            operationId = (payload.opt("operation") as? String).orEmpty(),
            alias = (payload.opt("alias") as? String).orEmpty(),
            contractVersion = payload.optInt("contractVersion", 0),
            status = status,
            data = payload.opt("data") as? JSONObject,
            input = input,
            error = error,
            observedAtMs = payload.optLong("observedAt", 0L),
        )
    }

    // Hub to provider.

    fun providerInvoke(pluginId: String, invocation: SkillProviderInvocation): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("invocationId", invocation.invocationId)
            .put("operation", invocation.operationId)
            .put("contractVersion", invocation.contractVersion)
            .put("arguments", invocation.arguments)
            .put("deadlineMs", invocation.deadlineMs)
    }

    fun parseProviderInvoke(payload: JSONObject): SkillProviderInvocation? {
        if (payload.opt("version") != VERSION) return null
        return SkillProviderInvocation(
            invocationId = (payload.opt("invocationId") as? String)?.takeIf(INVOCATION_ID::matches) ?: return null,
            operationId = (payload.opt("operation") as? String)?.takeIf(SkillOperation.ID::matches) ?: return null,
            contractVersion = payload.optInt("contractVersion", 0).takeIf { it in 1..SkillOperation.MAX_VERSION }
                ?: return null,
            arguments = payload.opt("arguments") as? JSONObject ?: return null,
            deadlineMs = payload.optLong("deadlineMs", 0L).takeIf { it > 0L } ?: return null,
        )
    }

    fun providerCancel(pluginId: String, invocationId: String, reason: String): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("invocationId", invocationId)
            .put("reason", reason)
    }

    fun parseProviderCancel(payload: JSONObject): String? {
        if (payload.opt("version") != VERSION) return null
        return (payload.opt("invocationId") as? String)?.takeIf(INVOCATION_ID::matches)
    }

    // Provider to hub.

    fun providerResult(result: SkillProviderResult): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("invocationId", result.invocationId)
        .put("status", result.status.wireValue)
        .apply {
            result.observedAtMs?.let { put("observedAt", it) }
            result.data?.let { put("data", it) }
            result.error?.let { put("error", errorJson(it)) }
            result.input?.let { input ->
                put(
                    "input",
                    JSONObject()
                        .put("reason", input.reason)
                        .apply { input.prompt?.let { put("prompt", it) } }
                        .put(
                            "choices",
                            JSONArray().apply {
                                input.choices.forEach { choice ->
                                    put(
                                        JSONObject()
                                            .put("label", choice.label)
                                            .apply { choice.detail?.let { put("detail", it) } }
                                            .put(
                                                "ref",
                                                JSONObject()
                                                    .put("type", choice.reference.type)
                                                    .put("value", choice.reference.value),
                                            ),
                                    )
                                }
                            },
                        ),
                )
            }
        }

    /**
     * Parses and checks a provider's answer against the status rules: completed carries data,
     * needs-input carries a bounded choice and nothing else, failed carries a stable error.
     */
    fun parseProviderResult(payload: JSONObject): SkillProviderResult? {
        if (payload.opt("version") != VERSION) return null
        val invocationId = (payload.opt("invocationId") as? String)?.takeIf(INVOCATION_ID::matches) ?: return null
        val status = SkillStatus.fromWire(payload.opt("status") as? String) ?: return null
        val data = if (payload.has("data")) payload.opt("data") as? JSONObject ?: return null else null
        val error = if (payload.has("error")) {
            (payload.opt("error") as? JSONObject)?.let(::parseError) ?: return null
        } else {
            null
        }
        val input = if (payload.has("input")) {
            (payload.opt("input") as? JSONObject)?.let(::parseProviderInput) ?: return null
        } else {
            null
        }
        val observedAt = if (payload.has("observedAt")) {
            payload.optLong("observedAt", -1L).takeIf { it > 0L } ?: return null
        } else {
            null
        }
        val shapeValid = when (status) {
            SkillStatus.COMPLETED -> data != null && error == null && input == null
            SkillStatus.ACCEPTED -> error == null && input == null
            SkillStatus.NEEDS_INPUT -> input != null && data == null && error == null
            SkillStatus.FAILED -> error != null && data == null && input == null
            SkillStatus.UNKNOWN -> data == null && input == null &&
                (error == null || error.dispatch == SkillDispatch.UNKNOWN)
        }
        if (!shapeValid) return null
        return SkillProviderResult(invocationId, status, data, input, error, observedAt)
    }

    private fun parseProviderInput(json: JSONObject): SkillProviderInput? {
        val reason = (json.opt("reason") as? String)?.takeIf(SkillErrorCodes.PROVIDER_CODE::matches) ?: return null
        val prompt = if (json.has("prompt")) {
            (json.opt("prompt") as? String)?.takeIf { it.isNotBlank() && it.length <= SkillLimits.MAX_PROMPT_CHARS }
                ?: return null
        } else {
            null
        }
        val array = if (json.has("choices")) json.opt("choices") as? JSONArray ?: return null else JSONArray()
        if (array.length() > SkillLimits.MAX_CHOICES) return null
        val choices = (0 until array.length()).map { index ->
            val item = array.opt(index) as? JSONObject ?: return null
            val label = (item.opt("label") as? String)
                ?.takeIf { it.isNotBlank() && it.length <= SkillLimits.MAX_CHOICE_LABEL_CHARS } ?: return null
            val detail = if (item.has("detail")) {
                (item.opt("detail") as? String)?.takeIf { it.length <= SkillLimits.MAX_CHOICE_DETAIL_CHARS }
                    ?: return null
            } else {
                null
            }
            val ref = item.opt("ref") as? JSONObject ?: return null
            val type = (ref.opt("type") as? String)?.takeIf(SkillSchema.REFERENCE_TYPE::matches) ?: return null
            val value = (ref.opt("value") as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= SkillSchemaValidator.MAX_REFERENCE_CHARS } ?: return null
            SkillProviderChoice(label, detail, SkillProviderReference(type, value))
        }
        return SkillProviderInput(reason, prompt, choices)
    }

    private fun parseError(json: JSONObject): SkillError? {
        val code = (json.opt("code") as? String)?.takeIf(SkillErrorCodes.PROVIDER_CODE::matches) ?: return null
        val dispatch = SkillDispatch.fromWire(json.opt("dispatch") as? String) ?: return null
        return SkillError(code, dispatch)
    }

    private fun errorJson(error: SkillError): JSONObject = JSONObject()
        .put("code", error.code)
        .put("dispatch", error.dispatch.wireValue)

    private fun inputJson(input: SkillInputRequest): JSONObject = JSONObject()
        .put("reason", input.reason)
        .apply { input.prompt?.let { put("prompt", it) } }
        .put(
            "choices",
            JSONArray().apply {
                input.choices.forEach { choice ->
                    put(
                        JSONObject()
                            .put("label", choice.label)
                            .apply { choice.detail?.let { put("detail", it) } }
                            .put("ref", choice.reference),
                    )
                }
            },
        )

    /** UTF-8 byte size of a JSON value's serialization, the unit every size limit uses. */
    fun utf8Size(json: JSONObject): Int = json.toString().toByteArray(Charsets.UTF_8).size
}

/**
 * Model-facing tool names, generated from the authenticated provider and operation identities.
 * A developer's description or a model-supplied name never selects a provider; only this alias,
 * which the hub maps back to identities it authenticated, does.
 */
object SkillAliases {
    fun generate(providerId: String, operationId: String, taken: Set<String>): String {
        val readable = SkillsContract.ALIAS_PREFIX + sanitize(providerId) + "__" + operationId
        if (readable.length <= MAX_ALIAS_CHARS && readable !in taken) return readable
        val hash = SkillCatalogParser.sha256Hex("$providerId\u0000$operationId").take(10)
        val room = MAX_ALIAS_CHARS - SkillsContract.ALIAS_PREFIX.length - hash.length - 1
        return SkillsContract.ALIAS_PREFIX + hash + "_" + operationId.take(room)
    }

    private fun sanitize(providerId: String): String =
        providerId.map { character -> if (character in 'a'..'z' || character in '0'..'9') character else '_' }
            .joinToString("")

    private const val MAX_ALIAS_CHARS = 64
}
