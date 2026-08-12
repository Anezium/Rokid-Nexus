package com.anezium.rokidbus.plugin.assistant

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings for the voice assistant: pick a provider, connect it, steer its model,
 * shape the assistant, remove the plugin.
 *
 * The provider list is the screen's centre of gravity — it is the only thing standing
 * between the assist button and an answer. Everything the chosen provider needs lives
 * in one config slot rebuilt on selection, so no section ever has to juggle another
 * provider's visibility.
 */
class AssistantSettingsActivity : Activity() {
    private val authStore by lazy { CodexAuthStore(applicationContext) }
    private val threadStore by lazy { AssistantThreadStore(applicationContext) }
    private val accountContextSync by lazy { AccountContextSync(applicationContext) }
    private val hermesCapabilitiesClient by lazy { HermesCapabilitiesClient() }
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var providerConfigSlot: LinearLayout
    private lateinit var windowSection: LinearLayout
    private lateinit var memoryField: EditText
    private lateinit var memoryStatus: TextView
    private lateinit var personaField: EditText
    private lateinit var personaStatus: TextView
    private lateinit var conversationsSlot: LinearLayout
    private lateinit var productivitySlot: LinearLayout
    private lateinit var calendarAccessSlot: LinearLayout
    private lateinit var syncSection: LinearLayout
    private lateinit var syncFooter: LinearLayout
    private lateinit var memorySyncStatus: TextView
    private lateinit var refreshButton: View

    private val providerDots = mutableMapOf<String, View>()
    private val providerNames = mutableMapOf<String, TextView>()
    private val providerStatuses = mutableMapOf<String, TextView>()

    // Views inside the provider config slot; they only exist for the provider that is
    // currently built, and every rebuild starts them over.
    private var apiKeyField: EditText? = null
    private var modelField: EditText? = null
    private var endpointField: EditText? = null
    private var photosCapSection: LinearLayout? = null
    private val planModelDots = mutableMapOf<String, View>()
    private val planModelNames = mutableMapOf<String, TextView>()
    private val reasoningDots = mutableMapOf<String, View>()
    private val reasoningNames = mutableMapOf<String, TextView>()
    private val apiModelDots = mutableMapOf<String, View>()
    private val apiModelNames = mutableMapOf<String, TextView>()
    private val effortDots = mutableMapOf<String, View>()
    private val effortNames = mutableMapOf<String, TextView>()
    private val photosCapDots = mutableMapOf<Boolean, View>()
    private val photosCapNames = mutableMapOf<Boolean, TextView>()

    private val keepDots = mutableMapOf<Boolean, View>()
    private val keepNames = mutableMapOf<Boolean, TextView>()
    private val photosDots = mutableMapOf<Boolean, View>()
    private val photosNames = mutableMapOf<Boolean, TextView>()
    private val voiceDots = mutableMapOf<Boolean, View>()
    private val voiceNames = mutableMapOf<Boolean, TextView>()
    private val keyboardDots = mutableMapOf<Boolean, View>()
    private val keyboardNames = mutableMapOf<Boolean, TextView>()
    private val windowDots = mutableMapOf<Int, View>()
    private val windowNames = mutableMapOf<Int, TextView>()
    private val syncDots = mutableMapOf<Boolean, View>()
    private val syncNames = mutableMapOf<Boolean, TextView>()
    @Volatile private var syncing = false
    private var hermesProbeGeneration = 0L

    private data class ProviderEntry(val id: String, val title: String, val caption: String)
    private data class ModelChoice(val id: String, val title: String, val caption: String)
    private data class ReasoningChoice(val id: String, val title: String, val hint: String? = null)

    /**
     * ChatGPT leads because a plan needs no key, followed by named API services and
     * self-hosted Hermes. Custom stays last as the escape hatch for everything the
     * catalog does not name.
     */
    private val providerEntries = listOf(
        ProviderEntry(
            CodexAuthStore.CHATGPT_PROVIDER_ID,
            "ChatGPT",
            "Sign in with your ChatGPT plan",
        ),
        ProviderEntry(ProviderCatalog.openAi.id, "OpenAI", "Pay-as-you-go API key"),
        ProviderEntry(ProviderCatalog.openRouter.id, "OpenRouter", "One key, many models"),
        ProviderEntry(ProviderCatalog.minimax.id, "MiniMax", "Coding Plan or API key"),
        ProviderEntry(ProviderCatalog.deepSeek.id, "DeepSeek", "Chat and Reasoner models"),
        ProviderEntry(ProviderCatalog.zai.id, "GLM (Z.ai)", "Zhipu's GLM models"),
        ProviderEntry(ProviderCatalog.hermes.id, "Hermes", "Your Hermes API server"),
        ProviderEntry(ProviderCatalog.custom.id, "Custom", "Any OpenAI-compatible server"),
    )

    /**
     * The GPT-5.6 family, ordered the way a wearer picks: fastest first, because the
     * glasses are a voice surface and most questions are quick ones. Captions follow
     * how OpenAI positions the tiers -- speed and depth, not price, since a ChatGPT
     * plan is not billed per token.
     */
    private val planModelChoices = listOf(
        ModelChoice(
            ChatGptCodexApiClient.FAST_MODEL_ID,
            "Luna",
            "Fastest. Best for quick questions",
        ),
        ModelChoice(
            ChatGptCodexApiClient.BALANCED_MODEL_ID,
            "Terra",
            "Balanced. Good for most things",
        ),
        ModelChoice(
            ChatGptCodexApiClient.DEEP_MODEL_ID,
            "Sol",
            "Deepest reasoning. Slower to answer",
        ),
    )
    private val reasoningChoices = listOf(
        ReasoningChoice("none", "None", "fastest"),
        ReasoningChoice("low", "Low"),
        ReasoningChoice("medium", "Medium"),
        ReasoningChoice("high", "High"),
        ReasoningChoice("xhigh", "X-High", "deepest"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // The ChatGPT flow leaves for the browser and comes back through its own
        // activity, so provider state is only ever trustworthy on resume.
        renderProviderList()
        rebuildProviderConfig()
        renderConversationSettings()
        renderPersona()
        renderMemory()
        renderProductivityCard()
        renderCalendarAccess()
        maybeDetectHermes(ProviderCatalog.custom)
    }

    override fun onDestroy() {
        settingsScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "Hold the assist button on your glasses and ask out loud. The answer " +
                        "streams onto the HUD, riding your ChatGPT plan or any AI " +
                        "provider you connect below. Phone input can add a Write choice " +
                        "to the listening notice.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Provider"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(providerCard(), NexusUi.block())
            providerConfigSlot = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(providerConfigSlot, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Personality"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(personaCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Conversation"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(keepCard(), NexusUi.block())
            windowSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
                addView(
                    NexusUi.sectionRow(
                        this@AssistantSettingsActivity,
                        "Start fresh after",
                    ),
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(windowCard(), NexusUi.block())
            }
            addView(windowSection, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Photos"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(photosCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Voice"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(voiceCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Phone input"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(keyboardCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            conversationsSlot = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(conversationsSlot, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Memory"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            syncSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    NexusUi.cardBody(
                        this@AssistantSettingsActivity,
                        "Your saved ChatGPT memories and custom instructions are pulled in " +
                            "automatically and added to every question you ask.",
                    ),
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(syncCard(), NexusUi.block())
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            }
            addView(syncSection, NexusUi.block())
            addView(notesCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Notes & reminders"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            productivitySlot = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(productivitySlot, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Calendar"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            calendarAccessSlot = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(calendarAccessSlot, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(uninstallCard(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AssistantSettingsActivity,
                    R.drawable.nexus_glyph_assistant,
                    "Assistant",
                    "Voice assistant · v${pluginVersionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AssistantSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderProviderList()
        rebuildProviderConfig()
        renderConversationSettings()
        renderPersona()
        renderMemory()
        renderProductivityCard()
        renderCalendarAccess()
    }

    // ------------------------------------------------------------------ providers

    private fun selectedProvider(): String =
        authStore.selectedProviderId() ?: when (authStore.authMode()) {
            CodexAuthStore.AUTH_MODE_CHATGPT -> CodexAuthStore.CHATGPT_PROVIDER_ID
            else -> ProviderCatalog.openAi.id
        }

    private fun providerReady(id: String): Boolean =
        if (id == CodexAuthStore.CHATGPT_PROVIDER_ID) {
            authStore.oauthTokens() != null
        } else {
            val preset = ProviderCatalog.requirePreset(id)
            !authStore.providerApiKey(id).isNullOrBlank() &&
                (preset.defaultBaseUrl.isNotBlank() || authStore.providerBaseUrl(id).isNotBlank())
        }

    private fun providerCard(): LinearLayout =
        NexusUi.card(this).apply {
            providerEntries.forEachIndexed { index, entry ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(providerRow(entry), NexusUi.block())
            }
        }

    private fun providerRow(entry: ProviderEntry): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Use ${entry.title}"
            background = NexusUi.pressed(this@AssistantSettingsActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
            )
            setOnClickListener { selectProvider(entry.id) }
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@AssistantSettingsActivity, entry.title).also {
                            providerNames[entry.id] = it
                        },
                        NexusUi.block(),
                    )
                    addView(
                        NexusUi.rowSub(this@AssistantSettingsActivity, entry.caption),
                        NexusUi.block(),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.metaLabel(this@AssistantSettingsActivity, "", NexusUi.GREEN).also {
                    providerStatuses[entry.id] = it
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12) },
            )
            addView(
                NexusUi.dot(this@AssistantSettingsActivity).also { providerDots[entry.id] = it },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                ),
            )
        }

    private fun selectProvider(id: String) {
        if (id == selectedProvider()) return
        hermesProbeGeneration += 1
        authStore.setSelectedProviderId(id)
        renderProviderList()
        rebuildProviderConfig()
        if (id == ProviderCatalog.custom.id) maybeDetectHermes(ProviderCatalog.custom)
    }

    private fun renderProviderList() {
        val selected = selectedProvider()
        providerEntries.forEach { entry ->
            val isSelected = entry.id == selected
            providerDots[entry.id]?.let {
                NexusUi.setDotColor(it, if (isSelected) NexusUi.GREEN else NexusUi.INK4)
            }
            providerNames[entry.id]?.setTextColor(if (isSelected) NexusUi.INK else NexusUi.INK2)
            providerStatuses[entry.id]?.text = when {
                !providerReady(entry.id) -> ""
                entry.id == ProviderCatalog.custom.id &&
                    authStore.providerBackend(entry.id) == ProviderBackend.HERMES -> "Hermes"
                else -> "Ready"
            }
        }
    }

    /**
     * Everything below the provider list belongs to the selected provider alone, so it
     * is rebuilt from scratch on every selection change instead of toggling seven
     * sections' visibility against each other.
     */
    private fun rebuildProviderConfig() {
        apiKeyField = null
        modelField = null
        endpointField = null
        photosCapSection = null
        planModelDots.clear(); planModelNames.clear()
        reasoningDots.clear(); reasoningNames.clear()
        apiModelDots.clear(); apiModelNames.clear()
        effortDots.clear(); effortNames.clear()
        photosCapDots.clear(); photosCapNames.clear()

        providerConfigSlot.removeAllViews()
        val selected = selectedProvider()
        if (selected == CodexAuthStore.CHATGPT_PROVIDER_ID) {
            buildChatGptConfig(providerConfigSlot)
        } else {
            ProviderCatalog.preset(selected)?.let { preset ->
                buildApiProviderConfig(providerConfigSlot, preset)
            }
        }
    }

    // ------------------------------------------------------------------ ChatGPT config

    private fun buildChatGptConfig(slot: LinearLayout) {
        val tokens = authStore.oauthTokens()
        val signedIn = tokens != null

        slot.addView(BusTheme.gap(this, 18))
        slot.addView(
            NexusUi.card(this).apply {
                addView(
                    LinearLayout(this@AssistantSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            NexusUi.rowTitle(
                                this@AssistantSettingsActivity,
                                if (signedIn) {
                                    authStore.accountLabel() ?: "ChatGPT account"
                                } else {
                                    "Not connected"
                                },
                            ),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                .apply {
                                    marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                                },
                        )
                        if (signedIn) {
                            addView(
                                NexusUi.metaLabel(
                                    this@AssistantSettingsActivity,
                                    "Connected",
                                    NexusUi.GREEN,
                                ),
                            )
                        }
                    },
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@AssistantSettingsActivity, 5))
                addView(
                    NexusUi.rowSub(
                        this@AssistantSettingsActivity,
                        if (signedIn) chatGptDetail(tokens) else "Sign in to start asking questions",
                    ),
                    NexusUi.block(),
                )
                chatGptWarning()?.let { message ->
                    addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                    addView(warningRow(message), NexusUi.block())
                }
            },
            NexusUi.block(),
        )
        slot.addView(BusTheme.gap(this, 12))
        // Solid accent only while the assistant cannot answer; once it works the
        // sign-in path steps back to an outline so nothing shouts for no reason.
        val signInButton = if (signedIn) {
            NexusUi.outlinePillButton(this, "Sign in again")
        } else {
            NexusUi.pillButton(this, "Sign in with ChatGPT")
        }
        slot.addView(
            signInButton.apply {
                setOnClickListener {
                    startActivity(CodexChatGptSignInActivity.createIntent(this@AssistantSettingsActivity))
                }
            },
            NexusUi.block(),
        )
        if (signedIn) {
            slot.addView(BusTheme.gap(this, 10))
            slot.addView(
                NexusUi.pillButton(this, "Disconnect", danger = true).apply {
                    setOnClickListener { confirmChatGptDisconnect() }
                },
                NexusUi.block(),
            )
            slot.addView(BusTheme.gap(this, 22))
            slot.addView(NexusUi.sectionRow(this, "Model"), NexusUi.block())
            slot.addView(BusTheme.gap(this, 12))
            slot.addView(planModelCard(), NexusUi.block())
            slot.addView(BusTheme.gap(this, 22))
            slot.addView(NexusUi.sectionRow(this, "Reasoning"), NexusUi.block())
            slot.addView(BusTheme.gap(this, 12))
            slot.addView(reasoningCard(), NexusUi.block())
            renderPlanModelSelection()
            renderReasoningSelection()
        }
    }

    private fun chatGptDetail(tokens: CodexChatGptOAuthTokenBundle?): String {
        val plan = tokens?.planType?.trim()?.takeIf(String::isNotEmpty)
        val planLabel = plan?.replaceFirstChar { it.uppercaseChar() }
        return if (planLabel == null) "Signed in with ChatGPT" else "Signed in with ChatGPT · $planLabel"
    }

    /** The exchange warning only matters while signed in but without a usable key. */
    private fun chatGptWarning(): String? {
        if (authStore.oauthTokens() == null) return null
        if (authStore.hasApiKey()) return null
        if (authStore.isConsumerChatGptAccount()) return null
        return authStore.apiKeyExchangeError()
    }

    private fun warningRow(message: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantSettingsActivity,
                NexusUi.alpha(NexusUi.AMBER, 0x10),
                NexusUi.alpha(NexusUi.AMBER, 0x38),
                12,
            )
            setPadding(
                NexusUi.dp(this@AssistantSettingsActivity, 12),
                NexusUi.dp(this@AssistantSettingsActivity, 10),
                NexusUi.dp(this@AssistantSettingsActivity, 12),
                NexusUi.dp(this@AssistantSettingsActivity, 10),
            )
            addView(
                NexusUi.metaLabel(this@AssistantSettingsActivity, "No API key", NexusUi.AMBER),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            addView(
                NexusUi.cardBody(this@AssistantSettingsActivity, message.trim()).apply {
                    textSize = 12f
                },
                NexusUi.block(),
            )
        }

    private fun planModelCard(): LinearLayout =
        NexusUi.card(this).apply {
            planModelChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(
                    pickerRow(
                        title = choice.title,
                        caption = choice.caption,
                        description = "Use ${choice.title}",
                        onClick = {
                            authStore.setChatGptModel(choice.id)
                            renderPlanModelSelection()
                        },
                        nameSink = { planModelNames[choice.id] = it },
                        dotSink = { planModelDots[choice.id] = it },
                    ),
                    NexusUi.block(),
                )
            }
        }

    private fun renderPlanModelSelection() {
        val selected = authStore.chatGptModel()
        planModelDots.forEach { (id, dotView) ->
            NexusUi.setDotColor(dotView, if (id == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        planModelNames.forEach { (id, nameView) ->
            nameView.setTextColor(if (id == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun reasoningCard(): LinearLayout =
        NexusUi.card(this).apply {
            reasoningChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(
                    pickerRow(
                        title = choice.title,
                        hint = choice.hint,
                        description = "Use ${choice.title} reasoning",
                        onClick = {
                            authStore.setChatGptReasoningEffort(choice.id)
                            renderReasoningSelection()
                        },
                        nameSink = { reasoningNames[choice.id] = it },
                        dotSink = { reasoningDots[choice.id] = it },
                    ),
                    NexusUi.block(),
                )
            }
        }

    private fun renderReasoningSelection() {
        val selected = authStore.chatGptReasoningEffort()
        reasoningDots.forEach { (effort, dotView) ->
            NexusUi.setDotColor(dotView, if (effort == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        reasoningNames.forEach { (effort, nameView) ->
            nameView.setTextColor(if (effort == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun confirmChatGptDisconnect() {
        confirmDialog(
            title = "Disconnect account",
            body = "The account, its key, and the synced memories are removed from " +
                "this phone. Keys you saved for other providers stay.",
            confirmLabel = "Disconnect",
        ) {
            authStore.clearChatGptAuth()
            renderProviderList()
            rebuildProviderConfig()
            renderMemory()
            toast("Disconnected.")
        }
    }

    // ------------------------------------------------------------------ API provider config

    private fun buildApiProviderConfig(slot: LinearLayout, preset: ProviderPreset) {
        val needsEndpoint = preset.defaultBaseUrl.isBlank()
        slot.addView(BusTheme.gap(this, 18))
        if (needsEndpoint) {
            // A self-hosted server has no home until the endpoint is set; ask for it first.
            slot.addView(endpointCard(preset), NexusUi.block())
            slot.addView(BusTheme.gap(this, 12))
        }
        slot.addView(apiKeyCard(preset), NexusUi.block())

        slot.addView(BusTheme.gap(this, 22))
        slot.addView(NexusUi.sectionRow(this, "Model"), NexusUi.block())
        slot.addView(BusTheme.gap(this, 12))
        slot.addView(apiModelCard(preset), NexusUi.block())

        photosCapSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Photos"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(photosCapCard(preset), NexusUi.block())
        }
        slot.addView(photosCapSection, NexusUi.block())

        if (preset.supportedEfforts.isNotEmpty()) {
            slot.addView(BusTheme.gap(this, 22))
            slot.addView(NexusUi.sectionRow(this, "Reasoning"), NexusUi.block())
            slot.addView(BusTheme.gap(this, 12))
            slot.addView(effortCard(preset), NexusUi.block())
            renderEffortSelection(preset)
        }

        if (!needsEndpoint) {
            slot.addView(BusTheme.gap(this, 12))
            slot.addView(endpointCard(preset), NexusUi.block())
        }
        renderApiModelSelection(preset)
    }

    private fun apiKeyCard(preset: ProviderPreset): LinearLayout =
        NexusUi.card(this).apply {
            val hasKey = !authStore.providerApiKey(preset.id).isNullOrBlank()
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowLabel(
                            this@AssistantSettingsActivity,
                            "${preset.displayName} API key",
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    endpointHost(preset)?.let { host ->
                        addView(NexusUi.rowSub(this@AssistantSettingsActivity, host))
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))

            val field = keyField(preset)
            apiKeyField = field
            var revealed = false
            val revealButton = NexusUi.textButton(this@AssistantSettingsActivity, "Show").apply {
                contentDescription = "Show ${preset.displayName} API key"
                setOnClickListener {
                    revealed = !revealed
                    field.transformationMethod = if (revealed) {
                        null
                    } else {
                        PasswordTransformationMethod.getInstance()
                    }
                    field.setSelection(field.text.length)
                    text = if (revealed) "Hide" else "Show"
                    contentDescription = if (revealed) {
                        "Hide ${preset.displayName} API key"
                    } else {
                        "Show ${preset.displayName} API key"
                    }
                }
            }
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        field,
                        LinearLayout.LayoutParams(0, NexusUi.dp(this@AssistantSettingsActivity, 52), 1f),
                    )
                    addView(
                        revealButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@AssistantSettingsActivity, 6) },
                    )
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 4))
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowSub(
                            this@AssistantSettingsActivity,
                            "Stays encrypted on this phone",
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    if (hasKey) {
                        addView(
                            NexusUi.textButton(
                                this@AssistantSettingsActivity,
                                "Forget",
                                danger = true,
                            ).apply {
                                contentDescription = "Forget ${preset.displayName} API key"
                                setOnClickListener { forgetApiKey(preset) }
                            },
                        )
                    }
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setOnClickListener { saveApiKey(preset) }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun keyField(preset: ProviderPreset): EditText =
        NexusUi.field(this, preset.keyHint.ifBlank { "Paste your key" }).apply {
            setText(authStore.providerApiKey(preset.id).orEmpty())
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Sink the field below the card so it reads as an input, not a flat row.
            background = NexusUi.bordered(this@AssistantSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveApiKey(preset)
                    true
                } else {
                    false
                }
            }
        }

    private fun saveApiKey(preset: ProviderPreset) {
        val field = apiKeyField ?: return
        val key = field.text.toString().trim()
        if (key.isEmpty()) {
            toast("Paste a ${preset.displayName} API key first.")
            return
        }
        val saved = runCatching {
            if (preset.id == ProviderCatalog.openAi.id) {
                // The legacy path keeps the account label and the Whisper fallback fed.
                authStore.saveApiKey(key, authStore.providerModel(preset.id))
            } else {
                authStore.saveProviderApiKey(preset.id, key)
            }
        }.isSuccess
        if (!saved) {
            toast("That key could not be saved.")
            return
        }
        hideKeyboard(field)
        renderProviderList()
        rebuildProviderConfig()
        toast("${preset.displayName} key saved.")
        maybeDetectHermes(preset, announce = true)
    }

    private fun forgetApiKey(preset: ProviderPreset) {
        confirmDialog(
            title = "Forget key",
            body = "The ${preset.displayName} key is removed from this phone. " +
                "The assistant stops answering through ${preset.displayName} until " +
                "you paste it again.",
            confirmLabel = "Forget",
        ) {
            authStore.clearProviderApiKey(preset.id)
            renderProviderList()
            rebuildProviderConfig()
            toast("Key removed.")
        }
    }

    private fun apiModelCard(preset: ProviderPreset): LinearLayout =
        NexusUi.card(this).apply {
            preset.suggestedModels.forEachIndexed { index, model ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(
                    pickerRow(
                        title = model.title,
                        caption = model.caption.takeIf(String::isNotBlank),
                        description = "Use ${model.title}",
                        onClick = { selectSuggestedModel(preset, model) },
                        nameSink = { apiModelNames[model.id] = it },
                        dotSink = { apiModelDots[model.id] = it },
                    ),
                    NexusUi.block(),
                )
            }
            if (preset.suggestedModels.isNotEmpty()) {
                addView(NexusUi.divider(this@AssistantSettingsActivity))
            }
            addView(
                NexusUi.rowSub(
                    this@AssistantSettingsActivity,
                    if (preset.suggestedModels.isEmpty()) {
                        "The model id your server answers to"
                    } else {
                        "Or any ${preset.displayName} model id"
                    },
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            val field = modelInputField(preset)
            modelField = field
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        field,
                        LinearLayout.LayoutParams(0, NexusUi.dp(this@AssistantSettingsActivity, 46), 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Use").apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            contentDescription = "Use the typed model"
                            setOnClickListener { saveTypedModel(preset) }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@AssistantSettingsActivity, 6) },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun modelInputField(preset: ProviderPreset): EditText =
        NexusUi.field(this, preset.defaultModel.ifBlank { "model-id" }).apply {
            val current = authStore.providerModel(preset.id)
            if (preset.suggestedModels.none { it.id == current }) setText(current)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = NexusUi.bordered(this@AssistantSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveTypedModel(preset)
                    true
                } else {
                    false
                }
            }
        }

    private fun selectSuggestedModel(preset: ProviderPreset, model: SuggestedModel) {
        authStore.setProviderModel(preset.id, model.id)
        // A fresh model means yesterday's photo answer no longer applies.
        authStore.clearProviderModelSupportsPhotosOverride(preset.id)
        modelField?.setText("")
        renderApiModelSelection(preset)
    }

    private fun saveTypedModel(preset: ProviderPreset) {
        val field = modelField ?: return
        val typed = field.text.toString().trim()
        if (typed.isEmpty()) {
            toast("Type a model id first.")
            return
        }
        authStore.setProviderModel(preset.id, typed)
        authStore.clearProviderModelSupportsPhotosOverride(preset.id)
        hideKeyboard(field)
        renderApiModelSelection(preset)
        toast("Using $typed.")
    }

    private fun renderApiModelSelection(preset: ProviderPreset) {
        val current = authStore.providerModel(preset.id)
        val suggested = preset.suggestedModels.any { it.id == current }
        apiModelDots.forEach { (id, dotView) ->
            NexusUi.setDotColor(dotView, if (id == current) NexusUi.GREEN else NexusUi.INK4)
        }
        apiModelNames.forEach { (id, nameView) ->
            nameView.setTextColor(if (id == current) NexusUi.INK else NexusUi.INK2)
        }
        // A model the catalog knows carries its own photo answer; only a typed one
        // needs the wearer to say whether it can see.
        photosCapSection?.visibility = if (suggested) View.GONE else View.VISIBLE
        if (!suggested) renderPhotosCapSelection(preset)
    }

    private fun photosCapCard(preset: ProviderPreset): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                pickerRow(
                    title = "Can see photos",
                    hint = "photos ride along",
                    description = "The model can see photos",
                    onClick = {
                        authStore.setProviderModelSupportsPhotos(preset.id, true)
                        renderPhotosCapSelection(preset)
                    },
                    nameSink = { photosCapNames[true] = it },
                    dotSink = { photosCapDots[true] = it },
                ),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(
                pickerRow(
                    title = "Text only",
                    hint = "photos are left out",
                    description = "The model cannot see photos",
                    onClick = {
                        authStore.setProviderModelSupportsPhotos(preset.id, false)
                        renderPhotosCapSelection(preset)
                    },
                    nameSink = { photosCapNames[false] = it },
                    dotSink = { photosCapDots[false] = it },
                ),
                NexusUi.block(),
            )
        }

    private fun renderPhotosCapSelection(preset: ProviderPreset) {
        val canSee = authStore.providerModelSupportsPhotos(preset.id)
        photosCapDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == canSee) NexusUi.GREEN else NexusUi.INK4)
        }
        photosCapNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == canSee) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun effortCard(preset: ProviderPreset): LinearLayout =
        NexusUi.card(this).apply {
            val choices = listOf("" to "None") + preset.supportedEfforts.map { effort ->
                effort to effort.replaceFirstChar { it.uppercaseChar() }
            }
            choices.forEachIndexed { index, (effort, title) ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(
                    pickerRow(
                        title = title,
                        hint = if (effort.isEmpty()) "fastest" else null,
                        description = "Use $title reasoning",
                        onClick = {
                            authStore.setProviderEffort(preset.id, effort)
                            renderEffortSelection(preset)
                        },
                        nameSink = { effortNames[effort] = it },
                        dotSink = { effortDots[effort] = it },
                    ),
                    NexusUi.block(),
                )
            }
        }

    private fun renderEffortSelection(preset: ProviderPreset) {
        val selected = authStore.providerEffort(preset.id)
        effortDots.forEach { (effort, dotView) ->
            NexusUi.setDotColor(dotView, if (effort == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        effortNames.forEach { (effort, nameView) ->
            nameView.setTextColor(if (effort == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun endpointCard(preset: ProviderPreset): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                NexusUi.rowLabel(this@AssistantSettingsActivity, "Endpoint"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 2))
            addView(
                NexusUi.rowSub(
                    this@AssistantSettingsActivity,
                    when {
                        preset.backend == ProviderBackend.HERMES ->
                            "The /v1 root of your Hermes API server"
                        preset.defaultBaseUrl.isBlank() ->
                            "The /v1 root of your OpenAI-compatible server"
                        else -> "Only change this if your plan answers on a different server"
                    },
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            val field = endpointField(preset)
            endpointField = field
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        field,
                        LinearLayout.LayoutParams(0, NexusUi.dp(this@AssistantSettingsActivity, 46), 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            contentDescription = "Save the endpoint"
                            setOnClickListener { saveEndpoint(preset) }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@AssistantSettingsActivity, 6) },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun endpointField(preset: ProviderPreset): EditText =
        NexusUi.field(
            this,
            preset.defaultBaseUrl.ifBlank { "https://your-server.example/v1" },
        ).apply {
            setText(authStore.providerBaseUrl(preset.id))
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = NexusUi.bordered(this@AssistantSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveEndpoint(preset)
                    true
                } else {
                    false
                }
            }
        }

    private fun saveEndpoint(preset: ProviderPreset) {
        val field = endpointField ?: return
        val url = field.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            toast("An endpoint starts with https://")
            return
        }
        authStore.setProviderBaseUrl(preset.id, url)
        hideKeyboard(field)
        renderProviderList()
        rebuildProviderConfig()
        toast(
            when {
                url.isNotEmpty() -> "Endpoint saved."
                preset.defaultBaseUrl.isNotEmpty() -> "Back to the standard endpoint."
                else -> "Endpoint cleared."
            },
        )
        maybeDetectHermes(preset, announce = true)
    }

    private fun endpointHost(preset: ProviderPreset): String? =
        authStore.providerBaseUrl(preset.id)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .takeIf(String::isNotBlank)

    private fun maybeDetectHermes(preset: ProviderPreset, announce: Boolean = false) {
        if (
            preset.id != ProviderCatalog.custom.id ||
            selectedProvider() != preset.id ||
            authStore.providerDetectedBackend(preset.id) != null ||
            !providerReady(preset.id)
        ) {
            return
        }
        val generation = ++hermesProbeGeneration
        val baseUrl = authStore.providerBaseUrl(preset.id)
        val apiKey = authStore.providerApiKey(preset.id).orEmpty()
        settingsScope.launch {
            when (hermesCapabilitiesClient.discover(baseUrl, apiKey)) {
                is HermesDiscoveryResult.Detected -> {
                    if (generation != hermesProbeGeneration) return@launch
                    authStore.setProviderDetectedBackend(preset.id, ProviderBackend.HERMES)
                    renderProviderList()
                    if (selectedProvider() == preset.id) renderPhotosCapSelection(preset)
                    if (announce) toast("Hermes server detected.")
                }
                HermesDiscoveryResult.NotHermes -> {
                    if (generation != hermesProbeGeneration) return@launch
                    authStore.setProviderDetectedBackend(
                        preset.id,
                        ProviderBackend.OPENAI_COMPAT,
                    )
                    renderProviderList()
                }
                HermesDiscoveryResult.Unavailable -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ personality

    private fun personaCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "Standing instructions for the assistant -- a persona, a tone, " +
                        "rules to follow. The HUD formatting rules stay on regardless.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            personaField = NexusUi.field(
                this@AssistantSettingsActivity,
                "You are…",
            ).apply {
                setSingleLine(false)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_NONE
                gravity = Gravity.TOP or Gravity.START
                minHeight = NexusUi.dp(this@AssistantSettingsActivity, 108)
                setPadding(
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                )
            }
            addView(personaField, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            personaStatus = NexusUi.rowSub(this@AssistantSettingsActivity, "")
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        personaStatus,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            setOnClickListener { savePersona() }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun savePersona() {
        authStore.setCustomSystemPrompt(personaField.text?.toString().orEmpty())
        hideKeyboard(personaField)
        renderPersona()
        toast(
            if (authStore.customSystemPrompt().isBlank()) {
                "Back to the standard assistant."
            } else {
                "Personality saved."
            },
        )
    }

    private fun renderPersona() {
        val stored = authStore.customSystemPrompt()
        if (personaField.text?.toString().orEmpty() != stored) {
            personaField.setText(stored)
        }
        personaStatus.text = when {
            stored.isBlank() -> "Using the standard assistant"
            stored.length >= CodexAuthStore.MAX_CUSTOM_SYSTEM_PROMPT_CHARS ->
                "Saved, trimmed to ${CodexAuthStore.MAX_CUSTOM_SYSTEM_PROMPT_CHARS} characters"
            else -> "Saved, ${stored.length} characters"
        }
    }

    // ------------------------------------------------------------------ conversation

    private fun keepCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(keepRow(true, "Continue", "follow-ups keep context"), NexusUi.block())
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(keepRow(false, "Always fresh", "every question starts over"), NexusUi.block())
        }

    private fun keepRow(
        keep: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (keep) "Continue the conversation" else "Always start fresh",
            onClick = {
                authStore.setKeepConversation(keep)
                renderConversationSettings()
            },
            nameSink = { keepNames[keep] = it },
            dotSink = { keepDots[keep] = it },
        )

    private fun photosCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                photosRow(true, "Keep photos", "the assistant can look again"),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(
                photosRow(false, "Text only", "a turn is just marked as a photo"),
                NexusUi.block(),
            )
        }

    private fun photosRow(
        keep: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (keep) "Keep photos in conversations" else "Do not keep photos",
            onClick = {
                authStore.setKeepPhotosInConversations(keep)
                // Turning this off is a promise the wearer expects kept now, not
                // at the next question: erase what is already on disk.
                if (!keep) {
                    Thread { runCatching { threadStore.deleteAllPhotos() } }.start()
                }
                renderConversationSettings()
            },
            nameSink = { photosNames[keep] = it },
            dotSink = { photosDots[keep] = it },
        )

    private fun voiceCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                voiceRow(true, "Speak answers", "you hear the answer"),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(
                voiceRow(false, "Silent", "the HUD only"),
                NexusUi.block(),
            )
        }

    private fun voiceRow(
        enabled: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (enabled) "Speak answers out loud" else "Show answers on the HUD only",
            onClick = {
                authStore.setSpeakAnswers(enabled)
                renderConversationSettings()
            },
            nameSink = { voiceNames[enabled] = it },
            dotSink = { voiceDots[enabled] = it },
        )

    private fun keyboardCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                keyboardRow(true, "Voice + Write", "type from the phone while listening"),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(
                keyboardRow(false, "Voice only", "keep the listening notice unchanged"),
                NexusUi.block(),
            )
        }

    private fun keyboardRow(
        enabled: Boolean,
        title: String,
        hint: String,
    ): LinearLayout = pickerRow(
        title = title,
        hint = hint,
        description = if (enabled) {
            "Offer phone keyboard input while Assistant listens"
        } else {
            "Use voice input only"
        },
        onClick = {
            authStore.setPhoneKeyboardInputEnabled(enabled)
            renderConversationSettings()
        },
        nameSink = { keyboardNames[enabled] = it },
        dotSink = { keyboardDots[enabled] = it },
    )

    private fun windowCard(): LinearLayout =
        NexusUi.card(this).apply {
            CodexAuthStore.SUPPORTED_IDLE_WINDOW_MINUTES.forEachIndexed { index, minutes ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(windowRow(minutes), NexusUi.block())
            }
        }

    private fun windowRow(minutes: Int): LinearLayout =
        pickerRow(
            title = conversationWindowLabel(minutes),
            hint = if (minutes == CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES) "default" else null,
            description = "Start fresh after ${conversationWindowLabel(minutes)}",
            onClick = {
                authStore.setConversationIdleWindowMinutes(minutes)
                renderConversationSettings()
            },
            nameSink = { windowNames[minutes] = it },
            dotSink = { windowDots[minutes] = it },
        )

    private fun conversationWindowLabel(minutes: Int): String =
        if (minutes == CodexAuthStore.SEVEN_DAYS_IN_MINUTES) "7 days" else "$minutes minutes"

    /**
     * The one-of-N row shared by every picker on this screen: label, optional hint, and
     * the dot that carries the selection. Selection colouring is applied by the render
     * pass, not here, so a row never has to know what else is on screen.
     */
    private fun pickerRow(
        title: String,
        hint: String? = null,
        caption: String? = null,
        description: String,
        onClick: () -> Unit,
        nameSink: (TextView) -> Unit,
        dotSink: (View) -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = description
            background = NexusUi.pressed(this@AssistantSettingsActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
            )
            setOnClickListener { onClick() }
            addView(
                if (caption == null) {
                    NexusUi.rowLabel(this@AssistantSettingsActivity, title).also(nameSink)
                } else {
                    LinearLayout(this@AssistantSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            NexusUi.rowLabel(this@AssistantSettingsActivity, title).also(nameSink),
                            NexusUi.block(),
                        )
                        addView(
                            NexusUi.rowSub(this@AssistantSettingsActivity, caption),
                            NexusUi.block(),
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            hint?.let {
                addView(
                    NexusUi.rowSub(this@AssistantSettingsActivity, it),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                    },
                )
            }
            addView(
                NexusUi.dot(this@AssistantSettingsActivity).also(dotSink),
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                ),
            )
        }

    private fun renderConversationSettings() {
        val keep = authStore.keepConversation()
        keepDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == keep) NexusUi.GREEN else NexusUi.INK4)
        }
        keepNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == keep) NexusUi.INK else NexusUi.INK2)
        }

        val window = authStore.conversationIdleWindowMinutes()
        windowDots.forEach { (minutes, dotView) ->
            NexusUi.setDotColor(dotView, if (minutes == window) NexusUi.GREEN else NexusUi.INK4)
        }
        windowNames.forEach { (minutes, nameView) ->
            nameView.setTextColor(if (minutes == window) NexusUi.INK else NexusUi.INK2)
        }
        windowSection.visibility = if (keep) View.VISIBLE else View.GONE

        val keepPhotos = authStore.keepPhotosInConversations()
        photosDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == keepPhotos) NexusUi.GREEN else NexusUi.INK4)
        }
        photosNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == keepPhotos) NexusUi.INK else NexusUi.INK2)
        }

        val speakAnswers = authStore.speakAnswers()
        voiceDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == speakAnswers) NexusUi.GREEN else NexusUi.INK4)
        }
        voiceNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == speakAnswers) NexusUi.INK else NexusUi.INK2)
        }

        val phoneKeyboard = authStore.phoneKeyboardInputEnabled()
        keyboardDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == phoneKeyboard) NexusUi.GREEN else NexusUi.INK4)
        }
        keyboardNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == phoneKeyboard) NexusUi.INK else NexusUi.INK2)
        }

        renderConversationsCard()
    }

    private fun renderConversationsCard() {
        // The store is a file read; keep it off the frame that is drawing the screen.
        Thread {
            val count = runCatching { threadStore.threads().size }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                conversationsSlot.removeAllViews()
                conversationsSlot.addView(
                    NexusUi.navCard(
                        this,
                        "Conversations",
                        conversationsSubtitle(count),
                    ) {
                        startActivity(
                            Intent(this, AssistantConversationsActivity::class.java),
                        )
                    },
                    NexusUi.block(),
                )
            }
        }.start()
    }

    private fun conversationsSubtitle(count: Int): String = when (count) {
        0 -> "Nothing saved yet"
        1 -> "1 saved on this phone"
        else -> "$count saved on this phone"
    }

    // ------------------------------------------------------- notes & reminders

    private fun renderProductivityCard() {
        // Two file reads; keep them off the frame that is drawing the screen.
        Thread {
            val reminders = runCatching {
                AssistantReminderStore(applicationContext).pending().size
            }.getOrDefault(0)
            val notes = runCatching {
                AssistantNoteStore(applicationContext).notes().size
            }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                productivitySlot.removeAllViews()
                productivitySlot.addView(
                    NexusUi.navCard(
                        this,
                        "Notes & reminders",
                        productivitySubtitle(notes, reminders),
                    ) {
                        startActivity(
                            Intent(this, AssistantProductivityActivity::class.java),
                        )
                    },
                    NexusUi.block(),
                )
            }
        }.start()
    }

    private fun productivitySubtitle(notes: Int, reminders: Int): String {
        if (notes == 0 && reminders == 0) {
            return "Say “remind me…” or “take a note” on the glasses"
        }
        val notePart = when (notes) {
            0 -> null
            1 -> "1 note"
            else -> "$notes notes"
        }
        val reminderPart = when (reminders) {
            0 -> null
            1 -> "1 pending reminder"
            else -> "$reminders pending reminders"
        }
        return listOfNotNull(notePart, reminderPart).joinToString(" · ")
    }

    // ------------------------------------------------------------------ calendar

    private fun renderCalendarAccess() {
        val granted = CALENDAR_PERMISSIONS.all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        calendarAccessSlot.removeAllViews()
        calendarAccessSlot.addView(
            NexusUi.card(this).apply {
                addView(
                    LinearLayout(this@AssistantSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            LinearLayout(this@AssistantSettingsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(
                                    NexusUi.rowTitle(
                                        this@AssistantSettingsActivity,
                                        "Phone calendar",
                                    ),
                                    NexusUi.block(),
                                )
                                addView(
                                    NexusUi.rowSub(
                                        this@AssistantSettingsActivity,
                                        if (granted) {
                                            "Appointments and agenda are available to the assistant"
                                        } else {
                                            "Allow the assistant to add and read calendar events"
                                        },
                                    ),
                                    NexusUi.block(),
                                )
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                            },
                        )
                        if (granted) {
                            addView(
                                NexusUi.metaLabel(
                                    this@AssistantSettingsActivity,
                                    "Granted",
                                    NexusUi.GREEN,
                                ),
                            )
                        } else {
                            addView(
                                NexusUi.textButton(
                                    this@AssistantSettingsActivity,
                                    "Grant access",
                                ).apply {
                                    setOnClickListener { requestCalendarAccess() }
                                },
                            )
                        }
                    },
                    NexusUi.block(),
                )
            },
            NexusUi.block(),
        )
    }

    private fun requestCalendarAccess() {
        requestPermissions(CALENDAR_PERMISSIONS, REQUEST_CALENDAR_ACCESS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR_ACCESS) renderCalendarAccess()
    }

    // ------------------------------------------------------------------ memory

    private fun syncCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(syncRow(true, "On", "memories + custom instructions"), NexusUi.block())
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(syncRow(false, "Off", "use only the notes below"), NexusUi.block())
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            memorySyncStatus = NexusUi.rowSub(this@AssistantSettingsActivity, "")
            refreshButton = NexusUi.textButton(this@AssistantSettingsActivity, "Refresh now").apply {
                setOnClickListener { refreshNow() }
            }
            syncFooter = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    memorySyncStatus,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(refreshButton)
            }
            addView(syncFooter, NexusUi.block())
        }

    private fun syncRow(
        enabled: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (enabled) "Sync memory from ChatGPT" else "Do not sync from ChatGPT",
            onClick = {
                authStore.setSyncAccountContext(enabled)
                if (enabled) {
                    // Turning it on should show something now, not at the next open.
                    refreshNow()
                } else {
                    // Off is a privacy promise kept at the tap: drop the cached copy now.
                    authStore.clearSyncedAccountContext()
                }
                renderMemory()
            },
            nameSink = { syncNames[enabled] = it },
            dotSink = { syncDots[enabled] = it },
        )

    private fun refreshNow() {
        if (syncing) return
        if (authStore.oauthTokens() == null) {
            toast("Connect a ChatGPT account first.")
            return
        }
        syncing = true
        refreshButton.isEnabled = false
        memorySyncStatus.text = "Syncing…"
        Thread {
            val result = runCatching {
                kotlinx.coroutines.runBlocking { accountContextSync.syncNow() }
            }.getOrElse { SyncResult.Unavailable(SyncUnavailableReason.UNKNOWN) }
            runOnUiThread {
                syncing = false
                refreshButton.isEnabled = true
                when (result) {
                    is SyncResult.Success -> toast(
                        if (result.chars == 0) {
                            "Synced. Nothing to add yet."
                        } else if (result.memoryCount == 1) {
                            "Synced 1 memory."
                        } else {
                            "Synced ${result.memoryCount} memories."
                        },
                    )
                    is SyncResult.Unavailable -> toast("Couldn't sync right now.")
                    SyncResult.NotSignedIn -> toast("Connect a ChatGPT account first.")
                    SyncResult.Disabled -> Unit
                }
                renderMemory()
            }
        }.start()
    }

    private fun notesCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "Standing notes woven into every question you ask. One-off notes " +
                        "you dictate on the glasses live in Notes & reminders below.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            memoryField = NexusUi.field(
                this@AssistantSettingsActivity,
                "Add a note",
            ).apply {
                setSingleLine(false)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_NONE
                gravity = Gravity.TOP or Gravity.START
                minHeight = NexusUi.dp(this@AssistantSettingsActivity, 108)
                setPadding(
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                )
            }
            addView(memoryField, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            memoryStatus = NexusUi.rowSub(this@AssistantSettingsActivity, "")
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        memoryStatus,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            setOnClickListener { saveMemory() }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun saveMemory() {
        val pasted = memoryField.text?.toString().orEmpty()
        authStore.setAssistantMemory(pasted)
        hideKeyboard(memoryField)
        renderMemory()
        toast(if (authStore.assistantMemory().isBlank()) "Notes cleared." else "Notes saved.")
    }

    private fun renderMemory() {
        val stored = authStore.assistantMemory()
        if (memoryField.text?.toString().orEmpty() != stored) {
            memoryField.setText(stored)
        }
        memoryStatus.text = when {
            stored.isBlank() -> "No notes yet"
            stored.length >= CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS ->
                "Saved, trimmed to ${CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS} characters"
            else -> "Saved, ${stored.length} characters"
        }

        val chatGpt = authStore.oauthTokens() != null
        syncSection.visibility = if (chatGpt) View.VISIBLE else View.GONE
        if (!chatGpt) return

        val on = authStore.syncAccountContext()
        syncDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == on) NexusUi.GREEN else NexusUi.INK4)
        }
        syncNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == on) NexusUi.INK else NexusUi.INK2)
        }
        syncFooter.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            memorySyncStatus.text = if (syncing) "Syncing…" else syncStatusText()
        }
    }

    private fun syncStatusText(): String {
        val at = authStore.accountContextSyncedAtMs()
        if (at <= 0L) return "Not synced yet"
        val chars = authStore.syncedAccountContext().length
        val ago = relativeTime(at)
        return if (chars == 0) "Synced $ago · nothing found" else "Synced $ago · $chars characters"
    }

    private fun relativeTime(ms: Long): String {
        val delta = System.currentTimeMillis() - ms
        return when {
            delta < 60_000L -> "just now"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
            else -> "${delta / 86_400_000L}d ago"
        }
    }

    // ------------------------------------------------------------------ shared chrome

    private fun confirmDialog(
        title: String,
        body: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantSettingsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantSettingsActivity, title))
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            addView(NexusUi.cardBody(this@AssistantSettingsActivity, body), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 14))
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantSettingsActivity,
                            confirmLabel,
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                onConfirm()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun uninstallCard(): LinearLayout =
        NexusUi.uninstallCard(this, "Assistant") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }

    private fun pluginVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "1.0.0" }

    private fun hideKeyboard(field: EditText) {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_CALENDAR_ACCESS = 1201
        val CALENDAR_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }
}
