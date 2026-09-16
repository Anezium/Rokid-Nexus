package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.phone.speech.HubSecretStore
import com.anezium.rokidbus.phone.speech.SpeechCredentialKind
import com.anezium.rokidbus.phone.speech.SpeechCredits
import com.anezium.rokidbus.phone.speech.SpeechEngine
import com.anezium.rokidbus.phone.speech.SpeechProvider
import com.anezium.rokidbus.phone.speech.SpeechReadiness
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechSettingsStore
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SpeechUtteranceListener
import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage

/**
 * The skeleton (header, scroll, section frames) is built once; every state change
 * re-renders only its own section, so the scroll position never moves and nothing flashes.
 */
class SpeechSettingsActivity : Activity() {
    private val settings by lazy { SpeechSettingsStore(this) }
    private val secrets by lazy { HubSecretStore(this) }

    private var shownProvider: SpeechProvider = SpeechProvider.OPENAI
    private var keyEditing = false
    private var testActive = false
    private var statusText: String? = null
    private var statusColor: Int = NexusUi.INK2
    private var transcriptText: String? = null
    private var transcriptFinal = false
    private var creditsGeneration = 0

    private lateinit var engineHeaderMeta: TextView
    private lateinit var languageHeaderMeta: TextView
    private lateinit var keyHeaderMeta: TextView
    private lateinit var readinessValue: TextView
    private lateinit var engineChipsHost: LinearLayout
    private lateinit var modelCardHost: LinearLayout
    private lateinit var languageGridHost: LinearLayout
    private lateinit var languageNoteHost: LinearLayout
    private lateinit var patienceHeaderMeta: TextView
    private lateinit var patienceCardHost: LinearLayout
    private lateinit var patienceNoteHost: LinearLayout
    private lateinit var keyCardHost: LinearLayout
    private lateinit var keySectionHost: LinearLayout
    private lateinit var testStatusView: TextView
    private lateinit var testTranscriptView: TextView
    private lateinit var testCard: LinearLayout
    private lateinit var testActionMeta: TextView
    private var creditsMain: TextView? = null
    private var creditsSub: TextView? = null
    private var creditsBar: LinearLayout? = null
    private var creditsBarFill: View? = null
    private var creditsBarRest: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownProvider = settings.selectedEngine()?.provider ?: SpeechProvider.OPENAI
        buildSkeleton()
        renderAll()
    }

    override fun onStop() {
        stopDictationIfActive()
        super.onStop()
    }

    // --- Skeleton, built exactly once ---

    private fun buildSkeleton() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        engineHeaderMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        languageHeaderMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        patienceHeaderMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        keyHeaderMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        readinessValue = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        engineChipsHost = host()
        modelCardHost = host()
        languageGridHost = host()
        languageNoteHost = host()
        patienceCardHost = host()
        patienceNoteHost = host()
        keyCardHost = host()
        keySectionHost = host()

        val content = NexusUi.contentColumn(this).apply {
            addView(sectionHeaderRow("Engine", engineHeaderMeta), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(engineChipsHost, NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(modelCardHost, NexusUi.block())

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            addView(sectionHeaderRow("Patience", patienceHeaderMeta), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(patienceCardHost, NexusUi.block())
            addView(patienceNoteHost, NexusUi.block())

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            addView(sectionHeaderRow("Language", languageHeaderMeta), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(languageGridHost, NexusUi.block())
            addView(languageNoteHost, NexusUi.block())

            addView(
                keySectionHost.apply {
                    addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
                    addView(sectionHeaderRow("API key", keyHeaderMeta), NexusUi.block())
                    addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
                    addView(keyCardHost, NexusUi.block())
                },
                NexusUi.block(),
            )

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            addView(sectionHeaderRow("Try it", readinessValue), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(dictationCard(), NexusUi.block())
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(titleHeader("Speech"), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun host(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun sectionHeaderRow(label: String, metaView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                NexusUi.dp(this@SpeechSettingsActivity, 2),
                NexusUi.dp(this@SpeechSettingsActivity, 2),
                NexusUi.dp(this@SpeechSettingsActivity, 2),
                0,
            )
            addView(
                NexusUi.sectionLabel(this@SpeechSettingsActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(metaView)
        }

    // --- Section renderers ---

    private fun renderAll() {
        renderEngineSection()
        renderPatienceSection()
        renderLanguageSection()
        renderKeySection()
        renderReadiness()
        renderDictation()
    }

    private fun renderEngineSection() {
        engineHeaderMeta.text = shownProvider.displayName.uppercase()
        engineChipsHost.removeAllViews()
        engineChipsHost.addView(providerSegments(), NexusUi.block())
        modelCardHost.removeAllViews()
        modelCardHost.addView(modelCard(), NexusUi.block())
    }

    private fun renderPatienceSection() {
        val patience = settings.patience()
        patienceHeaderMeta.text = patience.name.uppercase()
        patienceCardHost.removeAllViews()
        patienceCardHost.addView(patienceCard(), NexusUi.block())
        
        patienceNoteHost.removeAllViews()
        patienceNoteHost.addView(BusTheme.gap(this, 8))
        patienceNoteHost.addView(
            NexusUi.rowSub(this, "How long the glasses wait for you to start, and how long a pause can last before your sentence is taken as finished.").apply { maxLines = 3 },
            NexusUi.block(),
        )
    }

    private fun patienceCard(): LinearLayout =
        NexusUi.card(this).apply {
            val options = com.anezium.rokidbus.phone.speech.SpeechPatience.entries.toTypedArray()
            options.forEachIndexed { index, option ->
                if (index > 0) addView(NexusUi.divider(this@SpeechSettingsActivity))
                addView(patienceRow(option), NexusUi.block())
            }
        }

    private fun patienceRow(option: com.anezium.rokidbus.phone.speech.SpeechPatience): LinearLayout {
        val selected = settings.patience() == option
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SpeechSettingsActivity, 8),
                NexusUi.dp(this@SpeechSettingsActivity, 8),
            ).apply { marginStart = NexusUi.dp(this@SpeechSettingsActivity, 12) }
        }
        NexusUi.setDotColor(dot, if (selected) NexusUi.GREEN else NexusUi.INK4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            setPadding(
                NexusUi.dp(this@SpeechSettingsActivity, 16),
                NexusUi.dp(this@SpeechSettingsActivity, 12),
                NexusUi.dp(this@SpeechSettingsActivity, 16),
                NexusUi.dp(this@SpeechSettingsActivity, 12),
            )
            setOnClickListener {
                if (settings.patience() != option) {
                    settings.setPatience(option)
                    renderPatienceSection()
                    onSpeechConfigChanged()
                }
            }
            val titleText = option.name.lowercase().replaceFirstChar { it.uppercase() }
            addView(
                NexusUi.rowTitle(
                    this@SpeechSettingsActivity,
                    titleText,
                ).apply { if (selected) setTextColor(NexusUi.INK) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(dot)
        }
    }

    private fun renderLanguageSection() {
        // The Android engine only auto-detects, so the grid goes read-only instead of rewriting
        // the stored language — picking a cloud engine again brings the user's choice back.
        val autoDetected = settings.selectedEngine()?.usesAndroidRecognizer == true
        val effective = if (autoDetected) TranscriptionLanguage.AUTO else settings.selectedLanguage()
        languageHeaderMeta.text = effective.summaryName.uppercase()
        languageGridHost.removeAllViews()
        languageGridHost.addView(languageGrid(effective, autoDetected), NexusUi.block())
        languageNoteHost.removeAllViews()
        val note = if (autoDetected) {
            "The Android engine picks the language up on its own. Choose a cloud engine to force one."
        } else {
            effective.uiNote
        }
        note?.let {
            languageNoteHost.addView(BusTheme.gap(this, 8))
            languageNoteHost.addView(
                NexusUi.rowSub(this, it).apply { maxLines = 3 },
                NexusUi.block(),
            )
        }
    }

    private fun renderKeySection() {
        // The Android recognizer takes no credentials, so the whole section goes away for it
        // rather than claiming a key is saved.
        if (shownProvider == SpeechProvider.ANDROID) {
            keySectionHost.visibility = View.GONE
            keyCardHost.removeAllViews()
            return
        }
        keySectionHost.visibility = View.VISIBLE
        keyHeaderMeta.text = shownProvider.displayName.uppercase()
        creditsMain = null
        creditsSub = null
        creditsBar = null
        creditsBarFill = null
        creditsBarRest = null
        keyCardHost.removeAllViews()
        keyCardHost.addView(credentialCard(), NexusUi.block())
    }

    private fun onSpeechConfigChanged() {
        stopDictationIfActive()
        statusText = null
        transcriptText = null
        transcriptFinal = false
        renderReadiness()
        renderDictation()
    }

    // --- Engine ---

    private fun providerSegments(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            SpeechProvider.values().forEachIndexed { index, provider ->
                addView(
                    providerChip(provider),
                    LinearLayout.LayoutParams(0, NexusUi.dp(this@SpeechSettingsActivity, 42), 1f).apply {
                        if (index > 0) marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                    },
                )
            }
        }

    private fun providerChip(provider: SpeechProvider): TextView =
        TextView(this).apply {
            text = provider.displayName
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            val selected = provider == shownProvider
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x14), NexusUi.alpha(NexusUi.GREEN, 0x50), 12)
            } else {
                NexusUi.pressedBordered(this@SpeechSettingsActivity, NexusUi.PANEL, 12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (shownProvider != provider) {
                    shownProvider = provider
                    keyEditing = false
                    renderEngineSection()
                    renderKeySection()
                    onSpeechConfigChanged()
                }
            }
        }

    private fun modelCard(): LinearLayout =
        NexusUi.card(this).apply {
            val models = SpeechEngine.values().filter { it.provider == shownProvider }
            models.forEachIndexed { index, engine ->
                if (index > 0) addView(NexusUi.divider(this@SpeechSettingsActivity))
                addView(modelRow(engine), NexusUi.block())
            }
        }

    private fun modelRow(engine: SpeechEngine): LinearLayout {
        val selected = settings.selectedEngine() == engine
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SpeechSettingsActivity, 8),
                NexusUi.dp(this@SpeechSettingsActivity, 8),
            ).apply { marginStart = NexusUi.dp(this@SpeechSettingsActivity, 12) }
        }
        NexusUi.setDotColor(dot, if (selected) NexusUi.GREEN else NexusUi.INK4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (settings.selectedEngine() != engine) {
                    settings.selectedEngineId = engine.id
                    renderEngineSection()
                    // The Android engine locks the language grid, so it has to redraw too.
                    renderLanguageSection()
                    onSpeechConfigChanged()
                }
            }
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(
                            this@SpeechSettingsActivity,
                            engine.displayName.removePrefix(engine.provider.displayName).trim(),
                        ).apply { if (selected) setTextColor(NexusUi.INK) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(dot)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SpeechSettingsActivity, 4))
            addView(
                NexusUi.cardBody(this@SpeechSettingsActivity, engine.choiceDescription).apply {
                    textSize = 12f
                    maxLines = 2
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SpeechSettingsActivity, 5))
            addView(
                NexusUi.metaLabel(
                    this@SpeechSettingsActivity,
                    engine.choiceBadges.joinToString("  ·  "),
                    if (selected) NexusUi.GREEN_DIM else NexusUi.INK4,
                ),
                NexusUi.block(),
            )
        }
    }

    // --- Language ---

    private fun languageGrid(
        effective: TranscriptionLanguage,
        readOnly: Boolean,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val languages = TranscriptionLanguage.values().toList()
            languages.chunked(3).forEachIndexed { rowIndex, row ->
                addView(
                    LinearLayout(this@SpeechSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEachIndexed { index, language ->
                            addView(
                                languageChip(language, effective, readOnly),
                                LinearLayout.LayoutParams(0, NexusUi.dp(this@SpeechSettingsActivity, 40), 1f).apply {
                                    if (index > 0) marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                                },
                            )
                        }
                        repeat(3 - row.size) {
                            addView(
                                View(this@SpeechSettingsActivity),
                                LinearLayout.LayoutParams(0, 1, 1f).apply {
                                    marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                                },
                            )
                        }
                    },
                    NexusUi.block().apply {
                        if (rowIndex > 0) topMargin = NexusUi.dp(this@SpeechSettingsActivity, 8)
                    },
                )
            }
        }

    private fun languageChip(
        language: TranscriptionLanguage,
        effective: TranscriptionLanguage,
        readOnly: Boolean,
    ): TextView =
        TextView(this).apply {
            text = language.label
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val selected = effective == language
            setTextColor(
                when {
                    selected && readOnly -> NexusUi.GREEN_DIM
                    selected -> NexusUi.GREEN
                    readOnly -> NexusUi.INK4
                    else -> NexusUi.INK2
                },
            )
            background = when {
                selected -> NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x14), NexusUi.alpha(NexusUi.GREEN, 0x50), 11)
                readOnly -> NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.PANEL, NexusUi.LINE, 11)
                else -> NexusUi.pressedBordered(this@SpeechSettingsActivity, NexusUi.PANEL, 11)
            }
            isClickable = !readOnly
            isFocusable = !readOnly
            if (readOnly) return@apply
            setOnClickListener {
                if (settings.selectedLanguage() != language) {
                    settings.selectedLanguageId = language.id
                    renderLanguageSection()
                    onSpeechConfigChanged()
                }
            }
        }

    // --- API key ---

    private fun credentialCard(): LinearLayout =
        NexusUi.card(this).apply {
            val hasKey = secrets.hasCredential(shownProvider.credentialKindForUi())
            if (hasKey && !keyEditing) {
                addView(keyStatusBlock(), NexusUi.block())
            } else {
                addView(keyEditBlock(hasKey), NexusUi.block())
            }
        }

    /** Saved state: the card is a status — balance first, actions tucked away. */
    private fun keyStatusBlock(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val main = TextView(this@SpeechSettingsActivity).apply {
                textSize = 17f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                setTextColor(NexusUi.INK2)
            }
            creditsMain = main
            addView(main, NexusUi.block())

            if (shownProvider == SpeechProvider.ELEVENLABS) {
                main.text = "Checking credits…"
                main.setTextColor(NexusUi.INK3)

                addView(BusTheme.gap(this@SpeechSettingsActivity, 9))
                val fill = View(this@SpeechSettingsActivity).apply {
                    background = NexusUi.rounded(this@SpeechSettingsActivity, NexusUi.GREEN_DIM, 2)
                }
                val rest = View(this@SpeechSettingsActivity)
                val bar = LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = NexusUi.rounded(this@SpeechSettingsActivity, NexusUi.LINE2, 2)
                    visibility = View.GONE
                    addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
                }
                creditsBar = bar
                creditsBarFill = fill
                creditsBarRest = rest
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@SpeechSettingsActivity, 4),
                    ),
                )

                addView(BusTheme.gap(this@SpeechSettingsActivity, 8))
                val sub = NexusUi.metaLabel(this@SpeechSettingsActivity, "", NexusUi.INK4)
                creditsSub = sub
                addView(sub, NexusUi.block())
                fetchCredits()
            } else {
                main.text = "Key saved."
                main.setTextColor(NexusUi.INK)
                if (shownProvider == SpeechProvider.AZURE) {
                    addView(BusTheme.gap(this@SpeechSettingsActivity, 6))
                    val region = secrets.azureRegion().orEmpty()
                    addView(
                        NexusUi.metaLabel(
                            this@SpeechSettingsActivity,
                            if (region.isBlank()) "No region set" else "Region · $region",
                            if (region.isBlank()) NexusUi.AMBER else NexusUi.INK4,
                        ),
                        NexusUi.block(),
                    )
                }
            }

            addView(NexusUi.divider(this@SpeechSettingsActivity))
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 10)
                    setOnClickListener {
                        keyEditing = true
                        renderKeySection()
                    }
                    addView(
                        NexusUi.rowLabel(this@SpeechSettingsActivity, "Replace key"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(NexusUi.metaLabel(this@SpeechSettingsActivity, "Edit ›", NexusUi.GREEN))
                },
                NexusUi.block(),
            )
        }

    /** Edit state: field(s) + a full-width Save, quiet secondary actions. */
    private fun keyEditBlock(hasKey: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val keyField = secretField(
                if (hasKey) "Paste a new ${shownProvider.displayName} key" else "Paste your ${shownProvider.displayName} API key",
            )
            addView(keyField, NexusUi.block())

            var regionField: EditText? = null
            if (shownProvider == SpeechProvider.AZURE) {
                addView(BusTheme.gap(this@SpeechSettingsActivity, 8))
                regionField = plainField("Region — e.g. westeurope").apply {
                    setText(secrets.azureRegion().orEmpty())
                }
                addView(regionField, NexusUi.block())
            }

            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(
                NexusUi.pillButton(this@SpeechSettingsActivity, "Save").apply {
                    setOnClickListener { onSaveCredential(keyField, regionField) }
                },
                NexusUi.block(),
            )

            if (hasKey) {
                addView(BusTheme.gap(this@SpeechSettingsActivity, 4))
                addView(
                    LinearLayout(this@SpeechSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            NexusUi.textButton(this@SpeechSettingsActivity, "Cancel").apply {
                                setOnClickListener {
                                    keyEditing = false
                                    renderKeySection()
                                }
                            },
                        )
                        addView(View(this@SpeechSettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                        addView(
                            NexusUi.textButton(this@SpeechSettingsActivity, "Remove key", danger = true).apply {
                                setOnClickListener { onClearCredential() }
                            },
                        )
                    },
                    NexusUi.block(),
                )
            }
        }

    private fun secretField(hintText: String): EditText =
        plainField(hintText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.MONOSPACE
        }

    private fun plainField(hintText: String): EditText =
        NexusUi.field(this, hintText).apply {
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
        }

    private fun onSaveCredential(keyField: EditText, regionField: EditText?) {
        val kind = shownProvider.credentialKindForUi()
        val key = keyField.text?.toString()?.trim().orEmpty()
        val region = regionField?.text?.toString()?.trim().orEmpty()
        var savedAnything = false

        if (key.isNotBlank()) {
            if (!secrets.saveApiKey(kind, key)) {
                toast("Could not save the key on this phone")
                return
            }
            savedAnything = true
        }
        if (regionField != null && region.isNotBlank() && region != secrets.azureRegion().orEmpty()) {
            if (!secrets.saveAzureRegion(region)) {
                toast("Region looks invalid — use the short form, e.g. westeurope")
                return
            }
            savedAnything = true
        }
        if (!savedAnything) {
            toast(if (regionField != null) "Paste a key or region first" else "Paste a key first")
            return
        }
        toast("Saved")
        keyEditing = false
        renderKeySection()
        onSpeechConfigChanged()
    }

    private fun onClearCredential() {
        val kind = shownProvider.credentialKindForUi()
        secrets.clearApiKey(kind)
        if (shownProvider == SpeechProvider.AZURE) secrets.clearAzureRegion()
        toast("Key removed")
        keyEditing = false
        renderKeySection()
        onSpeechConfigChanged()
    }

    /** Async ElevenLabs quota refresh; the generation counter drops stale results. */
    private fun fetchCredits() {
        val generation = ++creditsGeneration
        Thread {
            val key = secrets.apiKey(SpeechCredentialKind.ELEVENLABS)
            val quota = key?.let { SpeechCredits.fetchElevenLabs(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != creditsGeneration) return@runOnUiThread
                renderCredits(quota)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun renderCredits(quota: SpeechCredits.ElevenLabsQuota?) {
        val main = creditsMain ?: return
        val sub = creditsSub ?: return
        if (quota == null) {
            main.text = "Credits unavailable"
            main.setTextColor(NexusUi.INK2)
            sub.visibility = View.VISIBLE
            sub.text = "ENABLE THE USER READ SCOPE TO SEE CREDITS"
            sub.setTextColor(NexusUi.INK4)
            creditsBar?.visibility = View.GONE
            return
        }

        val low = quota.remaining < quota.limit / 10L
        val format = java.text.NumberFormat.getIntegerInstance()
        val remainingText = format.format(quota.remaining)
        val line = SpannableString("$remainingText credits left")
        line.setSpan(
            ForegroundColorSpan(if (low) NexusUi.AMBER else NexusUi.INK),
            0,
            remainingText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        main.setTextColor(NexusUi.INK2)
        main.text = line

        val reset = quota.resetUnixSeconds
        if (reset == null) {
            sub.visibility = View.GONE
        } else {
            val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.US)
                .format(java.util.Date(reset * 1000L))
            sub.visibility = View.VISIBLE
            sub.text = "resets $date".uppercase(java.util.Locale.US)
            sub.setTextColor(NexusUi.INK4)
        }

        creditsBar?.visibility = View.VISIBLE
        creditsBarFill?.let { fill ->
            (fill.background as? GradientDrawable)
                ?.setColor(if (low) NexusUi.AMBER else NexusUi.GREEN_DIM)
            fill.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                quota.remaining.toFloat().coerceAtLeast(0f),
            )
        }
        creditsBarRest?.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            (quota.limit - quota.remaining).toFloat().coerceAtLeast(0f),
        )
        creditsBar?.requestLayout()
    }

    // --- Try it ---

    /** The whole card is the control: tap to dictate, tap again to stop. */
    private fun dictationCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.pressedBordered(this@SpeechSettingsActivity, NexusUi.PANEL, 15)
            setPadding(
                NexusUi.dp(this@SpeechSettingsActivity, 15),
                NexusUi.dp(this@SpeechSettingsActivity, 14),
                NexusUi.dp(this@SpeechSettingsActivity, 15),
                NexusUi.dp(this@SpeechSettingsActivity, 14),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onDictationCardTap() }
            testCard = this

            testStatusView = NexusUi.statusLine(this@SpeechSettingsActivity)
            testTranscriptView = NexusUi.cardBody(this@SpeechSettingsActivity, "").apply {
                textSize = 15f
                minHeight = NexusUi.dp(this@SpeechSettingsActivity, 56)
            }
            testActionMeta = NexusUi.metaLabel(this@SpeechSettingsActivity, "", NexusUi.GREEN)

            addView(testStatusView, NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(testTranscriptView, NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(this@SpeechSettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(testActionMeta)
                },
                NexusUi.block(),
            )
        }

    private fun onDictationCardTap() {
        if (testActive) {
            BusHubService.cancelSpeechDictationTest()
            return
        }
        if (settings.readiness(secrets) == SpeechReadiness.MISSING_MIC_PERMISSION) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        transcriptText = null
        transcriptFinal = false
        when (val result = BusHubService.startSpeechDictationTest(dictationListener)) {
            SpeechStartResult.OK -> {
                testActive = true
                setStatus("Starting…", NexusUi.INK2)
            }
            SpeechStartResult.BUSY ->
                setStatus("Microphone is busy — another plugin is using it.", NexusUi.AMBER)
            SpeechStartResult.NO_LINK ->
                setStatus("Connect the glasses first — Nexus must be running.", NexusUi.AMBER)
            SpeechStartResult.NOT_READY ->
                setStatus(readinessGuidance(), NexusUi.AMBER)
            SpeechStartResult.START_FAILED ->
                setStatus("Could not start — try again.", NexusUi.AMBER)
            else -> setStatus("Could not start ($result)", NexusUi.AMBER)
        }
        renderDictation()
    }

    private val dictationListener = object : SpeechUtteranceListener {
        override fun onState(state: SpeechSessionState) {
            if (isFinishing || isDestroyed) return
            when (state) {
                SpeechSessionState.LISTENING ->
                    setStatus("Listening — speak toward the glasses…", NexusUi.INK2)
                SpeechSessionState.RECOGNIZING ->
                    setStatus("Hearing you…", NexusUi.INK2)
                SpeechSessionState.PROCESSING ->
                    setStatus("Transcribing…", NexusUi.INK2)
            }
        }

        override fun onPartial(text: String) {
            if (isFinishing || isDestroyed) return
            transcriptText = text
            transcriptFinal = false
            renderDictation()
        }

        override fun onFinal(text: String) {
            if (isFinishing || isDestroyed) return
            transcriptText = text
            transcriptFinal = true
            renderDictation()
        }

        override fun onEnded(reason: SpeechEndReason, error: SttError?) {
            if (isFinishing || isDestroyed) return
            testActive = false
            if (reason == SpeechEndReason.COMPLETED && shownProvider == SpeechProvider.ELEVENLABS) {
                fetchCredits()
            }
            when (reason) {
                SpeechEndReason.COMPLETED -> setStatus("Done.", NexusUi.GREEN_DIM)
                SpeechEndReason.CANCELLED -> setStatus("Stopped.", NexusUi.INK3)
                SpeechEndReason.NO_SPEECH ->
                    setStatus("No speech heard — try again, closer to the glasses.", NexusUi.AMBER)
                SpeechEndReason.LINK_LOST -> setStatus("Glasses link lost.", NexusUi.AMBER)
                SpeechEndReason.ERROR -> setStatus(errorLabel(error), NexusUi.AMBER)
            }
            renderDictation()
        }
    }

    private fun errorLabel(error: SttError?): String {
        val base = error?.detail ?: "Something went wrong — try again."
        val provider = error?.providerLabel
        return if (provider != null) "$provider: $base" else base
    }

    private fun readinessGuidance(): String =
        when (settings.readiness(secrets)) {
            SpeechReadiness.READY -> "Tap and speak toward the glasses."
            SpeechReadiness.NO_ENGINE -> "Pick an engine above to get started."
            SpeechReadiness.MISSING_KEY -> "Add your ${settings.selectedEngine()?.provider?.displayName ?: "provider"} API key above."
            SpeechReadiness.MISSING_REGION -> "Add your Azure region above."
            SpeechReadiness.MISSING_MIC_PERMISSION ->
                "The Android engine needs the microphone permission — tap to allow it."
        }

    private fun renderReadiness() {
        val readiness = settings.readiness(secrets)
        readinessValue.text = when (readiness) {
            SpeechReadiness.READY -> "Ready"
            SpeechReadiness.NO_ENGINE -> "Pick engine"
            SpeechReadiness.MISSING_KEY -> "Add key"
            SpeechReadiness.MISSING_REGION -> "Add region"
            SpeechReadiness.MISSING_MIC_PERMISSION -> "Allow mic"
        }.uppercase()
        readinessValue.setTextColor(
            if (readiness == SpeechReadiness.READY) NexusUi.GREEN_DIM else NexusUi.AMBER,
        )
        if (statusText == null) {
            statusText = readinessGuidance()
            statusColor = if (readiness == SpeechReadiness.READY) NexusUi.INK2 else NexusUi.AMBER
        }
    }

    private fun renderDictation() {
        if (!::testActionMeta.isInitialized) return
        testStatusView.text = statusText.orEmpty()
        testStatusView.setTextColor(statusColor)
        val transcript = transcriptText
        if (transcript.isNullOrBlank()) {
            testTranscriptView.text = "Your words appear here."
            testTranscriptView.setTextColor(NexusUi.INK4)
        } else {
            testTranscriptView.text = transcript
            testTranscriptView.setTextColor(if (transcriptFinal) NexusUi.INK else NexusUi.INK2)
        }
        val ready = settings.readiness(secrets) == SpeechReadiness.READY
        when {
            testActive -> {
                testActionMeta.text = "STOP"
                testActionMeta.setTextColor(NexusUi.DANGER)
                testCard.background = NexusUi.bordered(
                    this,
                    NexusUi.alpha(NexusUi.GREEN, 0x0A),
                    NexusUi.alpha(NexusUi.GREEN, 0x45),
                    15,
                )
            }
            ready -> {
                testActionMeta.text = "DICTATE ›"
                testActionMeta.setTextColor(NexusUi.GREEN)
                testCard.background = NexusUi.pressedBordered(this, NexusUi.PANEL, 15)
            }
            else -> {
                // The mic permission is granted from this very card, so it is not a "set up above":
                // it is an action, and it gets an action's colour.
                val needsMic = settings.readiness(secrets) == SpeechReadiness.MISSING_MIC_PERMISSION
                testActionMeta.text = if (needsMic) "ALLOW MIC ›" else "SET UP ABOVE"
                testActionMeta.setTextColor(if (needsMic) NexusUi.AMBER else NexusUi.INK4)
                testCard.background = NexusUi.pressedBordered(this, NexusUi.PANEL, 15)
            }
        }
    }

    private fun setStatus(text: String, color: Int) {
        statusText = text
        statusColor = color
        renderDictation()
    }

    private fun stopDictationIfActive() {
        if (testActive) {
            BusHubService.cancelSpeechDictationTest()
            testActive = false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // --- Shell ---

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@SpeechSettingsActivity, 10),
                        NexusUi.dp(this@SpeechSettingsActivity, 12),
                        NexusUi.dp(this@SpeechSettingsActivity, 22),
                        NexusUi.dp(this@SpeechSettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@SpeechSettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@SpeechSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@SpeechSettingsActivity, 1),
                    )
                },
            )
        }

    private fun backButton(): TextView =
        TextView(this).apply {
            text = "‹"
            textSize = 26f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SpeechSettingsActivity, 44),
                NexusUi.dp(this@SpeechSettingsActivity, 44),
            )
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        renderReadiness()
        renderDictation()
        if (granted) {
            setStatus("Microphone allowed — tap to dictate.", NexusUi.INK2)
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Permanently denied: the system dialog will not come back, so send them to Settings.
            setStatus("Allow the microphone in Android settings to use the Android engine.", NexusUi.AMBER)
        } else {
            setStatus(readinessGuidance(), NexusUi.AMBER)
        }
    }
}

private const val REQUEST_RECORD_AUDIO = 4101


private fun SpeechProvider.credentialKindForUi(): SpeechCredentialKind =
    when (this) {
        SpeechProvider.ANDROID -> SpeechCredentialKind.NONE
        SpeechProvider.OPENAI -> SpeechCredentialKind.OPENAI
        SpeechProvider.ELEVENLABS -> SpeechCredentialKind.ELEVENLABS
        SpeechProvider.AZURE -> SpeechCredentialKind.AZURE
    }
