package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import android.app.Activity
import android.app.Dialog
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
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
import com.anezium.rokidbus.plugin.lens.LENS_GEMINI_MODEL_DEFAULT
import com.anezium.rokidbus.plugin.lens.LENS_GEMINI_MODELS
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREFS_NAME
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREF_DEEPL_API_KEY
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREF_ENGINE
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREF_GEMINI_API_KEY
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREF_GEMINI_MODEL
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_PREF_TARGET_LANG
import com.anezium.rokidbus.plugin.lens.LENS_TRANSLATION_TARGET_LANG_DEFAULT
import com.anezium.rokidbus.plugin.lens.TranslationEngine
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
    private var permissionStatus: TextView? = null

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
        LanguageChoice("fr", "Fran\u00e7ais"),
        LanguageChoice("es", "Espa\u00f1ol"),
        LanguageChoice("de", "Deutsch"),
        LanguageChoice("it", "Italiano"),
        LanguageChoice("pt", "Portugu\u00eas"),
        LanguageChoice("nl", "Nederlands"),
        LanguageChoice("pl", "Polski"),
        LanguageChoice("ru", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439"),
        LanguageChoice("el", "\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac"),
        LanguageChoice("ja", "\u65e5\u672c\u8a9e"),
        LanguageChoice("ko", "\ud55c\uad6d\uc5b4"),
        LanguageChoice("zh", "\u4e2d\u6587"),
        LanguageChoice("ar", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629"),
        LanguageChoice("tr", "T\u00fcrk\u00e7e"),
        LanguageChoice("hi", "\u0939\u093f\u0928\u094d\u0926\u0940"),
    ).filter { TranslateLanguage.fromLanguageTag(it.code) != null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translationPrefs = lensPreferences(this)
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
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Camera link permission"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(permissionCard(), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 22))
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
            addView(NexusUi.sectionRow(this@LensSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@LensSettingsActivity, 12))
            addView(uninstallCard(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@LensSettingsActivity,
                    NexusPluginIcons.drawableFor("lens"),
                    "Lens",
                    "Camera translation \u00b7 v${pluginVersionName()}",
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
        renderPermission()
    }

    private fun permissionCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                LinearLayout(this@LensSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(this@LensSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(NexusUi.rowLabel(this@LensSettingsActivity, "Nearby camera link"), NexusUi.block())
                            addView(
                                NexusUi.rowSub(this@LensSettingsActivity, "").also { permissionStatus = it },
                                NexusUi.block(),
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@LensSettingsActivity, "Grant").apply {
                            setOnClickListener { requestCameraLinkPermission() }
                        },
                    )
                },
                NexusUi.block(),
            )
            renderPermission()
        }

    private fun renderPermission() {
        permissionStatus?.apply {
            val granted = hasCameraLinkPermission()
            text = if (granted) "Granted" else if (Build.VERSION.SDK_INT >= 33) {
                "Nearby devices required"
            } else {
                "Location permission required by Wi-Fi Direct"
            }
            setTextColor(if (granted) NexusUi.GREEN_DIM else NexusUi.AMBER)
        }
    }

    private fun hasCameraLinkPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraLinkPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        requestPermissions(arrayOf(permission), REQUEST_CAMERA_LINK_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_LINK_PERMISSION) renderPermission()
    }

    private fun uninstallCard(): LinearLayout =
        NexusUi.uninstallCard(this, "Lens") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
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
                NexusUi.rowSub(this@LensSettingsActivity, "CHANGE \u203a").apply {
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
                status.text = "Downloading\u2026"
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
                        LinearLayout.LayoutParams(0, NexusUi.dp(this@LensSettingsActivity, 52), 1f),
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

    private fun pluginVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "1.0.1" }

    private fun keyField(prefKey: String): EditText =
        NexusUi.field(this, "Paste your key").apply {
            setText(translationPrefs.getString(prefKey, ""))
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            // Sink the field below the card so it reads as an input, not a flat row.
            background = NexusUi.bordered(this@LensSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    translationPrefs.edit().putString(prefKey, s?.toString()?.trim().orEmpty()).apply()
                }
            })
        }

    private companion object {
        const val REQUEST_CAMERA_LINK_PERMISSION = 41
    }
}
