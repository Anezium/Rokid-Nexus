package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.ComposeTypeStart

/**
 * The phone keyboard for a glasses composer capture. Every change is mirrored to the glasses;
 * Keep draft hands the text to the glasses' draft and Cancel or Back leaves the draft as it was.
 * Nothing is sent to anyone from here: sending stays a deliberate step on the glasses.
 */
internal class ComposeKeyboardActivity : Activity(), ComposeKeyboardScreen {
    private lateinit var editor: EditText
    private lateinit var counter: TextView
    private var request: ComposeTypeStart? = null
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        val attached = ComposeKeyboardPrompt.attach(applicationContext, this)
        if (attached == null) {
            finish()
            return
        }
        bind(attached.first, attached.second)
    }

    override fun onStop() {
        ComposeKeyboardPrompt.detach(this)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        end(commit = false)
    }

    override fun onCaptureChanged(request: ComposeTypeStart, text: String) {
        runOnUiThread { bind(request, text) }
    }

    override fun onCaptureClosed() {
        runOnUiThread {
            request = null
            hideKeyboard()
            finish()
        }
    }

    private fun bind(next: ComposeTypeStart, text: String) {
        request = next
        binding = true
        editor.filters = arrayOf(InputFilter.LengthFilter(next.maxChars))
        editor.setText(text)
        editor.setSelection(editor.text.length)
        binding = false
        updateCounter()
        editor.requestFocus()
        editor.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun end(commit: Boolean) {
        val current = request
        request = null
        if (current != null) {
            ComposeKeyboardPrompt.onDone(applicationContext, current.captureId, commit, editor.text.toString())
        }
        hideKeyboard()
        finish()
    }

    private fun updateCounter() {
        val max = request?.maxChars ?: return
        counter.text = "${editor.text.length} / $max"
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        editor = EditText(this).apply {
            hint = "Type for the glasses"
            setHintTextColor(NexusUi.INK4)
            setTextColor(NexusUi.INK)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(
                NexusUi.dp(this@ComposeKeyboardActivity, 16),
                NexusUi.dp(this@ComposeKeyboardActivity, 14),
                NexusUi.dp(this@ComposeKeyboardActivity, 16),
                NexusUi.dp(this@ComposeKeyboardActivity, 14),
            )
            background = NexusUi.bordered(this@ComposeKeyboardActivity, NexusUi.PANEL, NexusUi.LINE2, 14)
            isSaveEnabled = false
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        updateCounter()
                        if (binding) return
                        val current = request ?: return
                        ComposeKeyboardPrompt.onText(current.captureId, s?.toString().orEmpty())
                    }
                },
            )
        }
        counter = NexusUi.metaLabel(this, "", NexusUi.INK3)

        // The glasses say "draft · not sent yet" over this text; the phone says the same, so
        // neither Keep draft nor leaving the screen reads as having sent anything.
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.metaLabel(this@ComposeKeyboardActivity, "Draft · not sent yet", NexusUi.AMBER),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(counter)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(
                NexusUi.textButton(this@ComposeKeyboardActivity, "Cancel").apply {
                    setOnClickListener { end(commit = false) }
                },
            )
            addView(
                android.view.View(this@ComposeKeyboardActivity),
                LinearLayout.LayoutParams(NexusUi.dp(this@ComposeKeyboardActivity, 8), 1),
            )
            addView(
                NexusUi.pillButton(this@ComposeKeyboardActivity, "Keep draft").apply {
                    NexusUi.stylePillAsPrimary(this@ComposeKeyboardActivity, this)
                    setOnClickListener { end(commit = true) }
                },
            )
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@ComposeKeyboardActivity,
                    "What you type shows on your glasses as you go. Keep draft leaves it there as the " +
                        "draft; Send is a separate step on the glasses. Cancel or Back keeps the draft " +
                        "as it was before.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@ComposeKeyboardActivity, 18))
            addView(editor, NexusUi.block())
            addView(BusTheme.gap(this@ComposeKeyboardActivity, 8))
            addView(status, NexusUi.block())
            addView(BusTheme.gap(this@ComposeKeyboardActivity, 14))
            addView(actions, NexusUi.block())
        }

        // Scrollable so the buttons stay reachable over the keyboard once the field has grown to
        // its eight lines on a short screen.
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    private fun titleHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(this@ComposeKeyboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    NexusUi.dp(this@ComposeKeyboardActivity, 10),
                    NexusUi.dp(this@ComposeKeyboardActivity, 12),
                    NexusUi.dp(this@ComposeKeyboardActivity, 22),
                    NexusUi.dp(this@ComposeKeyboardActivity, 12),
                )
                addView(backButton())
                addView(
                    NexusUi.metaLabel(this@ComposeKeyboardActivity, "PHONE KEYBOARD", NexusUi.INK).apply {
                        textSize = 12f
                        letterSpacing = 0.2f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(NexusUi.wordmark(this@ComposeKeyboardActivity, "NEXUS"))
            },
            NexusUi.block(),
        )
        addView(NexusUi.divider(this@ComposeKeyboardActivity))
    }

    private fun backButton(): TextView = TextView(this).apply {
        text = "‹"
        textSize = 26f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(NexusUi.INK)
        background = NexusUi.pressed(this@ComposeKeyboardActivity, Color.TRANSPARENT, 22)
        isClickable = true
        isFocusable = true
        contentDescription = "Cancel"
        setOnClickListener { end(commit = false) }
        layoutParams = LinearLayout.LayoutParams(
            NexusUi.dp(this@ComposeKeyboardActivity, 44),
            NexusUi.dp(this@ComposeKeyboardActivity, 44),
        )
    }
}
