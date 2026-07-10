package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREFS_NAME
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_DEEPL_API_KEY
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_ENGINE
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_GEMINI_API_KEY
import com.anezium.rokidbus.phone.lens.TranslationEngine

class LensSettingsActivity : Activity() {
    private lateinit var translationPrefs: SharedPreferences
    private val engineDots = mutableMapOf<TranslationEngine, View>()
    private val engineNames = mutableMapOf<TranslationEngine, TextView>()

    private data class EngineChoice(
        val engine: TranslationEngine,
        val title: String,
        val caption: String,
    )

    private val engineChoices = listOf(
        EngineChoice(TranslationEngine.AUTO, "Auto", "Best configured engine, offline fallback"),
        EngineChoice(TranslationEngine.GEMINI, "Gemini", "AI grade, fixes OCR errors - needs key"),
        EngineChoice(TranslationEngine.DEEPL, "DeepL", "High quality - needs key"),
        EngineChoice(TranslationEngine.GOOGLE_WEB, "Google", "Good quality - no key needed"),
        EngineChoice(TranslationEngine.MLKIT_OFFLINE, "On-device", "Works offline, basic quality"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translationPrefs = getSharedPreferences(LENS_TRANSLATION_PREFS_NAME, MODE_PRIVATE)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(BusTheme.wordmark(this@LensSettingsActivity, "Rokid Nexus"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 18))
            addView(NexusUi.hero(this@LensSettingsActivity).apply { text = "Lens" }, NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 8))
            addView(
                NexusUi.cardBody(
                    this@LensSettingsActivity,
                    "Live camera translation on the glasses. Point, freeze, read.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LensSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Translation engine"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(engineCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 10))
            addView(apiKeysCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Privacy"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 8))
            addView(
                NexusUi.cardBody(
                    this@LensSettingsActivity,
                    "Online engines send only the recognized text of frames you freeze - never " +
                        "images. On-device keeps everything local. API keys stay on this phone.",
                ),
                NexusUi.block(),
            )
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
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    private fun engineCard(): LinearLayout =
        NexusUi.card(this).apply {
            engineChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@LensSettingsActivity))
                addView(engineRow(choice), NexusUi.block())
            }
            renderEngineSelection()
        }

    private fun engineRow(choice: EngineChoice): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@LensSettingsActivity, android.graphics.Color.TRANSPARENT, 10)
            setPadding(0, NexusUi.dp(this@LensSettingsActivity, 4), 0, NexusUi.dp(this@LensSettingsActivity, 4))
            setOnClickListener { selectEngine(choice.engine) }
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@LensSettingsActivity, choice.title).also {
                            engineNames[choice.engine] = it
                        },
                        NexusUi.block(),
                    )
                    addView(NexusUi.rowSub(this@LensSettingsActivity, choice.caption), NexusUi.block())
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.dot(this@LensSettingsActivity).also { engineDots[choice.engine] = it },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@LensSettingsActivity, 8),
                    NexusUi.dp(this@LensSettingsActivity, 8),
                ),
            )
        }

    private fun selectEngine(engine: TranslationEngine) {
        translationPrefs.edit().putString(LENS_TRANSLATION_PREF_ENGINE, engine.name).apply()
        renderEngineSelection()
    }

    private fun renderEngineSelection() {
        val selected = translationPrefs.getString(LENS_TRANSLATION_PREF_ENGINE, null)
            ?.let { stored -> TranslationEngine.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } }
            ?: TranslationEngine.AUTO
        engineDots.forEach { (engine, dotView) ->
            NexusUi.setDotColor(dotView, if (engine == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        engineNames.forEach { (engine, nameView) ->
            nameView.setTextColor(if (engine == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun apiKeysCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                keyRow("Gemini API key", "aistudio.google.com - free", LENS_TRANSLATION_PREF_GEMINI_API_KEY),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@LensSettingsActivity))
            addView(
                keyRow("DeepL API key", "deepl.com/pro-api - free 500k/mo", LENS_TRANSLATION_PREF_DEEPL_API_KEY),
                NexusUi.block(),
            )
        }

    private fun keyRow(label: String, hintSource: String, prefKey: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowLabel(this@LensSettingsActivity, label),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(NexusUi.rowSub(this@LensSettingsActivity, hintSource))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LensSettingsActivity, 6))
            addView(keyField(prefKey), NexusUi.block())
        }

    private fun keyField(prefKey: String): EditText =
        EditText(this).apply {
            setText(translationPrefs.getString(prefKey, ""))
            hint = "Paste key - empty disables"
            setHintTextColor(NexusUi.INK4)
            setTextColor(NexusUi.INK)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply {
                cornerRadius = NexusUi.dp(this@LensSettingsActivity, 10).toFloat()
                setColor(NexusUi.BG)
                setStroke(NexusUi.dp(this@LensSettingsActivity, 1), NexusUi.LINE)
            }
            setPadding(
                NexusUi.dp(this@LensSettingsActivity, 12),
                NexusUi.dp(this@LensSettingsActivity, 10),
                NexusUi.dp(this@LensSettingsActivity, 12),
                NexusUi.dp(this@LensSettingsActivity, 10),
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    translationPrefs.edit().putString(prefKey, s?.toString()?.trim().orEmpty()).apply()
                }
            })
        }
}
