package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantUiControllerTest {
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
                noticeIntervalMs = 0L,
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
    fun `after an ink answer a later thinking transient renders nothing and starts no keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            controller.onInkAnswerShown()
            renderer.calls.clear()

            controller.showTransient("Thinking…")
            controller.showTranscript("ignored after ink")
            controller.showAnswer("Spoken reply.", legacyCardLines = listOf("Spoken reply."))
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 2)
            runCurrent()

            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `errors after an ink answer still render as notices`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            controller.onInkAnswerShown()
            renderer.calls.clear()

            controller.showError("Request failed.")

            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", "Request failed.")),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `beginGestureFlow clears ink ownership so a transient can render again`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            controller.onInkAnswerShown()
            controller.beginGestureFlow()
            renderer.calls.clear()

            controller.showTransient("Thinking…")

            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", "Thinking…")),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `opening or closing the session clears ink ownership`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            controller.onInkAnswerShown()

            controller.onOpen()
            controller.cancelLauncherHint()
            renderer.calls.clear()
            controller.showTransient("Thinking…")
            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", "Thinking…")),
                renderer.calls,
            )

            controller.onInkAnswerShown()
            controller.onClose()
            renderer.calls.clear()
            controller.showTransient("Thinking…")
            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", "Thinking…")),
                renderer.calls,
            )
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
                        body = "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS - 1) +
                            AssistantUiController.ELLIPSIS,
                    ),
                    RenderCall.UpdateNotice(body = "Final answer"),
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
    fun `answer paragraphs become normalized notice body lines`() =
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
                        body = "a\nb\nc",
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
                answer,
                (renderer.calls.single() as RenderCall.ShowNotice).body,
            )
            controller.onClose()
        }

    @Test
    fun `answer body keeps every paragraph regardless of count as long as it fits the budget`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            // More paragraphs than the notice-surface line cap: a Hermes-style answer
            // written as many short lines/bullets must not lose its tail to that cap
            // while the shared character budget still has room.
            val manyParagraphs =
                (1..NoticeSurfaceContract.MAX_LINES + 1).joinToString("\n") { "line $it" }
            controller.showAnswer(
                body = manyParagraphs,
                legacyCardLines = listOf("legacy answer"),
            )

            assertEquals(
                manyParagraphs,
                (renderer.calls.single() as RenderCall.ShowNotice).body,
            )
            controller.onClose()
        }

    @Test
    fun `answer body truncates validly at the character budget`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()

            val halfBudget = NoticeSurfaceContract.MAX_BODY_CHARS / 2
            controller.showAnswer(
                body = "A".repeat(halfBudget) + "\n" + "B".repeat(halfBudget),
                legacyCardLines = listOf("legacy answer"),
            )
            val body = (renderer.calls.single() as RenderCall.ShowNotice).body
            assertValidTruncatedBody(body)
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

            val body = (renderer.calls.single() as RenderCall.ShowNotice).body
            assertEquals(AssistantUiController.MAX_NOTICE_BODY_CHARS, body?.length)
            assertValidTruncatedBody(body)
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
                    RenderCall.UpdateNotice(body = "First paragraph\nSecond paragraph"),
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
                    RenderCall.UpdateNotice(body = "First paragraph\nSecond paragraph"),
                    RenderCall.UpdateNotice(body = "First paragraph\nSecond paragraph"),
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
                List(3) { RenderCall.UpdateNotice(body = "A long answer.") },
                renderer.calls,
            )

            // Once heard, the answer is owed a glance at its tail — not its full reading time.
            renderer.calls.clear()
            renderer.updateTtls.clear()
            controller.onAnswerSpeechFinished()
            assertEquals(
                listOf(RenderCall.UpdateNotice(body = "A long answer.")),
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

            controller.beginGestureFlow()
            controller.showTransient("Listeningâ€¦")
            assertTrue(controller.isEngagedNoticeEpisode)
            renderer.calls.clear()
            controller.onClose()
            assertTrue(!controller.isEngagedNoticeEpisode)
            assertEquals(listOf(RenderCall.HideNotice), renderer.calls)
        }

    @Test
    fun `a launcher open anchors a card and keeps the band as the render target`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)

            assertTrue(controller.onLauncherOpen())
            assertTrue(controller.isAnchored)
            assertTrue(controller.isNoticeBandMode)
            assertEquals(
                listOf(RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true)),
                renderer.calls,
            )
            assertEquals(listOf(AssistantUiController.ANCHOR_FOOTER), renderer.footers)
            // No hint ever lands on an anchored session.
            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, renderer.calls.size)

            controller.beginGestureFlow()
            controller.showTransient("Listening…", legacyForceShow = true)
            assertEquals(RenderCall.ShowNotice("Assistant", "Listening…"), renderer.calls.last())

            controller.onSurfaceHidden()
            assertFalse(controller.isAnchored)
            controller.onClose()
        }

    @Test
    fun `without a band the anchored card takes the conversation like any open card`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = false)
            val controller = controller(renderer)

            assertTrue(controller.onLauncherOpen())
            assertFalse(controller.isNoticeBandMode)
            controller.showTransient("Listening…", legacyForceShow = true)
            assertEquals(RenderCall.ShowCard(listOf("Listening…"), forceShow = true), renderer.calls.last())
            controller.onClose()
        }

    @Test
    fun `the options menu takes the card and back restores the anchor`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.showTransient("Listening…", legacyForceShow = true)

            val view = AssistantOptionsMenu.View(
                text = AssistantOptionsMenu.TEXT_NEXUS,
                sub = "Tap to give it back to Rokid.",
                footer = AssistantOptionsMenu.FOOTER_SWITCH,
            )
            controller.showOptions(view, forceShow = false)
            // The band that was up goes away; the menu is an update of the anchored card.
            assertEquals(
                listOf(
                    RenderCall.HideNotice,
                    RenderCall.ShowRichCard(view.text, view.sub, view.footer, forceShow = false),
                ),
                renderer.calls.takeLast(2),
            )

            controller.restoreAnchor()
            // A fresh show under the anchor's own key: the glasses carry fields over between
            // cards sharing a key, and the menu's subtitle must not survive on the anchor.
            assertEquals(
                RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                renderer.calls.last(),
            )
            assertEquals(
                listOf(
                    AssistantUiController.ANCHOR_CONTENT_KEY,
                    AssistantUiController.OPTIONS_CONTENT_KEY,
                    AssistantUiController.ANCHOR_CONTENT_KEY,
                ),
                renderer.contentKeys,
            )
            assertTrue(controller.isAnchored)
            assertTrue(controller.isNoticeBandMode)
            controller.onClose()
        }

    @Test
    fun `the Type chip arms well after listening starts and only where a field can open`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            assertFalse(controller.offersTyping)

            // The engine confirming it started redraws, and does not restart the wait.
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS / 2)
            runCurrent()
            controller.showListening()
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS / 2 - 1)
            runCurrent()
            assertFalse(controller.offersTyping)
            assertTrue(renderer.noticeActions.all { it.isEmpty() })

            advanceTimeBy(1)
            runCurrent()
            assertTrue(controller.offersTyping)
            assertEquals(AssistantUiController.TYPE_ACTIONS, renderer.noticeActions.last())
            assertEquals(RenderCall.UpdateNotice(null), renderer.calls.last())

            val unsupported = FakeRenderer(supportsNotice = true, supportsQuestionField = false)
            val plain = controller(unsupported)
            plain.onLauncherOpen()
            plain.beginGestureFlow()
            plain.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 2)
            runCurrent()
            assertFalse(plain.offersTyping)
            assertTrue(unsupported.noticeActions.all { it.isEmpty() })

            controller.onClose()
            plain.onClose()
        }

    @Test
    fun `the chip rides transcripts and keepalives and a fresh show takes it away`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
            runCurrent()
            renderer.clear()

            // Re-sending the row would ask the question again and re-arm the band.
            controller.showTranscript("what is the")
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertTrue(renderer.calls.all { it is RenderCall.UpdateNotice })
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertTrue(controller.offersTyping)

            // An update cannot clear a row, so leaving listening is a fresh band without one.
            controller.showTransient("Thinking…")
            assertEquals(RenderCall.ShowNotice("Assistant", "Thinking…"), renderer.calls.last())
            assertEquals(emptyList<NexusNoticeAction>(), renderer.noticeActions.last())
            assertEquals(true, renderer.noticeEngagement.last())
            assertFalse(controller.offersTyping)

            // And from then on it is ordinary updates again.
            controller.showAnswer("An answer.", listOf("An answer."))
            assertEquals(RenderCall.UpdateNotice("An answer."), renderer.calls.last())
            controller.onClose()
        }

    @Test
    fun `a spoken question that ends before the chip never shows it`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS - 1)
            runCurrent()
            controller.showTransient("Thinking…")
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 2)
            runCurrent()

            assertEquals(RenderCall.UpdateNotice("Thinking…"), renderer.calls.last())
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertFalse(controller.offersTyping)
            controller.onClose()
        }

    @Test
    fun `typing opens the field inside a quiet band that stays alive without asking again`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)

            controller.beginTyping()

            assertEquals(
                listOf(
                    RenderCall.ShowNotice("Assistant", null),
                    RenderCall.ShowQuestionField(
                        AssistantUiController.QUESTION_FIELD,
                        AssistantUiController.TYPING_FOOTER,
                    ),
                ),
                renderer.calls,
            )
            assertTrue(AssistantUiController.QUESTION_FIELD.inNotice)
            // Enter is the field's: an interactive band would claim it first.
            assertEquals(false, renderer.noticeEngagement.single())
            assertEquals(emptyList<NexusNoticeAction>(), renderer.noticeActions.single())
            assertEquals(AssistantUiController.TYPING_FOOTER, renderer.noticeFooters.single())
            assertEquals(AssistantUiController.TYPING_TTL_MS, renderer.showTtls.single())
            assertTrue(controller.isTyping)
            assertFalse(controller.isAnchored)
            assertFalse(controller.offersTyping)
            assertTrue(controller.isNoticeBandMode)

            renderer.clear()
            advanceTimeBy(AssistantUiController.TYPING_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertEquals(3, renderer.calls.size)
            assertTrue(renderer.calls.all { it == RenderCall.UpdateNotice(null) })
            assertTrue(renderer.noticeEngagement.all { it == null })
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertTrue(renderer.updateTtls.all { it == AssistantUiController.TYPING_TTL_MS })
            controller.onClose()
        }

    @Test
    fun `a submitted question puts the anchor back and the pipeline redraws the band fresh`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)
            controller.beginTyping()
            renderer.clear()

            assertTrue(controller.endTyping(AssistantTypingEnd.SUBMITTED))

            assertEquals(
                listOf(RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true)),
                renderer.calls,
            )
            assertEquals(AssistantUiController.ANCHOR_CONTENT_KEY, renderer.contentKeys.last())
            assertTrue(controller.isAnchored)
            assertFalse(controller.isTyping)

            // The typing footer cannot be updated away, so Thinking is a fresh engaged band.
            controller.showTransient("Thinking…")
            assertEquals(RenderCall.ShowNotice("Assistant", "Thinking…"), renderer.calls.last())
            assertNull(renderer.noticeFooters.last())
            assertEquals(true, renderer.noticeEngagement.last())

            // The keepalive ended with the field.
            renderer.clear()
            advanceTimeBy(AssistantUiController.TYPING_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.UpdateNotice(null) })
            assertFalse(controller.endTyping(AssistantTypingEnd.SUBMITTED))
            controller.onClose()
        }

    @Test
    fun `cancelling restores what was there before the field`() =
        runTest {
            val anchoredRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val anchored = controller(anchoredRenderer)
            armedChip(anchored, anchoredRenderer)
            anchored.beginTyping()
            anchoredRenderer.clear()

            anchored.endTyping(AssistantTypingEnd.CANCELLED)

            assertEquals(
                listOf(
                    RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                    RenderCall.HideNotice,
                ),
                anchoredRenderer.calls,
            )
            assertTrue(anchored.isAnchored)
            anchored.onClose()

            // Opened by the assist button, nothing was on screen: hiding the field is the
            // hub's close, exactly what Back on that band would have been.
            val bareRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val bare = controller(bareRenderer)
            bare.onOpen()
            bare.cancelLauncherHint()
            bare.beginGestureFlow()
            bare.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
            runCurrent()
            bare.beginTyping()
            bareRenderer.clear()

            bare.endTyping(AssistantTypingEnd.CANCELLED)

            assertEquals(listOf(RenderCall.HideCard, RenderCall.HideNotice), bareRenderer.calls)
            assertFalse(bare.isTyping)
            assertTrue(bare.isNoticeBandMode)
            bare.onClose()
        }

    @Test
    fun `a question typed without an anchor keeps only a bare holder under its band`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            assistButtonTyping(controller)
            renderer.clear()

            controller.endTyping(AssistantTypingEnd.SUBMITTED)

            // No anchor words, no footer, a key of its own so nothing is inherited either.
            assertEquals(listOf(RenderCall.ShowCard(emptyList(), forceShow = true)), renderer.calls)
            assertEquals(AssistantUiController.HOLDER_CONTENT_KEY, renderer.contentKeys.last())
            assertEquals(null, renderer.footers.last())
            assertFalse(controller.isAnchored)
            assertTrue(controller.isNoticeBandMode)
            controller.onClose()
        }

    @Test
    fun `Back on the typing band cancels and a lifetime timeout carries the field on`() =
        runTest {
            var pipelineCancels = 0
            var captureResets = 0
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = AssistantUiController(
                scope = this,
                renderer = renderer,
                cancelPipeline = { pipelineCancels += 1 },
                resetCapture = { captureResets += 1 },
                noticeIntervalMs = 0L,
            )
            armedChip(controller, renderer)
            controller.beginTyping()
            renderer.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)
            assertEquals(listOf(RenderCall.ShowNotice("Assistant", null)), renderer.calls)
            assertEquals(false, renderer.noticeEngagement.single())
            assertTrue(controller.isTyping)
            assertEquals(0, pipelineCancels)

            renderer.clear()
            controller.onNoticeClosed(NexusNoticeCloseReason.REPLACED)
            assertTrue(renderer.calls.isEmpty())
            assertTrue(controller.isTyping)

            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertEquals(
                listOf(RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true)),
                renderer.calls,
            )
            assertFalse(controller.isTyping)
            assertEquals(1, pipelineCancels)
            assertEquals(1, captureResets)
            controller.onClose()
        }

    @Test
    fun `a field rejected after it was sent says so and leaves the anchor in charge`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)
            controller.beginTyping()
            renderer.clear()

            renderer.rejectQuestionField(AssistantUiController.SURFACE_BUSY)

            assertEquals(
                RenderCall.ShowNotice("Assistant", AssistantUiController.SCREEN_BUSY),
                renderer.calls.single(),
            )
            assertFalse(controller.isTyping)
            assertTrue(controller.isAnchored)

            // A stale rejection for a field that already ended changes nothing.
            renderer.clear()
            renderer.rejectQuestionField(AssistantUiController.SURFACE_BUSY)
            assertTrue(renderer.calls.isEmpty())

            val refused = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                questionFieldResult = NexusSdkResult.NOT_REGISTERED,
            )
            val refusedController = controller(refused)
            armedChip(refusedController, refused)
            refusedController.beginTyping()
            assertFalse(refusedController.isTyping)
            assertTrue(refusedController.isAnchored)
            // The listening band may still be the one up, so it goes before the error does.
            assertEquals(
                listOf(
                    RenderCall.HideNotice,
                    RenderCall.ShowNotice("Assistant", AssistantUiController.QUESTION_FIELD_FAILED),
                ),
                refused.calls.takeLast(2),
            )
            controller.onClose()
            refusedController.onClose()
        }

    @Test
    fun `a new spoken question supersedes the field without closing the session`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
            runCurrent()
            controller.beginTyping()
            renderer.clear()

            controller.endTyping(AssistantTypingEnd.SUPERSEDED)
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)

            // The holder, never the anchor, and the quiet band is replaced rather than hidden
            // first, so nothing leaves that card uncovered in between.
            assertEquals(
                listOf(
                    RenderCall.ShowCard(emptyList(), forceShow = true),
                    RenderCall.ShowNotice("Assistant", AssistantUiController.LISTENING_BODY),
                ),
                renderer.calls,
            )
            assertEquals(true, renderer.noticeEngagement.last())
            controller.onClose()
        }

    @Test
    fun `the quiet band waits its turn behind a transcript and the field opens only after it`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
            runCurrent()
            assertTrue(controller.offersTyping)
            advanceTimeBy(AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            renderer.clear()

            controller.showTranscript("what is")
            controller.beginTyping()

            // Sent now it would be the hub's sixth message this second, and dropped.
            assertEquals(listOf(RenderCall.UpdateNotice("what is")), renderer.calls)
            assertTrue(controller.isTyping)

            advanceTimeBy(AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            assertEquals(
                listOf(
                    RenderCall.UpdateNotice("what is"),
                    RenderCall.ShowNotice("Assistant", null),
                    RenderCall.ShowQuestionField(
                        AssistantUiController.QUESTION_FIELD,
                        AssistantUiController.TYPING_FOOTER,
                    ),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `a stream of partials never puts more than four band messages in a second`() =
        runTest {
            val renderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                clock = { currentTime },
            )
            val controller = controller(renderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            repeat(60) { index ->
                controller.showTranscript("partial $index")
                advanceTimeBy(100)
                runCurrent()
            }
            advanceTimeBy(1_000)
            runCurrent()

            val times = renderer.noticeSendTimes
            times.forEachIndexed { index, start ->
                val inWindow = times.drop(index).count { it < start + 1_000 }
                assertTrue("$inWindow messages from $start ms", inWindow <= 4)
            }
            // Pacing folds, it does not lose: the band ends on the latest words, chip and all.
            assertEquals(RenderCall.UpdateNotice("partial 59"), renderer.calls.last())
            assertTrue(controller.offersTyping)
            controller.onClose()
        }

    @Test
    fun `a quiet band that cannot be shown hides the listening band instead of opening the field`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)
            renderer.failNextShow = true

            controller.beginTyping()

            assertEquals(
                listOf(
                    RenderCall.ShowNotice("Assistant", null),
                    RenderCall.HideNotice,
                    RenderCall.ShowNotice("Assistant", AssistantUiController.QUESTION_FIELD_FAILED),
                ),
                renderer.calls,
            )
            assertFalse(controller.isTyping)
            assertFalse(controller.offersTyping)
            assertTrue(controller.isAnchored)
            controller.onClose()
        }

    @Test
    fun `a quiet band that fails after waiting never opens the field either`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS + AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            renderer.clear()
            controller.showTranscript("what is")
            controller.beginTyping()
            renderer.failNextShow = true

            advanceTimeBy(AssistantNoticePacer.MIN_INTERVAL_MS * 2)
            runCurrent()

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice("what is"),
                    RenderCall.ShowNotice("Assistant", null),
                    RenderCall.HideNotice,
                    RenderCall.ShowNotice("Assistant", AssistantUiController.QUESTION_FIELD_FAILED),
                ),
                renderer.calls,
            )
            assertFalse(controller.isTyping)
            controller.onClose()
        }

    @Test
    fun `a rate-limited message while typing brings the quiet band back without a second field`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)
            controller.beginTyping()
            renderer.clear()

            controller.onNoticeRejected()

            assertEquals(listOf(RenderCall.ShowNotice("Assistant", null)), renderer.calls)
            assertEquals(false, renderer.noticeEngagement.single())
            assertEquals(AssistantUiController.TYPING_FOOTER, renderer.noticeFooters.single())
            assertTrue(controller.isTyping)
            controller.onClose()
        }

    @Test
    fun `a rate-limited message while listening redraws the whole band with its chip`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)

            controller.onNoticeRejected()

            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", AssistantUiController.LISTENING_BODY)),
                renderer.calls,
            )
            assertEquals(AssistantUiController.TYPE_ACTIONS, renderer.noticeActions.single())
            assertEquals(true, renderer.noticeEngagement.single())
            assertTrue(controller.offersTyping)

            // Back to ordinary updates once the band is known again.
            renderer.clear()
            controller.showTranscript("what is")
            assertEquals(listOf(RenderCall.UpdateNotice("what is")), renderer.calls)
            controller.onClose()
        }

    @Test
    fun `a dismissed band takes a show still waiting to leave with it`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS + AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            renderer.clear()
            controller.showTranscript("what is")
            // Clearing the chip takes a fresh show, and it has to wait.
            controller.showTransient("Thinking…")

            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(RenderCall.UpdateNotice("what is")), renderer.calls)
            controller.onClose()
        }

    @Test
    fun `re-opening during a capture brings the listening band back fresh and re-arms the chip`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)

            // What the service does when an open lands while its capture is still live.
            controller.onLauncherOpen()
            assertFalse(controller.offersTyping)
            controller.resumeInFlight(capturing = true, transcribing = false)

            assertEquals(
                listOf(
                    RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                    RenderCall.ShowNotice("Assistant", AssistantUiController.LISTENING_BODY),
                ),
                renderer.calls,
            )
            // A fresh band: the chip left on the glasses from before the open is gone...
            assertEquals(emptyList<NexusNoticeAction>(), renderer.noticeActions.single())
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
            runCurrent()
            // ...and comes back once the new wait is over, live this time.
            assertEquals(AssistantUiController.TYPE_ACTIONS, renderer.noticeActions.last())
            assertTrue(controller.offersTyping)
            controller.onClose()
        }

    @Test
    fun `a cancel that beats the field still closes a session that had no anchor`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS + AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            renderer.clear()
            controller.showTranscript("what is")
            // The quiet band waits out the pacer, so the field is not up yet...
            controller.beginTyping()

            // ...when Back takes the band.
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            advanceTimeBy(1_000)
            runCurrent()

            // Mic off and nothing on screen: the hub has to hear the close, as after the field.
            assertEquals(
                listOf(RenderCall.UpdateNotice("what is"), RenderCall.HideCard),
                renderer.calls,
            )
            assertFalse(controller.isTyping)
            controller.onClose()

            // With the anchor up there is nothing to close: it simply stays.
            val anchoredRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val anchored = controller(anchoredRenderer, AssistantNoticePacer.MIN_INTERVAL_MS)
            anchored.onLauncherOpen()
            anchored.beginGestureFlow()
            anchored.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS + AssistantNoticePacer.MIN_INTERVAL_MS)
            runCurrent()
            anchoredRenderer.clear()
            anchored.showTranscript("what is")
            anchored.beginTyping()
            anchored.onNoticeClosed(NexusNoticeCloseReason.USER)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(RenderCall.UpdateNotice("what is")), anchoredRenderer.calls)
            assertTrue(anchored.isAnchored)
            anchored.onClose()
        }

    @Test
    fun `re-opening while audio is transcribed keeps Transcribing up instead of the hint`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            // An assist-button open queues its hint; the transcription it lands on is not over.
            controller.onOpen()
            controller.resumeInFlight(capturing = true, transcribing = true)

            assertEquals(
                listOf(RenderCall.ShowNotice("Assistant", AssistantUiController.TRANSCRIBING_BODY)),
                renderer.calls,
            )
            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()
            // No hint card over it, and the keepalive holds it past the derived 4 s minimum.
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 2)
            runCurrent()
            assertTrue(renderer.calls.none { it is RenderCall.ShowCard })
            assertEquals(
                listOf(
                    RenderCall.UpdateNotice(AssistantUiController.TRANSCRIBING_BODY),
                    RenderCall.UpdateNotice(AssistantUiController.TRANSCRIBING_BODY),
                ),
                renderer.calls.drop(1),
            )
            // Past listening, so no Type chip for recorded audio.
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertFalse(controller.offersTyping)

            // Nothing under way: a re-open leaves the reset as it is.
            renderer.clear()
            controller.onOpen()
            controller.resumeInFlight(capturing = false, transcribing = false)
            assertTrue(renderer.calls.none { it is RenderCall.ShowNotice })
            controller.onClose()
        }

    @Test
    fun `voice only never arms the chip even where a field could open`() =
        runTest {
            val renderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                chosenInputMode = AssistantInputMode.VOICE_ONLY,
            )
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()

            assertFalse(controller.startQuestion())
            controller.showListening(legacyForceShow = true)
            controller.showTranscript("what is")
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 4)
            runCurrent()

            // The band as it was before the chip: nothing a tap could turn into a keyboard.
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertFalse(controller.offersTyping)
            assertTrue(renderer.calls.none { it is RenderCall.ShowQuestionField })
            assertEquals(AssistantInputMode.VOICE_ONLY, controller.inputMode)
            controller.onClose()
        }

    @Test
    fun `type first opens the field at once and never shows a listening band`() =
        runTest {
            val renderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            renderer.clear()

            // True: the caller must not open the microphone.
            assertTrue(controller.startQuestion())

            assertEquals(
                listOf(
                    RenderCall.ShowNotice("Assistant", null),
                    RenderCall.ShowQuestionField(
                        AssistantUiController.QUESTION_FIELD,
                        AssistantUiController.TYPING_FOOTER,
                    ),
                ),
                renderer.calls,
            )
            assertEquals(false, renderer.noticeEngagement.first())
            assertTrue(controller.isTyping)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 4)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.ShowNotice("Assistant", AssistantUiController.LISTENING_BODY) })
            assertTrue(renderer.noticeActions.all { it.isEmpty() })

            // Back cancels: the anchor comes back, and the next question opens typed again.
            renderer.clear()
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertEquals(
                listOf(
                    RenderCall.ShowCard(AssistantUiController.TYPE_FIRST_ANCHOR_LINES, forceShow = true),
                ),
                renderer.calls,
            )
            assertFalse(controller.isTyping)
            controller.beginGestureFlow()
            assertTrue(controller.startQuestion())
            assertTrue(controller.isTyping)
            controller.onClose()
        }

    @Test
    fun `without the editable bit or a band every choice falls back to voice`() =
        runTest {
            val noField = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = false,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val noFieldController = controller(noField)
            noFieldController.onLauncherOpen()
            noFieldController.beginGestureFlow()
            assertEquals(AssistantInputMode.VOICE_ONLY, noFieldController.inputMode)
            // False: the caller listens, exactly as Voice only would.
            assertFalse(noFieldController.startQuestion())
            assertTrue(noField.calls.none { it is RenderCall.ShowQuestionField })
            noFieldController.onClose()

            val noBand = FakeRenderer(
                supportsNotice = false,
                supportsQuestionField = true,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val noBandController = controller(noBand)
            assertEquals(AssistantInputMode.VOICE_ONLY, noBandController.inputMode)
            assertFalse(noBandController.startQuestion())

            val chipNoField = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = false,
                chosenInputMode = AssistantInputMode.VOICE_AND_TYPE,
            )
            val chipController = controller(chipNoField)
            chipController.onLauncherOpen()
            chipController.beginGestureFlow()
            chipController.showListening(legacyForceShow = true)
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 2)
            runCurrent()
            assertTrue(chipNoField.noticeActions.all { it.isEmpty() })
            assertFalse(chipController.offersTyping)
            chipController.onClose()
        }

    @Test
    fun `leaving Voice + Type mid-question stops the chip arriving and takes its tap back`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            controller.onLauncherOpen()
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)

            // Switched before the chip's wait is over: it never arrives.
            renderer.chosenInputMode = AssistantInputMode.VOICE_ONLY
            advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS * 2)
            runCurrent()
            assertTrue(renderer.noticeActions.all { it.isEmpty() })
            assertFalse(controller.offersTyping)
            controller.onClose()

            // Switched once it is up: still drawn, but a tap on it no longer opens anything.
            val armedRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val armed = controller(armedRenderer)
            armedChip(armed, armedRenderer)
            armedRenderer.chosenInputMode = AssistantInputMode.VOICE_ONLY
            assertFalse(armed.offersTyping)
            armedRenderer.chosenInputMode = AssistantInputMode.TYPE_FIRST
            assertFalse(armed.offersTyping)
            armedRenderer.chosenInputMode = AssistantInputMode.VOICE_AND_TYPE
            assertTrue(armed.offersTyping)
            armed.onClose()
        }

    @Test
    fun `type first anchors on typing words while the voice modes keep Ask out loud`() =
        runTest {
            val renderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val controller = controller(renderer)
            controller.onLauncherOpen()
            assertEquals(
                RenderCall.ShowCard(AssistantUiController.TYPE_FIRST_ANCHOR_LINES, forceShow = true),
                renderer.calls.last(),
            )
            // The anchor the field hands back after Back says the same.
            controller.beginGestureFlow()
            assertTrue(controller.startQuestion())
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertEquals(
                RenderCall.ShowCard(AssistantUiController.TYPE_FIRST_ANCHOR_LINES, forceShow = true),
                renderer.calls.last(),
            )
            assertTrue(renderer.footers.all { it == AssistantUiController.ANCHOR_FOOTER })
            // And the assist-button hint does not ask the wearer to speak.
            renderer.clear()
            controller.onOpen()
            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()
            assertEquals(
                RenderCall.ShowCard(listOf(AssistantUiController.TYPE_FIRST_LAUNCHER_HINT), forceShow = true),
                renderer.calls.last(),
            )
            controller.onClose()

            AssistantInputMode.entries.filter { it != AssistantInputMode.TYPE_FIRST }.forEach { mode ->
                val voiceRenderer = FakeRenderer(
                    supportsNotice = true,
                    supportsQuestionField = true,
                    chosenInputMode = mode,
                )
                val voice = controller(voiceRenderer)
                voice.onLauncherOpen()
                assertEquals(
                    RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                    voiceRenderer.calls.last(),
                )
                voice.onOpen()
                advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
                runCurrent()
                assertEquals(
                    RenderCall.ShowCard(listOf(AssistantUiController.LAUNCHER_HINT), forceShow = true),
                    voiceRenderer.calls.last(),
                )
                voice.onClose()
            }

            // Type first that the glasses cannot honour is voice, words included.
            val fallbackRenderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = false,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val fallback = controller(fallbackRenderer)
            fallback.onLauncherOpen()
            assertEquals(
                RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                fallbackRenderer.calls.last(),
            )
            fallback.onClose()
        }

    @Test
    fun `the holder goes only once the band has closed and the voice is done`() =
        runTest {
            var speaking = false
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, speaking = { speaking })
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showTransient("Thinking…")
            controller.showAnswer("42", listOf("42"))
            controller.onPipelineFinished()
            speaking = true
            controller.onAnswerSpeechStarted()
            renderer.clear()

            // The band has gone but the voice is still reading: the session has to stay.
            controller.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)
            assertTrue(renderer.calls.none { it == RenderCall.HideCard })

            speaking = false
            controller.onAnswerSpeechFinished()
            assertEquals(listOf(RenderCall.HideCard), renderer.calls)
            controller.onClose()

            // Silent answers: the band closing is the whole end.
            val silentRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val silent = controller(silentRenderer)
            assistButtonTyping(silent)
            silent.endTyping(AssistantTypingEnd.SUBMITTED)
            silent.showAnswer("42", listOf("42"))
            silent.onPipelineFinished()
            assertTrue(silentRenderer.calls.none { it == RenderCall.HideCard })
            silent.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)
            assertEquals(RenderCall.HideCard, silentRenderer.calls.last())
            silent.onClose()
        }

    @Test
    fun `the holder waits out a model call whose band was taken by another plugin`() =
        runTest {
            var busy = false
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, busy = { busy })
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            busy = true
            controller.showTransient("Thinking…")
            renderer.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.REPLACED)
            assertTrue(renderer.calls.none { it == RenderCall.HideCard })

            // The answer takes the band back, and only its close ends the session.
            controller.showAnswer("42", listOf("42"))
            busy = false
            controller.onPipelineFinished()
            assertTrue(renderer.calls.none { it == RenderCall.HideCard })
            controller.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)
            assertEquals(RenderCall.HideCard, renderer.calls.last())
            controller.onClose()
        }

    @Test
    fun `Back while thinking and an error band both end the holder cleanly`() =
        runTest {
            var cancels = 0
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = AssistantUiController(
                scope = this,
                renderer = renderer,
                cancelPipeline = { cancels += 1 },
                resetCapture = {},
                noticeIntervalMs = 0L,
            )
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showTransient("Thinking…")
            renderer.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertEquals(1, cancels)
            assertEquals(listOf(RenderCall.HideCard), renderer.calls)
            controller.onClose()

            val errorRenderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val errored = controller(errorRenderer)
            assistButtonTyping(errored)
            errored.endTyping(AssistantTypingEnd.SUBMITTED)
            errored.showError("Request failed. Try again.")
            errorRenderer.clear()
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertEquals(listOf(RenderCall.HideNotice, RenderCall.HideCard), errorRenderer.calls)
            errored.onClose()
        }

    @Test
    fun `a voice that never reports its end cannot hold the session open for good`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, speaking = { true })
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showAnswer("42", listOf("42"))
            renderer.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)
            advanceTimeBy(AssistantUiController.HOLDER_SPEECH_GRACE_MS - 1)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideCard })
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(RenderCall.HideCard), renderer.calls)
            controller.onClose()
        }

    @Test
    fun `a follow-up press while the holder is up stays a band and never shows the anchor`() =
        runTest {
            var busy = false
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer, busy = { busy })
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showAnswer("42", listOf("42"))
            renderer.clear()

            // The assist button again: a new capture over the same holder.
            busy = true
            controller.beginGestureFlow()
            controller.showListening(legacyForceShow = true)
            // The answer's band turns into Listening in place; the card tier is untouched.
            assertEquals(
                listOf(RenderCall.UpdateNotice(AssistantUiController.LISTENING_BODY)),
                renderer.calls,
            )
            // It comes to nothing: its error band closes and the session goes with it.
            busy = false
            controller.showError("Didn't catch that")
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertEquals(RenderCall.HideCard, renderer.calls.last())
            assertTrue(renderer.calls.none { it is RenderCall.ShowCard })
            controller.onClose()

            // Type first instead: the field takes the holder's place, and Back closes as usual.
            val typedRenderer = FakeRenderer(
                supportsNotice = true,
                supportsQuestionField = true,
                chosenInputMode = AssistantInputMode.TYPE_FIRST,
            )
            val typed = controller(typedRenderer)
            typed.onOpen()
            typed.cancelLauncherHint()
            typed.beginGestureFlow()
            assertTrue(typed.startQuestion())
            typed.endTyping(AssistantTypingEnd.SUBMITTED)
            typed.showAnswer("42", listOf("42"))
            typed.beginGestureFlow()
            assertTrue(typed.startQuestion())
            typedRenderer.clear()
            typed.onNoticeClosed(NexusNoticeCloseReason.USER)
            assertEquals(listOf(RenderCall.HideCard), typedRenderer.calls)
            typed.onClose()
        }

    @Test
    fun `an Ink answer takes over from the holder without closing the session`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            assistButtonTyping(controller)
            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showTransient("Thinking…")
            renderer.clear()

            controller.onInkAnswerShown()

            // The page holds the session now; its dismissal is what ends it.
            assertEquals(listOf(RenderCall.HideNotice, RenderCall.HideCard), renderer.calls)
            controller.onNoticeClosed(NexusNoticeCloseReason.OWNER)
            controller.onPipelineFinished()
            assertEquals(1, renderer.calls.count { it == RenderCall.HideCard })
            controller.onClose()
        }

    @Test
    fun `the launcher path still brings its anchor back after a typed question`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true, supportsQuestionField = true)
            val controller = controller(renderer)
            armedChip(controller, renderer)
            controller.beginTyping()
            renderer.clear()

            controller.endTyping(AssistantTypingEnd.SUBMITTED)
            controller.showAnswer("42", listOf("42"))
            controller.onNoticeClosed(NexusNoticeCloseReason.TIMEOUT)

            assertEquals(
                RenderCall.ShowCard(AssistantUiController.ANCHOR_LINES, forceShow = true),
                renderer.calls.first(),
            )
            assertTrue(controller.isAnchored)
            // The anchor is the wearer's to close: nothing releases it on its own.
            assertTrue(renderer.calls.none { it == RenderCall.HideCard })
            controller.onClose()
        }

    /** An assist-button session (no anchor) with its typed field open. */
    private fun TestScope.assistButtonTyping(controller: AssistantUiController) {
        controller.onOpen()
        controller.cancelLauncherHint()
        controller.beginGestureFlow()
        controller.showListening(legacyForceShow = true)
        advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
        runCurrent()
        controller.beginTyping()
        assertTrue(controller.isTyping)
        assertFalse(controller.isAnchored)
    }

    private fun TestScope.armedChip(controller: AssistantUiController, renderer: FakeRenderer) {
        controller.onLauncherOpen()
        controller.beginGestureFlow()
        controller.showListening(legacyForceShow = true)
        advanceTimeBy(AssistantUiController.TYPE_CHIP_ARM_DELAY_MS)
        runCurrent()
        assertTrue(controller.offersTyping)
        renderer.clear()
    }

    /** Unpaced unless asked: most of these tests are about what is sent, not when. */
    private fun TestScope.controller(
        renderer: FakeRenderer,
        noticeIntervalMs: Long = 0L,
        busy: () -> Boolean = { false },
        speaking: () -> Boolean = { false },
    ): AssistantUiController =
        AssistantUiController(
            scope = this,
            renderer = renderer,
            cancelPipeline = {},
            resetCapture = {},
            noticeIntervalMs = noticeIntervalMs,
            sessionBusy = busy,
            answerSpeaking = speaking,
        )

    private fun assertValidTruncatedBody(body: String?) {
        requireNotNull(body)
        assertTrue(body.length <= NoticeSurfaceContract.MAX_BODY_CHARS)
        assertTrue(body.endsWith(AssistantUiController.ELLIPSIS))
        NexusNotice(title = AssistantUiController.NOTICE_TITLE, body = body)
        NexusNoticeUpdate(body = body)
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

        data class ShowRichCard(
            val text: String,
            val sub: String?,
            val footer: String?,
            val forceShow: Boolean,
        ) : RenderCall

        data class ShowQuestionField(
            val field: EditableSurfaceField,
            val footer: String,
        ) : RenderCall

        data object HideCard : RenderCall
    }

    private class FakeRenderer(
        private val supportsNotice: Boolean,
        override val supportsQuestionField: Boolean = false,
        private val questionFieldResult: NexusSdkResult = NexusSdkResult.SENT,
        // The chip's own mode, so the tests written before the Input setting keep their meaning.
        override var chosenInputMode: AssistantInputMode = AssistantInputMode.VOICE_AND_TYPE,
        private val clock: () -> Long = { 0L },
    ) : AssistantUiRenderer {
        val calls = mutableListOf<RenderCall>()

        /** When each show or update left, for the rate the hub would count. */
        val noticeSendTimes = mutableListOf<Long>()

        /** The next show fails the way an unreachable hub makes it fail. */
        var failNextShow = false

        /** Kept beside [calls] so the existing call assertions stay about bodies alone. */
        val updateTtls = mutableListOf<Long?>()
        val showTtls = mutableListOf<Long?>()
        val noticeEngagement = mutableListOf<Boolean?>()

        /** Per show or update; an update's empty row means the key was left out. */
        val noticeActions = mutableListOf<List<NexusNoticeAction>>()
        val noticeFooters = mutableListOf<String?>()
        private var questionFieldRejected: ((String) -> Unit)? = null

        override val supportsNoticeSurface: Boolean
            get() = supportsNotice

        override fun showNotice(notice: NexusNotice): NexusSdkResult {
            calls += RenderCall.ShowNotice(notice.title, notice.body, notice.lines)
            showTtls += notice.ttlMs
            noticeEngagement += notice.interactive
            noticeActions += notice.actions
            noticeFooters += notice.footer
            if (failNextShow) {
                failNextShow = false
                return NexusSdkResult.NOT_REGISTERED
            }
            noticeSendTimes += clock()
            return NexusSdkResult.SENT
        }

        override fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult {
            noticeSendTimes += clock()
            calls += RenderCall.UpdateNotice(update.body, update.lines)
            updateTtls += update.ttlMs
            noticeEngagement += update.interactive
            noticeActions += update.actions
            noticeFooters += update.footer
            return NexusSdkResult.SENT
        }

        override fun showQuestionField(
            field: EditableSurfaceField,
            footer: String,
            onRejected: (code: String) -> Unit,
        ): NexusSdkResult {
            calls += RenderCall.ShowQuestionField(field, footer)
            questionFieldRejected = onRejected
            return questionFieldResult
        }

        override fun hideCard(): NexusSdkResult {
            calls += RenderCall.HideCard
            return NexusSdkResult.SENT
        }

        fun rejectQuestionField(code: String) {
            questionFieldRejected?.invoke(code)
        }

        fun clear() {
            calls.clear()
            updateTtls.clear()
            showTtls.clear()
            noticeEngagement.clear()
            noticeActions.clear()
            noticeFooters.clear()
        }

        override fun hideNotice(): NexusSdkResult {
            calls += RenderCall.HideNotice
            return NexusSdkResult.SENT
        }

        /** Footers and keys are recorded apart so the existing call assertions stay about bodies alone. */
        val footers = mutableListOf<String?>()
        val contentKeys = mutableListOf<String?>()

        override fun showCard(
            lines: List<String>,
            forceShow: Boolean,
            footer: String?,
            contentKey: String?,
        ): NexusSdkResult {
            calls += RenderCall.ShowCard(lines, forceShow)
            footers += footer
            contentKeys += contentKey
            return NexusSdkResult.SENT
        }

        override fun showRichCard(
            subtitle: String?,
            lines: List<NexusCardLine>,
            footer: String?,
            forceShow: Boolean,
            contentKey: String?,
        ): NexusSdkResult {
            val row = lines.single()
            calls += RenderCall.ShowRichCard(row.text, row.sub, footer, forceShow)
            contentKeys += contentKey
            return NexusSdkResult.SENT
        }
    }
}
