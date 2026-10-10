package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal data class VisibleNotificationContent(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val renderedText: String,
    val revision: String,
    val imageId: String?,
)

internal fun VisibleNotificationContent.hasSameVisibleContent(other: VisibleNotificationContent): Boolean =
    packageName == other.packageName &&
        appLabel == other.appLabel &&
        title == other.title &&
        renderedText == other.renderedText &&
        revision == other.revision &&
        imageId == other.imageId

internal sealed interface ReplySendResult {
    data object Sent : ReplySendResult
    data object Missing : ReplySendResult
    data object Blank : ReplySendResult
    data object NoFreeFormInput : ReplySendResult

    /**
     * The send threw. [authorityLost] when the app cancelled the reply intent, so nothing was
     * sent and nothing can be; otherwise the app may or may not have the text.
     */
    data class Failed(val causeClass: String, val authorityLost: Boolean = false) : ReplySendResult
}

internal object ReplyRepository {
    private data class ReadableReply(
        val snapshot: RelayInboxSnapshot,
        val content: VisibleNotificationContent,
    )

    data class CaptureResult(
        val reply: PendingReply,
        val contentChanged: Boolean,
        val shouldShowNow: Boolean,
        val redacted: Boolean,
    )

    data class PendingReply(
        val id: String,
        val notificationKey: String,
        val content: VisibleNotificationContent,
        val footer: String,
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long,
        val imagePreview: NotificationImagePreview?,
    )

    private val pending = linkedMapOf<String, PendingReply>()
    private val recent = linkedMapOf<String, ReadableReply>()

    /** Per thread, the replies this process handed to the app, until the app's own copy shows them. */
    private val handoffs = mutableMapOf<String, List<RelayHandoff>>()
    private var nextHandoffSerial = 0L
    private val lastCaptureAtMs = AtomicLong(0L)

    @Synchronized
    fun capture(
        context: Context,
        sbn: StatusBarNotification,
        action: Notification.Action,
    ): CaptureResult? {
        val remoteInputs = action.remoteInputs ?: return null
        if (remoteInputs.isEmpty()) return null

        val settings = RelaySettings(context)
        val extras = sbn.notification.extras
        val appLabel = appLabel(context, sbn.packageName)
        val sourceTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val displayTitle = (sourceTitle.ifBlank { appLabel })
            .take(NoticeSurfaceContract.MAX_TITLE_CHARS)
        val extracted = NotificationTextExtractor.extractThread(
            NotificationTextExtractor.fromExtras(extras),
            settings.messagesPerThread(),
        )
        val extractedText = extracted.rendered
        val redacted = AndroidSensitiveNotificationDetector.isRedacted(sourceTitle, extractedText)
        val renderedText = if (redacted) {
            SensitiveNotificationDetector.HIDDEN_BODY
        } else {
            extractedText
        }
        // A redacted body names nobody either: what Android hid stays hidden in every form.
        val messages = if (redacted) {
            listOf(RelayMessage(RelayMessageRole.UNKNOWN, SensitiveNotificationDetector.HIDDEN_BODY))
        } else {
            extracted.messages
        }
        val footer = appLabel.trim().take(NoticeSurfaceContract.MAX_FOOTER_CHARS)
        val imagePreview = if (settings.imagePreviewsEnabled()) {
            NotificationImageExtractor.extract(context, sbn.notification)
        } else {
            null
        }
        val revision = notificationRevision(sbn, extras, imagePreview)
        if (hasRemoteInputHistory(extras) && settings.clearAfterReply()) {
            NotificationControl.cancelAfterReply(sbn.key)
        }

        val id = stableId(sbn.key)
        val content = VisibleNotificationContent(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = displayTitle,
            renderedText = renderedText,
            revision = revision,
            imageId = imagePreview?.id,
        )
        val previous = pending[id]
        val previousContent = previous?.content ?: recent[id]?.content
        val contentChanged = previousContent == null || !previousContent.hasSameVisibleContent(content)
        val capturedAtMs = if (contentChanged) {
            nextCaptureAtMs()
        } else {
            previous?.capturedAtMs ?: recent.getValue(id).snapshot.capturedAtMs
        }
        val reply = PendingReply(
            id = id,
            notificationKey = sbn.key,
            content = content,
            footer = footer,
            actionIntent = action.actionIntent,
            remoteInputs = remoteInputs,
            capturedAtMs = capturedAtMs,
            imagePreview = imagePreview,
        )
        pending[id] = reply
        recent[id] = ReadableReply(
            snapshot = RelayInboxSnapshot(
                id = id,
                sender = content.title,
                appLabel = content.appLabel,
                renderedText = content.renderedText,
                capturedAtMs = capturedAtMs,
                messages = messages,
            ),
            content = content,
        )
        handoffs[id]?.let { log ->
            val gone = RelayTranscript.superseded(messages, log)
            if (gone.isNotEmpty()) handoffs[id] = log.filterNot { it.serial in gone }
        }
        trimRecent()
        return CaptureResult(
            reply = reply,
            contentChanged = contentChanged,
            shouldShowNow = contentChanged && isMostRecent(id),
            redacted = redacted,
        )
    }

    fun sendReply(context: Context, notificationId: String, text: String): ReplySendResult {
        val reply = synchronized(this) { pending[notificationId] } ?: return ReplySendResult.Missing
        if (text.isBlank()) return ReplySendResult.Blank
        val intent = Intent()
        val results = Bundle()
        reply.remoteInputs.forEach { input ->
            if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
        }
        if (results.isEmpty) return ReplySendResult.NoFreeFormInput

        RemoteInput.addResultsToIntent(reply.remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val failure = runCatching {
            reply.actionIntent.send(context, 0, intent)
        }.exceptionOrNull()
        val settings = RelaySettings(context)
        if (failure is PendingIntent.CanceledException) {
            // The app withdrew the reply action: nothing went out, and nothing can through it.
            synchronized(this) { if (pending[notificationId] === reply) pending.remove(notificationId) }
            NotificationControl.notifyInboxChanged()
            return ReplySendResult.Failed(failure.javaClass.simpleName.ifBlank { "CanceledException" }, authorityLost = true)
        }
        if (failure != null) {
            recordHandoff(notificationId, text, RelayHandoffStatus.UNCONFIRMED, settings.messagesPerThread())
            NotificationControl.notifyInboxChanged()
            return ReplySendResult.Failed(failure.javaClass.simpleName.ifBlank { "SendException" })
        }

        if (settings.clearAfterReply()) {
            NotificationControl.cancelAfterReply(reply.notificationKey)
        }
        // The thread stays: the reply reads in it as ours, handed to the app, until a later
        // capture shows the app's own copy of it. Nothing here claims it was delivered or read.
        recordHandoff(notificationId, text, RelayHandoffStatus.HANDED_OFF, settings.messagesPerThread())
        NotificationControl.notifyInboxChanged()
        return ReplySendResult.Sent
    }

    /** Logs a reply in its thread after the newest message captured so far, within the thread's bounds. */
    @Synchronized
    private fun recordHandoff(notificationId: String, text: String, status: RelayHandoffStatus, maxEntries: Int) {
        val readable = recent[notificationId] ?: return
        val anchor = RelayTranscript.messageIds(readable.snapshot.messages).lastOrNull()
        val entry = RelayHandoff(nextHandoffSerial++, text.trim(), status, anchor)
        handoffs[notificationId] = RelayTranscript.bounded(
            handoffs[notificationId].orEmpty() + entry,
            maxEntries,
            NoticeSurfaceContract.MAX_BODY_CHARS,
        )
    }

    /**
     * Drops the thread's reply authority because its notification left the phone. The readable
     * thread stays. Returns whether Relay knew the thread at all.
     */
    @Synchronized
    fun forget(sbn: StatusBarNotification): Boolean {
        val id = stableId(sbn.key)
        val known = id in pending || id in recent
        pending.remove(id)
        return known
    }

    /**
     * The just-in-time check before a send: the thread still has a live reply action under the
     * notification it was captured from, and that action still takes free-form text.
     */
    @Synchronized
    fun hasReplyAuthority(notificationId: String): Boolean {
        val reply = pending[notificationId] ?: return false
        return stableId(reply.notificationKey) == notificationId &&
            reply.remoteInputs.any { it.allowFreeFormInput }
    }

    /** The thread a `relay:thread:` page id names, readable or still replyable, or null. */
    @Synchronized
    fun threadIdForPage(pageId: String): String? =
        (recent.keys + pending.keys).firstOrNull { RelayPages.threadPageId(it) == pageId }

    @Synchronized
    fun inboxEntries(): List<RelayInboxEntry> = RelayInboxCatalog.entries(
        snapshots = recent.values.map { readable ->
            handoffs[readable.snapshot.id]?.let { readable.snapshot.copy(handoffs = it) } ?: readable.snapshot
        },
        liveReplyIds = pending.keys.toSet(),
    )

    /** Keeps readable text in memory while dropping every process-bound reply object. */
    @Synchronized
    fun markAllReadOnly() {
        pending.clear()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        recent.clear()
        handoffs.clear()
    }

    @Synchronized
    fun contains(notificationId: String): Boolean = notificationId in pending

    @Synchronized
    private fun isMostRecent(notificationId: String): Boolean =
        pending.values.maxByOrNull(PendingReply::capturedAtMs)?.id == notificationId

    private fun trimRecent() {
        while (recent.size > RelayInboxCatalog.MAX_ENTRIES) {
            val oldestId = recent.values
                .minWithOrNull(
                    compareBy<ReadableReply> { it.snapshot.capturedAtMs }
                        .thenBy { it.snapshot.id },
                )
                ?.snapshot
                ?.id
                ?: return
            recent.remove(oldestId)
            handoffs.remove(oldestId)
        }
    }

    private fun notificationRevision(
        sbn: StatusBarNotification,
        extras: Bundle,
        imagePreview: NotificationImagePreview?,
    ): String {
        val imageRevision = imagePreview?.let { preview ->
            ":img:${preview.id}:${preview.width}x${preview.height}:${preview.bytes.size}"
        } ?: ":img:none"
        val messages = NotificationTextExtractor.messagingStyleMessages(extras)
        if (messages.isNotEmpty()) {
            return "msg:${messages.size}:${messages.first().timestamp}:${messages.last().timestamp}$imageRevision"
        }
        return "plain:${sbn.notification.`when`}$imageRevision"
    }

    private fun hasRemoteInputHistory(extras: Bundle): Boolean =
        extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?.any { !it.isNullOrBlank() } == true

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun nextCaptureAtMs(): Long {
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastCaptureAtMs.get()
            val next = if (now > previous) now else previous + 1L
            if (lastCaptureAtMs.compareAndSet(previous, next)) return next
        }
    }

    fun stableId(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .take(10)
            .joinToString("") { byte -> "%02x".format(byte) }
}
