package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONObject
import com.anezium.rokidbus.phone.lens.LENS_GEMINI_MODEL_DEFAULT
import com.anezium.rokidbus.phone.lens.LENS_GEMINI_MODELS
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREFS_NAME
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_DEEPL_API_KEY
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_ENGINE
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_GEMINI_API_KEY
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_GEMINI_MODEL
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_PREF_TARGET_LANG
import com.anezium.rokidbus.phone.lens.LENS_TRANSLATION_TARGET_LANG_DEFAULT
import com.anezium.rokidbus.phone.lens.TranslationEngine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class LensSettingsActivity : Activity() {
    private lateinit var translationPrefs: SharedPreferences
    private val engineDots = mutableMapOf<TranslationEngine, View>()
    private val engineNames = mutableMapOf<TranslationEngine, TextView>()
    private var deepLUsageLabel: TextView? = null
    private val modelDots = mutableMapOf<String, View>()
    private val modelNames = mutableMapOf<String, TextView>()
    private lateinit var outputLanguageValue: TextView

    private val remoteModelManager by lazy { RemoteModelManager.getInstance() }
    private val offlinePackStatus = mutableMapOf<String, TextView>()
    private val offlinePackActions = mutableMapOf<String, Button>()
    private val downloadedPacks = mutableSetOf<String>()
    private val downloadingPacks = mutableSetOf<String>()
    private val failedPacks = mutableSetOf<String>()

    private data class EngineChoice(
        val engine: TranslationEngine,
        val title: String,
        val caption: String,
    )

    private val engineChoices = listOf(
        EngineChoice(TranslationEngine.AUTO, "Auto", "Best configured engine, offline fallback"),
        EngineChoice(TranslationEngine.DEEPL, "DeepL", "High quality - needs key"),
        EngineChoice(TranslationEngine.GEMINI, "Gemini", "AI grade, fixes OCR errors - needs key"),
        EngineChoice(TranslationEngine.GOOGLE_WEB, "Google", "No key - unstable, last-resort fallback"),
        EngineChoice(TranslationEngine.MLKIT_OFFLINE, "On-device", "Works offline, basic quality"),
    )

    private data class ModelChoice(
        val model: String,
        val title: String,
        val caption: String,
    )

    // Free-tier models only; the router whitelist (LENS_GEMINI_MODELS) is the source of truth.
    private val modelChoices = listOf(
        ModelChoice("gemini-2.5-flash", "Gemini 2.5 Flash", "Stable, recommended"),
        ModelChoice("gemini-3-flash-preview", "Gemini 3 Flash", "Smarter - preview, may vanish"),
        ModelChoice("gemini-flash-latest", "Gemini Flash Latest", "Always the newest flash"),
    )

    private data class LanguageChoice(
        val code: String,
        val nativeName: String,
    ) {
        val displayName: String = "$nativeName ($code)"
    }

    private val languageChoices = listOf(
        LanguageChoice("en", "English"),
        LanguageChoice("fr", "Français"),
        LanguageChoice("es", "Español"),
        LanguageChoice("de", "Deutsch"),
        LanguageChoice("it", "Italiano"),
        LanguageChoice("pt", "Português"),
        LanguageChoice("nl", "Nederlands"),
        LanguageChoice("pl", "Polski"),
        LanguageChoice("ru", "Русский"),
        LanguageChoice("ja", "日本語"),
        LanguageChoice("ko", "한국어"),
        LanguageChoice("zh", "中文"),
        LanguageChoice("ar", "العربية"),
        LanguageChoice("tr", "Türkçe"),
        LanguageChoice("hi", "हिन्दी"),
    ).filter { TranslateLanguage.fromLanguageTag(it.code) != null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translationPrefs = getSharedPreferences(LENS_TRANSLATION_PREFS_NAME, MODE_PRIVATE)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@LensSettingsActivity,
                    "Live camera translation on the glasses. Point, freeze, read.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LensSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Output language"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(outputLanguageCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Translation engine"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(engineCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "DeepL"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(deepLCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Gemini"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(geminiCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Offline language packs"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 8))
            addView(
                NexusUi.cardBody(
                    this@LensSettingsActivity,
                    "Packs power the On-device engine and the offline fallback. Each is about " +
                        "30 MB, downloads automatically on first use, and stays on this phone - " +
                        "or grab them here before a trip.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(offlineLanguagesCard(), NexusUi.block())
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

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@LensSettingsActivity,
                    BusClientR.drawable.ic_plugin_lens,
                    "Lens",
                    "Camera translation · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@LensSettingsActivity, content),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshDeepLUsage()
        refreshOfflinePacks()
    }

    /** Fetches DeepL /v2/usage off the main thread and swaps the key row hint for live quota. */
    private fun refreshDeepLUsage() {
        val label = deepLUsageLabel ?: return
        val apiKey = translationPrefs.getString(LENS_TRANSLATION_PREF_DEEPL_API_KEY, "").orEmpty().trim()
        if (apiKey.isEmpty()) return
        Thread {
            val usage = runCatching {
                val host = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
                val connection = URL("https://$host/v2/usage").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 4_000
                    connection.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    json.getLong("character_count") to json.getLong("character_limit")
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val (used, limit) = usage
                val formatter = NumberFormat.getIntegerInstance(Locale.FRANCE)
                label.text = "${formatter.format(used)} / ${formatter.format(limit)} chars used"
            }
        }.apply { isDaemon = true }.start()
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

    private fun deepLCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                keyRow(
                    label = "API key",
                    hintSource = "deepl.com/pro-api - free",
                    prefKey = LENS_TRANSLATION_PREF_DEEPL_API_KEY,
                    a11yName = "DeepL API key",
                ),
                NexusUi.block(),
            )
        }

    private fun geminiCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                keyRow(
                    label = "API key",
                    hintSource = "aistudio.google.com - free",
                    prefKey = LENS_TRANSLATION_PREF_GEMINI_API_KEY,
                    a11yName = "Gemini API key",
                ),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@LensSettingsActivity))
            addView(BusTheme.gap(this@LensSettingsActivity, 4))
            addView(NexusUi.metaLabel(this@LensSettingsActivity, "Model"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 2))
            modelChoices.forEach { choice ->
                addView(modelRow(choice), NexusUi.block())
            }
            renderModelSelection()
        }

    private fun modelRow(choice: ModelChoice): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@LensSettingsActivity, android.graphics.Color.TRANSPARENT, 10)
            setPadding(0, NexusUi.dp(this@LensSettingsActivity, 4), 0, NexusUi.dp(this@LensSettingsActivity, 4))
            setOnClickListener { selectModel(choice.model) }
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@LensSettingsActivity, choice.title).also {
                            modelNames[choice.model] = it
                        },
                        NexusUi.block(),
                    )
                    addView(NexusUi.rowSub(this@LensSettingsActivity, choice.caption), NexusUi.block())
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.dot(this@LensSettingsActivity).also { modelDots[choice.model] = it },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@LensSettingsActivity, 8),
                    NexusUi.dp(this@LensSettingsActivity, 8),
                ),
            )
        }

    private fun selectModel(model: String) {
        translationPrefs.edit().putString(LENS_TRANSLATION_PREF_GEMINI_MODEL, model).apply()
        renderModelSelection()
    }

    private fun renderModelSelection() {
        val selected = translationPrefs.getString(LENS_TRANSLATION_PREF_GEMINI_MODEL, null)
            ?.takeIf { it in LENS_GEMINI_MODELS }
            ?: LENS_GEMINI_MODEL_DEFAULT
        modelDots.forEach { (model, dotView) ->
            NexusUi.setDotColor(dotView, if (model == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        modelNames.forEach { (model, nameView) ->
            nameView.setTextColor(if (model == selected) NexusUi.INK else NexusUi.INK2)
        }
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

    private fun outputLanguageCard(): LinearLayout =
        NexusUi.pressableCard(this).apply {
            setOnClickListener { showOutputLanguagePicker() }
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowLabel(this@LensSettingsActivity, "Translate everything into"))
                    addView(BusTheme.gap(this@LensSettingsActivity, 4))
                    addView(
                        NexusUi.rowSub(
                            this@LensSettingsActivity,
                            selectedLanguageChoice().displayName,
                        ).also { outputLanguageValue = it },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.rowSub(this@LensSettingsActivity, "CHANGE ›").apply {
                    setTextColor(NexusUi.GREEN)
                },
            )
        }

    private fun selectedLanguageChoice(): LanguageChoice {
        val stored = translationPrefs.getString(
            LENS_TRANSLATION_PREF_TARGET_LANG,
            LENS_TRANSLATION_TARGET_LANG_DEFAULT,
        )
        return languageChoices.firstOrNull { it.code == stored }
            ?: languageChoices.first { it.code == LENS_TRANSLATION_TARGET_LANG_DEFAULT }
    }

    private fun showOutputLanguagePicker() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val selectedCode = selectedLanguageChoice().code
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            languageChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@LensSettingsActivity))
                addView(
                    languageChoiceRow(choice, choice.code == selectedCode) {
                        translationPrefs.edit()
                            .putString(LENS_TRANSLATION_PREF_TARGET_LANG, choice.code)
                            .apply()
                        outputLanguageValue.text = choice.displayName
                        dialog.dismiss()
                    },
                    NexusUi.block(),
                )
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@LensSettingsActivity, NexusUi.PANEL, NexusUi.LINE2, 16)
            setPadding(
                NexusUi.dp(this@LensSettingsActivity, 18),
                NexusUi.dp(this@LensSettingsActivity, 18),
                NexusUi.dp(this@LensSettingsActivity, 18),
                NexusUi.dp(this@LensSettingsActivity, 10),
            )
            addView(NexusUi.cardTitle(this@LensSettingsActivity, "Output language"))
            addView(BusTheme.gap(this@LensSettingsActivity, 8))
            addView(
                ScrollView(this@LensSettingsActivity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(options, NexusUi.block())
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                NexusUi.textButton(this@LensSettingsActivity, "Cancel").apply {
                    setOnClickListener { dialog.dismiss() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END },
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.84f).toInt(),
        )
    }

    private fun languageChoiceRow(
        choice: LanguageChoice,
        selected: Boolean,
        onSelect: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(this@LensSettingsActivity, android.graphics.Color.TRANSPARENT, 10)
            setPadding(0, NexusUi.dp(this@LensSettingsActivity, 7), 0, NexusUi.dp(this@LensSettingsActivity, 7))
            setOnClickListener { onSelect() }
            addView(
                NexusUi.rowLabel(this@LensSettingsActivity, choice.displayName).apply {
                    setTextColor(if (selected) NexusUi.INK else NexusUi.INK2)
                    textDirection = View.TEXT_DIRECTION_LTR
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.dot(this@LensSettingsActivity).apply {
                    NexusUi.setDotColor(this, if (selected) NexusUi.GREEN else NexusUi.INK4)
                },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@LensSettingsActivity, 8),
                    NexusUi.dp(this@LensSettingsActivity, 8),
                ),
            )
        }

    private fun offlineLanguagesCard(): LinearLayout =
        NexusUi.card(this).apply {
            languageChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@LensSettingsActivity))
                addView(offlinePackRow(choice), NexusUi.block())
            }
        }

    private fun offlinePackRow(choice: LanguageChoice): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, NexusUi.dp(this@LensSettingsActivity, 4), 0, NexusUi.dp(this@LensSettingsActivity, 4))
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@LensSettingsActivity, choice.displayName).apply {
                            // RTL names (Arabic) must not flip the whole row to the right.
                            textDirection = View.TEXT_DIRECTION_LTR
                        },
                        NexusUi.block(),
                    )
                    addView(
                        NexusUi.rowSub(this@LensSettingsActivity, "~30 MB").also {
                            offlinePackStatus[choice.code] = it
                        },
                        NexusUi.block(),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.textButton(this@LensSettingsActivity, "Get").apply {
                    contentDescription = "Download ${choice.nativeName} pack"
                    offlinePackActions[choice.code] = this
                    setOnClickListener {
                        when {
                            choice.code in downloadingPacks -> Unit
                            choice.code in downloadedPacks -> removePack(choice.code)
                            else -> downloadPack(choice.code)
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun renderOfflinePack(code: String) {
        val status = offlinePackStatus[code] ?: return
        val action = offlinePackActions[code] ?: return
        val name = languageChoices.firstOrNull { it.code == code }?.nativeName ?: code
        when (code) {
            in downloadingPacks -> {
                status.text = "Downloading…"
                status.setTextColor(NexusUi.INK3)
                action.visibility = View.INVISIBLE
            }
            in downloadedPacks -> {
                status.text = "Downloaded"
                status.setTextColor(NexusUi.GREEN_DIM)
                action.visibility = View.VISIBLE
                // Muted, not red: a full column of danger-colored buttons overwhelms the card.
                action.text = "Remove"
                action.setTextColor(NexusUi.INK3)
                action.contentDescription = "Remove $name pack"
            }
            in failedPacks -> {
                status.text = "Download failed"
                status.setTextColor(NexusUi.AMBER)
                action.visibility = View.VISIBLE
                action.text = "Retry"
                action.setTextColor(NexusUi.GREEN)
                action.contentDescription = "Retry $name pack download"
            }
            else -> {
                status.text = "~30 MB"
                status.setTextColor(NexusUi.INK3)
                action.visibility = View.VISIBLE
                action.text = "Get"
                action.setTextColor(NexusUi.GREEN)
                action.contentDescription = "Download $name pack"
            }
        }
    }

    /** Syncs pack rows with the models ML Kit actually has on disk. */
    private fun refreshOfflinePacks() {
        remoteModelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (isDestroyed || isFinishing) return@addOnSuccessListener
                downloadedPacks.clear()
                models.mapTo(downloadedPacks) { it.language }
                offlinePackStatus.keys.forEach(::renderOfflinePack)
            }
    }

    private fun downloadPack(code: String) {
        val language = TranslateLanguage.fromLanguageTag(code) ?: return
        if (!downloadingPacks.add(code)) return
        failedPacks.remove(code)
        renderOfflinePack(code)
        remoteModelManager.download(
            TranslateRemoteModel.Builder(language).build(),
            DownloadConditions.Builder().build(),
        )
            .addOnSuccessListener {
                downloadingPacks.remove(code)
                downloadedPacks.add(code)
                if (!isDestroyed && !isFinishing) renderOfflinePack(code)
            }
            .addOnFailureListener {
                downloadingPacks.remove(code)
                failedPacks.add(code)
                if (!isDestroyed && !isFinishing) renderOfflinePack(code)
            }
    }

    private fun removePack(code: String) {
        val language = TranslateLanguage.fromLanguageTag(code) ?: return
        remoteModelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build())
            .addOnCompleteListener {
                if (isDestroyed || isFinishing) return@addOnCompleteListener
                downloadedPacks.remove(code)
                renderOfflinePack(code)
                refreshOfflinePacks()
            }
    }

    private fun keyRow(
        label: String,
        hintSource: String,
        prefKey: String,
        a11yName: String = label,
    ): LinearLayout =
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
                    addView(
                        NexusUi.rowSub(this@LensSettingsActivity, hintSource).also { hint ->
                            if (prefKey == LENS_TRANSLATION_PREF_DEEPL_API_KEY) deepLUsageLabel = hint
                        },
                    )
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@LensSettingsActivity, 6))
            val field = keyField(prefKey)
            var revealed = false
            val revealButton = NexusUi.textButton(this@LensSettingsActivity, "Show").apply {
                contentDescription = "Show $a11yName"
                setOnClickListener {
                    revealed = !revealed
                    field.transformationMethod = if (revealed) {
                        null
                    } else {
                        PasswordTransformationMethod.getInstance()
                    }
                    field.setSelection(field.text.length)
                    text = if (revealed) "Hide" else "Show"
                    contentDescription = if (revealed) "Hide $a11yName" else "Show $a11yName"
                }
            }
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        field,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        revealButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@LensSettingsActivity, 6) },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun keyField(prefKey: String): EditText =
        EditText(this).apply {
            setText(translationPrefs.getString(prefKey, ""))
            hint = "Paste key - empty disables"
            setHintTextColor(NexusUi.INK4)
            setTextColor(NexusUi.INK)
            textSize = 12f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
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
