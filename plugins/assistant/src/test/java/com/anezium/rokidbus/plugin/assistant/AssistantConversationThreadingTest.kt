package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantConversationThreadingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun typedQuestionFormattingSurvivesTheSharedPipelineProviderRequestAndConversation() {
        val store = AssistantThreadStore(temporaryFolder.root) { 1_000L }
        val threading = AssistantConversationThreading(store)
        val context = threading.prepare(keepConversation = true, idleWindowMinutes = 10)
        val question = "  Explain this code:\n\n    if (ready) {\n\tstart()\n    }\n"
        var providerRequest: ChatRequest? = null
        val input = AssistantTextInput(
            availability = { AssistantTextInputStatus.READY },
            onQuestion = { text ->
                assertTrue(dispatchAssistantQuestion(text) { userText ->
                    providerRequest = ChatRequest(userText = userText, history = context.history)
                    threading.recordCompletedTurn(context, userText, "It starts.", hadPhoto = false)
                })
                AssistantTextInputStatus.SENT
            },
            onNote = { throw AssertionError("Question was routed to note storage") },
        )
        val entry = checkNotNull(input.begin(AssistantTextEntryKind.PHONE_QUESTION).entry)
        assertEquals(AssistantTextInputStatus.SENT, input.submitPhone(entry.id, question))
        assertEquals(question, providerRequest?.userText)
        assertEquals(question, store.threads().single().messages.first().text)
        assertEquals(
            question,
            threading.prepare(keepConversation = true, idleWindowMinutes = 10).history.first().content,
        )
    }

    @Test
    fun enabledSecondRequestCarriesFirstCompletedTurn() {
        val store = AssistantThreadStore(temporaryFolder.root) { 1_000L }
        val threading = AssistantConversationThreading(store)
        val firstRequest = threading.prepare(keepConversation = true, idleWindowMinutes = 10)
        assertTrue(firstRequest.history.isEmpty())
        val conversationId = firstRequest.threadId
        assertTrue(!conversationId.isNullOrBlank())

        threading.recordCompletedTurn(
            context = firstRequest,
            userText = "Paris",
            assistantText = "Paris is in France.",
            hadPhoto = false,
        )

        val secondRequest = threading.prepare(keepConversation = true, idleWindowMinutes = 10)
        assertEquals(conversationId, secondRequest.threadId)
        assertEquals(conversationId, store.threads().single().id)
        assertEquals(
            listOf("user", "assistant"),
            secondRequest.history.map(ChatMessage::role),
        )
        assertEquals(
            listOf("Paris", "Paris is in France."),
            secondRequest.history.map(ChatMessage::content),
        )
    }

    @Test
    fun disabledConversationHasNoHistoryAndRecordsNothing() {
        val store = AssistantThreadStore(temporaryFolder.root) { 1_000L }
        val threading = AssistantConversationThreading(store)
        val enabled = threading.prepare(keepConversation = true, idleWindowMinutes = 10)
        threading.recordCompletedTurn(enabled, "First", "Stored", hadPhoto = false)
        val storedMessages = store.threads().single().messages

        val disabled = threading.prepare(keepConversation = false, idleWindowMinutes = 10)
        assertTrue(disabled.history.isEmpty())
        threading.recordCompletedTurn(disabled, "Second", "Must not be stored", hadPhoto = false)

        assertEquals(storedMessages, store.threads().single().messages)
    }

    @Test
    fun failedTurnRecordsNothing() {
        val store = AssistantThreadStore(temporaryFolder.root) { 1_000L }
        val threading = AssistantConversationThreading(store)
        val request = threading.prepare(keepConversation = true, idleWindowMinutes = 10)

        threading.recordCompletedTurn(
            context = request,
            userText = "Will fail",
            assistantText = null,
            hadPhoto = false,
        )

        assertTrue(store.threads().isEmpty())
    }

    @Test
    fun replayAttachesOnlyTheTwoMostRecentReadablePhotos() {
        var now = 1_000L
        val store = AssistantThreadStore(temporaryFolder.root) { now }
        val threading = AssistantConversationThreading(store)
        repeat(3) { index ->
            val context = threading.prepare(keepConversation = true, idleWindowMinutes = 10)
            threading.recordCompletedTurn(
                context = context,
                userText = "Photo $index",
                assistantText = "Answer $index",
                hadPhoto = true,
                photoJpeg = byteArrayOf((index + 1).toByte()),
            )
            now += 1L
        }

        val history = threading.prepare(
            keepConversation = true,
            idleWindowMinutes = 10,
        ).history
        val userHistory = history.filter { message -> message.role == "user" }
        assertEquals(2, MAX_REPLAYED_THREAD_PHOTOS)
        assertTrue(userHistory[0].photos.single().isOmittedHistoryPhoto())
        assertEquals(
            listOf(0, 1, 1),
            userHistory.map { message ->
                message.photos.count { photo -> !photo.isOmittedHistoryPhoto() }
            },
        )

        val input = ChatRequest(userText = "What colour was it?", history = history)
            .toCodexResponsesInput()
        val imageUrls = buildList {
            for (messageIndex in 0 until input.length()) {
                val content = input.getJSONObject(messageIndex).getJSONArray("content")
                for (contentIndex in 0 until content.length()) {
                    val item = content.getJSONObject(contentIndex)
                    if (item.optString("type") == "input_image") {
                        add(item.getString("image_url"))
                    }
                }
            }
        }

        assertEquals(
            listOf(
                "data:image/jpeg;base64,Ag==",
                "data:image/jpeg;base64,Aw==",
            ),
            imageUrls,
        )
        val oldestPhotoContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals(
            "Photo 0\n[photo]",
            oldestPhotoContent.getJSONObject(0).getString("text"),
        )
        assertEquals(1, oldestPhotoContent.length())
    }
}
