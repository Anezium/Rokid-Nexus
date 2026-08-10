package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.Locale

/**
 * How Nexus answers out loud, as opposed to how it listens, which is Speech.
 *
 * The phone owns synthesis so the chosen voice and speed stay consistent whichever safe
 * listening device is active.
 */
class VoiceSettingsActivity : Activity() {
    private val voiceSettings by lazy { PhoneTtsSettingsStore(this) }

    private lateinit var introCard: TextView
    private lateinit var headerMeta: TextView
    private lateinit var languageHeaderMeta: TextView
    private lateinit var speedHost: LinearLayout
    private lateinit var languageGridHost: LinearLayout
    private lateinit var voiceListHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildSkeleton()
        render()
    }

    override fun onResume() {
        super.onResume()
        // The voice list comes from the running hub, so it can appear or vanish while
        // this screen is open.
        render()
    }

    private fun buildSkeleton() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        introCard = NexusUi.cardBody(this, "")
        headerMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        languageHeaderMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        speedHost = host()
        languageGridHost = host()
        voiceListHost = host()

        val phoneSettingsHost = host().apply {
            addView(sectionHeaderRow("Speed", headerMeta), NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(speedHost, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(sectionHeaderRow("Language", languageHeaderMeta), NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(languageGridHost, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(
                sectionHeaderRow(
                    "Voice",
                    NexusUi.metaLabel(this@VoiceSettingsActivity, "", NexusUi.INK4),
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(
                NexusUi.card(this@VoiceSettingsActivity).apply {
                    addView(voiceListHost, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 14))
            addView(
                NexusUi.textButton(this@VoiceSettingsActivity, "Hear it").apply {
                    setOnClickListener { hearSample() }
                },
                NexusUi.block(),
            )
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(introCard, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 22))
            addView(phoneSettingsHost, NexusUi.block())
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

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader("VOICE"), NexusUi.block())
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun host(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@VoiceSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@VoiceSettingsActivity, 10),
                        NexusUi.dp(this@VoiceSettingsActivity, 12),
                        NexusUi.dp(this@VoiceSettingsActivity, 22),
                        NexusUi.dp(this@VoiceSettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@VoiceSettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@VoiceSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@VoiceSettingsActivity, 1),
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
            background = NexusUi.pressed(this@VoiceSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@VoiceSettingsActivity, 44),
                NexusUi.dp(this@VoiceSettingsActivity, 44),
            )
        }

    private fun sectionHeaderRow(label: String, metaView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.sectionLabel(this@VoiceSettingsActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(metaView)
        }

    private fun render() {
        introCard.text =
            "Answers use your phone's voice and play through your glasses or earbuds. " +
                "If neither can play them, nothing is spoken — never the phone's own speaker."

        val rate = voiceSettings.speechRate()
        headerMeta.text = formatRate(rate)

        speedHost.removeAllViews()
        speedHost.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                SPEECH_RATES.forEachIndexed { index, value ->
                    addView(speedChip(value, rate, first = index == 0))
                }
            },
            NexusUi.block(),
        )

        val allVoices = PhoneTtsUiApi.availableVoices()
        val selectedLanguageTag = voiceSettings.languageTag()
        val selectedLocale = selectedLanguageTag
            ?.let(Locale::forLanguageTag)
            ?: Locale.getDefault()
        val languages = availableLanguages(allVoices)
        languageHeaderMeta.text = selectedLanguageTag
            ?.let { displayLanguageName(selectedLocale).uppercase() }
            ?: "SYSTEM"
        languageGridHost.removeAllViews()
        languageGridHost.addView(
            languageGrid(languages, selectedLanguageTag),
            NexusUi.block(),
        )

        voiceListHost.removeAllViews()
        if (allVoices.isEmpty()) {
            // An empty list means the hub is not running, not that the phone has no voices.
            voiceListHost.addView(
                NexusUi.metaLabel(this, "START THE HUB TO CHOOSE A VOICE", NexusUi.INK4),
                NexusUi.block(),
            )
            return
        }
        val voices = allVoices.filter { option ->
            option.locale.toLanguageTag().equals(
                selectedLocale.toLanguageTag(),
                ignoreCase = true,
            )
        }
        val selected = voiceSettings.voiceName(selectedLocale)
        voiceListHost.addView(
            selectableRow("Default", null, selected == null) {
                voiceSettings.setVoiceName(selectedLocale, null)
                render()
                hearSample()
            },
            NexusUi.block(),
        )
        voices.forEachIndexed { index, option ->
            voiceListHost.addView(
                selectableRow(
                    "Voice ${index + 1}",
                    if (option.needsNetwork) "needs network" else "on device",
                    selected == option.name,
                ) {
                    voiceSettings.setVoiceName(selectedLocale, option.name)
                    render()
                    hearSample()
                },
                NexusUi.block(),
            )
        }
    }

    private fun availableLanguages(voices: List<PhoneTtsVoiceOption>): List<TtsLanguageOption> {
        val available = voices
            .distinctBy { it.locale.toLanguageTag().lowercase(Locale.ROOT) }
            .map { option ->
                TtsLanguageOption(
                    languageTag = option.locale.toLanguageTag(),
                    label = displayLanguageName(option.locale),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        return listOf(TtsLanguageOption(null, "System")) + available
    }

    private fun displayLanguageName(locale: Locale): String {
        val displayName = locale.getDisplayName(Locale.getDefault())
            .ifBlank { locale.toLanguageTag() }
        return displayName.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase(Locale.getDefault()) else first.toString()
        }
    }

    private fun languageGrid(
        languages: List<TtsLanguageOption>,
        selectedLanguageTag: String?,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            languages.chunked(3).forEachIndexed { rowIndex, row ->
                addView(
                    LinearLayout(this@VoiceSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEachIndexed { index, language ->
                            addView(
                                languageChip(language, selectedLanguageTag),
                                LinearLayout.LayoutParams(
                                    0,
                                    NexusUi.dp(this@VoiceSettingsActivity, 40),
                                    1f,
                                ).apply {
                                    if (index > 0) {
                                        marginStart = NexusUi.dp(this@VoiceSettingsActivity, 8)
                                    }
                                },
                            )
                        }
                        repeat(3 - row.size) {
                            addView(
                                View(this@VoiceSettingsActivity),
                                LinearLayout.LayoutParams(0, 1, 1f).apply {
                                    marginStart = NexusUi.dp(this@VoiceSettingsActivity, 8)
                                },
                            )
                        }
                    },
                    NexusUi.block().apply {
                        if (rowIndex > 0) {
                            topMargin = NexusUi.dp(this@VoiceSettingsActivity, 8)
                        }
                    },
                )
            }
        }

    private fun languageChip(
        language: TtsLanguageOption,
        selectedLanguageTag: String?,
    ): TextView =
        TextView(this).apply {
            text = language.label
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val selected = language.languageTag.equals(selectedLanguageTag, ignoreCase = true)
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(
                    this@VoiceSettingsActivity,
                    NexusUi.alpha(NexusUi.GREEN, 0x14),
                    NexusUi.alpha(NexusUi.GREEN, 0x50),
                    11,
                )
            } else {
                NexusUi.pressedBordered(this@VoiceSettingsActivity, NexusUi.PANEL, 11)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!language.languageTag.equals(selectedLanguageTag, ignoreCase = true)) {
                    voiceSettings.setLanguageTag(language.languageTag)
                    render()
                    hearSample()
                }
            }
        }

    private fun speedChip(rate: Float, current: Float, first: Boolean): TextView =
        TextView(this).apply {
            text = formatRate(rate)
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val selected = kotlin.math.abs(rate - current) < 0.01f
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(
                    this@VoiceSettingsActivity,
                    NexusUi.alpha(NexusUi.GREEN, 0x14),
                    NexusUi.alpha(NexusUi.GREEN, 0x50),
                    11,
                )
            } else {
                NexusUi.pressedBordered(this@VoiceSettingsActivity, NexusUi.PANEL, 11)
            }
            setPadding(
                NexusUi.dp(this@VoiceSettingsActivity, 16),
                NexusUi.dp(this@VoiceSettingsActivity, 9),
                NexusUi.dp(this@VoiceSettingsActivity, 16),
                NexusUi.dp(this@VoiceSettingsActivity, 9),
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                if (!first) marginStart = NexusUi.dp(this@VoiceSettingsActivity, 8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                voiceSettings.setSpeechRate(rate)
                render()
                hearSample()
            }
        }

    private fun selectableRow(
        label: String,
        badge: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@VoiceSettingsActivity, 8),
                NexusUi.dp(this@VoiceSettingsActivity, 8),
            ).apply { marginStart = NexusUi.dp(this@VoiceSettingsActivity, 12) }
        }
        NexusUi.setDotColor(dot, if (selected) NexusUi.GREEN else NexusUi.INK4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.pressed(this@VoiceSettingsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(
                0,
                NexusUi.dp(this@VoiceSettingsActivity, 7),
                0,
                NexusUi.dp(this@VoiceSettingsActivity, 7),
            )
            setOnClickListener {
                onClick()
            }
            addView(
                NexusUi.rowTitle(this@VoiceSettingsActivity, label).apply {
                    if (selected) setTextColor(NexusUi.INK)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            badge?.let {
                addView(
                    NexusUi.metaLabel(
                        this@VoiceSettingsActivity,
                        it.uppercase(),
                        if (selected) NexusUi.GREEN_DIM else NexusUi.INK4,
                    ),
                )
            }
            addView(dot)
        }
    }

    private fun hearSample() {
        val locale = voiceSettings.languageTag()
            ?.let(Locale::forLanguageTag)
            ?: Locale.getDefault()
        if (!PhoneTtsUiApi.speakSample(sampleText(locale), locale)) {
            Toast.makeText(this, "Start the hub to hear the voice.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The sample has to be in the language the voice will actually speak, or it previews
     * the wrong thing entirely — a French voice reading English is exactly the mismatch
     * this screen exists to let you avoid.
     */
    private fun sampleText(locale: Locale): String = when (locale.language) {
        "en" -> "This is how I will read your answers."
        "fr" -> "Voilà comment je vais lire vos réponses."
        "pt" -> "É assim que vou ler suas respostas."
        else -> "Rokid Nexus. 1, 2, 3."
    }

    private fun formatRate(rate: Float): String =
        if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"
}

private data class TtsLanguageOption(
    val languageTag: String?,
    val label: String,
)

private val SPEECH_RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f)
