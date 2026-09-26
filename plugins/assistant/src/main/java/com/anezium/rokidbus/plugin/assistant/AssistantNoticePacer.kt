package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps this plugin's band traffic under the phone hub's five notice messages a second.
 *
 * The hub counts shows and updates per plugin and turns the sixth away with
 * `NOTICE_RATE_LIMITED` — after the SDK has already answered `SENT`, so the band just never
 * changes. A transcript every 300 ms, the keepalive and an answer streaming every 250 ms get
 * close on their own; the state change that lands in the same second (the Type tap's quiet
 * band, the fresh show that clears a chip, the final answer) is then the one lost.
 *
 * So messages leave at least [intervalMs] apart. One that arrives sooner waits in a single
 * slot that later ones fold into — latest field wins, an action row or `interactive` carried
 * by an earlier one survives, a show replaces whatever was waiting — so the band still ends
 * in the state the controller asked for, in order. A hide is not rate-limited: it leaves at
 * once and drops whatever was waiting, which belonged to the band it removes.
 *
 * Main thread only, like the controller that owns it.
 */
internal class AssistantNoticePacer(
    private val scope: CoroutineScope,
    private val sendShow: (NexusNotice) -> NexusSdkResult,
    private val sendUpdate: (NexusNoticeUpdate) -> NexusSdkResult,
    private val sendHide: () -> NexusSdkResult,
    private val intervalMs: Long = MIN_INTERVAL_MS,
    /** A message that waited failed when it finally left; the caller already heard `SENT`. */
    private val onDeferredFailure: (NexusSdkResult) -> Unit = {},
) {
    private sealed interface Message {
        data class Show(val notice: NexusNotice) : Message

        data class Update(val update: NexusNoticeUpdate) : Message
    }

    private var waiting: Message? = null
    private val waitingCallbacks = mutableListOf<(NexusSdkResult) -> Unit>()
    private var cooldownJob: Job? = null

    /**
     * Returns the real result when the show leaves now, `SENT` when it waits. [onSent] hears
     * the result of the message that finally carries it, whenever that is.
     */
    fun show(notice: NexusNotice, onSent: ((NexusSdkResult) -> Unit)? = null): NexusSdkResult =
        submit(Message.Show(notice), onSent)

    fun update(update: NexusNoticeUpdate): NexusSdkResult = submit(Message.Update(update), null)

    fun hide(): NexusSdkResult {
        dropWaiting()
        return sendHide()
    }

    /** Forgets what was waiting, for a band that is gone. The clock keeps running. */
    fun dropWaiting() {
        waiting = null
        waitingCallbacks.clear()
    }

    private fun submit(message: Message, onSent: ((NexusSdkResult) -> Unit)?): NexusSdkResult {
        if (intervalMs <= 0 || cooldownJob?.isActive != true) {
            val result = send(message)
            startCooldown()
            onSent?.invoke(result)
            return result
        }
        waiting = fold(waiting, message)
        onSent?.let(waitingCallbacks::add)
        return NexusSdkResult.SENT
    }

    private fun startCooldown() {
        if (intervalMs <= 0) return
        cooldownJob = scope.launch {
            delay(intervalMs)
            cooldownJob = null
            drain()
        }
    }

    private fun drain() {
        val message = waiting ?: return
        val callbacks = waitingCallbacks.toList()
        dropWaiting()
        val result = send(message)
        startCooldown()
        if (result != NexusSdkResult.SENT) onDeferredFailure(result)
        callbacks.forEach { it(result) }
    }

    private fun send(message: Message): NexusSdkResult = when (message) {
        is Message.Show -> sendShow(message.notice)
        is Message.Update -> sendUpdate(message.update)
    }

    internal companion object {
        /** Four a second: one under the hub's limit, so arrival jitter cannot make a sixth. */
        const val MIN_INTERVAL_MS = 250L

        private fun fold(waiting: Message?, next: Message): Message = when {
            waiting == null || next is Message.Show -> next
            waiting is Message.Show -> Message.Show(applyUpdate(waiting.notice, (next as Message.Update).update))
            else -> Message.Update(mergeUpdates((waiting as Message.Update).update, (next as Message.Update).update))
        }

        internal fun applyUpdate(show: NexusNotice, update: NexusNoticeUpdate): NexusNotice = NexusNotice(
            title = update.title ?: show.title,
            body = when {
                update.body != null -> update.body
                update.lines.isNotEmpty() -> null
                else -> show.body
            },
            lines = when {
                update.body != null -> emptyList()
                update.lines.isNotEmpty() -> update.lines
                else -> show.lines
            },
            footer = update.footer ?: show.footer,
            interactive = update.interactive ?: show.interactive,
            actions = update.actions.ifEmpty { show.actions },
            ttlMs = update.ttlMs ?: show.ttlMs,
            image = show.image,
            wakeDisplay = show.wakeDisplay,
            backdrop = show.backdrop,
        )

        internal fun mergeUpdates(first: NexusNoticeUpdate, then: NexusNoticeUpdate): NexusNoticeUpdate =
            NexusNoticeUpdate(
                title = then.title ?: first.title,
                body = when {
                    then.body != null -> then.body
                    then.lines.isNotEmpty() -> null
                    else -> first.body
                },
                lines = when {
                    then.body != null -> emptyList()
                    then.lines.isNotEmpty() -> then.lines
                    else -> first.lines
                },
                footer = then.footer ?: first.footer,
                interactive = then.interactive ?: first.interactive,
                actions = then.actions.ifEmpty { first.actions },
                ttlMs = then.ttlMs ?: first.ttlMs,
                rearm = then.rearm ?: first.rearm,
            )
    }
}
