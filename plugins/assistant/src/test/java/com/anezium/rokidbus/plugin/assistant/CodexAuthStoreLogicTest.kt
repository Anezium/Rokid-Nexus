package com.anezium.rokidbus.plugin.assistant

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAuthStoreLogicTest {
    @Test
    fun conversationSettingsHaveExpectedDefaults() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertTrue(store.keepConversation())
        assertTrue(store.keepPhotosInConversations())
        assertTrue(store.speakAnswers())
        assertEquals(CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES, store.conversationIdleWindowMinutes())
        assertEquals("", store.assistantMemory())
        assertTrue(store.syncAccountContext())
        assertEquals("", store.syncedAccountContext())
        assertEquals(0L, store.accountContextSyncedAtMs())
        assertEquals("", store.customSystemPrompt())
    }

    @Test
    fun inputModeDefaultsToVoiceOnlyAndRoundTrips() {
        val store = CodexAuthStore(FakeSharedPreferences())

        // An install that never saw the setting, new or updated, keeps asking out loud.
        assertEquals(AssistantInputMode.VOICE_ONLY, store.inputMode())
        AssistantInputMode.entries.forEach { mode ->
            store.setInputMode(mode)
            assertEquals(mode, store.inputMode())
        }
    }

    @Test
    fun speakAnswersDefaultsTrueAndRoundTrips() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertTrue(store.speakAnswers())

        store.setSpeakAnswers(false)
        assertFalse(store.speakAnswers())

        store.setSpeakAnswers(true)
        assertTrue(store.speakAnswers())
    }

    @Test
    fun idleWindowSetterCoercesToNearestSupportedValue() {
        val store = CodexAuthStore(FakeSharedPreferences())

        store.setConversationIdleWindowMinutes(7)

        assertEquals(5, store.conversationIdleWindowMinutes())
        assertTrue(
            store.conversationIdleWindowMinutes() in
                CodexAuthStore.SUPPORTED_IDLE_WINDOW_MINUTES,
        )
    }

    @Test
    fun sevenDayConversationWindowRoundTrips() {
        val store = CodexAuthStore(FakeSharedPreferences())

        store.setConversationIdleWindowMinutes(CodexAuthStore.SEVEN_DAYS_IN_MINUTES)

        assertEquals(
            CodexAuthStore.SEVEN_DAYS_IN_MINUTES,
            store.conversationIdleWindowMinutes(),
        )
    }

    @Test
    fun unlimitedConversationWindowRoundTrips() {
        val prefs = FakeSharedPreferences()
        val store = CodexAuthStore(prefs)

        store.setConversationIdleWindowMinutes(CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES)

        assertEquals(
            CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES,
            store.conversationIdleWindowMinutes(),
        )
        assertEquals(
            CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES,
            CodexAuthStore(prefs).conversationIdleWindowMinutes(),
        )
        assertEquals(
            listOf(
                2, 5, 10, 30,
                CodexAuthStore.SEVEN_DAYS_IN_MINUTES,
                CodexAuthStore.UNLIMITED_IDLE_WINDOW_MINUTES,
            ),
            CodexAuthStore.SUPPORTED_IDLE_WINDOW_MINUTES,
        )
    }

    @Test
    fun storedFiniteIdleWindowsKeepTheirNearestSupportedValue() {
        val expectedWindows = mapOf(
            Int.MIN_VALUE to 2,
            7 to 5,
            5055 to 30,
            5056 to CodexAuthStore.SEVEN_DAYS_IN_MINUTES,
            Int.MAX_VALUE - 1 to CodexAuthStore.SEVEN_DAYS_IN_MINUTES,
        )

        expectedWindows.forEach { (storedMinutes, expectedMinutes) ->
            val store = CodexAuthStore(
                FakeSharedPreferences(mapOf("conversation_idle_window_minutes" to storedMinutes)),
            )

            assertEquals(expectedMinutes, store.conversationIdleWindowMinutes())
            store.setConversationIdleWindowMinutes(storedMinutes)
            assertEquals(expectedMinutes, store.conversationIdleWindowMinutes())
        }
    }

    @Test
    fun memorySetterTrimsAndTruncatesToMaximumLength() {
        val store = CodexAuthStore(FakeSharedPreferences())
        val oversized = " \n" + "x".repeat(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS + 25) + "\t "

        store.setAssistantMemory(oversized)

        assertEquals(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS, store.assistantMemory().length)
        assertEquals("x".repeat(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS), store.assistantMemory())
    }

    @Test
    fun customSystemPromptTrimsAndTruncatesToMaximumLength() {
        val store = CodexAuthStore(FakeSharedPreferences())
        val oversized = " \n" +
            "x".repeat(CodexAuthStore.MAX_CUSTOM_SYSTEM_PROMPT_CHARS + 25) +
            "\t "

        store.setCustomSystemPrompt(oversized)

        assertEquals(CodexAuthStore.MAX_CUSTOM_SYSTEM_PROMPT_CHARS, store.customSystemPrompt().length)
        assertEquals(
            "x".repeat(CodexAuthStore.MAX_CUSTOM_SYSTEM_PROMPT_CHARS),
            store.customSystemPrompt(),
        )
    }

    @Test
    fun migratesLegacyApiKeyModeToOpenAiProvider() {
        val store = testStore(
            FakeSharedPreferences(
                mapOf(
                    "auth_mode" to CodexAuthStore.AUTH_MODE_API_KEY,
                    "api_key" to "legacy-openai-key",
                    "model" to "vendor/free-text-model",
                ),
            ),
        )

        assertEquals(ProviderCatalog.openAi.id, store.selectedProviderId())
        assertEquals("legacy-openai-key", store.providerApiKey(ProviderCatalog.openAi.id))
        assertEquals("vendor/free-text-model", store.providerModel(ProviderCatalog.openAi.id))
        assertEquals("vendor/free-text-model", store.model())
    }

    @Test
    fun migratesLegacyChatGptModeAndKeepsLegacyOpenAiKeyReadable() {
        val store = testStore(
            FakeSharedPreferences(
                mapOf(
                    "auth_mode" to CodexAuthStore.AUTH_MODE_CHATGPT,
                    "api_key" to "exchanged-openai-key",
                ),
            ),
        )

        assertEquals(CodexAuthStore.CHATGPT_PROVIDER_ID, store.selectedProviderId())
        assertEquals("exchanged-openai-key", store.apiKey())
        assertEquals("exchanged-openai-key", store.providerApiKey(ProviderCatalog.openAi.id))
    }

    @Test
    fun migrationDoesNotOverwriteProviderSettingsAlreadyWritten() {
        val store = testStore(
            FakeSharedPreferences(
                mapOf(
                    "auth_mode" to CodexAuthStore.AUTH_MODE_API_KEY,
                    "selected_provider_id" to ProviderCatalog.minimax.id,
                    "api_key" to "legacy-openai-key",
                    "api_key.openai" to "provider-openai-key",
                    "model" to "legacy-model",
                    "model.openai" to "provider-model",
                ),
            ),
        )

        assertEquals(ProviderCatalog.minimax.id, store.selectedProviderId())
        assertEquals("provider-openai-key", store.providerApiKey(ProviderCatalog.openAi.id))
        assertEquals("provider-model", store.providerModel(ProviderCatalog.openAi.id))
    }

    @Test
    fun providerApiKeysAreIsolatedAndLegacyAccessorStaysOpenAiOnly() {
        val store = testStore(FakeSharedPreferences())

        store.saveProviderApiKey(ProviderCatalog.openAi.id, "openai-key")
        store.saveProviderApiKey(ProviderCatalog.minimax.id, "minimax-key")

        assertEquals("openai-key", store.providerApiKey(ProviderCatalog.openAi.id))
        assertEquals("minimax-key", store.providerApiKey(ProviderCatalog.minimax.id))
        assertEquals("openai-key", store.apiKey())
    }

    @Test
    fun selectedProviderAndPerProviderValuesRoundTrip() {
        val store = testStore(FakeSharedPreferences())

        store.setSelectedProviderId(ProviderCatalog.openRouter.id)
        store.setProviderModel(ProviderCatalog.openRouter.id, " company/free-text ")
        store.setProviderBaseUrl(ProviderCatalog.openRouter.id, " https://gateway.test/v1/ ")
        store.setProviderEffort(ProviderCatalog.openRouter.id, "medium")

        assertEquals(ProviderCatalog.openRouter.id, store.selectedProviderId())
        assertEquals("company/free-text", store.providerModel(ProviderCatalog.openRouter.id))
        assertEquals("https://gateway.test/v1/", store.providerBaseUrl(ProviderCatalog.openRouter.id))
        assertEquals("medium", store.providerEffort(ProviderCatalog.openRouter.id))
    }

    @Test
    fun providerVisionDefaultsFromCatalogAndCanBeOverridden() {
        val store = testStore(FakeSharedPreferences())

        assertTrue(store.providerModelSupportsPhotos(ProviderCatalog.openAi.id))
        store.setProviderModel(ProviderCatalog.openAi.id, "free-text")
        assertFalse(store.providerModelSupportsPhotos(ProviderCatalog.openAi.id))
        store.setProviderModelSupportsPhotos(ProviderCatalog.openAi.id, true)
        assertTrue(store.providerModelSupportsPhotos(ProviderCatalog.openAi.id))
    }

    @Test
    fun providerReadinessUsesSelectedKeyAndRequiresSelfHostedBaseUrl() {
        val store = testStore(FakeSharedPreferences())
        store.saveProviderApiKey(ProviderCatalog.minimax.id, "minimax-key")
        store.setSelectedProviderId(ProviderCatalog.minimax.id)

        assertTrue(store.hasUsableAuth())

        store.saveProviderApiKey(ProviderCatalog.custom.id, "custom-key")
        store.setSelectedProviderId(ProviderCatalog.custom.id)
        assertFalse(store.hasUsableAuth())

        store.setProviderBaseUrl(ProviderCatalog.custom.id, "https://custom.test/v1")
        assertTrue(store.hasUsableAuth())

        store.saveProviderApiKey(ProviderCatalog.hermes.id, "hermes-key")
        store.setSelectedProviderId(ProviderCatalog.hermes.id)
        assertFalse(store.hasUsableAuth())

        store.setProviderBaseUrl(ProviderCatalog.hermes.id, "https://hermes.test/v1")
        assertTrue(store.hasUsableAuth())
    }

    @Test
    fun customBackendIsOnlyHermesAfterCapabilityDetectionAndResetsWithConnectionDetails() {
        val store = testStore(FakeSharedPreferences())

        assertEquals(ProviderBackend.HERMES, store.providerBackend(ProviderCatalog.hermes.id))
        assertEquals(ProviderBackend.OPENAI_COMPAT, store.providerBackend(ProviderCatalog.custom.id))
        assertFalse(store.providerModelSupportsPhotos(ProviderCatalog.custom.id))

        store.setProviderDetectedBackend(ProviderCatalog.custom.id, ProviderBackend.HERMES)

        assertEquals(ProviderBackend.HERMES, store.providerBackend(ProviderCatalog.custom.id))
        assertTrue(store.providerModelSupportsPhotos(ProviderCatalog.custom.id))

        store.setProviderBaseUrl(ProviderCatalog.custom.id, "https://new-server.test/v1")

        assertEquals(null, store.providerDetectedBackend(ProviderCatalog.custom.id))
        assertEquals(ProviderBackend.OPENAI_COMPAT, store.providerBackend(ProviderCatalog.custom.id))
    }

    @Test
    fun conversationSettingsRoundTripAndClearWithTheStore() {
        val store = CodexAuthStore(FakeSharedPreferences())
        store.setKeepConversation(false)
        store.setKeepPhotosInConversations(false)
        store.setSpeakAnswers(false)
        store.setConversationIdleWindowMinutes(30)
        store.setAssistantMemory("Uses metric units.")

        assertFalse(store.keepConversation())
        assertFalse(store.keepPhotosInConversations())
        assertFalse(store.speakAnswers())
        assertEquals(30, store.conversationIdleWindowMinutes())
        assertEquals("Uses metric units.", store.assistantMemory())

        store.clear()

        assertTrue(store.keepConversation())
        assertTrue(store.keepPhotosInConversations())
        assertTrue(store.speakAnswers())
        assertEquals(CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES, store.conversationIdleWindowMinutes())
        assertEquals("", store.assistantMemory())
    }

    @Test
    fun syncedAccountContextSettingsRoundTripCapAndClear() {
        val store = CodexAuthStore(FakeSharedPreferences())
        val oversized = " " +
            "x".repeat(CodexAuthStore.MAX_SYNCED_ACCOUNT_CONTEXT_CHARS + 25) +
            " "

        store.setSyncAccountContext(false)
        store.setSyncedAccountContext(oversized)
        store.setAccountContextSyncedAtMs(123_456L)

        assertFalse(store.syncAccountContext())
        assertEquals(
            "x".repeat(CodexAuthStore.MAX_SYNCED_ACCOUNT_CONTEXT_CHARS),
            store.syncedAccountContext(),
        )
        assertEquals(123_456L, store.accountContextSyncedAtMs())

        store.clearSyncedAccountContext()

        assertEquals("", store.syncedAccountContext())
        assertEquals(0L, store.accountContextSyncedAtMs())
        assertFalse(store.syncAccountContext())
    }

    @Test
    fun readinessUsesOAuthTokensForChatGptAndKeyForApiMode() {
        assertTrue(
            hasUsableAuth(
                authMode = CodexAuthStore.AUTH_MODE_CHATGPT,
                hasOAuthTokens = true,
                hasApiKey = false,
            ),
        )
        assertTrue(
            hasUsableAuth(
                authMode = CodexAuthStore.AUTH_MODE_API_KEY,
                hasOAuthTokens = false,
                hasApiKey = true,
            ),
        )
        assertFalse(
            hasUsableAuth(
                authMode = null,
                hasOAuthTokens = false,
                hasApiKey = false,
            ),
        )
    }

    @Test
    fun classifiesConsumerAccountApiKeyExchangeFailuresCalmly() {
        assertEquals(
            ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT,
            classifyApiKeyExchangeError(
                """
                401 {"error":{"message":"Invalid ID token: missing organization_id",
                "code":"invalid_subject_token"}}
                """.trimIndent(),
            ),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT,
            classifyApiKeyExchangeError("token exchange failed: invalid_subject_token"),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.UNEXPECTED,
            classifyApiKeyExchangeError("ChatGPT auth failed (503): unavailable"),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.NONE,
            classifyApiKeyExchangeError(null),
        )
    }

    @Test
    fun chatGptReasoningEffortDefaultsNoneAndRoundTripsSupportedValues() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertEquals("none", store.chatGptReasoningEffort())

        listOf("none", "low", "medium", "high", "xhigh").forEach { effort ->
            store.setChatGptReasoningEffort(effort)
            assertEquals(effort, store.chatGptReasoningEffort())
        }
    }

    @Test
    fun chatGptReasoningEffortRejectsUnknownValues() {
        val store = CodexAuthStore(FakeSharedPreferences())

        listOf("minimal", "max", "ultra", "LOW", " medium ", "").forEach { effort ->
            assertThrows(IllegalArgumentException::class.java) {
                store.setChatGptReasoningEffort(effort)
            }
        }
        assertEquals("none", store.chatGptReasoningEffort())
    }

    @Test
    fun effortClampPreservesASavedNoneForRequestBoundaryValidation() {
        val store = CodexAuthStore(
            FakeSharedPreferences(mapOf("chatgpt_reasoning_effort" to "none")),
        )

        assertEquals("none", store.chatGptReasoningEffort())
    }

    @Test
    fun legacyModelMigrationOfTheSavedChatGptModel() {
        mapOf(
            "gpt-5.6-luna" to "gpt-6-luna",
            "gpt-5.6-terra" to "gpt-6.1-sol",
            "gpt-5.6-sol" to "gpt-6-astra",
            "gpt-6-sol" to "gpt-6.1-sol",
            "retired-model" to "retired-model",
        ).forEach { (saved, expected) ->
            val store = CodexAuthStore(FakeSharedPreferences(mapOf("chatgpt_model" to saved)))

            assertEquals(saved, expected, store.chatGptModel())
        }
        assertEquals("gpt-6-luna", CodexAuthStore(FakeSharedPreferences()).chatGptModel())
    }

    @Test
    fun forgettingOneProvidersKeyLeavesTheOthersAlone() {
        val store = testStore(FakeSharedPreferences())
        store.saveProviderApiKey(ProviderCatalog.minimax.id, "minimax-key")
        store.saveProviderApiKey(ProviderCatalog.deepSeek.id, "deepseek-key")

        store.clearProviderApiKey(ProviderCatalog.minimax.id)

        assertEquals(null, store.providerApiKey(ProviderCatalog.minimax.id))
        assertEquals("deepseek-key", store.providerApiKey(ProviderCatalog.deepSeek.id))
    }

    @Test
    fun chatGptDisconnectKeepsOtherProvidersKeysAndDropsChatGptSelection() {
        val prefs = FakeSharedPreferences(
            mapOf(
                "auth_mode" to CodexAuthStore.AUTH_MODE_CHATGPT,
                "api_key" to "exchanged-openai-key",
                "oauth_tokens" to "not-json-but-present",
                "account_label" to "someone@example.com",
            ),
        )
        val store = testStore(prefs)
        store.saveProviderApiKey(ProviderCatalog.minimax.id, "minimax-key")
        assertEquals(CodexAuthStore.CHATGPT_PROVIDER_ID, store.selectedProviderId())

        store.clearChatGptAuth()

        assertEquals(null, store.selectedProviderId())
        assertEquals(null, store.oauthTokens())
        assertEquals(null, store.accountLabel())
        // The exchanged key leaves with the account it came from.
        assertEquals(null, store.apiKey())
        assertEquals("minimax-key", store.providerApiKey(ProviderCatalog.minimax.id))
    }

    @Test
    fun signInLedgerOnlyCompletesTheAttemptThatStoredTokens() {
        val prefs = FakeSharedPreferences()
        val store = testStore(prefs)
        val first = CodexChatGptOAuth.AuthAttempt(
            state = "state-a",
            codeVerifier = "verifier-a",
            redirectUri = "http://localhost:1455/auth/callback",
            authorizeUrl = "https://auth.openai.com/oauth/authorize?state=state-a",
        )

        assertEquals(null, store.pendingChatGptSignIn())
        assertFalse(store.isChatGptSignInCompleted(null))

        store.savePendingChatGptSignIn(first)
        // The whole attempt comes back so a restarted process can rebind the
        // callback server; the verifier never sits in the clear.
        assertEquals(first, store.pendingChatGptSignIn())
        assertEquals("encrypted:verifier-a", prefs.getString("chatgpt_sign_in_verifier", null))
        // Started is not finished: a return after a process death must not claim success.
        assertFalse(store.isChatGptSignInCompleted("state-a"))

        store.markChatGptSignInCompleted("state-a")
        assertTrue(store.isChatGptSignInCompleted("state-a"))

        // A later attempt stays open until its own exchange lands, even though
        // older tokens are still stored.
        store.savePendingChatGptSignIn(first.copy(state = "state-b", codeVerifier = "verifier-b"))
        assertEquals("state-b", store.pendingChatGptSignIn()?.state)
        assertEquals("verifier-b", store.pendingChatGptSignIn()?.codeVerifier)
        assertFalse(store.isChatGptSignInCompleted("state-b"))
        assertTrue(store.isChatGptSignInCompleted("state-a"))

        store.clearChatGptAuth()
        assertEquals(null, store.pendingChatGptSignIn())
        assertFalse(store.isChatGptSignInCompleted("state-a"))
    }

    @Test
    fun catalogsRefreshByAgeAndManualRefreshPreservesTheSelectedModel() = runTest {
        val store = testStore(FakeSharedPreferences())
        store.saveProviderApiKey("deepseek", "test-key")
        store.setProviderModel("deepseek", "my-saved-model")
        var now = 1_000L
        var requests = 0
        val repository = ModelCatalogRepository(store, ModelCatalogTransport {
            requests++
            assertEquals("https://api.deepseek.com/v1/models", it.url)
            assertEquals("Bearer test-key", it.headers["Authorization"])
            ModelCatalogResponse(200, """{"data":[{"id":"new-model","input_modalities":["text","image"]}]}""")
        }, clock = { now })
        assertEquals(ModelCatalogRefresh.UPDATED, repository.refresh("deepseek"))
        assertEquals(ModelCatalogRefresh.CURRENT, repository.refresh("deepseek"))
        assertEquals(1, requests)
        assertEquals("my-saved-model", store.providerModel("deepseek"))
        assertEquals(ModelCatalogRefresh.UPDATED, repository.refresh("deepseek", force = true))
        now += ModelCatalogRepository.REFRESH_INTERVAL_MS
        assertEquals(ModelCatalogRefresh.UPDATED, repository.refresh("deepseek"))
        assertEquals(3, requests)
    }

    @Test
    fun failedOrEmptyCatalogRefreshKeepsTheLastSuccessfulListAndBacksOff() = runTest {
        val store = testStore(FakeSharedPreferences())
        store.saveProviderApiKey("openai", "test-key")
        val request = store.modelCatalogRequest("openai")!!
        store.saveModelCatalog(request, ModelCatalogSnapshot(listOf(AvailableModel("cached-model")), 1L))
        var status = 404
        var requests = 0
        val repository = ModelCatalogRepository(store, ModelCatalogTransport {
            requests++
            ModelCatalogResponse(status, """{"data":[]}""")
        }, clock = { ModelCatalogRepository.REFRESH_INTERVAL_MS + 100L })
        assertEquals(ModelCatalogRefresh.UNAVAILABLE, repository.refresh("openai"))
        assertEquals(ModelCatalogRefresh.UNAVAILABLE, repository.refresh("openai"))
        assertEquals(1, requests)
        status = 200
        assertEquals(ModelCatalogRefresh.UNAVAILABLE, repository.refresh("openai", force = true))
        assertEquals(listOf("cached-model"), store.availableModels("openai").map { it.id })
    }

    @Test
    fun modelCatalogIsIsolatedByProviderEndpointAndCredential() {
        val prefs = FakeSharedPreferences()
        val store = testStore(prefs)
        store.saveProviderApiKey("custom", "first-key")
        store.setProviderBaseUrl("custom", "https://first.test/v1")
        val first = store.modelCatalogRequest("custom")!!
        assertTrue(store.saveModelCatalog(first, ModelCatalogSnapshot(listOf(AvailableModel("private-model")), 1L)))
        assertFalse(prefs.getString("model_catalog.custom", "")!!.contains("first-key"))
        assertEquals(listOf("private-model"), testStore(prefs).availableModels("custom").map { it.id })
        assertEquals(null, store.cachedModelCatalog("openai"))
        store.setProviderBaseUrl("custom", "https://second.test/v1")
        assertEquals(null, store.cachedModelCatalog("custom"))
        assertFalse(store.saveModelCatalog(first, ModelCatalogSnapshot(listOf(AvailableModel("late-result")), 2L)))
        store.setProviderBaseUrl("custom", "https://first.test/v1")
        store.saveProviderApiKey("custom", "second-key")
        assertEquals(null, store.cachedModelCatalog("custom"))
    }

    @Test
    fun discoveredVisionAndEffortsReachProviderSettingsAndRespectOverrides() {
        val store = testStore(FakeSharedPreferences())
        store.saveProviderApiKey("deepseek", "test-key")
        store.setProviderModel("deepseek", "future-model")
        val model = AvailableModel("future-model", vision = true, efforts = listOf("high", "max"))
        store.saveModelCatalog(store.modelCatalogRequest("deepseek")!!, ModelCatalogSnapshot(listOf(model), 1L))
        assertTrue(store.providerModelSupportsPhotos("deepseek"))
        store.setProviderEffort("deepseek", "max")
        assertEquals("max", store.providerEffort("deepseek"))
        store.setProviderModelSupportsPhotos("deepseek", false)
        assertFalse(store.providerModelSupportsPhotos("deepseek"))
    }

    @Test
    fun newChatGptModelAndEffortSurviveCatalogChangesWithoutSwitchingModels() {
        val store = testStore(FakeSharedPreferences())
        store.saveOAuth(catalogTokens(), apiKey = null)
        val model = AvailableModel("future-model", vision = false, efforts = listOf("high", "max"), defaultEffort = "max")
        val request = store.modelCatalogRequest(CodexAuthStore.CHATGPT_PROVIDER_ID)!!
        store.saveModelCatalog(request, ModelCatalogSnapshot(listOf(model), 1L))
        store.setChatGptModel(model.id)
        assertEquals(model.id, store.chatGptModel())
        assertEquals("max", store.chatGptReasoningEffort())
        store.setChatGptReasoningEffort("high")
        assertEquals("high", store.chatGptReasoningEffort())
        store.saveModelCatalog(request, ModelCatalogSnapshot(listOf(AvailableModel("another-model")), 2L))
        assertEquals(model.id, store.chatGptModel())
    }

    @Test
    fun chatGptCatalogRetriesOnceWithRefreshedTokensAndScopesByAccount() = runTest {
        val store = testStore(FakeSharedPreferences())
        store.saveOAuth(catalogTokens(), apiKey = null)
        var requests = 0
        var refreshes = 0
        val repository = ModelCatalogRepository(store, ModelCatalogTransport {
            requests++
            assertTrue(it.url.startsWith("https://chatgpt.com/backend-api/codex/models?client_version="))
            assertEquals("account-a", it.headers["ChatGPT-Account-Id"])
            if (requests == 1) ModelCatalogResponse(401) else {
                assertEquals("Bearer renewed", it.headers["Authorization"])
                ModelCatalogResponse(200, """{"models":[{"slug":"future-model","visibility":"list"}]}""")
            }
        }, refreshChatGpt = {
            refreshes++
            store.saveOAuth(catalogTokens(access = "renewed"), apiKey = null)
        })
        assertEquals(ModelCatalogRefresh.UPDATED, repository.refresh(CodexAuthStore.CHATGPT_PROVIDER_ID))
        assertEquals(2, requests)
        assertEquals(1, refreshes)
        store.saveOAuth(catalogTokens(account = "account-b"), apiKey = null)
        assertEquals(null, store.cachedModelCatalog(CodexAuthStore.CHATGPT_PROVIDER_ID))
    }

    @Test
    fun catalogDiscoveryDoesNotRunWithoutCredentialsAndDoesNotSwallowCancellation() = runTest {
        val store = testStore(FakeSharedPreferences())
        val repository = ModelCatalogRepository(store, ModelCatalogTransport { throw CancellationException("Cancelled") })
        assertEquals(ModelCatalogRefresh.UNAVAILABLE, repository.refresh("openai"))
        store.saveProviderApiKey("openai", "test-key")
        var cancelled = false
        try {
            repository.refresh("openai")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun catalogTokens(access: String = "access", account: String = "account-a") =
        CodexChatGptOAuthTokenBundle(access, "id", "refresh", account, "free", null)

    private fun testStore(prefs: FakeSharedPreferences): CodexAuthStore = CodexAuthStore(
        prefs = prefs,
        encryptSecret = { value -> "encrypted:$value" },
        decryptSecret = { value -> value.removePrefix("encrypted:") },
    )

    private class FakeSharedPreferences(
        initialValues: Map<String, Any?> = emptyMap(),
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                stage(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = stage(key, values?.toSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                stage(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                stage(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                stage(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                stage(key, value)

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                key?.let {
                    removals += it
                    pending -= it
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
                pending.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            private fun stage(key: String?, value: Any?): SharedPreferences.Editor = apply {
                key?.let {
                    pending[it] = value
                    removals -= it
                }
            }
        }
    }
}
