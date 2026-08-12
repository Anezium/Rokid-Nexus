package com.anezium.rokidbus.plugin.assistant

import android.content.SharedPreferences
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
        assertFalse(store.phoneKeyboardInputEnabled())
        assertEquals(CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES, store.conversationIdleWindowMinutes())
        assertEquals("", store.assistantMemory())
        assertTrue(store.syncAccountContext())
        assertEquals("", store.syncedAccountContext())
        assertEquals(0L, store.accountContextSyncedAtMs())
        assertEquals("", store.customSystemPrompt())
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
    fun phoneKeyboardInputDefaultsOffAndRoundTrips() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertFalse(store.phoneKeyboardInputEnabled())
        store.setPhoneKeyboardInputEnabled(true)
        assertTrue(store.phoneKeyboardInputEnabled())
        store.setPhoneKeyboardInputEnabled(false)
        assertFalse(store.phoneKeyboardInputEnabled())
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
        store.setPhoneKeyboardInputEnabled(true)
        store.setConversationIdleWindowMinutes(30)
        store.setAssistantMemory("Uses metric units.")

        assertFalse(store.keepConversation())
        assertFalse(store.keepPhotosInConversations())
        assertFalse(store.speakAnswers())
        assertTrue(store.phoneKeyboardInputEnabled())
        assertEquals(30, store.conversationIdleWindowMinutes())
        assertEquals("Uses metric units.", store.assistantMemory())

        store.clear()

        assertTrue(store.keepConversation())
        assertTrue(store.keepPhotosInConversations())
        assertTrue(store.speakAnswers())
        assertFalse(store.phoneKeyboardInputEnabled())
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

        listOf("minimal", "ultra", "LOW", " medium ", "").forEach { effort ->
            assertThrows(IllegalArgumentException::class.java) {
                store.setChatGptReasoningEffort(effort)
            }
        }
        assertEquals("none", store.chatGptReasoningEffort())
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
