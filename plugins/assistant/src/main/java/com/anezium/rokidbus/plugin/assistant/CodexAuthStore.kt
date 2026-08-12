package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CodexAuthStore internal constructor(
    private val prefs: SharedPreferences,
    private val encryptSecret: (String) -> String,
    private val decryptSecret: (String) -> String,
) {
    constructor(context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        encryptSecret = CodexKeystoreAesGcm::encrypt,
        decryptSecret = CodexKeystoreAesGcm::decrypt,
    )

    internal constructor(prefs: SharedPreferences) : this(
        prefs = prefs,
        encryptSecret = CodexKeystoreAesGcm::encrypt,
        decryptSecret = CodexKeystoreAesGcm::decrypt,
    )

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun hasUsableAuth(): Boolean {
        val selected = selectedProviderId()
        return when {
            selected == CHATGPT_PROVIDER_ID -> oauthTokens() != null
            selected != null -> {
                val preset = ProviderCatalog.preset(selected) ?: return false
                !providerApiKey(selected).isNullOrBlank() &&
                    (preset.defaultBaseUrl.isNotBlank() || providerBaseUrl(selected).isNotBlank())
            }
            else -> hasUsableAuth(
                authMode = authMode(),
                hasOAuthTokens = oauthTokens() != null,
                hasApiKey = hasApiKey(),
            )
        }
    }

    /** The OpenAI key used by Whisper and account-context exchange, never another provider's key. */
    fun apiKey(): String? = providerApiKey(ProviderCatalog.openAi.id)

    fun selectedProviderId(): String? {
        ensureProviderMigration()
        return prefs.getString(KEY_SELECTED_PROVIDER_ID, null)
            ?.takeIf(String::isNotBlank)
            ?.takeIf(::isValidSelectedProviderId)
    }

    fun setSelectedProviderId(id: String) {
        require(isValidSelectedProviderId(id)) { "Unknown provider: $id" }
        ensureProviderMigration()
        prefs.edit().putString(KEY_SELECTED_PROVIDER_ID, id).apply()
    }

    fun providerApiKey(id: String): String? {
        requireProviderPreset(id)
        ensureProviderMigration()
        val encryptedValues = buildList {
            prefs.getString(providerKey(KEY_PROVIDER_API_KEY_PREFIX, id), null)?.let(::add)
            if (id == ProviderCatalog.openAi.id) {
                prefs.getString(KEY_API_KEY, null)?.let(::add)
            }
        }
        return encryptedValues.firstNotNullOfOrNull { encrypted ->
            runCatching { decryptSecret(encrypted) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }
    }

    fun saveProviderApiKey(id: String, key: String) {
        requireProviderPreset(id)
        val trimmed = key.trim()
        require(trimmed.isNotBlank()) { "API key is blank." }
        ensureProviderMigration()
        prefs.edit().apply {
            putString(providerKey(KEY_PROVIDER_API_KEY_PREFIX, id), encryptSecret(trimmed))
            if (id == ProviderCatalog.custom.id) {
                remove(providerKey(KEY_PROVIDER_DETECTED_BACKEND_PREFIX, id))
            }
        }.apply()
    }

    fun clearProviderApiKey(id: String) {
        requireProviderPreset(id)
        ensureProviderMigration()
        val editor = prefs.edit().remove(providerKey(KEY_PROVIDER_API_KEY_PREFIX, id))
        if (id == ProviderCatalog.custom.id) {
            editor.remove(providerKey(KEY_PROVIDER_DETECTED_BACKEND_PREFIX, id))
        }
        // The legacy slot doubles as the OpenAI key, so forgetting OpenAI clears both.
        if (id == ProviderCatalog.openAi.id) editor.remove(KEY_API_KEY)
        editor.apply()
    }

    /**
     * Signing out of ChatGPT removes the account and everything that arrived with it —
     * tokens, the exchanged key, the synced memories — but never another provider's key.
     */
    fun clearChatGptAuth() {
        ensureProviderMigration()
        val editor = prefs.edit()
            .remove(KEY_OAUTH_TOKENS)
            .remove(KEY_API_KEY)
            .remove(KEY_ACCOUNT_LABEL)
            .remove(KEY_AUTH_MODE)
            .remove(KEY_API_KEY_EXCHANGE_ERROR)
            .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
            .remove(KEY_SYNCED_ACCOUNT_CONTEXT)
            .remove(KEY_ACCOUNT_CONTEXT_SYNCED_AT)
        if (prefs.getString(KEY_SELECTED_PROVIDER_ID, null) == CHATGPT_PROVIDER_ID) {
            editor.remove(KEY_SELECTED_PROVIDER_ID)
        }
        editor.apply()
    }

    fun providerModel(id: String): String {
        val preset = requireProviderPreset(id)
        ensureProviderMigration()
        return prefs.getString(providerKey(KEY_PROVIDER_MODEL_PREFIX, id), null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: preset.defaultModel
    }

    fun setProviderModel(id: String, model: String) {
        requireProviderPreset(id)
        ensureProviderMigration()
        prefs.edit()
            .putString(providerKey(KEY_PROVIDER_MODEL_PREFIX, id), model.trim())
            .apply()
    }

    fun providerBaseUrl(id: String): String {
        val preset = requireProviderPreset(id)
        ensureProviderMigration()
        return prefs.getString(providerKey(KEY_PROVIDER_BASE_URL_PREFIX, id), null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: preset.defaultBaseUrl
    }

    fun setProviderBaseUrl(id: String, url: String) {
        requireProviderPreset(id)
        ensureProviderMigration()
        val normalized = url.trim()
        val changed = normalized != providerBaseUrl(id)
        prefs.edit().apply {
            putString(providerKey(KEY_PROVIDER_BASE_URL_PREFIX, id), normalized)
            if (changed && id == ProviderCatalog.custom.id) {
                remove(providerKey(KEY_PROVIDER_DETECTED_BACKEND_PREFIX, id))
            }
        }.apply()
    }

    internal fun providerBackend(id: String): ProviderBackend {
        val preset = requireProviderPreset(id)
        ensureProviderMigration()
        return if (preset.backend == ProviderBackend.HERMES) {
            ProviderBackend.HERMES
        } else {
            providerDetectedBackend(id) ?: ProviderBackend.OPENAI_COMPAT
        }
    }

    internal fun providerDetectedBackend(id: String): ProviderBackend? {
        requireProviderPreset(id)
        ensureProviderMigration()
        return prefs.getString(providerKey(KEY_PROVIDER_DETECTED_BACKEND_PREFIX, id), null)
            ?.let { stored -> ProviderBackend.entries.firstOrNull { it.name == stored } }
    }

    internal fun setProviderDetectedBackend(id: String, backend: ProviderBackend) {
        require(id == ProviderCatalog.custom.id) { "Only Custom providers are auto-detected." }
        ensureProviderMigration()
        prefs.edit()
            .putString(providerKey(KEY_PROVIDER_DETECTED_BACKEND_PREFIX, id), backend.name)
            .apply()
    }

    fun providerEffort(id: String): String {
        val preset = requireProviderPreset(id)
        ensureProviderMigration()
        if (preset.supportedEfforts.isEmpty()) return ""
        return prefs.getString(providerKey(KEY_PROVIDER_EFFORT_PREFIX, id), "")
            .orEmpty()
            .trim()
            .takeIf(preset.supportedEfforts::contains)
            .orEmpty()
    }

    fun setProviderEffort(id: String, effort: String) {
        val preset = requireProviderPreset(id)
        val normalized = effort.trim()
        require(normalized.isEmpty() || normalized in preset.supportedEfforts) {
            "Unsupported ${preset.displayName} reasoning effort: $effort"
        }
        ensureProviderMigration()
        prefs.edit()
            .putString(providerKey(KEY_PROVIDER_EFFORT_PREFIX, id), normalized)
            .apply()
    }

    fun providerModelSupportsPhotos(id: String): Boolean {
        val preset = requireProviderPreset(id)
        ensureProviderMigration()
        val key = providerKey(KEY_PROVIDER_MODEL_SUPPORTS_PHOTOS_PREFIX, id)
        val visionOverride = prefs.getBoolean(key, false).takeIf { prefs.contains(key) }
        return ProviderCatalog.supportsVision(
            preset = preset,
            model = providerModel(id),
            visionOverride = visionOverride,
            backend = providerBackend(id),
        )
    }

    fun setProviderModelSupportsPhotos(id: String, supportsPhotos: Boolean) {
        requireProviderPreset(id)
        ensureProviderMigration()
        prefs.edit()
            .putBoolean(
                providerKey(KEY_PROVIDER_MODEL_SUPPORTS_PHOTOS_PREFIX, id),
                supportsPhotos,
            )
            .apply()
    }

    fun clearProviderModelSupportsPhotosOverride(id: String) {
        requireProviderPreset(id)
        ensureProviderMigration()
        prefs.edit()
            .remove(providerKey(KEY_PROVIDER_MODEL_SUPPORTS_PHOTOS_PREFIX, id))
            .apply()
    }

    fun oauthTokens(): CodexChatGptOAuthTokenBundle? {
        ensureProviderMigration()
        val encrypted = prefs.getString(KEY_OAUTH_TOKENS, null) ?: return null
        return runCatching {
            val value = JSONObject(decryptSecret(encrypted))
            CodexChatGptOAuthTokenBundle(
                accessToken = value.getString("accessToken"),
                idToken = value.getString("idToken"),
                refreshToken = value.optString("refreshToken").takeIf(String::isNotBlank),
                accountId = value.getString("accountId"),
                planType = value.optString("planType").takeIf(String::isNotBlank),
                email = value.optString("email").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun accountLabel(): String? =
        prefs.getString(KEY_ACCOUNT_LABEL, null)?.takeIf(String::isNotBlank)

    fun apiKeyExchangeError(): String? {
        val stored = prefs.getString(KEY_API_KEY_EXCHANGE_ERROR, null)
            ?.takeIf(String::isNotBlank)
        return stored?.takeUnless {
            classifyApiKeyExchangeError(it) == ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT
        }
    }

    fun isConsumerChatGptAccount(): Boolean =
        prefs.getBoolean(KEY_CONSUMER_ACCOUNT_NO_API_ORG, false) ||
            classifyApiKeyExchangeError(
                prefs.getString(KEY_API_KEY_EXCHANGE_ERROR, null),
            ) == ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT

    fun model(): String = providerModel(ProviderCatalog.openAi.id)

    fun chatGptModel(): String = ChatGptCodexApiClient.supportedModel(
        prefs.getString(
            KEY_CHATGPT_MODEL,
            ChatGptCodexApiClient.DEFAULT_MODEL_ID,
        ).orEmpty(),
    )

    fun chatGptReasoningEffort(): String = ChatGptCodexApiClient.supportedReasoningEffort(
        prefs.getString(
            KEY_CHATGPT_REASONING_EFFORT,
            ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT,
        ).orEmpty(),
    )

    fun keepConversation(): Boolean = prefs.getBoolean(KEY_KEEP_CONVERSATION, true)

    fun keepPhotosInConversations(): Boolean =
        prefs.getBoolean(KEY_KEEP_PHOTOS_IN_CONVERSATIONS, true)

    fun speakAnswers(): Boolean = prefs.getBoolean(KEY_SPEAK_ANSWERS, true)

    fun phoneKeyboardInputEnabled(): Boolean =
        prefs.getBoolean(KEY_PHONE_KEYBOARD_INPUT, false)

    fun conversationIdleWindowMinutes(): Int = supportedIdleWindowMinutes(
        prefs.getInt(KEY_CONVERSATION_IDLE_WINDOW_MINUTES, DEFAULT_IDLE_WINDOW_MINUTES),
    )

    fun assistantMemory(): String = prefs.getString(KEY_ASSISTANT_MEMORY, "").orEmpty()

    fun customSystemPrompt(): String =
        prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "")
            .orEmpty()
            .trim()
            .take(MAX_CUSTOM_SYSTEM_PROMPT_CHARS)

    fun syncAccountContext(): Boolean = prefs.getBoolean(KEY_SYNC_ACCOUNT_CONTEXT, true)

    fun syncedAccountContext(): String =
        prefs.getString(KEY_SYNCED_ACCOUNT_CONTEXT, "").orEmpty()

    fun accountContextSyncedAtMs(): Long =
        prefs.getLong(KEY_ACCOUNT_CONTEXT_SYNCED_AT, 0L)

    /** [AUTH_MODE_CHATGPT], [AUTH_MODE_API_KEY], or null when nothing is connected. */
    fun authMode(): String? {
        ensureProviderMigration()
        return prefs.getString(KEY_AUTH_MODE, null)?.takeIf(String::isNotBlank)
    }

    fun setModel(model: String) {
        setProviderModel(ProviderCatalog.openAi.id, model)
    }

    fun setChatGptModel(model: String) {
        prefs.edit()
            .putString(KEY_CHATGPT_MODEL, ChatGptCodexApiClient.supportedModel(model))
            .apply()
    }

    fun setChatGptReasoningEffort(effort: String) {
        require(effort in ChatGptCodexApiClient.SUPPORTED_REASONING_EFFORTS) {
            "Unsupported ChatGPT reasoning effort: $effort"
        }
        prefs.edit().putString(KEY_CHATGPT_REASONING_EFFORT, effort).apply()
    }

    fun setKeepConversation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONVERSATION, enabled).apply()
    }

    fun setKeepPhotosInConversations(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_PHOTOS_IN_CONVERSATIONS, enabled).apply()
    }

    fun setSpeakAnswers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_ANSWERS, enabled).apply()
    }

    fun setPhoneKeyboardInputEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_KEYBOARD_INPUT, enabled).apply()
    }

    fun setConversationIdleWindowMinutes(minutes: Int) {
        prefs.edit()
            .putInt(KEY_CONVERSATION_IDLE_WINDOW_MINUTES, supportedIdleWindowMinutes(minutes))
            .apply()
    }

    fun setAssistantMemory(memory: String) {
        prefs.edit()
            .putString(KEY_ASSISTANT_MEMORY, memory.trim().take(MAX_ASSISTANT_MEMORY_CHARS))
            .apply()
    }

    fun setCustomSystemPrompt(prompt: String) {
        prefs.edit()
            .putString(
                KEY_CUSTOM_SYSTEM_PROMPT,
                prompt.trim().take(MAX_CUSTOM_SYSTEM_PROMPT_CHARS),
            )
            .apply()
    }

    fun setSyncAccountContext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ACCOUNT_CONTEXT, enabled).apply()
    }

    fun setSyncedAccountContext(text: String) {
        prefs.edit()
            .putString(
                KEY_SYNCED_ACCOUNT_CONTEXT,
                text.trim().take(MAX_SYNCED_ACCOUNT_CONTEXT_CHARS),
            )
            .apply()
    }

    fun setAccountContextSyncedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_ACCOUNT_CONTEXT_SYNCED_AT, ms.coerceAtLeast(0L)).apply()
    }

    fun clearSyncedAccountContext() {
        prefs.edit()
            .remove(KEY_SYNCED_ACCOUNT_CONTEXT)
            .remove(KEY_ACCOUNT_CONTEXT_SYNCED_AT)
            .apply()
    }

    fun saveApiKey(
        apiKey: String,
        model: String = OpenAiApiClient.DEFAULT_MODEL_ID,
    ) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotBlank()) { "API key is blank." }
        ensureProviderMigration()
        val encrypted = encryptSecret(trimmed)
        prefs.edit()
            .putString(KEY_API_KEY, encrypted)
            .putString(providerKey(KEY_PROVIDER_API_KEY_PREFIX, ProviderCatalog.openAi.id), encrypted)
            .putString(providerKey(KEY_PROVIDER_MODEL_PREFIX, ProviderCatalog.openAi.id), model.trim())
            .putString(KEY_ACCOUNT_LABEL, "API key ...${trimmed.takeLast(4)}")
            .putString(KEY_AUTH_MODE, AUTH_MODE_API_KEY)
            .putString(KEY_SELECTED_PROVIDER_ID, ProviderCatalog.openAi.id)
            .remove(KEY_API_KEY_EXCHANGE_ERROR)
            .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
            .apply()
    }

    fun saveOAuth(
        tokens: CodexChatGptOAuthTokenBundle,
        apiKey: String?,
        model: String = ChatGptCodexApiClient.DEFAULT_MODEL_ID,
        apiKeyExchangeError: String? = null,
    ) {
        ensureProviderMigration()
        val tokenJson = JSONObject()
            .put("accessToken", tokens.accessToken)
            .put("idToken", tokens.idToken)
            .put("accountId", tokens.accountId)
            .put("planType", tokens.planType)
            .put("email", tokens.email)
            .apply {
                tokens.refreshToken?.let { put("refreshToken", it) }
            }
            .toString()
        val editor = prefs.edit()
            .putString(KEY_OAUTH_TOKENS, encryptSecret(tokenJson))
            .putString(KEY_CHATGPT_MODEL, ChatGptCodexApiClient.supportedModel(model))
            .putString(KEY_ACCOUNT_LABEL, tokens.displayLabel())
            .putString(KEY_AUTH_MODE, AUTH_MODE_CHATGPT)
            .putString(KEY_SELECTED_PROVIDER_ID, CHATGPT_PROVIDER_ID)

        val trimmedApiKey = apiKey?.trim().orEmpty()
        if (trimmedApiKey.isNotBlank()) {
            editor
                .putString(KEY_API_KEY, encryptSecret(trimmedApiKey))
                .remove(KEY_API_KEY_EXCHANGE_ERROR)
                .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
        } else {
            when (classifyApiKeyExchangeError(apiKeyExchangeError)) {
                ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT -> editor
                    .putBoolean(KEY_CONSUMER_ACCOUNT_NO_API_ORG, true)
                    .remove(KEY_API_KEY_EXCHANGE_ERROR)
                ApiKeyExchangeErrorClassification.UNEXPECTED -> editor
                    .putString(
                        KEY_API_KEY_EXCHANGE_ERROR,
                        apiKeyExchangeError.orEmpty().redactProviderSecrets().take(300),
                    )
                    .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
                ApiKeyExchangeErrorClassification.NONE -> editor
                    .remove(KEY_API_KEY_EXCHANGE_ERROR)
                    .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
            }
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    @Synchronized
    private fun ensureProviderMigration() {
        if (prefs.getBoolean(KEY_PROVIDER_MIGRATION_COMPLETE, false)) return
        val editor = prefs.edit()
        val legacyMode = prefs.getString(KEY_AUTH_MODE, null)
        if (!prefs.contains(KEY_SELECTED_PROVIDER_ID)) {
            when (legacyMode) {
                AUTH_MODE_CHATGPT -> editor.putString(KEY_SELECTED_PROVIDER_ID, CHATGPT_PROVIDER_ID)
                AUTH_MODE_API_KEY -> editor.putString(
                    KEY_SELECTED_PROVIDER_ID,
                    ProviderCatalog.openAi.id,
                )
            }
        }
        if (legacyMode == AUTH_MODE_API_KEY) {
            val openAiKey = providerKey(KEY_PROVIDER_API_KEY_PREFIX, ProviderCatalog.openAi.id)
            if (!prefs.contains(openAiKey)) {
                prefs.getString(KEY_API_KEY, null)?.let { encrypted ->
                    editor.putString(openAiKey, encrypted)
                }
            }
            val openAiModel = providerKey(KEY_PROVIDER_MODEL_PREFIX, ProviderCatalog.openAi.id)
            if (!prefs.contains(openAiModel)) {
                prefs.getString(KEY_MODEL, null)?.let { model ->
                    editor.putString(openAiModel, model)
                }
            }
        }
        editor.putBoolean(KEY_PROVIDER_MIGRATION_COMPLETE, true).apply()
    }

    private fun requireProviderPreset(id: String): ProviderPreset =
        ProviderCatalog.requirePreset(id)

    private fun isValidSelectedProviderId(id: String): Boolean =
        id == CHATGPT_PROVIDER_ID || ProviderCatalog.preset(id) != null

    private fun providerKey(prefix: String, id: String): String = "$prefix.$id"

    companion object {
        const val AUTH_MODE_CHATGPT = "chatgpt_oauth"
        const val AUTH_MODE_API_KEY = "api_key"
        // Must equal the router id of the ChatGPT provider: selectedProviderId() feeds
        // ProviderRouter.providerFor() directly.
        const val CHATGPT_PROVIDER_ID = ChatGptCodexProvider.ID
        val SUPPORTED_IDLE_WINDOW_MINUTES = listOf(2, 5, 10, 30, SEVEN_DAYS_IN_MINUTES)
        const val DEFAULT_IDLE_WINDOW_MINUTES = 10
        const val SEVEN_DAYS_IN_MINUTES = 7 * 24 * 60
        const val MAX_ASSISTANT_MEMORY_CHARS = 4000
        const val MAX_CUSTOM_SYSTEM_PROMPT_CHARS = 4000
        const val MAX_SYNCED_ACCOUNT_CONTEXT_CHARS = 6000

        private const val PREFS_NAME = "assistant_auth_v1"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
        private const val KEY_PROVIDER_API_KEY_PREFIX = "api_key"
        private const val KEY_PROVIDER_MODEL_PREFIX = "model"
        private const val KEY_PROVIDER_BASE_URL_PREFIX = "base_url"
        private const val KEY_PROVIDER_EFFORT_PREFIX = "effort"
        private const val KEY_PROVIDER_DETECTED_BACKEND_PREFIX = "detected_backend"
        private const val KEY_PROVIDER_MODEL_SUPPORTS_PHOTOS_PREFIX = "model_supports_photos"
        private const val KEY_PROVIDER_MIGRATION_COMPLETE = "provider_migration_complete"
        private const val KEY_OAUTH_TOKENS = "oauth_tokens"
        private const val KEY_MODEL = "model"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"
        private const val KEY_CHATGPT_REASONING_EFFORT = "chatgpt_reasoning_effort"
        private const val KEY_KEEP_CONVERSATION = "keep_conversation"
        private const val KEY_KEEP_PHOTOS_IN_CONVERSATIONS =
            "keep_photos_in_conversations"
        private const val KEY_SPEAK_ANSWERS = "speak_answers"
        private const val KEY_PHONE_KEYBOARD_INPUT = "phone_keyboard_input"
        private const val KEY_CONVERSATION_IDLE_WINDOW_MINUTES =
            "conversation_idle_window_minutes"
        private const val KEY_ASSISTANT_MEMORY = "assistant_memory"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_SYNC_ACCOUNT_CONTEXT = "sync_account_context"
        private const val KEY_SYNCED_ACCOUNT_CONTEXT = "synced_account_context"
        private const val KEY_ACCOUNT_CONTEXT_SYNCED_AT = "account_context_synced_at"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_API_KEY_EXCHANGE_ERROR = "api_key_exchange_error"
        private const val KEY_CONSUMER_ACCOUNT_NO_API_ORG = "consumer_account_no_api_org"

        private fun supportedIdleWindowMinutes(minutes: Int): Int =
            SUPPORTED_IDLE_WINDOW_MINUTES.minBy { supported ->
                kotlin.math.abs(supported.toLong() - minutes.toLong())
            }
    }
}

internal fun hasUsableAuth(
    authMode: String?,
    hasOAuthTokens: Boolean,
    hasApiKey: Boolean,
): Boolean = when (authMode) {
    CodexAuthStore.AUTH_MODE_CHATGPT -> hasOAuthTokens
    CodexAuthStore.AUTH_MODE_API_KEY -> hasApiKey
    else -> hasApiKey
}

internal enum class ApiKeyExchangeErrorClassification {
    NONE,
    CONSUMER_ACCOUNT,
    UNEXPECTED,
}

internal fun classifyApiKeyExchangeError(
    message: String?,
): ApiKeyExchangeErrorClassification {
    val normalized = message?.trim().orEmpty()
    if (normalized.isEmpty()) return ApiKeyExchangeErrorClassification.NONE
    return if (
        normalized.contains("invalid_subject_token", ignoreCase = true) ||
        normalized.contains("missing organization_id", ignoreCase = true)
    ) {
        ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT
    } else {
        ApiKeyExchangeErrorClassification.UNEXPECTED
    }
}

internal object CodexKeystoreAesGcm {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rokid_nexus_assistant_auth_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(payload: String): String {
        val value = JSONObject(payload)
        val iv = Base64.decode(value.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(value.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
