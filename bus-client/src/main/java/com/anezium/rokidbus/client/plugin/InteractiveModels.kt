package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivityCommandStatus
import com.anezium.rokidbus.shared.ActivityPersistence
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject

/**
 * What an action means to the hub: an explicit desired state, never a toggle. Attach one to a page
 * or activity action so the hub can draw it, check it and, only when the user approved that family
 * for this plugin, run it on one tap. Needs [NexusPluginClient.supportsLauncherInteractive]; without
 * it the type is dropped and the action is an ordinary confirmed one.
 */
sealed interface NexusActionType {
    /** Set one checklist entry to [checked]. Family `checklist`. */
    data class SetChecked(val checked: Boolean) : NexusActionType

    /** Family `media`, like play, pause and next. */
    data object MediaPrevious : NexusActionType
    data object MediaPlay : NexusActionType
    data object MediaPause : NexusActionType
    data object MediaNext : NexusActionType

    /** A conversation's send. Only the hub composer's deliberate Send reaches it; never quick. */
    data object ComposeSend : NexusActionType

    val isMedia: Boolean
        get() = this == MediaPrevious || this == MediaPlay || this == MediaPause || this == MediaNext

    /** Whether the user may approve this type to run without a confirmation. */
    val quickEligible: Boolean
        get() = this is SetChecked || isMedia
}

internal fun NexusActionType.toContract(): PageActionType = when (this) {
    is NexusActionType.SetChecked -> PageActionType.SetChecked(checked)
    NexusActionType.MediaPrevious -> PageActionType.MediaPrevious
    NexusActionType.MediaPlay -> PageActionType.MediaPlay
    NexusActionType.MediaPause -> PageActionType.MediaPause
    NexusActionType.MediaNext -> PageActionType.MediaNext
    NexusActionType.ComposeSend -> PageActionType.ComposeSend
}

internal fun PageActionType.toNexus(): NexusActionType = when (this) {
    is PageActionType.SetChecked -> NexusActionType.SetChecked(checked)
    PageActionType.MediaPrevious -> NexusActionType.MediaPrevious
    PageActionType.MediaPlay -> NexusActionType.MediaPlay
    PageActionType.MediaPause -> NexusActionType.MediaPause
    PageActionType.MediaNext -> NexusActionType.MediaNext
    PageActionType.ComposeSend -> NexusActionType.ComposeSend
}

/**
 * One of this plugin's own pages, with optional [params] of at most 2048 bytes. Opening it is
 * consultation inside the Nexus session: the page request arrives with these params, and the
 * plugin is neither opened nor closed.
 */
class NexusPageDestination(val pageId: String, params: JSONObject? = null) {
    private val paramsJson: String? = params?.let { PageSurfaceContract.boundedParams(it) ?: throw IllegalArgumentException("params out of bounds") }

    init {
        val owner = PageTemplateContract.namespaceOf(pageId)
        require(owner != null && owner != PageTemplateContract.LOCAL_NAMESPACE) { "pageId must be a provider page" }
    }

    /** A copy of the params, or null. */
    val params: JSONObject?
        get() = paramsJson?.let(::JSONObject)

    internal fun toContract(): PageDestination = PageDestination(pageId, paramsJson)

    override fun equals(other: Any?): Boolean =
        other is NexusPageDestination && other.pageId == pageId && other.paramsJson == paramsJson

    override fun hashCode(): Int = 31 * pageId.hashCode() + (paramsJson?.hashCode() ?: 0)

    override fun toString(): String = "NexusPageDestination(pageId=$pageId, params=$paramsJson)"
}

/** What the first tap on this plugin's activity card does. */
enum class NexusActivityActivation(internal val contract: ActivityActivation) {
    /** Open [NexusActivity.destination] directly. */
    DESTINATION(ActivityActivation.DESTINATION),

    /** Focus the activity's commands, starting at [NexusActivity.defaultActionId]; never run one. */
    COMMANDS(ActivityActivation.COMMANDS),
}

enum class NexusActivityPersistence(internal val contract: ActivityPersistence) {
    /** Ends when this plugin's registration does, as every activity always has. */
    SESSION(ActivityPersistence.SESSION),

    /**
     * A standing entry the user enabled in this plugin ("Show in activities"). When the plugin
     * disconnects, Nexus keeps it as an inert offline snapshot; no service needs to stay alive.
     */
    ONGOING(ActivityPersistence.ONGOING),
}

/**
 * The wearer ran one of this plugin's activity commands on the revision they were shown. [seq] is
 * that revision; [typed] is the type this plugin declared on the action, as the hub holds it.
 * Answer with a [NexusActivityCommandResult], or null to send none: the glasses then show the
 * command as unconfirmed. Nothing is ever retried.
 */
class NexusActivityCommand internal constructor(
    val activityId: String,
    val actionId: String,
    val invocationId: String,
    val seq: Long,
    val typed: NexusActionType?,
)

enum class NexusActivityCommandStatus(internal val contract: ActivityCommandStatus) {
    DONE(ActivityCommandStatus.DONE),
    REJECTED(ActivityCommandStatus.REJECTED),

    /** The command was for a state that no longer holds; nothing was done. */
    STALE(ActivityCommandStatus.STALE),
}

/** The outcome of one [NexusActivityCommand]; [message] is at most 64 characters. */
class NexusActivityCommandResult private constructor(
    val status: NexusActivityCommandStatus,
    val message: String?,
) {
    init {
        require(message == null || message.length <= ActivitySurfaceContract.MAX_RESULT_MESSAGE_CHARS)
    }

    companion object {
        fun done(message: String? = null) = NexusActivityCommandResult(NexusActivityCommandStatus.DONE, message)

        fun rejected(message: String? = null) = NexusActivityCommandResult(NexusActivityCommandStatus.REJECTED, message)

        fun stale(message: String? = null) = NexusActivityCommandResult(NexusActivityCommandStatus.STALE, message)
    }
}
