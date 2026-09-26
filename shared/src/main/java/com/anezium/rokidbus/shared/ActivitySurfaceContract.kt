package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/** Why an activity stopped being live. Delivered to its owner exactly once. */
enum class ActivityCloseReason(val wireValue: String) {
    /** The owner explicitly ended it. */
    OWNER("owner"),

    /** Another activity took its corner. */
    REPLACED("replaced"),

    /** The owner's bus connection disappeared. */
    DISCONNECT("disconnect"),

    /** Its optional maximum duration elapsed. */
    MAX_DURATION("max-duration"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityCloseReason? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Platform-owned progress affordance for an activity. */
sealed interface ActivityProgress {
    data class Percent(val value: Int) : ActivityProgress
    data object Indeterminate : ActivityProgress
}

/** One one-shot command in an activity's platform-rendered action row. */
data class ActivityAction(
    val id: String,
    val glyph: String,
    val label: String,
)

/**
 * A row of ordered positions: stops on a line, stages of a delivery.
 *
 * [at] is where the process is now and [target] is where the wearer is headed;
 * both index one of [count] positions, with `at <= target`.
 */
data class ActivityTrack(
    val count: Int,
    val at: Int,
    val target: Int,
    val label: String? = null,
)

/** Canonical, presentation-free state of one live activity. */
data class ActivitySurfaceContent(
    val glyph: String,
    val primary: String,
    val secondary: String?,
    val progress: ActivityProgress?,
    val eta: String?,
    val detail: List<String>,
    val actions: List<ActivityAction>,
    val maxDurationMs: Long?,
    /** Start-time opt-in; only a later significant update may use it. */
    val wakeDisplay: Boolean = false,
    /** Extras: a short line or route mark drawn in the glyph's place. */
    val badge: String? = null,
    /**
     * Extras: a second quantity that belongs with [primary], such as the
     * distance of a walk timed in minutes. Beside the primary when expanded,
     * under it in the chip.
     */
    val measure: String? = null,
    /** Extras: drawn instead of [progress] by glasses that announce extras. */
    val track: ActivityTrack? = null,
)

/**
 * One field of an update. Absence keeps the current value, while a present
 * field may carry null to clear an optional value.
 */
@JvmInline
value class ActivityField<T>(val value: T)

/** A validated activity update, including its transient presentation hint. */
data class ActivitySurfacePatch(
    val glyph: ActivityField<String>? = null,
    val primary: ActivityField<String>? = null,
    val secondary: ActivityField<String?>? = null,
    val progress: ActivityField<ActivityProgress?>? = null,
    val eta: ActivityField<String?>? = null,
    val detail: ActivityField<List<String>>? = null,
    val actions: ActivityField<List<ActivityAction>>? = null,
    val badge: ActivityField<String?>? = null,
    val measure: ActivityField<String?>? = null,
    val track: ActivityField<ActivityTrack?>? = null,
    val significant: Boolean = false,
    /** Transient like [significant], and only valid together with it. */
    val urgent: Boolean = false,
) {
    fun applyTo(content: ActivitySurfaceContent): ActivitySurfaceContent = content.copy(
        glyph = if (glyph != null) glyph.value else content.glyph,
        primary = if (primary != null) primary.value else content.primary,
        secondary = if (secondary != null) secondary.value else content.secondary,
        progress = if (progress != null) progress.value else content.progress,
        eta = if (eta != null) eta.value else content.eta,
        detail = if (detail != null) detail.value else content.detail,
        actions = if (actions != null) actions.value else content.actions,
        badge = if (badge != null) badge.value else content.badge,
        measure = if (measure != null) measure.value else content.measure,
        track = if (track != null) track.value else content.track,
    )
}

sealed interface ActivitySurfaceValidationResult {
    data class Valid(val content: ActivitySurfaceContent) : ActivitySurfaceValidationResult
    data class Invalid(val reason: String) : ActivitySurfaceValidationResult
}

sealed interface ActivitySurfacePatchResult {
    data class Valid(val patch: ActivitySurfacePatch) : ActivitySurfacePatchResult
    data class Invalid(val reason: String) : ActivitySurfacePatchResult
}

/** Pure activity-surface validation and normalization with no Android dependencies. */
object ActivitySurfaceContract {
    const val KIND = "activity"
    const val VERSION = 1

    /**
     * Optional fields beyond v1: badge, measure, track, and the urgent tone. Announced
     * separately because both hubs match [VERSION] exactly; raising it would
     * disable activities between a new and an old hub.
     */
    const val EXTRAS_VERSION = 1
    const val LOCAL_SURFACE_ID = "activity"
    /** Hub-only reconnect marker. The leading '@' is not legal in a plugin id. */
    const val EMPTY_ASSERT_OWNER_PLUGIN_ID = "@nexus-hub"

    const val MAX_PRIMARY_CHARS = 12
    const val MAX_SECONDARY_CHARS = 28
    const val MAX_ETA_CHARS = 8
    const val MAX_DETAIL_LINES = 2
    const val MAX_DETAIL_CHARS = 32
    const val MAX_ACTIONS = 3
    const val MAX_ACTIVE_ACTIVITIES = 2
    const val MAX_BADGE_CHARS = 5
    const val MAX_MEASURE_CHARS = 8
    const val MIN_TRACK_COUNT = 2
    const val MAX_TRACK_COUNT = 12
    const val MAX_TRACK_LABEL_CHARS = 20
    const val TONE_URGENT = "urgent"

    const val MIN_MAX_DURATION_MS = 60_000L
    const val MAX_MAX_DURATION_MS = 43_200_000L
    const val MAX_UPDATES_PER_SECOND = 4

    const val ERROR_INVALID_ACTIVITY = "INVALID_ACTIVITY"
    const val ERROR_ACTIVITY_RATE_LIMITED = "ACTIVITY_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun emptySlotAssertPayload(seq: Long): JSONObject = JSONObject()
        .put("surfaceId", "$EMPTY_ASSERT_OWNER_PLUGIN_ID:$LOCAL_SURFACE_ID")
        .put("localSurfaceId", LOCAL_SURFACE_ID)
        .put("ownerPluginId", EMPTY_ASSERT_OWNER_PLUGIN_ID)
        .put("seq", seq)

    fun isEmptySlotAssert(payload: JSONObject): Boolean =
        payload.optString("surfaceId") ==
            "$EMPTY_ASSERT_OWNER_PLUGIN_ID:$LOCAL_SURFACE_ID" &&
            payload.optString("localSurfaceId") == LOCAL_SURFACE_ID &&
            payload.optString("ownerPluginId") == EMPTY_ASSERT_OWNER_PLUGIN_ID

    fun validateStart(payload: JSONObject): ActivitySurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be activity")

        val glyph = when (val result = readGlyph(payload, "glyph", required = true)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> return invalid("glyph is required")
        } ?: return invalid("glyph is required")

        val primary = when (
            val result = readText(payload, "primary", MAX_PRIMARY_CHARS, required = true)
        ) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> return invalid("primary is required")
        } ?: return invalid("primary is required")

        val secondary = when (
            val result = readText(payload, "secondary", MAX_SECONDARY_CHARS)
        ) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val progress = when (val result = readProgress(payload, "progress")) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val eta = when (val result = readText(payload, "eta", MAX_ETA_CHARS)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val detail = when (val result = readDetail(payload, "detail")) {
            is ReadResult.Present -> result.value.orEmpty()
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> emptyList()
        }
        val actions = when (val result = readActions(payload, "actions")) {
            is ReadResult.Present -> result.value.orEmpty()
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> emptyList()
        }
        val maxDurationMs = when (val result = readMaxDuration(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val wakeDisplay = when (val value = payload.opt("wakeDisplay")) {
            null -> false
            is Boolean -> value
            else -> return invalid("wakeDisplay must be a boolean")
        }
        val badge = when (val result = readText(payload, "badge", MAX_BADGE_CHARS)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val measure = when (val result = readText(payload, "measure", MAX_MEASURE_CHARS)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val track = when (val result = readTrack(payload, "track")) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        if (payload.has("significant")) return invalid("significant is update-only")
        if (payload.has("tone")) return invalid("tone is update-only")

        return ActivitySurfaceValidationResult.Valid(
            ActivitySurfaceContent(
                glyph = glyph,
                primary = primary,
                secondary = secondary,
                progress = progress,
                eta = eta,
                detail = detail,
                actions = actions,
                maxDurationMs = maxDurationMs,
                wakeDisplay = wakeDisplay,
                badge = badge,
                measure = measure,
                track = track,
            ),
        )
    }

    fun validateUpdate(payload: JSONObject): ActivitySurfacePatchResult {
        if (payload.has("kind") && payload.opt("kind") != KIND) {
            return patchInvalid("kind must be activity")
        }

        val glyph = when (val result = readGlyph(payload, "glyph")) {
            is ReadResult.Present -> ActivityField(
                result.value ?: return patchInvalid("glyph is required"),
            )
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val primary = when (val result = readText(payload, "primary", MAX_PRIMARY_CHARS)) {
            is ReadResult.Present -> ActivityField(
                result.value ?: return patchInvalid("primary must contain text"),
            )
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val secondary = when (
            val result = readText(payload, "secondary", MAX_SECONDARY_CHARS)
        ) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val progress = when (val result = readProgress(payload, "progress")) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val eta = when (val result = readText(payload, "eta", MAX_ETA_CHARS)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val detail = when (val result = readDetail(payload, "detail")) {
            is ReadResult.Present -> ActivityField(result.value.orEmpty())
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val actions = when (val result = readActions(payload, "actions")) {
            is ReadResult.Present -> ActivityField(result.value.orEmpty())
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val badge = when (val result = readText(payload, "badge", MAX_BADGE_CHARS)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val measure = when (val result = readText(payload, "measure", MAX_MEASURE_CHARS)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val track = when (val result = readTrack(payload, "track")) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val significant = when (val value = payload.opt("significant")) {
            null -> false
            is Boolean -> value
            else -> return patchInvalid("significant must be a boolean")
        }
        val urgent = when (val value = payload.opt("tone")) {
            null -> false
            TONE_URGENT -> true
            else -> return patchInvalid("tone must be $TONE_URGENT")
        }
        if (urgent && !significant) return patchInvalid("urgent tone requires significant")

        return ActivitySurfacePatchResult.Valid(
            ActivitySurfacePatch(
                glyph = glyph,
                primary = primary,
                secondary = secondary,
                progress = progress,
                eta = eta,
                detail = detail,
                actions = actions,
                badge = badge,
                measure = measure,
                track = track,
                significant = significant,
                urgent = urgent,
            ),
        )
    }

    /** Full state for start, canonical storage, and reconnect resend. */
    fun toPayload(surfaceId: String, content: ActivitySurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("glyph", content.glyph)
        .put("primary", content.primary)
        .put("detail", detailJson(content.detail))
        .put("actions", actionsJson(content.actions))
        .apply {
            content.secondary?.let { put("secondary", it) }
            content.progress?.let { put("progress", progressJson(it)) }
            content.eta?.let { put("eta", it) }
            content.maxDurationMs?.let {
                put("maxDurationMs", it.coerceIn(MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS))
            }
            if (content.wakeDisplay) put("wakeDisplay", true)
            content.badge?.let { put("badge", it) }
            content.measure?.let { put("measure", it) }
            content.track?.let { put("track", trackJson(it)) }
        }

    /**
     * A full-object SDK update. Nullable fields are explicit nulls and lists are
     * always present, so the object can clear previous optional state.
     */
    fun toUpdatePayload(
        surfaceId: String,
        content: ActivitySurfaceContent,
        significant: Boolean,
        urgent: Boolean = false,
    ): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("glyph", content.glyph)
        .put("primary", content.primary)
        .put("secondary", content.secondary ?: JSONObject.NULL)
        .put("progress", content.progress?.let(::progressJson) ?: JSONObject.NULL)
        .put("eta", content.eta ?: JSONObject.NULL)
        .put("detail", detailJson(content.detail))
        .put("actions", actionsJson(content.actions))
        .put("badge", content.badge ?: JSONObject.NULL)
        .put("measure", content.measure ?: JSONObject.NULL)
        .put("track", content.track?.let(::trackJson) ?: JSONObject.NULL)
        .apply {
            if (significant) put("significant", true)
            if (urgent) put("tone", TONE_URGENT)
        }

    fun actionPayload(surfaceId: String, actionId: String): JSONObject = JSONObject()
        .put("activityId", surfaceId)
        .put("id", actionId)

    fun closedPayload(surfaceId: String, reason: ActivityCloseReason): JSONObject = JSONObject()
        .put("activityId", surfaceId)
        .put("reason", reason.wireValue)

    private sealed interface ReadResult<out T> {
        data object Absent : ReadResult<Nothing>
        data class Present<T>(val value: T) : ReadResult<T>
        data class Invalid(val reason: String) : ReadResult<Nothing>
    }

    private fun readGlyph(
        payload: JSONObject,
        key: String,
        required: Boolean = false,
    ): ReadResult<String?> {
        if (!payload.has(key)) {
            return if (required) ReadResult.Invalid("$key is required") else ReadResult.Absent
        }
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) {
            return if (required) ReadResult.Invalid("$key must be a string") else ReadResult.Present(null)
        }
        val glyph = (raw as? String)?.trim()
            ?: return ReadResult.Invalid("$key must be a string")
        if (!GlyphContract.isWellFormedName(glyph)) {
            return ReadResult.Invalid("$key is invalid")
        }
        return ReadResult.Present(glyph)
    }

    private fun readText(
        payload: JSONObject,
        key: String,
        maxChars: Int,
        required: Boolean = false,
    ): ReadResult<String?> {
        if (!payload.has(key)) {
            return if (required) ReadResult.Invalid("$key is required") else ReadResult.Absent
        }
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) {
            return if (required) ReadResult.Invalid("$key must be a string") else ReadResult.Present(null)
        }
        val text = (raw as? String)?.trim()
            ?: return ReadResult.Invalid("$key must be a string")
        if (text.length > maxChars) {
            return ReadResult.Invalid("$key exceeds $maxChars characters")
        }
        if (required && text.isEmpty()) {
            return ReadResult.Invalid("$key must contain text")
        }
        return ReadResult.Present(text.takeIf { it.isNotEmpty() })
    }

    private fun readProgress(payload: JSONObject, key: String): ReadResult<ActivityProgress?> {
        if (!payload.has(key)) return ReadResult.Absent
        return when (val raw = payload.opt(key)) {
            JSONObject.NULL -> ReadResult.Present(null)
            "indeterminate" -> ReadResult.Present(ActivityProgress.Indeterminate)
            is Number -> {
                val value = integerLong(raw)
                if (value == null || value !in 0L..100L) {
                    ReadResult.Invalid("$key must be an integer from 0 to 100 or indeterminate")
                } else {
                    ReadResult.Present(ActivityProgress.Percent(value.toInt()))
                }
            }
            else -> ReadResult.Invalid("$key must be an integer from 0 to 100 or indeterminate")
        }
    }

    private fun readDetail(payload: JSONObject, key: String): ReadResult<List<String>?> {
        if (!payload.has(key)) return ReadResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        val array = raw as? JSONArray ?: return ReadResult.Invalid("$key must be an array")
        if (array.length() > MAX_DETAIL_LINES) {
            return ReadResult.Invalid("$key exceeds $MAX_DETAIL_LINES entries")
        }
        val lines = buildList {
            for (index in 0 until array.length()) {
                val line = (array.opt(index) as? String)?.trim()
                    ?: return ReadResult.Invalid("$key must contain strings")
                if (line.length > MAX_DETAIL_CHARS) {
                    return ReadResult.Invalid("$key entry exceeds $MAX_DETAIL_CHARS characters")
                }
                add(line)
            }
        }
        return ReadResult.Present(lines)
    }

    private fun readActions(payload: JSONObject, key: String): ReadResult<List<ActivityAction>?> {
        if (!payload.has(key)) return ReadResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        val array = raw as? JSONArray ?: return ReadResult.Invalid("$key must be an array")
        if (array.length() > MAX_ACTIONS) {
            return ReadResult.Invalid("$key exceeds $MAX_ACTIONS entries")
        }
        val actions = buildList {
            for (index in 0 until array.length()) {
                val entry = array.opt(index) as? JSONObject
                    ?: return ReadResult.Invalid("$key must contain objects")
                val id = (entry.opt("id") as? String)?.trim()
                    ?: return ReadResult.Invalid("action id must be a string")
                if (id.isEmpty()) return ReadResult.Invalid("action id must contain text")
                val glyph = (entry.opt("glyph") as? String)?.trim()
                    ?: return ReadResult.Invalid("action glyph must be a string")
                if (!GlyphContract.isWellFormedName(glyph)) {
                    return ReadResult.Invalid("action glyph is invalid")
                }
                val label = (entry.opt("label") as? String)?.trim()
                    ?: return ReadResult.Invalid("action label must be a string")
                if (label.isEmpty()) return ReadResult.Invalid("action label must contain text")
                add(ActivityAction(id = id, glyph = glyph, label = label))
            }
        }
        return ReadResult.Present(actions)
    }

    private fun readTrack(payload: JSONObject, key: String): ReadResult<ActivityTrack?> {
        if (!payload.has(key)) return ReadResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        val track = raw as? JSONObject ?: return ReadResult.Invalid("$key must be an object")
        val count = (track.opt("count") as? Number)?.let(::integerLong)
            ?.takeIf { it in MIN_TRACK_COUNT..MAX_TRACK_COUNT }
            ?: return ReadResult.Invalid(
                "$key count must be an integer from $MIN_TRACK_COUNT to $MAX_TRACK_COUNT",
            )
        val at = (track.opt("at") as? Number)?.let(::integerLong)
            ?.takeIf { it in 0 until count }
            ?: return ReadResult.Invalid("$key at must index one of its positions")
        val target = (track.opt("target") as? Number)?.let(::integerLong)
            ?.takeIf { it in at until count }
            ?: return ReadResult.Invalid("$key target must be a position at or after at")
        val label = when (val result = readText(track, "label", MAX_TRACK_LABEL_CHARS)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return ReadResult.Invalid("$key ${result.reason}")
            ReadResult.Absent -> null
        }
        return ReadResult.Present(
            ActivityTrack(
                count = count.toInt(),
                at = at.toInt(),
                target = target.toInt(),
                label = label,
            ),
        )
    }

    private fun readMaxDuration(payload: JSONObject): ReadResult<Long?> {
        if (!payload.has("maxDurationMs")) return ReadResult.Absent
        return when (val raw = payload.opt("maxDurationMs")) {
            JSONObject.NULL -> ReadResult.Present(null)
            is Number -> {
                val duration = integerLong(raw)
                    ?: return ReadResult.Invalid("maxDurationMs must be an integer")
                ReadResult.Present(duration.coerceIn(MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS))
            }
            else -> ReadResult.Invalid("maxDurationMs must be an integer")
        }
    }

    private fun progressJson(progress: ActivityProgress): Any = when (progress) {
        is ActivityProgress.Percent -> progress.value
        ActivityProgress.Indeterminate -> "indeterminate"
    }

    private fun trackJson(track: ActivityTrack): JSONObject = JSONObject()
        .put("count", track.count)
        .put("at", track.at)
        .put("target", track.target)
        .apply { track.label?.let { put("label", it) } }

    private fun detailJson(detail: List<String>): JSONArray =
        JSONArray().apply { detail.forEach(::put) }

    private fun actionsJson(actions: List<ActivityAction>): JSONArray = JSONArray().apply {
        actions.forEach { action ->
            put(
                JSONObject()
                    .put("id", action.id)
                    .put("glyph", action.glyph)
                    .put("label", action.label),
            )
        }
    }

    private fun integerLong(number: Number): Long? {
        val double = number.toDouble()
        val long = number.toLong()
        return long.takeIf { double.isFinite() && double == long.toDouble() }
    }

    private fun invalid(reason: String) = ActivitySurfaceValidationResult.Invalid(reason)

    private fun patchInvalid(reason: String) = ActivitySurfacePatchResult.Invalid(reason)
}
