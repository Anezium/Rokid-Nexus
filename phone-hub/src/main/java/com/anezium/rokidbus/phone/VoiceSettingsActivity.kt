package com.anezium.rokidbus.phone

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var speedHost: LinearLayout
    private lateinit var languageHost: LinearLayout
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
        speedHost = host()
        languageHost = host()
        voiceListHost = host()

        val phoneSettingsHost = host().apply {
            addView(
                sectionHeaderRow(getString(R.string.voice_language), NexusUi.metaLabel(this@VoiceSettingsActivity, "", NexusUi.INK4)),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(NexusUi.card(this@VoiceSettingsActivity).apply {
                addView(languageHost, NexusUi.block())
            }, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(sectionHeaderRow(getString(R.string.voice_speed), headerMeta), NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(speedHost, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(
                sectionHeaderRow(
                    getString(R.string.voice_section),
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
                NexusUi.textButton(this@VoiceSettingsActivity, getString(R.string.voice_hear_sample)).apply {
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
                addView(titleHeader(getString(R.string.voice_title)), NexusUi.block())
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
        introCard.text = getString(R.string.voice_intro)

        val locale = voiceSettings.speechLocale()
        languageHost.removeAllViews()
        languageHost.addView(
            selectableRow(
                locale.getDisplayName(locale),
                getString(if (voiceSettings.languageTag() == null) R.string.voice_follow_phone else R.string.voice_independent_language),
                selected = true,
                onClick = ::chooseLanguage,
            ),
            NexusUi.block(),
        )

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

        voiceListHost.removeAllViews()
        val voices = PhoneTtsUiApi.availableVoices(locale)
        if (!PhoneTtsUiApi.isReady) {
            voiceListHost.addView(
                NexusUi.cardBody(this, getString(R.string.voice_engine_not_ready)),
                NexusUi.block(),
            )
            return
        }
        if (voices.isEmpty()) {
            voiceListHost.addView(
                NexusUi.cardBody(this, getString(R.string.voice_no_data, locale.getDisplayName(locale))),
                NexusUi.block(),
            )
            return
        }
        val selected = voiceSettings.voiceName()
        voiceListHost.addView(
            selectableRow(getString(R.string.voice_default), null, selected == null) {
                voiceSettings.setVoiceName(null)
                render()
                hearSample()
            },
            NexusUi.block(),
        )
        voices.forEach { option ->
            voiceListHost.addView(
                selectableRow(
                    "${option.locale.getDisplayName(option.locale)} · ${option.name}",
                    getString(when {
                        !option.isInstalled -> R.string.voice_download_required
                        option.needsNetwork -> R.string.voice_needs_network
                        else -> R.string.voice_on_device
                    }),
                    selected == option.name,
                ) {
                    voiceSettings.setVoiceName(option.name)
                    render()
                    hearSample()
                }.apply {
                    isEnabled = option.isInstalled
                    isClickable = option.isInstalled
                    alpha = if (option.isInstalled) 1f else 0.5f
                },
                NexusUi.block(),
            )
        }
    }

    private fun chooseLanguage() {
        val locales = (PhoneTtsUiApi.availableVoices().map { it.locale } + listOf(
            Locale.getDefault(),
            voiceSettings.speechLocale(),
            Locale.forLanguageTag("ru-RU"),
        )).filter { it.language.isNotBlank() }
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.getDisplayName(it).lowercase(Locale.ROOT) }
        val tags = listOf<String?>(null) + locales.map { it.toLanguageTag() }
        val labels = listOf(getString(R.string.voice_follow_phone)) + locales.map { it.getDisplayName(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_language)
            .setSingleChoiceItems(labels.toTypedArray(), tags.indexOf(voiceSettings.languageTag())) { dialog, index ->
                voiceSettings.setLanguageTag(tags[index])
                render()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            contentDescription = listOfNotNull(label, badge).joinToString(", ")
            isSelected = selected
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
                LinearLayout(this@VoiceSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@VoiceSettingsActivity, label).apply {
                        maxLines = 2
                        if (selected) setTextColor(NexusUi.INK)
                    }, NexusUi.block())
                    badge?.let {
                        addView(NexusUi.rowSub(this@VoiceSettingsActivity, it), NexusUi.block())
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(dot)
        }
    }

    private fun hearSample() {
        val locale = voiceSettings.speechLocale()
        if (!PhoneTtsUiApi.speakSample(sampleText(locale), locale)) {
            Toast.makeText(this, getString(R.string.voice_sample_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The sample has to be in the language the voice will actually speak, or it previews
     * the wrong thing entirely — a French voice reading English is exactly the mismatch
     * this screen exists to let you avoid.
     */
    private fun sampleText(locale: Locale): String = when (locale.language) {
        "ru" -> getString(R.string.voice_sample_ru)
        "fr" -> getString(R.string.voice_sample_fr)
        "en" -> getString(R.string.voice_sample_en)
        else -> locale.getDisplayName(locale)
    }

    private fun formatRate(rate: Float): String =
        if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"
}

private val SPEECH_RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f)
