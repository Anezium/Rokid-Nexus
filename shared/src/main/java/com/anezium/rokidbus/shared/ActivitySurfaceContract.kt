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

/**
 * One one-shot command in an activity's platform-rendered action row.
 *
 * [typed] is launcher-bundle meaning (`media.*` only on an activity). [hubQuick] is the phone's
 * stamp that the user approved this action's family; it is only read from a payload the phone
 * forwarded, never from a plugin.
 */
data class ActivityAction(
    val id: String,
    val glyph: String,
    val label: String,
    val typed: PageActionType? = null,
    val hubQuick: Boolean = false,
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

/** What the first activation of an activity card does. */
enum class ActivityActivation(val wireValue: String) {
    /** Open the activity's destination page directly. */
    DESTINATION("destination"),

    /** Focus the inline commands; never execute on that first tap. */
    COMMANDS("commands"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityActivation? = entries.firstOrNull { it.wireValue == value }
    }
}

/** How long an activity stands. */
enum class ActivityPersistence(val wireValue: String) {
    /** Ends with its owner's registration, as every v1 activity does. */
    SESSION("session"),

    /**
     * A standing entry the user enabled in the plugin ("Show in activities"): when its owner
     * disconnects it stays as an inert offline snapshot instead of ending.
     */
    ONGOING("ongoing"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityPersistence? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Hub-stamped liveness of a collection entry. */
enum class ActivityState(val wireValue: String) {
    LIVE("live"),

    /** The owner is gone: content and destination kept, actions dropped, never actionable. */
    OFFLINE("offline"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityState? = entries.firstOrNull { it.wireValue == value }
    }
}

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
    /** Launcher bundle: one of the owner's own pages, opened directly from the card. */
    val destination: PageDestination? = null,
    /** Launcher bundle: an explicit first activation; null applies [effectiveActivation]'s default. */
    val activation: ActivityActivation? = null,
    /** Launcher bundle: the command focused first; null means the first action. */
    val defaultActionId: String? = null,
    val persistence: ActivityPersistence = ActivityPersistence.SESSION,
    /**
     * Launcher bundle: the longer form of [primary] for the surfaces with room for it, such as a
     * whole track title; where only [primary] fits, [primary] is drawn.
     */
    val headline: String? = null,
    /**
     * Launcher bundle: [secondary] uncut, for the lines that scroll what is wider than their
     * room; where it is absent, [secondary] is drawn.
     */
    val wholeSecondary: String? = null,
    /** Launcher bundle: [detail] uncut, drawn instead of it where a line scrolls. */
    val wholeDetail: List<String> = emptyList(),
    val artwork: LauncherArtwork? = null,
    /** Hub stamp: null on anything a plugin sent. */
    val state: ActivityState? = null,
    /** Hub stamp: the user pinned this owner's entry on the phone. */
    val userPinned: Boolean = false,
    /** Hub stamp: wall-clock time the content was last confirmed live. */
    val knownAtWallMs: Long? = null,
    /** Stable opaque event identity; a changed value may briefly reveal a user-enabled island. */
    val eventKey: String? = null,
) {
    /**
     * What the first activation does: the explicit [activation], else the destination when
     * there is one, else the commands when there are any. Null means the card opens the hub's
     * summary of the activity.
     */
    val effectiveActivation: ActivityActivation?
        get() = activation ?: when {
            destination != null -> ActivityActivation.DESTINATION
            actions.isNotEmpty() -> ActivityActivation.COMMANDS
            else -> null
        }

    /** The command focused first in commands mode. */
    val effectiveDefaultActionId: String?
        get() = defaultActionId?.takeIf { id -> actions.any { it.id == id } } ?: actions.firstOrNull()?.id

    /** Whether this content uses a launcher-bundle field other than the headline, which tightens the action-id rule. */
    val usesInteractiveFields: Boolean
        get() = destination != null || activation != null || defaultActionId != null ||
            persistence == ActivityPersistence.ONGOING || actions.any { it.typed != null }

    val isOffline: Boolean get() = state == ActivityState.OFFLINE
}

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
    val destination: ActivityField<PageDestination?>? = null,
    val activation: ActivityField<ActivityActivation?>? = null,
    val defaultActionId: ActivityField<String?>? = null,
    val persistence: ActivityField<ActivityPersistence>? = null,
    val headline: ActivityField<String?>? = null,
    val wholeSecondary: ActivityField<String?>? = null,
    val wholeDetail: ActivityField<List<String>>? = null,
    val artwork: ActivityField<LauncherArtwork?>? = null,
    val state: ActivityField<ActivityState?>? = null,
    val userPinned: ActivityField<Boolean>? = null,
    val knownAtWallMs: ActivityField<Long?>? = null,
    val eventKey: ActivityField<String?>? = null,
) {
    fun applyTo(content: ActivitySurfaceContent): ActivitySurfaceContent = content.copy(
        eventKey = if (eventKey != null) eventKey.value else content.eventKey,
        artwork = if (artwork != null) artwork.value else content.artwork,
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
        destination = if (destination != null) destination.value else content.destination,
        activation = if (activation != null) activation.value else content.activation,
        defaultActionId = if (defaultActionId != null) defaultActionId.value else content.defaultActionId,
        persistence = if (persistence != null) persistence.value else content.persistence,
        headline = if (headline != null) headline.value else content.headline,
        // A short form changed without its whole form must not keep drawing the old one.
        wholeSecondary = when {
            wholeSecondary != null -> wholeSecondary.value
            secondary != null -> null
            else -> content.wholeSecondary
        },
        wholeDetail = when {
            wholeDetail != null -> wholeDetail.value
            detail != null -> emptyList()
            else -> content.wholeDetail
        },
        state = if (state != null) state.value else content.state,
        userPinned = if (userPinned != null) userPinned.value else content.userPinned,
        knownAtWallMs = if (knownAtWallMs != null) knownAtWallMs.value else content.knownAtWallMs,
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

/** The phone's own stamps on an activity it forwards to negotiated glasses. */
data class ActivityHubStamps(
    val state: ActivityState = ActivityState.LIVE,
    val userPinned: Boolean = false,
    val knownAtWallMs: Long? = null,
)

/** How the wearer reached an activity command. */
enum class ActivityCommandMode(val wireValue: String) {
    CONFIRMED("confirmed"),
    QUICK("quick"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityCommandMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One activity command in its launcher-bundle shape. From the glasses it carries [mode] and no
 * [typed]; to the owner the phone adds [typed] from its own canonical copy and leaves out [mode].
 */
data class ActivityCommand(
    val activityId: String,
    val actionId: String,
    val invocationId: String,
    val seq: Long,
    val mode: ActivityCommandMode? = null,
    val typed: PageActionType? = null,
)

enum class ActivityCommandStatus(val wireValue: String) {
    DONE("done"),
    REJECTED("rejected"),
    STALE("stale"),
    ;

    companion object {
        fun fromWireValue(value: String): ActivityCommandStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The owner's answer to one [ActivityCommand]. A lost one is unconfirmed, never resent. */
data class ActivityCommandResult(
    val activityId: String,
    val invocationId: String,
    val status: ActivityCommandStatus,
    val message: String? = null,
)

/** One `/activity/geometry` revision; latest wins, data plane only. */
data class ActivityGeometryUpdate(val revision: Long, val geometry: RouteGeometry)

/** One `/activity/heading` sample; [deg] in 0 until 360. */
data class ActivityHeading(val deg: Double, val atWallMs: Long, val accuracyDeg: Double? = null)

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
    const val MAX_HEADLINE_CHARS = 40
    const val MAX_EVENT_KEY_CHARS = 64
    const val MAX_SECONDARY_CHARS = 28
    const val MAX_ETA_CHARS = 8
    const val MAX_DETAIL_LINES = 2
    const val MAX_DETAIL_CHARS = 32
    /** Launcher bundle: a whole secondary or detail line, which the glasses scroll. */
    const val MAX_WHOLE_CHARS = 120
    const val MAX_ACTIONS = 3

    /** The v1 capacity, kept as the floor every pair without the launcher bundle runs. */
    const val MAX_ACTIVE_ACTIVITIES = 2

    /** Corner slots and islands; unchanged by the launcher bundle. */
    const val MAX_AMBIENT_ACTIVITIES = 2

    /** Logical collection entries, one per owner, once the launcher bundle is negotiated. */
    const val MAX_COLLECTION_ACTIVITIES = 8

    /** Offline snapshots the phone persists; never more than the collection holds. */
    const val MAX_OFFLINE_SNAPSHOTS = MAX_COLLECTION_ACTIVITIES
    const val MAX_BADGE_CHARS = 5
    const val MAX_MEASURE_CHARS = 8
    const val MIN_TRACK_COUNT = 2
    const val MAX_TRACK_COUNT = 12
    const val MAX_TRACK_LABEL_CHARS = 20
    const val TONE_URGENT = "urgent"

    const val MIN_MAX_DURATION_MS = 60_000L
    const val MAX_MAX_DURATION_MS = 43_200_000L
    const val MAX_UPDATES_PER_SECOND = 4

    /** How long the phone waits for an owner's `/activity/result` before the command is unconfirmed. */
    const val COMMAND_DEADLINE_MS = 8_000L
    const val MAX_RESULT_MESSAGE_CHARS = 64

    /** Heading samples accepted per owner and second; the rest are dropped. */
    const val MAX_HEADINGS_PER_SECOND = 5

    /** Glasses treat a heading older than this as absent. */
    const val HEADING_STALE_MS = 3_000L
    const val MAX_HEADING_ACCURACY_DEG = 180.0

    const val ERROR_INVALID_ACTIVITY = "INVALID_ACTIVITY"
    const val ERROR_ACTIVITY_RATE_LIMITED = "ACTIVITY_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    /** The user turned this plugin's activity off on the phone; its starts are refused. */
    const val ERROR_ACTIVITY_DISABLED = "ACTIVITY_DISABLED"

    private const val FIELD_DESTINATION = "destination"
    private const val FIELD_ACTIVATION = "activation"
    private const val FIELD_DEFAULT_ACTION_ID = "defaultActionId"
    private const val FIELD_PERSISTENCE = "persistence"
    private const val FIELD_HEADLINE = "headline"
    private const val FIELD_WHOLE_SECONDARY = "wholeSecondary"
    private const val FIELD_WHOLE_DETAIL = "wholeDetail"
    private const val FIELD_STATE = "state"
    private const val FIELD_USER_PINNED = "userPinned"
    private const val FIELD_KNOWN_AT_WALL_MS = "knownAtWallMs"

    /** Plugin-side launcher fields on the activity object. */
    private val launcherFields = listOf(FIELD_DESTINATION, FIELD_ACTIVATION, FIELD_DEFAULT_ACTION_ID, FIELD_PERSISTENCE, FIELD_HEADLINE,
        FIELD_WHOLE_SECONDARY, FIELD_WHOLE_DETAIL, "artwork", "eventKey",
    )

    /** Hub stamps on the activity object; per action there is also `hubQuick`. */
    private val stampFields = listOf(FIELD_STATE, FIELD_USER_PINNED, FIELD_KNOWN_AT_WALL_MS)

    /** The collection a pair holds: [MAX_COLLECTION_ACTIVITIES] with the launcher bundle, else the v1 two. */
    fun effectiveCollectionCapacity(launcherInteractiveVersion: Int): Int =
        if (LauncherInteractiveContract.isActive(launcherInteractiveVersion)) MAX_COLLECTION_ACTIVITIES else MAX_ACTIVE_ACTIVITIES

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

    /**
     * [hubStamps] reads the phone's stamps (`state`, `userPinned`, `knownAtWallMs`, per-action
     * `hubQuick`); leave it false for anything a plugin sent, so a plugin can never stamp itself.
     */
    fun validateStart(payload: JSONObject, hubStamps: Boolean = false): ActivitySurfaceValidationResult {
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
        val actions = when (val result = readActions(payload, "actions", hubStamps)) {
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

        val destination = when (val result = readDestination(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val activation = when (val result = readEnum(payload, FIELD_ACTIVATION, ActivityActivation::fromWireValue)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val defaultActionId = when (val result = readActionId(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val persistence = when (val result = readEnum(payload, FIELD_PERSISTENCE, ActivityPersistence::fromWireValue)) {
            is ReadResult.Present -> result.value ?: ActivityPersistence.SESSION
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> ActivityPersistence.SESSION
        }
        val eventKey = when (val result = readEventKey(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val headline = when (val result = readHeadline(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val wholeSecondary = when (val result = readWholeLine(payload)) {
            is ReadResult.Present -> result.value
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> null
        }
        val wholeDetail = when (val result = readDetail(payload, FIELD_WHOLE_DETAIL, MAX_WHOLE_CHARS)) {
            is ReadResult.Present -> result.value.orEmpty()
            is ReadResult.Invalid -> return invalid(result.reason)
            ReadResult.Absent -> emptyList()
        }
        val stamps = if (hubStamps) {
            when (val result = readStamps(payload)) {
                is ReadResult.Present -> result.value
                is ReadResult.Invalid -> return invalid(result.reason)
                ReadResult.Absent -> StampRead()
            }
        } else {
            StampRead()
        }

        val artwork = try { LauncherArtwork.parse(payload.opt("artwork")) }
        catch (_: IllegalArgumentException) { return invalid("Invalid artwork") }
        val content = ActivitySurfaceContent(
            artwork = artwork,
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
            destination = destination,
            activation = activation,
            defaultActionId = defaultActionId,
            persistence = persistence,
            headline = headline,
            eventKey = eventKey,
            wholeSecondary = wholeSecondary,
            wholeDetail = wholeDetail,
            state = stamps.state?.value,
            userPinned = stamps.userPinned?.value ?: false,
            knownAtWallMs = stamps.knownAtWallMs?.value,
        )
        interactiveProblem(content)?.let { return invalid(it) }
        return ActivitySurfaceValidationResult.Valid(content)
    }

    /**
     * Validates one update on its own. Rules that tie launcher fields to the actions a previous
     * message set are checked here only when the update carries both; the receiver checks
     * [interactiveProblem] on the patched content, which is the complete check.
     */
    fun validateUpdate(payload: JSONObject, hubStamps: Boolean = false): ActivitySurfacePatchResult {
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
        val actions = when (val result = readActions(payload, "actions", hubStamps)) {
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

        val destination = when (val result = readDestination(payload)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val activation = when (val result = readEnum(payload, FIELD_ACTIVATION, ActivityActivation::fromWireValue)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val defaultActionId = when (val result = readActionId(payload)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val persistence = when (val result = readEnum(payload, FIELD_PERSISTENCE, ActivityPersistence::fromWireValue)) {
            is ReadResult.Present -> ActivityField(result.value ?: ActivityPersistence.SESSION)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val eventKey = when (val result = readEventKey(payload)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val headline = when (val result = readHeadline(payload)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val wholeSecondary = when (val result = readWholeLine(payload)) {
            is ReadResult.Present -> ActivityField(result.value)
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val wholeDetail = when (val result = readDetail(payload, FIELD_WHOLE_DETAIL, MAX_WHOLE_CHARS)) {
            is ReadResult.Present -> ActivityField(result.value.orEmpty())
            is ReadResult.Invalid -> return patchInvalid(result.reason)
            ReadResult.Absent -> null
        }
        val stamps = if (hubStamps) {
            when (val result = readStamps(payload)) {
                is ReadResult.Present -> result.value
                is ReadResult.Invalid -> return patchInvalid(result.reason)
                ReadResult.Absent -> StampRead()
            }
        } else {
            StampRead()
        }

        val artwork = try {
            if (payload.has("artwork")) ActivityField(LauncherArtwork.parse(payload.opt("artwork"))) else null
        } catch (_: IllegalArgumentException) { return patchInvalid("Invalid artwork") }
        val patch = ActivitySurfacePatch(
            artwork = artwork,
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
            destination = destination,
            activation = activation,
            defaultActionId = defaultActionId,
            persistence = persistence,
            headline = headline,
            eventKey = eventKey,
            wholeSecondary = wholeSecondary,
            wholeDetail = wholeDetail,
            state = stamps.state,
            userPinned = stamps.userPinned,
            knownAtWallMs = stamps.knownAtWallMs,
        )
        selfContainedProblem(patch)?.let { return patchInvalid(it) }
        return ActivitySurfacePatchResult.Valid(patch)
    }

    /**
     * The launcher rules on whole content, null when it keeps them: an explicit activation has
     * its prerequisite, the default action is one of the actions, and once any launcher field is
     * used every action id is a safe id and unique. Without launcher fields the v1 permissive
     * action rules stand. An offline snapshot has no actions, so its commands rules do not apply.
     */
    fun interactiveProblem(content: ActivitySurfaceContent): String? {
        if (content.activation == ActivityActivation.DESTINATION && content.destination == null) {
            return "activation destination needs a destination"
        }
        if (!content.isOffline) {
            if (content.activation == ActivityActivation.COMMANDS && content.actions.isEmpty()) {
                return "activation commands needs an action"
            }
            val defaultActionId = content.defaultActionId
            if (defaultActionId != null && content.actions.none { it.id == defaultActionId }) {
                return "defaultActionId must name one of the actions"
            }
        }
        if (content.usesInteractiveFields) idRuleProblem(content.actions)?.let { return it }
        return null
    }

    /** Whether [content]'s destination, if any, is one of [ownerPluginId]'s own pages. */
    fun destinationAllowed(content: ActivitySurfaceContent, ownerPluginId: String): Boolean =
        content.destination?.let { PageTemplateContract.isDestinationOf(it, ownerPluginId) } ?: true

    /** Whether a patch's destination, if it sets one, is one of [ownerPluginId]'s own pages. */
    fun destinationAllowed(patch: ActivitySurfacePatch, ownerPluginId: String): Boolean =
        patch.destination?.value?.let { PageTemplateContract.isDestinationOf(it, ownerPluginId) } ?: true

    /** The ids of [content]'s actions the phone may stamp quick for a user who approved [approved]. */
    fun quickActionIds(content: ActivitySurfaceContent, approved: Set<QuickActionFamily>): Set<String> =
        if (content.isOffline) emptySet() else content.actions.filter { QuickActionPolicyContract.isQuick(it.typed, approved) }.mapTo(linkedSetOf()) { it.id }

    /**
     * The inert offline form of [content]: what it said, with its destination, and nothing that
     * acts. Every old revision is stale once the phone sends it with a new `seq`.
     */
    fun offlineSnapshot(content: ActivitySurfaceContent, knownAtWallMs: Long?): ActivitySurfaceContent = content.copy(
        actions = emptyList(),
        activation = null,
        defaultActionId = null,
        state = ActivityState.OFFLINE,
        knownAtWallMs = knownAtWallMs ?: content.knownAtWallMs,
    )

    /** A copy without any hub stamp; the phone runs this on everything a plugin sends. */
    fun stripHubStamps(payload: JSONObject): JSONObject = JSONObject(payload.toString()).apply {
        stampFields.forEach { remove(it) }
        optJSONArray("actions")?.let { actions ->
            for (index in 0 until actions.length()) actions.optJSONObject(index)?.remove(QuickActionPolicyContract.FIELD_HUB_QUICK)
        }
    }

    /**
     * [stripHubStamps], then the phone's own [stamps] and `hubQuick: true` on [quickActionIds].
     * Stamps are written in full, so an update re-states them rather than leaving old ones.
     */
    fun withHubStamps(payload: JSONObject, stamps: ActivityHubStamps, quickActionIds: Set<String>): JSONObject =
        stripHubStamps(payload).apply {
            put(FIELD_STATE, stamps.state.wireValue)
            put(FIELD_USER_PINNED, stamps.userPinned)
            put(FIELD_KNOWN_AT_WALL_MS, stamps.knownAtWallMs ?: JSONObject.NULL)
            optJSONArray("actions")?.let { actions ->
                for (index in 0 until actions.length()) {
                    val action = actions.optJSONObject(index) ?: continue
                    if (action.optString("id") in quickActionIds) action.put(QuickActionPolicyContract.FIELD_HUB_QUICK, true)
                }
            }
        }

    /**
     * A copy without anything of the launcher bundle, for glasses that did not negotiate it:
     * destination, activation, default action, persistence, headline, whole lines, every hub stamp, and the actions'
     * `typed` and `hubQuick`. What is left is a v1 (and extras) activity.
     */
    fun stripLauncherFields(payload: JSONObject): JSONObject = stripHubStamps(payload).apply {
        launcherFields.forEach { remove(it) }
        optJSONArray("actions")?.let { actions ->
            for (index in 0 until actions.length()) actions.optJSONObject(index)?.remove(PageActionTypeContract.FIELD_TYPED)
        }
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
            content.artwork?.let { put("artwork", it.toJson()) }
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
            // Launcher fields and stamps only when used, so a v1 activity serializes as it always did.
            content.destination?.let { put(FIELD_DESTINATION, PageTemplateContract.encodeDestination(it)) }
            content.activation?.let { put(FIELD_ACTIVATION, it.wireValue) }
            content.defaultActionId?.let { put(FIELD_DEFAULT_ACTION_ID, it) }
            if (content.persistence != ActivityPersistence.SESSION) put(FIELD_PERSISTENCE, content.persistence.wireValue)
            content.headline?.let { put(FIELD_HEADLINE, it) }
            content.eventKey?.let { put("eventKey", it) }
            content.wholeSecondary?.let { put(FIELD_WHOLE_SECONDARY, it) }
            if (content.wholeDetail.isNotEmpty()) put(FIELD_WHOLE_DETAIL, detailJson(content.wholeDetail))
            content.state?.let { put(FIELD_STATE, it.wireValue) }
            if (content.userPinned) put(FIELD_USER_PINNED, true)
            content.knownAtWallMs?.let { put(FIELD_KNOWN_AT_WALL_MS, it) }
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
        .put("artwork", content.artwork?.toJson() ?: JSONObject.NULL)
        .put("secondary", content.secondary ?: JSONObject.NULL)
        .put("progress", content.progress?.let(::progressJson) ?: JSONObject.NULL)
        .put("eta", content.eta ?: JSONObject.NULL)
        .put("detail", detailJson(content.detail))
        .put("actions", actionsJson(content.actions))
        .put("badge", content.badge ?: JSONObject.NULL)
        .put("measure", content.measure ?: JSONObject.NULL)
        .put("track", content.track?.let(::trackJson) ?: JSONObject.NULL)
        .put(FIELD_DESTINATION, content.destination?.let(PageTemplateContract::encodeDestination) ?: JSONObject.NULL)
        .put(FIELD_ACTIVATION, content.activation?.wireValue ?: JSONObject.NULL)
        .put(FIELD_DEFAULT_ACTION_ID, content.defaultActionId ?: JSONObject.NULL)
        .put(FIELD_PERSISTENCE, content.persistence.wireValue)
        .put(FIELD_HEADLINE, content.headline ?: JSONObject.NULL)
        .put("eventKey", content.eventKey ?: JSONObject.NULL)
        .put(FIELD_WHOLE_SECONDARY, content.wholeSecondary ?: JSONObject.NULL)
        .put(FIELD_WHOLE_DETAIL, detailJson(content.wholeDetail))
        .apply {
            if (significant) put("significant", true)
            if (urgent) put("tone", TONE_URGENT)
            content.state?.let { put(FIELD_STATE, it.wireValue) }
            if (content.userPinned) put(FIELD_USER_PINNED, true)
            content.knownAtWallMs?.let { put(FIELD_KNOWN_AT_WALL_MS, it) }
        }

    /** The v1 action shape, which the launcher bundle keeps for glasses that did not negotiate it. */
    fun actionPayload(surfaceId: String, actionId: String): JSONObject = JSONObject()
        .put("activityId", surfaceId)
        .put("id", actionId)

    fun closedPayload(surfaceId: String, reason: ActivityCloseReason): JSONObject = JSONObject()
        .put("activityId", surfaceId)
        .put("reason", reason.wireValue)

    /** Whether an `/activity/action` payload is the launcher-bundle command shape. */
    fun isCommandPayload(payload: JSONObject): Boolean = payload.has("invocationId")

    /**
     * The command shape of `/activity/action`: from the glasses `{activityId, id, invocationId,
     * seq, mode}`; from the phone to the owner `{activityId, id, invocationId, seq, typed?}`.
     */
    fun commandPayload(command: ActivityCommand): JSONObject = JSONObject()
        .put("activityId", command.activityId)
        .put("id", command.actionId)
        .put("invocationId", command.invocationId)
        .put("seq", command.seq)
        .apply {
            command.mode?.let { put("mode", it.wireValue) }
            command.typed?.let { put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(it)) }
        }

    /**
     * A command, or null when it is malformed. [requireMode] is the phone's reading of what the
     * glasses sent; an owner reads the phone's delivery, which carries no mode.
     */
    fun parseCommand(payload: JSONObject, requireMode: Boolean = false): ActivityCommand? {
        val activityId = (payload.opt("activityId") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val actionId = (payload.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val invocationId = (payload.opt("invocationId") as? String)?.takeIf(PageSurfaceContract::isValidId) ?: return null
        val seq = nonnegativeLong(payload.opt("seq")) ?: return null
        val mode = if (payload.has("mode")) {
            (payload.opt("mode") as? String)?.let(ActivityCommandMode::fromWireValue) ?: return null
        } else {
            if (requireMode) return null
            null
        }
        val typed = when (val read = PageActionTypeContract.readField(payload)) {
            is PageActionTypeRead.Known -> read.type
            PageActionTypeRead.Absent, is PageActionTypeRead.Unknown -> null
            is PageActionTypeRead.Invalid -> return null
        }
        return ActivityCommand(activityId, actionId, invocationId, seq, mode, typed)
    }

    /**
     * Whether the wearer may have reached [action] in [mode]: a quick command only for a
     * quick-eligible type. The user's policy and the canonical revision stay the phone's checks.
     */
    fun modeAllowedFor(mode: ActivityCommandMode, action: ActivityAction): Boolean = when (mode) {
        ActivityCommandMode.CONFIRMED -> true
        ActivityCommandMode.QUICK -> action.typed?.quickEligible == true
    }

    /** `/activity/result`. A plugin also sends its local `surfaceId` so the phone can stamp the owner. */
    fun resultPayload(result: ActivityCommandResult): JSONObject = JSONObject()
        .put("activityId", result.activityId)
        .put("invocationId", result.invocationId)
        .put("status", result.status.wireValue)
        .apply { result.message?.let { put("message", it) } }

    fun parseResult(payload: JSONObject): ActivityCommandResult? {
        val activityId = (payload.opt("activityId") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val invocationId = (payload.opt("invocationId") as? String)?.takeIf(PageSurfaceContract::isValidId) ?: return null
        val status = (payload.opt("status") as? String)?.let(ActivityCommandStatus::fromWireValue) ?: return null
        val message = if (payload.has("message") && payload.opt("message") != JSONObject.NULL) {
            (payload.opt("message") as? String)?.takeIf { it.length <= MAX_RESULT_MESSAGE_CHARS } ?: return null
        } else {
            null
        }
        return ActivityCommandResult(activityId, invocationId, status, message)
    }

    /** `/activity/geometry` as a plugin sends it; the phone adds the owner it authenticated. */
    fun geometryPayload(update: ActivityGeometryUpdate): JSONObject = JSONObject()
        .put("revision", update.revision)
        .put("geometry", RouteGeometryContract.encode(update.geometry))

    fun parseGeometry(payload: JSONObject): ActivityGeometryUpdate? {
        val revision = nonnegativeLong(payload.opt("revision")) ?: return null
        val geometry = RouteGeometryContract.parseOrNull(payload.opt("geometry")) ?: return null
        return ActivityGeometryUpdate(revision, geometry)
    }

    /** `/activity/heading`; [ActivityHeading.atWallMs] is the sample's own wall-clock time. */
    fun headingPayload(heading: ActivityHeading): JSONObject = JSONObject()
        .put("deg", heading.deg)
        .put("atWallMs", heading.atWallMs)
        .apply { heading.accuracyDeg?.let { put("accuracyDeg", it) } }

    fun parseHeading(payload: JSONObject): ActivityHeading? {
        val deg = (payload.opt("deg") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 } ?: return null
        val atWallMs = nonnegativeLong(payload.opt("atWallMs")) ?: return null
        val accuracy = if (payload.has("accuracyDeg") && payload.opt("accuracyDeg") != JSONObject.NULL) {
            (payload.opt("accuracyDeg") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_HEADING_ACCURACY_DEG } ?: return null
        } else {
            null
        }
        return ActivityHeading(deg, atWallMs, accuracy)
    }

    private sealed interface ReadResult<out T> {
        data object Absent : ReadResult<Nothing>
        data class Present<T>(val value: T) : ReadResult<T>
        data class Invalid(val reason: String) : ReadResult<Nothing>
    }

    private data class StampRead(
        val state: ActivityField<ActivityState?>? = null,
        val userPinned: ActivityField<Boolean>? = null,
        val knownAtWallMs: ActivityField<Long?>? = null,
    )

    /** Checks that need nothing outside the patch itself; [interactiveProblem] does the rest. */
    private fun selfContainedProblem(patch: ActivitySurfacePatch): String? {
        val actions = patch.actions?.value
        if (patch.activation?.value == ActivityActivation.DESTINATION && patch.destination != null && patch.destination.value == null) {
            return "activation destination needs a destination"
        }
        if (actions == null) return null
        val offline = patch.state?.value == ActivityState.OFFLINE
        if (!offline && patch.activation?.value == ActivityActivation.COMMANDS && actions.isEmpty()) {
            return "activation commands needs an action"
        }
        val defaultActionId = patch.defaultActionId?.value
        if (!offline && defaultActionId != null && actions.none { it.id == defaultActionId }) {
            return "defaultActionId must name one of the actions"
        }
        val interactive = patch.destination?.value != null || patch.activation?.value != null ||
            defaultActionId != null || patch.persistence?.value == ActivityPersistence.ONGOING ||
            actions.any { it.typed != null }
        return if (interactive) idRuleProblem(actions) else null
    }

    private fun idRuleProblem(actions: List<ActivityAction>): String? {
        if (actions.any { !PageSurfaceContract.isValidId(it.id) }) return "action ids must be safe ids"
        if (actions.map { it.id }.toSet().size != actions.size) return "action ids must be unique"
        return null
    }

    private fun readDestination(payload: JSONObject): ReadResult<PageDestination?> {
        if (!payload.has(FIELD_DESTINATION)) return ReadResult.Absent
        val raw = payload.opt(FIELD_DESTINATION)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        return when (val result = PageTemplateContract.readDestination(raw)) {
            is PageSurfaceValidationResult.Valid -> ReadResult.Present(result.value)
            is PageSurfaceValidationResult.Invalid -> ReadResult.Invalid(result.reason)
        }
    }

    private fun <T> readEnum(payload: JSONObject, key: String, parse: (String) -> T?): ReadResult<T?> {
        if (!payload.has(key)) return ReadResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        val value = (raw as? String)?.let(parse) ?: return ReadResult.Invalid("$key is unknown")
        return ReadResult.Present(value)
    }

    private fun readActionId(payload: JSONObject): ReadResult<String?> {
        if (!payload.has(FIELD_DEFAULT_ACTION_ID)) return ReadResult.Absent
        val raw = payload.opt(FIELD_DEFAULT_ACTION_ID)
        if (raw == JSONObject.NULL) return ReadResult.Present(null)
        val id = (raw as? String)?.takeIf(PageSurfaceContract::isValidId)
            ?: return ReadResult.Invalid("$FIELD_DEFAULT_ACTION_ID must be a safe id")
        return ReadResult.Present(id)
    }

    private fun readStamps(payload: JSONObject): ReadResult<StampRead> {
        val state = if (payload.has(FIELD_STATE)) {
            when (val result = readEnum(payload, FIELD_STATE, ActivityState::fromWireValue)) {
                is ReadResult.Present -> ActivityField(result.value)
                is ReadResult.Invalid -> return result
                ReadResult.Absent -> null
            }
        } else {
            null
        }
        val userPinned = if (payload.has(FIELD_USER_PINNED)) {
            ActivityField(payload.opt(FIELD_USER_PINNED) as? Boolean ?: return ReadResult.Invalid("$FIELD_USER_PINNED must be a boolean"))
        } else {
            null
        }
        val knownAt = if (payload.has(FIELD_KNOWN_AT_WALL_MS)) {
            val raw = payload.opt(FIELD_KNOWN_AT_WALL_MS)
            ActivityField(
                if (raw == JSONObject.NULL) {
                    null
                } else {
                    nonnegativeLong(raw) ?: return ReadResult.Invalid("$FIELD_KNOWN_AT_WALL_MS must be a nonnegative integer")
                },
            )
        } else {
            null
        }
        return ReadResult.Present(StampRead(state, userPinned, knownAt))
    }

    /** The opaque event identity is bounded and printable like a drawn line. */
    private fun readEventKey(payload: JSONObject): ReadResult<String?> {
        val result = readText(payload, "eventKey", MAX_EVENT_KEY_CHARS)
        if (result is ReadResult.Present && result.value?.any(Char::isISOControl) == true) {
            return ReadResult.Invalid("eventKey must be printable")
        }
        return result
    }

    /** [readText] with no control character: a headline is one drawn line. */
    private fun readHeadline(payload: JSONObject): ReadResult<String?> {
        val result = readText(payload, FIELD_HEADLINE, MAX_HEADLINE_CHARS)
        if (result is ReadResult.Present && result.value?.any(Char::isISOControl) == true) {
            return ReadResult.Invalid("$FIELD_HEADLINE must be printable")
        }
        return result
    }

    /** [readText] with no control character: a whole secondary is one drawn line too. */
    private fun readWholeLine(payload: JSONObject): ReadResult<String?> {
        val result = readText(payload, FIELD_WHOLE_SECONDARY, MAX_WHOLE_CHARS)
        if (result is ReadResult.Present && result.value?.any(Char::isISOControl) == true) {
            return ReadResult.Invalid("$FIELD_WHOLE_SECONDARY must be printable")
        }
        return result
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

    private fun readDetail(payload: JSONObject, key: String, maxChars: Int = MAX_DETAIL_CHARS): ReadResult<List<String>?> {
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
                if (line.length > maxChars) {
                    return ReadResult.Invalid("$key entry exceeds $maxChars characters")
                }
                add(line)
            }
        }
        return ReadResult.Present(lines)
    }

    private fun readActions(
        payload: JSONObject,
        key: String,
        hubStamps: Boolean,
    ): ReadResult<List<ActivityAction>?> {
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
                // An activity command is a transport control or nothing typed: any other type,
                // known or not, is refused rather than silently confirmed as something else.
                val typed = when (val read = PageActionTypeContract.readField(entry)) {
                    PageActionTypeRead.Absent -> null
                    is PageActionTypeRead.Known ->
                        read.type.takeIf { it.isMedia } ?: return ReadResult.Invalid("an activity action may only be typed media")
                    is PageActionTypeRead.Unknown -> return ReadResult.Invalid("an activity action may only be typed media")
                    is PageActionTypeRead.Invalid -> return ReadResult.Invalid(read.reason)
                }
                val hubQuick = if (hubStamps && entry.has(QuickActionPolicyContract.FIELD_HUB_QUICK)) {
                    entry.opt(QuickActionPolicyContract.FIELD_HUB_QUICK) as? Boolean
                        ?: return ReadResult.Invalid("hubQuick must be a boolean")
                } else {
                    false
                }
                add(
                    ActivityAction(
                        id = id,
                        glyph = glyph,
                        label = label,
                        typed = typed,
                        hubQuick = hubQuick && typed?.quickEligible == true,
                    ),
                )
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
                    .put("label", action.label)
                    .apply {
                        action.typed?.let { put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(it)) }
                        if (action.hubQuick) put(QuickActionPolicyContract.FIELD_HUB_QUICK, true)
                    },
            )
        }
    }

    private fun nonnegativeLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }

    private fun integerLong(number: Number): Long? {
        val double = number.toDouble()
        val long = number.toLong()
        return long.takeIf { double.isFinite() && double == long.toDouble() }
    }

    private fun invalid(reason: String) = ActivitySurfaceValidationResult.Invalid(reason)

    private fun patchInvalid(reason: String) = ActivitySurfacePatchResult.Invalid(reason)
}
