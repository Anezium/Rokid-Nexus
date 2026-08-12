package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class CanonicalPhoneNotice(
    val ownerPluginId: String,
    val content: NoticeSurfaceContent,
    /**
     * What the glasses are sent for the event that produced this notice: full
     * state for a show, the owner's stamped patch for an update. The glasses
     * validate either with the same reader, and only the patch form can carry a
     * clear -- so only the patch form is honest about an update.
     */
    val payload: JSONObject,
    /** Canonical ordinary deadline; the glasses own the actual expiry timer. */
    val ttlDeadlineMs: Long,
    /** Fixed at the first show, until a page turn replaces both deadlines. */
    val hardDeadlineMs: Long,
    /** The wearer has already picked. A notice takes exactly one answer. */
    val answered: Boolean = false,
) {
    val deadlineMs: Long get() = minOf(ttlDeadlineMs, hardDeadlineMs)
}

/** What the phone owes a `/notice/action` arriving from the glasses. */
internal sealed interface PhoneNoticeActionResult {
    data class Owner(val ownerPluginId: String) : PhoneNoticeActionResult

    /** The one answer was already taken. Distinct from [NotCurrent] on purpose. */
    data object AlreadyAnswered : PhoneNoticeActionResult

    /** No such notice, or no such action on the one that is up. */
    data object NotCurrent : PhoneNoticeActionResult
}

internal sealed interface PhoneNoticeShowResult {
    data class Accepted(
        val notice: CanonicalPhoneNotice,
        val replacedOwnerPluginId: String?,
    ) : PhoneNoticeShowResult

    data class Rejected(val code: String) : PhoneNoticeShowResult
}

internal sealed interface PhoneNoticeUpdateResult {
    data class Accepted(val notice: CanonicalPhoneNotice) : PhoneNoticeUpdateResult
    data class Rejected(val code: String) : PhoneNoticeUpdateResult

    /** Nothing visible, or not this plugin's slot. Logged, never an error. */
    data object Ignored : PhoneNoticeUpdateResult
}

internal sealed interface PhoneNoticeClearResult {
    data class Cleared(
        val ownerPluginId: String,
        val reason: NoticeCloseReason,
        val payload: JSONObject,
    ) : PhoneNoticeClearResult

    data object Ignored : PhoneNoticeClearResult
}

/**
 * Canonical single-slot notice state on the phone.
 *
 * Deliberately unlike [PhonePinState] in one respect: a notice is never held
 * for a link that is down. A pin is a standing fact and is worth delivering
 * late; a notice is a moment, and one delivered thirty seconds after the event
 * is a lie about the present. When the glasses cannot be reached the plugin is
 * told so and can decide for itself.
 */
internal class PhoneNoticeState(
    private val nowMs: () -> Long,
    initialSequence: Long = System.currentTimeMillis(),
) {
    private val sequence = AtomicLong(initialSequence)
    private val recentMessagesByPlugin = mutableMapOf<String, ArrayDeque<Long>>()
    private var active: CanonicalPhoneNotice? = null

    @Synchronized
    fun show(
        ownerPluginId: String,
        payload: JSONObject,
        binary: ByteArray? = null,
    ): PhoneNoticeShowResult {
        val expectedSurfaceId = "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (payload.optString("surfaceId") != expectedSurfaceId ||
            payload.optString("localSurfaceId") != NoticeSurfaceContract.LOCAL_SURFACE_ID ||
            payload.optString("ownerPluginId") != ownerPluginId
        ) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val validation = NoticeSurfaceContract.validateShow(payload, binary)
        if (validation !is NoticeSurfaceValidationResult.Valid) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneNoticeShowResult.Rejected(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED)
        }

        val previousOwner = active?.ownerPluginId
        val content = validation.content
        val notice = CanonicalPhoneNotice(
            ownerPluginId = ownerPluginId,
            content = content,
            payload = normalized(expectedSurfaceId, ownerPluginId, content),
            ttlDeadlineMs = now + content.ttlMs,
            hardDeadlineMs = now + NoticeSurfaceContract.MAX_LIFETIME_MS,
        )
        active = notice
        return PhoneNoticeShowResult.Accepted(
            notice,
            previousOwner?.takeIf { it != ownerPluginId },
        )
    }

    @Synchronized
    fun update(ownerPluginId: String, payload: JSONObject): PhoneNoticeUpdateResult {
        val current = active ?: return PhoneNoticeUpdateResult.Ignored
        if (current.ownerPluginId != ownerPluginId) return PhoneNoticeUpdateResult.Ignored

        val patch = NoticeSurfaceContract.validateUpdate(payload)
        if (patch !is NoticeSurfacePatchResult.Valid) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val patched = patch.patch.applyTo(current.content)
        if (
            patched.title.isNullOrEmpty() &&
            patched.body.isNullOrEmpty() &&
            patched.lines.isEmpty()
        ) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        if (!NoticeSurfaceContract.hasValidInteraction(patched)) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_INVALID_NOTICE)
        }
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneNoticeUpdateResult.Rejected(NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED)
        }

        val surfaceId = "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        // A row, the plain interactive flag, or a text field is the owner
        // asking again and is owed a new answer. A display-only update must not
        // quietly reopen an answered notice.
        val answered = if (
            patch.patch.actions != null ||
            patch.patch.interactive != null ||
            patch.patch.textInput != null
        ) {
            false
        } else {
            current.answered
        }
        val notice = current.copy(
            content = patched,
            // The owner's patch, stamped, rather than a re-serialisation of the
            // canonical state above. The phone still holds the authority -- the
            // patch was validated, applied, and could have been rejected before
            // reaching here -- but what travels is what the owner actually said,
            // so a field they cleared arrives as a cleared field instead of an
            // absent one the glasses would read as "leave it alone".
            payload = stamped(
                NoticeSurfaceContract.toUpdatePayload(surfaceId, patch.patch),
                ownerPluginId,
            ),
            ttlDeadlineMs = now + patched.ttlMs,
            answered = answered,
        )
        active = notice
        return PhoneNoticeUpdateResult.Accepted(notice)
    }

    @Synchronized
    fun hide(ownerPluginId: String): PhoneNoticeClearResult =
        if (active?.ownerPluginId == ownerPluginId) {
            clearActive(NoticeCloseReason.OWNER)
        } else {
            PhoneNoticeClearResult.Ignored
        }

    /** The glasses reported the wearer dismissed it, or the band timed out there. */
    @Synchronized
    fun closedByGlasses(surfaceId: String, reason: NoticeCloseReason): PhoneNoticeClearResult {
        val current = active ?: return PhoneNoticeClearResult.Ignored
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected) return PhoneNoticeClearResult.Ignored
        return clearActive(reason)
    }

    @Synchronized
    fun ownerLostAccess(ownerPluginId: String): PhoneNoticeClearResult =
        if (active?.ownerPluginId == ownerPluginId) {
            clearActive(NoticeCloseReason.DISCONNECT)
        } else {
            PhoneNoticeClearResult.Ignored
        }

    @Synchronized
    fun ownerPluginId(): String? = active?.ownerPluginId

    /**
     * Takes the canonical notice's one answer and names the plugin owed it.
     *
     * Checked against the canonical content rather than trusted from the wire,
     * so a pick that raced a replacement is dropped instead of being handed to
     * whoever holds the slot now. The answered flag lives here as well as on the
     * glasses because the duplicate that prompted this rule is a race, and a
     * race is exactly what survives one side losing its state.
     */
    @Synchronized
    fun takeAnswer(surfaceId: String, actionId: String): PhoneNoticeActionResult {
        if (actionId.isBlank()) return PhoneNoticeActionResult.NotCurrent
        val current = active ?: return PhoneNoticeActionResult.NotCurrent
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected) return PhoneNoticeActionResult.NotCurrent
        if (current.content.actions.none { it.id == actionId }) {
            return PhoneNoticeActionResult.NotCurrent
        }
        if (current.answered) return PhoneNoticeActionResult.AlreadyAnswered
        active = current.copy(answered = true)
        return PhoneNoticeActionResult.Owner(current.ownerPluginId)
    }

    /**
     * The same gate for the plain confirming gesture of a band with no row.
     *
     * A notice takes exactly one answer whichever kind it is, so input is not
     * the unguarded path it used to be: it must be the current notice, that
     * notice must actually have asked for a gesture, and it only gets to answer
     * once.
     */
    @Synchronized
    fun takeInputAnswer(surfaceId: String): PhoneNoticeActionResult {
        val current = active ?: return PhoneNoticeActionResult.NotCurrent
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected) return PhoneNoticeActionResult.NotCurrent
        if (!current.content.expectsInput) return PhoneNoticeActionResult.NotCurrent
        if (current.answered) return PhoneNoticeActionResult.AlreadyAnswered
        active = current.copy(answered = true)
        return PhoneNoticeActionResult.Owner(current.ownerPluginId)
    }

    /** Owner gate for one submitted platform text field. Text itself never enters canonical state. */
    @Synchronized
    fun takeTextSubmission(surfaceId: String, inputId: String): PhoneNoticeActionResult {
        val current = active ?: return PhoneNoticeActionResult.NotCurrent
        val expected = "${current.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        if (surfaceId != expected || current.content.textInput?.id != inputId) {
            return PhoneNoticeActionResult.NotCurrent
        }
        if (current.answered) return PhoneNoticeActionResult.AlreadyAnswered
        active = current.copy(answered = true)
        return PhoneNoticeActionResult.Owner(current.ownerPluginId)
    }

    @Synchronized
    fun expireIfDue(): PhoneNoticeClearResult {
        val deadline = active?.deadlineMs ?: return PhoneNoticeClearResult.Ignored
        return if (nowMs() >= deadline) clearActive(NoticeCloseReason.TIMEOUT) else PhoneNoticeClearResult.Ignored
    }

    @Synchronized
    fun expiryDeadlineMs(): Long? = active?.deadlineMs

    /**
     * Full state for a show. A show is always a fresh, unanswered question, so
     * there is nothing here to strip: what the owner sent is what the glasses
     * get.
     */
    private fun normalized(
        surfaceId: String,
        ownerPluginId: String,
        content: NoticeSurfaceContent,
    ): JSONObject = stamped(
        NoticeSurfaceContract.toPayload(surfaceId, content),
        ownerPluginId,
    )

    /**
     * The hub's own fields, added to whatever is going out. A plugin can supply
     * neither a trusted owner nor a sequence.
     */
    private fun stamped(payload: JSONObject, ownerPluginId: String): JSONObject = payload
        .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("seq", sequence.incrementAndGet())

    /**
     * Sliding one-second window shared by show and update, so a plugin cannot
     * dodge the budget by alternating between them.
     */
    private fun admit(ownerPluginId: String, now: Long): Boolean {
        val window = recentMessagesByPlugin.getOrPut(ownerPluginId) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= RATE_WINDOW_MS) {
            window.removeFirst()
        }
        if (window.size >= NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) return false
        window.addLast(now)
        return true
    }

    private fun clearActive(reason: NoticeCloseReason): PhoneNoticeClearResult.Cleared {
        val notice = checkNotNull(active)
        active = null
        val surfaceId = "${notice.ownerPluginId}:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"
        return PhoneNoticeClearResult.Cleared(
            ownerPluginId = notice.ownerPluginId,
            reason = reason,
            payload = JSONObject()
                .put("surfaceId", surfaceId)
                .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
                .put("ownerPluginId", notice.ownerPluginId)
                .put("seq", sequence.incrementAndGet()),
        )
    }

    private companion object {
        const val RATE_WINDOW_MS = 1_000L
    }
}
