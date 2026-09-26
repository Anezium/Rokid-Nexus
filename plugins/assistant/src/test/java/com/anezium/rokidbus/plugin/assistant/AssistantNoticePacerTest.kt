package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantNoticePacerTest {
    private val chip = listOf(NexusNoticeAction("type", "keyboard", "Type"))

    @Test
    fun `an idle band sends at once and the next message waits its turn`() = runTest {
        val sent = Sent()
        val pacer = pacer(sent)

        assertEquals(NexusSdkResult.SENT, pacer.show(NexusNotice(title = "A", body = "one")))
        assertEquals(NexusSdkResult.SENT, pacer.update(NexusNoticeUpdate(body = "two")))
        assertEquals(listOf<Any>(NexusNotice(title = "A", body = "one")), sent.messages)

        advanceTimeBy(INTERVAL - 1)
        runCurrent()
        assertEquals(1, sent.messages.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(NexusNoticeUpdate(body = "two"), sent.messages.last())
    }

    @Test
    fun `waiting updates fold into one that keeps a row and interactive set earlier`() = runTest {
        val sent = Sent()
        val pacer = pacer(sent)
        pacer.update(NexusNoticeUpdate(body = "first"))

        pacer.update(NexusNoticeUpdate(actions = chip, interactive = true))
        pacer.update(NexusNoticeUpdate(body = "latest", ttlMs = 5_000))
        advanceTimeBy(INTERVAL)
        runCurrent()

        assertEquals(
            NexusNoticeUpdate(body = "latest", interactive = true, actions = chip, ttlMs = 5_000),
            sent.messages.last(),
        )
        assertEquals(2, sent.messages.size)
    }

    @Test
    fun `an update folds into a waiting show and a later show replaces both`() = runTest {
        val sent = Sent()
        val pacer = pacer(sent)
        pacer.update(NexusNoticeUpdate(body = "first"))

        pacer.show(NexusNotice(title = "A", body = "stale", interactive = true))
        pacer.update(NexusNoticeUpdate(body = "fresh", footer = "hint"))
        advanceTimeBy(INTERVAL)
        runCurrent()
        assertEquals(
            NexusNotice(title = "A", body = "fresh", footer = "hint", interactive = true),
            sent.messages.last(),
        )

        pacer.show(NexusNotice(title = "A", body = "one"))
        pacer.show(NexusNotice(title = "A", body = "two"))
        advanceTimeBy(INTERVAL)
        runCurrent()
        assertEquals(NexusNotice(title = "A", body = "two"), sent.messages.last())
        assertEquals(3, sent.messages.size)
    }

    @Test
    fun `a hide leaves at once and takes whatever was waiting with it`() = runTest {
        val sent = Sent()
        val pacer = pacer(sent)
        var callbackResult: NexusSdkResult? = null
        pacer.update(NexusNoticeUpdate(body = "first"))
        pacer.show(NexusNotice(title = "A", body = "waiting")) { callbackResult = it }

        pacer.hide()
        advanceTimeBy(INTERVAL * 4)
        runCurrent()

        assertEquals(listOf<Any>(NexusNoticeUpdate(body = "first"), HIDE), sent.messages)
        assertEquals(null, callbackResult)
    }

    @Test
    fun `onSent hears the result of the message that finally carried it`() = runTest {
        val sent = Sent()
        val failures = mutableListOf<NexusSdkResult>()
        val pacer = pacer(sent, onDeferredFailure = { failures += it })
        val results = mutableListOf<NexusSdkResult>()

        pacer.show(NexusNotice(title = "A", body = "now")) { results += it }
        assertEquals(listOf(NexusSdkResult.SENT), results)

        pacer.show(NexusNotice(title = "A", body = "later")) { results += it }
        sent.nextResult = NexusSdkResult.NOT_REGISTERED
        advanceTimeBy(INTERVAL)
        runCurrent()

        assertEquals(listOf(NexusSdkResult.SENT, NexusSdkResult.NOT_REGISTERED), results)
        assertEquals(listOf(NexusSdkResult.NOT_REGISTERED), failures)
    }

    @Test
    fun `a burst never puts more than four messages in a second`() = runTest {
        val sent = Sent(clock = { testScheduler.currentTime })
        val pacer = pacer(sent)
        repeat(50) { index ->
            pacer.update(NexusNoticeUpdate(body = "partial $index"))
            advanceTimeBy(40)
            runCurrent()
        }
        advanceTimeBy(1_000)
        runCurrent()

        sent.times.forEachIndexed { index, start ->
            assertTrue(sent.times.drop(index).count { it < start + 1_000 } <= 4)
        }
        assertEquals(NexusNoticeUpdate(body = "partial 49"), sent.messages.last())
    }

    private fun TestScope.pacer(
        sent: Sent,
        onDeferredFailure: (NexusSdkResult) -> Unit = {},
    ): AssistantNoticePacer = AssistantNoticePacer(
        scope = this,
        sendShow = sent::show,
        sendUpdate = sent::update,
        sendHide = sent::hide,
        intervalMs = INTERVAL,
        onDeferredFailure = onDeferredFailure,
    )

    private class Sent(private val clock: () -> Long = { 0L }) {
        val messages = mutableListOf<Any>()
        val times = mutableListOf<Long>()
        var nextResult = NexusSdkResult.SENT

        fun show(notice: NexusNotice): NexusSdkResult = record(notice)

        fun update(update: NexusNoticeUpdate): NexusSdkResult = record(update)

        fun hide(): NexusSdkResult {
            messages += HIDE
            return NexusSdkResult.SENT
        }

        private fun record(message: Any): NexusSdkResult {
            messages += message
            times += clock()
            return nextResult.also { nextResult = NexusSdkResult.SENT }
        }
    }

    private companion object {
        const val INTERVAL = AssistantNoticePacer.MIN_INTERVAL_MS
        const val HIDE = "hide"
    }
}
