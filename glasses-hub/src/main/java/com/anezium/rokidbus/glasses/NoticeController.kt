package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class NexusNoticeSurface(
    val surfaceId: String,
    val seq: Long,
    val content: NoticeSurfaceContent,
    val expiresAtMs: Long,
    val hardExpiresAtMs: Long,
    val selectedActionIndex: Int = 0,
    val pageCount: Int = 1,
    val pageIndex: Int = 0,
    val engaged: Boolean = false,
    val imageBitmap: Bitmap? = null,
    /**
     * The wearer has picked. A notice takes exactly one answer: measured on
     * device, two temple taps 188 ms apart fired the action twice, and for a
     * messaging plugin that is two replies sent.
     */
    val answered: Boolean = false,
    val ownerPluginId: String = "",
) {
    /**
     * The actions still on offer. An answered band shows none: the question has
     * been answered, so the choices have no reason to stay in the wearer's eye.
     */
    val liveActions: List<NoticeAction>
        get() = if (answered) emptyList() else content.actions

    val liveTextInput
        get() = content.textInput.takeUnless { answered }

    val isDisplayEngaged: Boolean
        get() = content.interactive || liveTextInput != null

    /**
     * Whether the band still wants a gesture. An answered one never does again
     * -- not another action, and not the plain confirming input either.
     */
    val expectsInput: Boolean get() = !answered && content.expectsInput

    /**
     * A band pages unless its row needs the directions to choose along.
     *
     * The rule used to be stricter — paged or answerable, never both — and its
     * reason was sound: forward and backward must never mean two things at
     * once. That reason survives; the line was simply drawn in the wrong place.
     * With at most one action there is nothing to step along, so the directions
     * are free to turn pages while the tap still answers.
     *
     * A relayed conversation is exactly that shape — long, and worth one reply —
     * and under the old rule it was ellipsized at eight lines with the rest
     * unreachable, in the tier built to carry it.
     *
     * Two or more actions still claim the directions, and such a band does not
     * page. No gesture ever carries two meanings.
     */
    val isPaged: Boolean get() = content.actions.size <= 1 && pageCount > 1

    val claimsDirection: Boolean get() = liveActions.size > 1 || isPaged
}

internal fun noticeVisibleForInput(
    activeNotice: NexusNoticeSurface?,
    cameraOverlayActive: Boolean,
): NexusNoticeSurface? = activeNotice.takeUnless { cameraOverlayActive }

internal fun noticeClaimsAllInput(
    activeNotice: NexusNoticeSurface?,
    cameraOverlayActive: Boolean,
): Boolean = noticeVisibleForInput(activeNotice, cameraOverlayActive)?.let { notice ->
    notice.content.backdrop || notice.liveTextInput != null
} == true

internal fun noticeOwnsRingInput(
    activeNotice: NexusNoticeSurface?,
    cameraOverlayActive: Boolean,
): Boolean = noticeVisibleForInput(activeNotice, cameraOverlayActive)?.let { notice ->
    notice.expectsInput ||
        notice.liveTextInput != null ||
        notice.claimsDirection ||
        notice.content.backdrop
} == true

/**
 * Where the selection lands after a step. Wraps in both directions: the row is
 * three glyphs at most, so a dead end at either edge would only be a way to
 * make the wearer press again for nothing.
 */
internal fun nextNoticeActionIndex(current: Int, delta: Int, count: Int): Int =
    if (count <= 0) 0 else ((current + delta) % count + count) % count

/**
 * The selection to keep when an update replaces the row.
 *
 * Follows the id, not the position: a plugin that reorders its answers, or
 * drops the one before the selected one, must not move the wearer's finger onto
 * a different answer than the one they were looking at. When the selected id is
 * gone the selection falls back to the first action, which is the only choice
 * that cannot be a surprise.
 */
internal fun preservedNoticeActionIndex(
    previous: List<NoticeAction>,
    previousIndex: Int,
    next: List<NoticeAction>,
): Int {
    if (next.isEmpty()) return 0
    val selectedId = previous.getOrNull(previousIndex)?.id ?: return 0
    return next.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
}

internal data class NoticePageWindow(
    val firstLine: Int,
    val lastLineExclusive: Int,
)

internal data class NoticePageCapacities(
    val firstPageLines: Int,
    val followingPageLines: Int,
)

internal const val MIN_BODY_LINES = 8
internal const val MAX_GROWN_BODY_LINES = 14

internal fun noticeBodyLineCapacity(
    availableBodyHeightPx: Int,
    measuredLineHeightPx: Int,
): Int {
    require(measuredLineHeightPx > 0)
    return (availableBodyHeightPx.coerceAtLeast(0) / measuredLineHeightPx)
        .coerceIn(MIN_BODY_LINES, MAX_GROWN_BODY_LINES)
}

internal fun noticeFirstPageBodyLines(capacity: Int, hasImage: Boolean): Int =
    if (hasImage) {
        (capacity - IMAGE_BODY_LINE_COST).coerceAtLeast(MIN_IMAGE_PAGE_BODY_LINES)
    } else {
        capacity
    }

internal fun noticePageCapacities(
    lineCount: Int,
    grownCapacity: Int,
    hasImage: Boolean,
    actionCount: Int,
): NoticePageCapacities {
    val capacity = if (actionCount <= 1 && lineCount > MIN_BODY_LINES) {
        grownCapacity.coerceIn(MIN_BODY_LINES, MAX_GROWN_BODY_LINES)
    } else {
        MIN_BODY_LINES
    }
    return NoticePageCapacities(
        firstPageLines = noticeFirstPageBodyLines(capacity, hasImage),
        followingPageLines = capacity,
    )
}

internal fun noticePageCount(
    lineCount: Int,
    firstPageLines: Int,
    followingPageLines: Int,
): Int {
    require(firstPageLines > 0)
    require(followingPageLines > 0)
    if (lineCount <= firstPageLines) return 1
    return 1 + (lineCount - firstPageLines + followingPageLines - 1) / followingPageLines
}

internal fun noticePageWindow(
    pageIndex: Int,
    lineCount: Int,
    firstPageLines: Int,
    followingPageLines: Int,
): NoticePageWindow {
    val count = noticePageCount(lineCount, firstPageLines, followingPageLines)
    val page = pageIndex.coerceIn(0, count - 1)
    val first = if (page == 0) {
        0
    } else {
        firstPageLines + (page - 1) * followingPageLines
    }
    val capacity = if (page == 0) firstPageLines else followingPageLines
    return NoticePageWindow(
        firstLine = first.coerceAtMost(lineCount),
        lastLineExclusive = (first + capacity).coerceAtMost(lineCount),
    )
}

private const val IMAGE_BODY_LINE_COST = 5
private const val MIN_IMAGE_PAGE_BODY_LINES = 3

/**
 * The exact text handed to the real [android.text.StaticLayout]. A body is
 * returned untouched for pixel compatibility; structured lines use the one
 * platform-owned hard break between entries and otherwise rely on that same
 * layout for wrapping and measurement.
 */
internal fun noticeBodyText(content: NoticeSurfaceContent): String? =
    if (content.lines.isEmpty()) content.body else content.lines.joinToString("\n")

/**
 * What a band's one answer turned out to be.
 *
 * Both kinds are the same event -- the wearer answered -- and differ only in
 * what goes on the wire, so they are one type. A band that offers a row is
 * answered by which choice was picked; a band that offers none is answered by
 * the fact that it was confirmed at all.
 */
internal sealed interface NoticeAnswer {
    data class Action(val action: NoticeAction) : NoticeAnswer
    data class Input(val keyCode: Int) : NoticeAnswer
    data class Text(val inputId: String, val text: String) : NoticeAnswer {
        override fun toString(): String =
            "Text(inputId=$inputId, text=<redacted:${text.length}>)"
    }
}

internal sealed interface NoticeStateDecision {
    data class Shown(val notice: NexusNoticeSurface) : NoticeStateDecision
    data class Updated(val notice: NexusNoticeSurface) : NoticeStateDecision

    /**
     * The one answer this band had to give, taken. Carries everything the send
     * needs so that the send and the flag forbidding a second one are a single
     * transition, which two taps 188 ms apart cannot get between.
     */
    data class Answered(
        val notice: NexusNoticeSurface,
        val answer: NoticeAnswer,
    ) : NoticeStateDecision

    data class Closed(
        val surfaceId: String,
        val seq: Long,
        val ttlMs: Long,
        val reason: NoticeCloseReason,
        val imageBitmap: Bitmap? = null,
    ) : NoticeStateDecision
    data object DroppedStale : NoticeStateDecision
    data object Ignored : NoticeStateDecision
}

/** Pure single-slot notice state: sequence guard, patching, and the TTL clock. */
internal class NoticeStateMachine {
    private var latestSeq = Long.MIN_VALUE
    private var active: NexusNoticeSurface? = null

    fun activeNotice(): NexusNoticeSurface? = active

    fun show(
        surfaceId: String,
        seq: Long,
        content: NoticeSurfaceContent,
        nowMs: Long,
        imageBitmap: Bitmap? = null,
        ownerPluginId: String = "",
    ): NoticeStateDecision {
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        latestSeq = seq
        val notice = NexusNoticeSurface(
            surfaceId = surfaceId,
            seq = seq,
            content = content,
            expiresAtMs = minOf(
                nowMs + content.ttlMs,
                nowMs + NoticeSurfaceContract.MAX_LIFETIME_MS,
            ),
            hardExpiresAtMs = nowMs + NoticeSurfaceContract.MAX_LIFETIME_MS,
            imageBitmap = imageBitmap,
            ownerPluginId = ownerPluginId,
        )
        active = notice
        return NoticeStateDecision.Shown(notice)
    }

    /**
     * Applies a patch to the visible notice. Ignored rather than rejected when
     * nothing is visible or the sender does not own the slot: an update racing a
     * TTL that fired a frame earlier is ordinary, not an error.
     */
    fun update(
        surfaceId: String,
        seq: Long,
        patch: com.anezium.rokidbus.shared.NoticeSurfacePatch,
        nowMs: Long,
    ): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.surfaceId != surfaceId) return NoticeStateDecision.Ignored
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        val patched = patch.applyTo(current.content)
        // An update is allowed to clear any single field, but not to leave the
        // wearer looking at an empty box.
        if (
            patched.title.isNullOrEmpty() &&
            patched.body.isNullOrEmpty() &&
            patched.lines.isEmpty()
        ) {
            return NoticeStateDecision.Ignored
        }
        if (!NoticeSurfaceContract.hasValidInteraction(patched)) {
            return NoticeStateDecision.Ignored
        }
        latestSeq = seq
        val remainsEngaged = current.engaged && !patched.expectsInput
        val notice = current.copy(
            seq = seq,
            content = patched,
            // Updates restart ordinary notices, but reading has its own clock:
            // text arriving while the wearer is between pages is not a gesture
            // and cannot silently buy another thirty seconds.
            expiresAtMs = if (remainsEngaged) {
                current.expiresAtMs
            } else {
                minOf(nowMs + patched.ttlMs, current.hardExpiresAtMs)
            },
            selectedActionIndex = preservedNoticeActionIndex(
                previous = current.content.actions,
                previousIndex = current.selectedActionIndex,
                next = patched.actions,
            ),
            // Carrying a row, the plain interactive flag, or a text field is
            // the owner asking again, so the band is owed a new answer. A
            // display-only update must not quietly reopen an answered notice.
            // Explicitly clearing one of those interaction fields resets too.
            answered = if (
                patch.actions != null || patch.interactive != null || patch.textInput != null
            ) {
                false
            } else {
                current.answered
            },
            // Paging survives an update that leaves the band pageable; a row of
            // two or more takes the directions back, and with them the pages.
            pageCount = if (patched.actions.size <= 1) current.pageCount else 1,
            pageIndex = if (patched.actions.size <= 1) current.pageIndex else 0,
            engaged = remainsEngaged,
        )
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /**
     * Takes the band's one answer, whichever kind it has to give.
     *
     * Marking and reading happen in the same call on purpose: the duplicate tap
     * that started this arrived 188 ms after the first, and any gap between
     * "what is this band's answer" and "this band is now answered" is a gap two
     * taps can both fit through. That is why the plain input case comes through
     * here too rather than being checked and then forwarded.
     */
    fun answer(confirmKeyCode: Int): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (!current.expectsInput) return NoticeStateDecision.Ignored
        val answer = when (
            val action = current.content.actions.getOrNull(current.selectedActionIndex)
        ) {
            null -> NoticeAnswer.Input(confirmKeyCode)
            else -> NoticeAnswer.Action(action)
        }
        val notice = current.copy(answered = true)
        active = notice
        return NoticeStateDecision.Answered(notice, answer)
    }

    fun submitText(surfaceId: String, inputId: String, text: String): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.surfaceId != surfaceId || current.liveTextInput?.id != inputId) {
            return NoticeStateDecision.Ignored
        }
        val submission = runCatching {
            NoticeSurfaceContract.parseTextSubmission(
                NoticeSurfaceContract.textSubmissionPayload(surfaceId, inputId, text),
            )
        }.getOrNull() ?: return NoticeStateDecision.Ignored
        val notice = current.copy(answered = true)
        active = notice
        return NoticeStateDecision.Answered(
            notice,
            NoticeAnswer.Text(submission.inputId, submission.text),
        )
    }

    /**
     * Steps the selection along the action row.
     *
     * Deliberately does not touch `expiresAtMs`: choosing is not a reason for
     * the band to live longer. A notice with actions dies on exactly the
     * deadline it would have died on with none, which is what keeps a question
     * from becoming a thing the wearer has to escape.
     */
    fun moveSelection(delta: Int): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        val count = current.liveActions.size
        if (count == 0) return NoticeStateDecision.Ignored
        val next = nextNoticeActionIndex(current.selectedActionIndex, delta, count)
        if (next == current.selectedActionIndex) return NoticeStateDecision.Ignored
        val notice = current.copy(selectedActionIndex = next)
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    fun setPageCount(
        surfaceId: String,
        seq: Long,
        count: Int,
    ): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.surfaceId != surfaceId || current.seq != seq) {
            return NoticeStateDecision.Ignored
        }
        // Measured pages are kept whenever the directions are free to turn them,
        // which is any row of at most one. Only a row of two or more collapses
        // back to a single page, because there the directions are choosing.
        val nextCount = if (current.content.actions.size <= 1) count.coerceAtLeast(1) else 1
        val nextIndex = current.pageIndex.coerceIn(0, nextCount - 1)
        if (current.pageCount == nextCount && current.pageIndex == nextIndex) {
            return NoticeStateDecision.Ignored
        }
        val notice = current.copy(pageCount = nextCount, pageIndex = nextIndex)
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /**
     * Page reading deliberately differs from action selection: the first real
     * turn kills both countdowns, then every reading gesture restarts one short
     * inactivity clock so pace, rather than message length, owns the deadline.
     */
    fun movePage(delta: Int, nowMs: Long): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (!current.isPaged) return NoticeStateDecision.Ignored
        val next = (current.pageIndex + delta).coerceIn(0, current.pageCount - 1)
        if (!current.engaged && next == current.pageIndex) {
            return NoticeStateDecision.Ignored
        }
        val notice = current.copy(
            pageIndex = next,
            engaged = true,
            expiresAtMs = nowMs + ENGAGED_INACTIVITY_MS,
        )
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /** The action the wearer would fire right now, if the band still offers any. */
    fun selectedAction(): NoticeAction? =
        active?.let { it.liveActions.getOrNull(it.selectedActionIndex) }

    fun hide(seq: Long, reason: NoticeCloseReason): NoticeStateDecision {
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        latestSeq = seq
        val closing = active ?: return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(
            surfaceId = closing.surfaceId,
            seq = closing.seq,
            ttlMs = closing.content.ttlMs,
            reason = reason,
            imageBitmap = closing.imageBitmap,
        )
    }

    /** BACK and TTL are local: they carry no sequence from the phone. */
    fun close(reason: NoticeCloseReason): NoticeStateDecision {
        val closing = active ?: return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(
            surfaceId = closing.surfaceId,
            seq = closing.seq,
            ttlMs = closing.content.ttlMs,
            reason = reason,
            imageBitmap = closing.imageBitmap,
        )
    }

    fun expire(nowMs: Long, expectedSeq: Long): NoticeStateDecision {
        val notice = active ?: return NoticeStateDecision.Ignored
        if (notice.seq != expectedSeq || nowMs < notice.expiresAtMs) return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(
            surfaceId = notice.surfaceId,
            seq = notice.seq,
            ttlMs = notice.content.ttlMs,
            reason = NoticeCloseReason.TIMEOUT,
            imageBitmap = notice.imageBitmap,
        )
    }

    private companion object {
        const val ENGAGED_INACTIVITY_MS = 30_000L
    }
}

internal object NoticeController {
    private data class PendingNoticeWake(
        val surfaceId: String,
        val seq: Long,
        val deadlineAtMs: Long,
        val screenOffObserved: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val state = NoticeStateMachine()
    private val listeners = CopyOnWriteArrayList<(NexusNoticeSurface?) -> Unit>()
    private val imageDecodeCoordinator = ImageDecodeCoordinator<Bitmap>()
    private val imageDecodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusNoticeImageDecode").apply { isDaemon = true }
    }
    private var expiry: Runnable? = null
    private var cameraOverlayActive = false
    private var serviceContext: Context? = null
    private var sleepDisplay: (() -> Boolean)? = null
    private var episodeOwnsWake = false
    private var noticeLockPending = false
    private var pendingNoticeWake: PendingNoticeWake? = null
    private val pendingNoticeWakeRetry = Runnable(::retryPendingNoticeWake)
    private var screenOffReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            runOnMain {
                episodeOwnsWake = false
                noticeLockPending = false
                pendingNoticeWake?.let { pending ->
                    pendingNoticeWake = pending.copy(
                        deadlineAtMs = SystemClock.elapsedRealtime() + LOCK_SETTLE_TIMEOUT_MS,
                        screenOffObserved = true,
                    )
                    main.removeCallbacks(pendingNoticeWakeRetry)
                    main.post(pendingNoticeWakeRetry)
                }
            }
        }
    }
    private val ringInputPolicy = RingSurfaceInputPolicy()
    private val ringTapExpiry = Runnable(::resolveRingTap)

    fun activeNotice(): NexusNoticeSurface? = state.activeNotice()

    internal fun prepareInkMorph(ownerPluginId: String): NoticeInkMorphToken? {
        if (Looper.myLooper() != Looper.getMainLooper() || ownerPluginId.isBlank()) return null
        val notice = visibleNotice()?.takeIf { it.ownerPluginId == ownerPluginId } ?: return null
        return NoticeOverlayRenderer.beginInkMorph(notice)
    }

    internal fun startInkMorphFade(token: NoticeInkMorphToken): Boolean =
        NoticeOverlayRenderer.startInkMorphFade(token)

    internal fun closeForInkMorph(token: NoticeInkMorphToken): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val current = state.activeNotice()
        if (current == null) return true
        if (
            current.surfaceId != token.surfaceId || current.seq != token.seq ||
            current.ownerPluginId != token.ownerPluginId
        ) {
            return false
        }
        applyDecision(
            state.close(NoticeCloseReason.OWNER),
            preserveOwnerClose = true,
        )
        return true
    }

    internal fun finishInkMorph(token: NoticeInkMorphToken) {
        NoticeOverlayRenderer.finishInkMorph(token)
    }

    internal fun cancelInkMorph(token: NoticeInkMorphToken) {
        val current = state.activeNotice()
        if (
            current != null && current.surfaceId == token.surfaceId &&
            current.seq == token.seq && current.ownerPluginId == token.ownerPluginId
        ) {
            NoticeOverlayRenderer.cancelInkMorph(token)
        } else {
            NoticeOverlayRenderer.finishInkMorph(token)
        }
    }

    fun onServiceConnected(context: Context, sleepDisplay: () -> Boolean) {
        runOnMain {
            serviceContext = context.applicationContext
            this.sleepDisplay = sleepDisplay
            registerScreenOffReceiver(context.applicationContext)
        }
    }

    fun onServiceDestroyed() {
        runOnMain {
            clearPendingNoticeWake()
            noticeLockPending = false
            sleepDisplay = null
            serviceContext = null
        }
    }

    fun onPhoneLinkLost() {
        runOnMain {
            discardPendingImage()
            applyDecision(state.close(NoticeCloseReason.DISCONNECT))
        }
    }

    fun visibleNotice(): NexusNoticeSurface? =
        noticeVisibleForInput(state.activeNotice(), cameraOverlayActive)

    fun observe(listener: (NexusNoticeSurface?) -> Unit): () -> Unit {
        listeners += listener
        listener(visibleNotice())
        return { listeners.remove(listener) }
    }

    fun handleNoticeEnvelope(context: Context, envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.NOTICE_SHOW -> {
            runOnMain { show(context.applicationContext, envelope) }
            true
        }
        BusPaths.NOTICE_UPDATE -> {
            runOnMain { update(envelope) }
            true
        }
        BusPaths.NOTICE_HIDE -> {
            runOnMain { hide(envelope) }
            true
        }
        else -> false
    }

    /**
     * BACK dismisses whatever notice is up, always, and is never forwarded to the
     * plugin that put it there. A notice a plugin could hold you inside would be
     * a different and much worse thing.
     *
     * Returns true when a notice was actually dismissed, so the caller knows
     * whether it consumed the key.
     */
    fun dismissFromBack(): Boolean {
        if (visibleNotice() == null) return false
        runOnMain {
            discardPendingImage()
            applyDecision(state.close(NoticeCloseReason.USER))
        }
        return true
    }

    /**
     * Whether a notice is up and asked for a gesture. Only then does anything
     * below claim a key, and only the keys that mean confirm and dismiss:
     * everything else keeps reaching whatever is underneath. Backdrop notices
     * add a separate modal fallback claim because they hide that native UI.
     */
    fun claimsInput(): Boolean = visibleNotice()?.expectsInput == true

    /**
     * Forward and backward belong to the band only when they can change its
     * state: they choose an offered answer or turn measured pages. A plain
     * one-page non-backdrop notice claims neither, so the surface underneath
     * stays usable.
     */
    fun claimsDirection(): Boolean =
        visibleNotice()?.claimsDirection == true

    /** A backdrop or live editor keeps touchpad input out of the native UI underneath. */
    fun claimsAllInput(): Boolean =
        noticeClaimsAllInput(state.activeNotice(), cameraOverlayActive)

    /**
     * The ring bridge must stand down for every interactive, paged, or backdrop
     * notice. Per-key claims still decide which gestures change notice state.
     */
    fun ownsRingInput(): Boolean =
        noticeOwnsRingInput(state.activeNotice(), cameraOverlayActive)

    /**
     * The wearer confirmed. The owner hears about it once; nobody else does.
     *
     * A band with actions answers with the selected one on `/notice/action`; a
     * band without them answers on `/notice/input`. Either way that is the
     * band's one answer, and the state machine decides which it is and marks it
     * spent in the same step -- there is no reading here for a second tap to
     * race.
     *
     * An answered band has no live confirm claim. A non-backdrop band therefore
     * lets the second of two fast taps through; a backdrop band's modal fallback
     * swallows it without answering again.
     */
    fun handleConfirm(keyCode: Int): Boolean {
        if (visibleNotice()?.expectsInput != true) return false
        runOnMain { applyDecision(state.answer(keyCode)) }
        return true
    }

    /** Steps the selection. False when there is nothing to step through. */
    fun handleDirection(delta: Int): Boolean {
        if (!claimsDirection()) return false
        runOnMain {
            // The same test that decided the band could page in the first place.
            // Asking "does it have any actions" instead sent a one-chip band's
            // swipes into a row with nowhere to go, so a paged conversation
            // could be seen but never turned.
            val decision = if (state.activeNotice()?.isPaged == true) {
                state.movePage(delta, SystemClock.elapsedRealtime())
            } else {
                state.moveSelection(delta)
            }
            applyDecision(decision)
        }
        return true
    }

    fun setPageCount(surfaceId: String, seq: Long, count: Int) {
        main.post { applyDecision(state.setPageCount(surfaceId, seq, count)) }
    }

    fun submitText(surfaceId: String, inputId: String, text: String) {
        runOnMain { applyDecision(state.submitText(surfaceId, inputId, text)) }
    }

    /**
     * Which ring keys the band takes. The tap belongs to a question; directions
     * belong to either its row or measured pages. Ownership is broader than
     * these per-key claims: unclaimed keys are swallowed while any interactive,
     * paged, or backdrop notice owns the R08 bridge focus.
     */
    fun claimsRingKey(keyCode: Int): Boolean = when (keyCode) {
        RingSurfaceInputPolicy.RING_KEYCODE_TAP -> claimsInput()
        RingSurfaceInputPolicy.RING_KEYCODE_FORWARD,
        RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD,
        -> claimsDirection()
        else -> false
    }

    fun handleRingKey(keyCode: Int, eventTimeMs: Long): Boolean {
        if (!claimsRingKey(keyCode)) return false
        return when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD -> handleDirection(1)
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD -> handleDirection(-1)
            else -> {
                ringInputPolicy.onKeyDown(keyCode, eventTimeMs)
                main.removeCallbacks(ringTapExpiry)
                main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
                true
            }
        }
    }

    fun cancelRingInput() {
        runOnMain {
            main.removeCallbacks(ringTapExpiry)
            ringInputPolicy.reset()
        }
    }

    private fun resolveRingTap() {
        when (ringInputPolicy.resolveExpired(SystemClock.elapsedRealtime())) {
            is RingSurfaceInputPolicy.Resolution.Forward ->
                handleConfirm(RingSurfaceInputPolicy.KEYCODE_ENTER)
            // A double tap on the ring is the wearer's dismiss, same as BACK.
            RingSurfaceInputPolicy.Resolution.Back -> dismissFromBack()
            RingSurfaceInputPolicy.Resolution.Ignore, null -> Unit
        }
    }

    private fun forwardAction(surfaceId: String, actionId: String) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_ACTION,
            NoticeSurfaceContract.actionPayload(surfaceId, actionId),
        )
    }

    private fun forwardInput(surfaceId: String, keyCode: Int) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_INPUT,
            JSONObject()
                .put("noticeId", surfaceId)
                .put("keyCode", keyCode)
                .put("action", KeyEvent.ACTION_DOWN),
        )
    }

    private fun forwardText(surfaceId: String, inputId: String, text: String) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_TEXT_SUBMIT,
            NoticeSurfaceContract.textSubmissionPayload(surfaceId, inputId, text),
        )
    }

    fun setCameraOverlayActive(active: Boolean) {
        runOnMain {
            if (cameraOverlayActive == active) return@runOnMain
            cameraOverlayActive = active
            notifyChanged()
        }
    }

    private fun show(context: Context, envelope: BusEnvelope) {
        val validation = NoticeSurfaceContract.validateShow(envelope.payload, envelope.binary)
        if (validation !is NoticeSurfaceValidationResult.Valid) {
            log("notice rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        if (surfaceId.isBlank()) {
            log("notice rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        val ownerPluginId = envelope.payload.optString("ownerPluginId")
        val image = validation.content.image
        if (image != null) {
            val bytes = envelope.binary ?: return
            val metadata = SurfaceImageMetadata(
                version = ImageSurfaceContract.VERSION,
                contentKey = image.contentKey,
                mimeType = image.mimeType,
                pixelWidth = image.pixelWidth,
                pixelHeight = image.pixelHeight,
                sha256 = image.sha256,
                caption = "",
            )
            val key = ImageDecodeKey(surfaceId, seq, image.contentKey)
            imageDecodeCoordinator.begin(key)
            imageDecodeExecutor.execute {
                val decoded = ImageHudView.decodeRgb565(bytes, metadata)
                if (decoded == null) {
                    log("Notice image decode failed id=$surfaceId seq=$seq")
                    main.post { imageDecodeCoordinator.cancel(key) }
                    return@execute
                }
                main.post {
                    when (val completion = imageDecodeCoordinator.complete(key, decoded)) {
                        is ImageDecodeCompletion.Rejected -> completion.stale.recycleSafely()
                        is ImageDecodeCompletion.Accepted -> {
                            completion.replaced?.takeUnless { it === decoded }?.recycleSafely()
                            showValidated(
                                context = context,
                                surfaceId = surfaceId,
                                seq = seq,
                                content = validation.content,
                                imageBitmap = decoded,
                                ownerPluginId = ownerPluginId,
                            )
                            imageDecodeCoordinator.invalidate(surfaceId)
                                ?.takeUnless { it === decoded }
                                ?.recycleSafely()
                        }
                    }
                }
            }
            return
        }
        imageDecodeCoordinator.invalidate()?.let { pending ->
            if (pending !== state.activeNotice()?.imageBitmap) pending.recycleSafely()
        }
        showValidated(
            context,
            surfaceId,
            seq,
            validation.content,
            ownerPluginId = ownerPluginId,
        )
    }

    private fun showValidated(
        context: Context,
        surfaceId: String,
        seq: Long,
        content: NoticeSurfaceContent,
        imageBitmap: Bitmap? = null,
        ownerPluginId: String = "",
    ) {
        val previous = state.activeNotice()
        val decision = state.show(
            surfaceId,
            seq,
            content,
            SystemClock.elapsedRealtime(),
            imageBitmap,
            ownerPluginId,
        )
        // A different plugin taking the slot is a close for the one that had it,
        // and its owner is owed the reason.
        if (decision is NoticeStateDecision.Shown &&
            previous != null &&
            previous.surfaceId != surfaceId
        ) {
            logNoticeClosed(previous, NoticeCloseReason.REPLACED)
            reportClosed(previous.surfaceId, NoticeCloseReason.REPLACED)
        }
        applyDecision(decision)
        if (decision is NoticeStateDecision.Shown) {
            requestNoticeWake(context, decision.notice)
            previous?.imageBitmap
                ?.takeUnless { it === decision.notice.imageBitmap }
                ?.recycleSafely()
        } else {
            imageBitmap?.recycleSafely()
        }
    }

    private fun update(envelope: BusEnvelope) {
        if (envelope.binary != null) {
            log("notice update rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val patch = NoticeSurfaceContract.validateUpdate(envelope.payload)
        if (patch !is NoticeSurfacePatchResult.Valid) {
            log("notice update rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        val wasEngaged = state.activeNotice()?.isDisplayEngaged == true
        val decision = state.update(surfaceId, seq, patch.patch, SystemClock.elapsedRealtime())
        applyDecision(
            decision,
            genuineEngagement = decision is NoticeStateDecision.Updated &&
                !wasEngaged && decision.notice.isDisplayEngaged,
        )
    }

    private fun hide(envelope: BusEnvelope) {
        discardPendingImage()
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        applyDecision(state.hide(seq, NoticeCloseReason.OWNER))
    }

    private fun applyDecision(
        decision: NoticeStateDecision,
        genuineEngagement: Boolean = false,
        preserveOwnerClose: Boolean = false,
    ) {
        when (decision) {
            is NoticeStateDecision.Shown -> {
                AssistantDisplayEpisode.accept(
                    serviceContext,
                    assistantEpisodeNoticeShownSignal(
                        surfaceId = decision.notice.surfaceId,
                        ownerPluginId = decision.notice.ownerPluginId,
                        seq = decision.notice.seq,
                        engaged = decision.notice.isDisplayEngaged,
                    ),
                )
                log(
                    "notice state=shown seq=${decision.notice.seq} " +
                        "ttlMs=${decision.notice.content.ttlMs}",
                )
                scheduleExpiry(decision.notice)
                notifyChanged()
            }
            is NoticeStateDecision.Updated -> {
                val signal = if (genuineEngagement) {
                    assistantEpisodeNoticeShownSignal(
                        surfaceId = decision.notice.surfaceId,
                        ownerPluginId = decision.notice.ownerPluginId,
                        seq = decision.notice.seq,
                        engaged = decision.notice.isDisplayEngaged,
                    )
                } else {
                    assistantEpisodeNoticeRedrawSignal(
                        surfaceId = decision.notice.surfaceId,
                        seq = decision.notice.seq,
                        engaged = decision.notice.isDisplayEngaged,
                        reason = DisplayHoldRenewReason.BAND_UPDATE,
                    )
                }
                AssistantDisplayEpisode.accept(serviceContext, signal)
                scheduleExpiry(decision.notice)
                notifyChanged()
            }
            is NoticeStateDecision.Answered -> {
                AssistantDisplayEpisode.accept(
                    serviceContext,
                    assistantEpisodeNoticeRedrawSignal(
                        surfaceId = decision.notice.surfaceId,
                        seq = decision.notice.seq,
                        engaged = decision.notice.isDisplayEngaged,
                        reason = DisplayHoldRenewReason.BAND_ANSWER,
                    ),
                )
                // No expiry rescheduling: answering neither shortens nor extends
                // the band's life, and the deadline it was already given still
                // stands. The re-render is what makes the row leave the band.
                when (val answer = decision.answer) {
                    is NoticeAnswer.Action ->
                        forwardAction(decision.notice.surfaceId, answer.action.id)
                    is NoticeAnswer.Input ->
                        forwardInput(decision.notice.surfaceId, answer.keyCode)
                    is NoticeAnswer.Text ->
                        forwardText(decision.notice.surfaceId, answer.inputId, answer.text)
                }
                notifyChanged()
            }
            is NoticeStateDecision.Closed -> {
                AssistantDisplayEpisode.accept(
                    serviceContext,
                    assistantEpisodeNoticeClosedSignal(
                        surfaceId = decision.surfaceId,
                        reason = decision.reason,
                        preserveOwnerClose = preserveOwnerClose,
                    ),
                )
                clearPendingNoticeWake()
                cancelExpiry()
                main.removeCallbacks(ringTapExpiry)
                ringInputPolicy.reset()
                log(
                    "notice state=closed seq=${decision.seq} ttlMs=${decision.ttlMs} " +
                        "reason=${decision.reason.wireValue}",
                )
                reportClosed(decision.surfaceId, decision.reason)
                notifyChanged()
                maybeSleepDisplay(decision.reason)
                decision.imageBitmap?.let { released ->
                    main.postDelayed({ released.recycleSafely() }, HudMotion.EXIT_MS + 1L)
                }
            }
            NoticeStateDecision.DroppedStale -> log("notice dropped stale")
            NoticeStateDecision.Ignored -> Unit
        }
    }

    /** Tells the phone the slot is free, and why, so it can tell the owner. */
    private fun reportClosed(surfaceId: String, reason: NoticeCloseReason) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_CLOSED,
            NoticeSurfaceContract.closedPayload(surfaceId, reason),
        )
    }

    private fun scheduleExpiry(notice: NexusNoticeSurface) {
        cancelExpiry()
        val task = Runnable {
            expiry = null
            applyDecision(state.expire(SystemClock.elapsedRealtime(), notice.seq))
        }
        expiry = task
        main.postDelayed(
            task,
            (notice.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        )
    }

    private fun cancelExpiry() {
        expiry?.let(main::removeCallbacks)
        expiry = null
    }

    private fun requestNoticeWake(context: Context, notice: NexusNoticeSurface) {
        clearPendingNoticeWake()
        val wakeDecision = DisplayWakePolicy.requestWake(
            context = context,
            kind = DisplayWakeKind.NOTICE,
            requested = notice.content.wakeDisplay,
            seq = notice.seq,
            newNotice = true,
        )
        when (wakeDecision) {
            is DisplayWakeDecision.Wake -> {
                noticeLockPending = false
                episodeOwnsWake = true
            }
            is DisplayWakeDecision.Refused -> {
                if (
                    wakeDecision.reason == DisplayWakeRefusal.ALREADY_INTERACTIVE &&
                    notice.content.wakeDisplay &&
                    noticeLockPending
                ) {
                    pendingNoticeWake = PendingNoticeWake(
                        surfaceId = notice.surfaceId,
                        seq = notice.seq,
                        deadlineAtMs = SystemClock.elapsedRealtime() + LOCK_SETTLE_TIMEOUT_MS,
                    )
                    main.postDelayed(pendingNoticeWakeRetry, LOCK_SETTLE_TIMEOUT_MS)
                }
            }
        }
    }

    private fun retryPendingNoticeWake() {
        val pending = pendingNoticeWake ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (!pending.screenOffObserved) {
            val remainingMs = pending.deadlineAtMs - nowMs
            if (remainingMs > 0L) {
                main.postDelayed(pendingNoticeWakeRetry, remainingMs)
            } else {
                clearPendingNoticeWake()
            }
            return
        }
        val notice = state.activeNotice()
        if (notice == null || notice.surfaceId != pending.surfaceId) {
            clearPendingNoticeWake()
            return
        }
        val context = serviceContext
        if (context == null) {
            clearPendingNoticeWake()
            return
        }
        val wakeDecision = DisplayWakePolicy.requestWake(
            context = context,
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            seq = pending.seq,
            newNotice = true,
        )
        when (wakeDecision) {
            is DisplayWakeDecision.Wake -> {
                noticeLockPending = false
                episodeOwnsWake = true
                clearPendingNoticeWake()
            }
            is DisplayWakeDecision.Refused -> {
                if (
                    wakeDecision.reason == DisplayWakeRefusal.ALREADY_INTERACTIVE &&
                    nowMs < pending.deadlineAtMs
                ) {
                    main.postDelayed(pendingNoticeWakeRetry, LOCK_SETTLE_RETRY_MS)
                } else {
                    clearPendingNoticeWake()
                }
            }
        }
    }

    private fun clearPendingNoticeWake() {
        main.removeCallbacks(pendingNoticeWakeRetry)
        pendingNoticeWake = null
    }

    private fun discardPendingImage() {
        imageDecodeCoordinator.invalidate()?.let { pending ->
            if (pending !== state.activeNotice()?.imageBitmap) pending.recycleSafely()
        }
    }

    private fun maybeSleepDisplay(closeReason: NoticeCloseReason) {
        // This is the older one-shot wake bookkeeping, not the Assistant hold.
        // While that independent episode is active, even a timed-out band must
        // not force the firmware to lock underneath its still-visible card.
        val surfaceActive = SurfaceController.activeSurface() != null
        val episodeEnds = NoticeSleepPolicy.episodeEnds(closeReason, surfaceActive)
        try {
            val context = serviceContext ?: return
            val sleep = sleepDisplay ?: return
            val power = context.getSystemService(PowerManager::class.java)
            if (power == null) {
                log("notice display sleep skipped condition=power_unavailable")
                return
            }
            when (
                val decision = NoticeSleepPolicy.decide(
                    closeReason = closeReason,
                    episodeOwnsWake = episodeOwnsWake,
                    isInteractive = power.isInteractive,
                    launcherShown = LauncherOverlayRenderer.isShown(),
                    surfaceActive = surfaceActive,
                    assistantEpisodeActive = AssistantDisplayEpisode.isActive(),
                    activityPresenting = ActivityController.isPresenting(),
                    cameraOverlayActive = cameraOverlayActive,
                )
            ) {
                NoticeSleepDecision.Sleep -> {
                    noticeLockPending = true
                    val locked = runCatching(sleep).getOrDefault(false)
                    if (!locked) noticeLockPending = false
                    log("notice display sleep locked=$locked")
                }
                is NoticeSleepDecision.Skip ->
                    log("notice display sleep skipped condition=${decision.reason.logValue}")
            }
        } finally {
            if (episodeEnds) episodeOwnsWake = false
        }
    }

    /**
     * Registered once per process, on the application context. The display
     * going dark by any hand — this sleep, the ROM's timeout, the wearer's own
     * toggle — ends the wake episode; without this, a stale flag could put the
     * display to sleep under a wearer who had long since woken it themselves.
     */
    private fun registerScreenOffReceiver(context: Context) {
        if (screenOffReceiverRegistered) return
        context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOffReceiverRegistered = true
    }

    private fun notifyChanged() {
        val visible = visibleNotice()
        RingFocusBroadcastCoordinator.setNoticeOwnsRing(ownsRingInput())
        listeners.forEach { listener -> runCatching { listener(visible) } }
    }

    private fun logNoticeClosed(notice: NexusNoticeSurface, reason: NoticeCloseReason) {
        log(
            "notice state=closed seq=${notice.seq} ttlMs=${notice.content.ttlMs} " +
                "reason=${reason.wireValue}",
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private const val LOCK_SETTLE_RETRY_MS = 75L
    private const val LOCK_SETTLE_TIMEOUT_MS = 2_000L
}

private fun Bitmap.recycleSafely() {
    if (!isRecycled) recycle()
}
