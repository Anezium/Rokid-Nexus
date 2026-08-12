package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeTextInput
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantUiControllerTest {
    @Test
    fun `write stays on listening updates then becomes a phone text field`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsTextInput = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showListening("Listening…", offerWrite = true)
            controller.showTranscript("partial question")

            assertEquals(AssistantUiController.WRITE_ACTION_ID, renderer.noticeActions.single()?.id)
            assertTrue(renderer.calls[1] is RenderCall.UpdateNotice)

            controller.showTextInput()

            assertTrue(renderer.calls.last() is RenderCall.ShowNotice)
            assertEquals(
                AssistantUiController.WRITE_INPUT_ID,
                renderer.noticeTextInputs.last()?.id,
            )

            controller.showTransient("Thinking…")

            assertTrue(renderer.calls.last() is RenderCall.ShowNotice)
            assertEquals(null, renderer.noticeTextInputs.last())
            controller.onClose()
        }

    @Test
    fun `write is omitted when the glasses do not support notice text input`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsTextInput = false)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showListening("Listening…", offerWrite = true)

            assertEquals(null, renderer.noticeActions.single())
            controller.onClose()
        }

    @Test
    fun `transient states use notices when supported and cards in legacy mode`() =
        runTest {
            val noticeRenderer = FakeRenderer(supportsNotice = true)
            val noticeController = controller(noticeRenderer)
            noticeController.onOpen()
            noticeController.cancelLauncherHint()

            noticeController.showTransient("Listening…", legacyForceShow = true)
            noticeController.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.ShowNotice("Assistant", "Listening…"),
                    RenderCall.UpdateNotice("Thinking…"),
                ),
                noticeRenderer.calls,
            )
            noticeController.onClose()

            val legacyRenderer = FakeRenderer(supportsNotice = false)
            val legacyController = controller(legacyRenderer)
            legacyController.onOpen()
            legacyController.cancelLauncherHint()

            legacyController.showTransient("Listening…", legacyForceShow = true)
            legacyController.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.ShowCard(listOf("Listening…"), forceShow = true),
                    RenderCall.ShowCard(listOf("Thinking…"), forceShow = false),
                ),
                legacyRenderer.calls,
            )
            legacyController.onClose()

            val openCardRenderer = FakeRenderer(supportsNotice = true)
            val openCardController = controller(openCardRenderer)
            openCardController.onOpen()
            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()

            openCardController.beginGestureFlow()
            openCardController.showTransient("Listening…")

            // An open card stays the render target for the whole interaction:
            // hiding it here would read as a self-close to the hub.
            assertEquals(
                listOf(
                    RenderCall.ShowCard(
                        listOf(AssistantUiController.LAUNCHER_HINT),
                        forceShow = true,
                    ),
                    RenderCall.ShowCard(listOf("Listening…"), forceShow = false),
                ),
                openCardRenderer.calls,
            )
            openCardController.onClose()
        }

    @Test
    fun `transcript updates are throttled latest wins and the tail is retained`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            val finalTail = "tail-" + "z".repeat(195)
            val longTranscript = "discarded-prefix-".repeat(20) + finalTail
            controller.showTranscript("first partial")
            controller.showTranscript("superseded partial")
            controller.showTranscript(longTranscript)

            assertEquals(
                listOf(RenderCall.UpdateNotice("first partial")),
                renderer.calls,
            )

            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS - 1)
            runCurrent()
            assertEquals(1, renderer.calls.size)

            advanceTimeBy(1)
            runCurrent()

            val throttledBody = (renderer.calls.last() as RenderCall.UpdateNotice).body.orEmpty()
            assertEquals("${AssistantUiController.ELLIPSIS} $finalTail", throttledBody)
            assertEquals(2, renderer.calls.size)

            controller.showTranscript("the trailing partial")
            controller.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice("the trailing partial"),
                    RenderCall.UpdateNotice("Thinking…"),
                ),
                renderer.calls.takeLast(2),
            )

            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS)
            runCurrent()
            assertEquals(4, renderer.calls.size)
            controller.onClose()
        }

    @Test
    fun `transcript tail stays within its small window below the notice limit`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listeningâ€¦")
            renderer.calls.clear()

            val tail = "z".repeat(AssistantUiController.TRANSCRIPT_TAIL_CHARS)
            val transcript = "discarded ".repeat(30) + tail
            assertTrue(transcript.length < AssistantUiController.MAX_NOTICE_BODY_CHARS)

            controller.showTranscript(transcript)

            assertEquals(
                listOf(RenderCall.UpdateNotice("${AssistantUiController.ELLIPSIS} $tail")),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `legacy mode ignores speech partials`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = false)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…", legacyForceShow = true)
            renderer.calls.clear()

            controller.showTranscript("ignored partial")
            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS)
            runCurrent()

            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `newer notice state cancels error hide and latest error hides after deadline`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            controller.showError("Speech is busy. Try again.")

            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS - 1)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            controller.showTransient("Listening…")
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            controller.showError("Didn't catch that")
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()

            assertEquals(1, renderer.calls.count { it == RenderCall.HideNotice })
            controller.onClose()
        }

    @Test
    fun `gesture claim cancels deferred hint while launcher open shows it`() =
        runTest {
            val launcherRenderer = FakeRenderer(supportsNotice = true)
            val launcherController = controller(launcherRenderer)
            launcherController.onOpen()

            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS - 1)
            runCurrent()
            assertTrue(launcherRenderer.calls.isEmpty())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(
                    RenderCall.ShowCard(
                        listOf(AssistantUiController.LAUNCHER_HINT),
                        forceShow = true,
                    ),
                ),
                launcherRenderer.calls,
            )
            launcherController.onClose()

            val gestureRenderer = FakeRenderer(supportsNotice = true)
            val gestureController = controller(gestureRenderer)
            gestureController.onOpen()
            gestureController.cancelLauncherHint()

            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()

            assertTrue(gestureRenderer.calls.isEmpty())
            gestureController.onClose()
        }

    @Test
    fun `user notice close cancels pipeline and capture without touching surface`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            var pipelineCancels = 0
            var captureResets = 0
            val controller = AssistantUiController(
                scope = this,
                renderer = renderer,
                cancelPipeline = { pipelineCancels += 1 },
                resetCapture = { captureResets += 1 },
            )
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.OWNER)
            assertEquals(0, pipelineCancels)
            assertEquals(0, captureResets)

            controller.showTransient("Listening…")
            renderer.calls.clear()
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)

            assertEquals(1, pipelineCancels)
            assertEquals(1, captureResets)
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `ink answer dismisses the in-flight notice and its keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            renderer.calls.clear()

            controller.onInkAnswerShown()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 2)
            runCurrent()

            assertEquals(listOf(RenderCall.HideNotice), renderer.calls)
            // Ink does not turn the card tier on: a later discrete failure may
            // still use the notice tier without replacing the Ink surface.
            assertTrue(controller.isNoticeBandMode)
            controller.onClose()
        }

    @Test
    fun `only the current request's ink page owns answer presentation`() {
        assertTrue(inkAnswerOwnsPresentation("request-1", "request-1"))
        assertTrue(!inkAnswerOwnsPresentation("request-1", "request-2"))
        assertTrue(!inkAnswerOwnsPresentation(null, "request-1"))
        assertTrue(!inkAnswerOwnsPresentation(null, null))
    }

    @Test
    fun `answers stay on the notice band with head truncation and no success hide`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            renderer.calls.clear()

            val longAnswer = "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS) + "tail"
            controller.showAnswer(
                body = longAnswer,
                legacyCardLines = listOf("legacy answer"),
            )
            controller.showAnswer(
                body = "Final answer",
                legacyCardLines = listOf("legacy final"),
            )

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice(
                        lines = listOf(
                            "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS - 2) +
                                AssistantUiController.ELLIPSIS,
                        ),
                    ),
                    RenderCall.UpdateNotice(lines = listOf("Final answer")),
                ),
                renderer.calls,
            )
            assertTrue(renderer.calls.none { it is RenderCall.ShowCard })
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })
            controller.onClose()
        }

    @Test
    fun `answer paragraphs become normalized notice lines`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showAnswer(
                body = "a\n\nb\n\tc  ",
                legacyCardLines = listOf("legacy answer"),
            )

            assertEquals(
                listOf(
                    RenderCall.ShowNotice(
                        title = AssistantUiController.NOTICE_TITLE,
                        lines = listOf("a", "b", "c"),
                    ),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `answer beyond the speech budget stays complete on the notice band`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            val answer = "A".repeat(1_200)

            controller.showAnswer(
                body = answer,
                legacyCardLines = listOf("legacy answer"),
            )

            assertEquals(
                listOf(answer),
                (renderer.calls.single() as RenderCall.ShowNotice).lines,
            )
            controller.onClose()
        }

    @Test
    fun `answer lines truncate validly at line and character budgets`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showAnswer(
                body = (1..NoticeSurfaceContract.MAX_LINES + 1).joinToString("\n") { "line $it" },
                legacyCardLines = listOf("legacy answer"),
            )
            val lineLimited = (renderer.calls.single() as RenderCall.ShowNotice).lines
            assertEquals(NoticeSurfaceContract.MAX_LINES, lineLimited.size)
            assertValidTruncatedLines(lineLimited)

            renderer.calls.clear()
            val halfBudget = NoticeSurfaceContract.MAX_BODY_CHARS / 2
            controller.showAnswer(
                body = "A".repeat(halfBudget) + "\n" + "B".repeat(halfBudget),
                legacyCardLines = listOf("legacy answer"),
            )
            val characterLimited = (renderer.calls.single() as RenderCall.UpdateNotice).lines
            assertEquals(2, characterLimited.size)
            assertValidTruncatedLines(characterLimited)
            controller.onClose()
        }

    @Test
    fun `single paragraph answer is cut validly with an ellipsis`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showAnswer(
                body = "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS + 100),
                legacyCardLines = listOf("legacy answer"),
            )

            val lines = (renderer.calls.single() as RenderCall.ShowNotice).lines
            assertEquals(1, lines.size)
            assertEquals(AssistantUiController.MAX_NOTICE_BODY_CHARS, lines.sumOf { it.length + 1 })
            assertValidTruncatedLines(lines)
            controller.onClose()
        }

    @Test
    fun `notice updates transition between transient bodies and answer lines`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showTransient("Thinkingâ€¦")
            controller.showAnswer(
                body = "First paragraph\nSecond paragraph",
                legacyCardLines = listOf("legacy answer"),
            )
            controller.showTransient("Searchingâ€¦")

            assertEquals(
                listOf(
                    RenderCall.ShowNotice(
                        title = AssistantUiController.NOTICE_TITLE,
                        body = "Thinkingâ€¦",
                    ),
                    RenderCall.UpdateNotice(lines = listOf("First paragraph", "Second paragraph")),
                    RenderCall.UpdateNotice(body = "Searchingâ€¦"),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `spoken answer keepalive and grace remain lines updates`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showAnswer(
                body = "First paragraph\nSecond paragraph",
                legacyCardLines = listOf("legacy answer"),
            )
            renderer.calls.clear()

            controller.onAnswerSpeechStarted()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            controller.onAnswerSpeechFinished()

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice(lines = listOf("First paragraph", "Second paragraph")),
                    RenderCall.UpdateNotice(lines = listOf("First paragraph", "Second paragraph")),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `legacy answers keep force show then update card behavior`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = false)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…", legacyForceShow = true)
            renderer.calls.clear()

            controller.showAnswer(
                body = "First",
                legacyCardLines = listOf("First"),
            )
            controller.showAnswer(
                body = "First chunk",
                legacyCardLines = listOf("First chunk"),
            )

            assertEquals(
                listOf(
                    RenderCall.ShowCard(listOf("First"), forceShow = true),
                    RenderCall.ShowCard(listOf("First chunk"), forceShow = false),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `in-flight band states are kept alive and terminal states stop the keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            // A wearer slow to start speaking produces no updates; the band's
            // TTL must be restarted for them.
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(RenderCall.UpdateNotice("Listening…")), renderer.calls)

            // The keepalive resends the freshest in-flight body, not the first.
            controller.showTransient("Thinking…")
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(RenderCall.UpdateNotice("Thinking…")), renderer.calls)

            // An answer owns its own TTL; keeping it alive would pin the band.
            controller.showAnswer("Done.", legacyCardLines = listOf("Done."))
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `a spoken answer stays up while the voice reads it`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            controller.showAnswer("A long answer.", legacyCardLines = listOf("A long answer."))
            renderer.calls.clear()

            // The voice takes its time waking up, and the band must not spend its life waiting.
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 2)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())

            controller.onAnswerSpeechStarted()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertEquals(
                List(3) { RenderCall.UpdateNotice(lines = listOf("A long answer.")) },
                renderer.calls,
            )

            // Once heard, the answer is owed a glance at its tail — not its full reading time.
            renderer.calls.clear()
            renderer.updateTtls.clear()
            controller.onAnswerSpeechFinished()
            assertEquals(
                listOf(RenderCall.UpdateNotice(lines = listOf("A long answer."))),
                renderer.calls,
            )
            assertEquals(
                listOf<Long?>(AssistantUiController.ANSWER_SPOKEN_GRACE_MS),
                renderer.updateTtls,
            )

            // And the voice having stopped, nothing keeps holding the band open.
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `a late utterance cannot hold open the state that replaced it`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showAnswer("First answer.", legacyCardLines = listOf("First answer."))

            // The wearer has already asked something else by the time the old voice reports in.
            controller.showTransient("Listening…")
            renderer.calls.clear()
            controller.onAnswerSpeechStarted()
            controller.onAnswerSpeechFinished()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `errors and user close stop the keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            controller.showError("Didn't catch that")
            renderer.calls.clear()

            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.none { it is RenderCall.UpdateNotice })

            controller.showTransient("Listening…")
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `display hold follows the complete engaged notice lifecycle`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            controller.showTransient("Listeningâ€¦")
            assertTrue(controller.isEngagedNoticeEpisode)
            assertEquals(listOf<Boolean?>(true), renderer.noticeEngagement)

            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 4)
            runCurrent()
            assertTrue(controller.isEngagedNoticeEpisode)
            assertEquals(
                listOf<Boolean?>(true, null, null, null, null),
                renderer.noticeEngagement,
            )

            controller.showAnswer("Done.", legacyCardLines = listOf("Done."))
            assertTrue(controller.isEngagedNoticeEpisode)
            assertEquals(null, renderer.noticeEngagement.last())

            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertTrue(!controller.isEngagedNoticeEpisode)

            controller.showTransient("Thinkingâ€¦")
            assertTrue(controller.isEngagedNoticeEpisode)
            controller.showError("Request failed.")
            assertTrue(!controller.isEngagedNoticeEpisode)
            assertEquals(false, renderer.noticeEngagement.last())

            controller.showTransient("Listeningâ€¦")
            assertTrue(controller.isEngagedNoticeEpisode)
            controller.onInkAnswerShown()
            assertTrue(!controller.isEngagedNoticeEpisode)

            controller.showTransient("Listeningâ€¦")
            assertTrue(controller.isEngagedNoticeEpisode)
            renderer.calls.clear()
            controller.onClose()
            assertTrue(!controller.isEngagedNoticeEpisode)
            assertEquals(listOf(RenderCall.HideNotice), renderer.calls)
        }

    private fun TestScope.controller(renderer: FakeRenderer): AssistantUiController =
        AssistantUiController(
            scope = this,
            renderer = renderer,
            cancelPipeline = {},
            resetCapture = {},
        )

    private fun assertValidTruncatedLines(lines: List<String>) {
        assertTrue(lines.size <= NoticeSurfaceContract.MAX_LINES)
        assertTrue(
            lines.sumOf { it.length.toLong() + 1L } <= NoticeSurfaceContract.MAX_BODY_CHARS,
        )
        assertTrue(lines.last().endsWith(AssistantUiController.ELLIPSIS))
        NexusNotice(title = AssistantUiController.NOTICE_TITLE, lines = lines)
        NexusNoticeUpdate(lines = lines)
    }

    private sealed interface RenderCall {
        data class ShowNotice(
            val title: String?,
            val body: String? = null,
            val lines: List<String> = emptyList(),
        ) : RenderCall

        data class UpdateNotice(
            val body: String? = null,
            val lines: List<String> = emptyList(),
        ) : RenderCall

        data object HideNotice : RenderCall


        data class ShowCard(
            val lines: List<String>,
            val forceShow: Boolean,
        ) : RenderCall
    }

    private class FakeRenderer(
        private val supportsNotice: Boolean,
        private val supportsTextInput: Boolean = false,
    ) : AssistantUiRenderer {
        val calls = mutableListOf<RenderCall>()

        /** Kept beside [calls] so the existing call assertions stay about bodies alone. */
        val updateTtls = mutableListOf<Long?>()
        val noticeEngagement = mutableListOf<Boolean?>()
        val noticeActions = mutableListOf<NexusNoticeAction?>()
        val noticeTextInputs = mutableListOf<NexusNoticeTextInput?>()

        override val supportsNoticeSurface: Boolean
            get() = supportsNotice

        override val supportsNoticeTextInput: Boolean
            get() = supportsTextInput

        override fun showNotice(notice: NexusNotice): NexusSdkResult {
            calls += RenderCall.ShowNotice(notice.title, notice.body, notice.lines)
            noticeEngagement += notice.interactive
            noticeActions += notice.actions.singleOrNull()
            noticeTextInputs += notice.textInput
            return NexusSdkResult.SENT
        }

        override fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult {
            calls += RenderCall.UpdateNotice(update.body, update.lines)
            updateTtls += update.ttlMs
            noticeEngagement += update.interactive
            return NexusSdkResult.SENT
        }

        override fun hideNotice(): NexusSdkResult {
            calls += RenderCall.HideNotice
            return NexusSdkResult.SENT
        }

        override fun showCard(
            lines: List<String>,
            forceShow: Boolean,
        ): NexusSdkResult {
            calls += RenderCall.ShowCard(lines, forceShow)
            return NexusSdkResult.SENT
        }
    }
}
