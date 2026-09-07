package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

internal fun showAssistantQuestionDialog(activity: Activity): Dialog? {
    val start = AssistantPluginService.beginPhoneQuestion()
    val entry = start.entry
    if (entry == null) {
        Toast.makeText(activity, start.status.message, Toast.LENGTH_SHORT).show()
        return null
    }

    val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val field = NexusUi.field(activity, "Ask Assistant…").apply {
        isSaveEnabled = false
        setSingleLine(false)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        maxLines = 6
        filters = arrayOf(InputFilter.LengthFilter(AssistantTextInput.MAX_TEXT_LENGTH))
    }
    val status = NexusUi.rowSub(activity, "0 / 512 characters · Answer on glasses")
    val send = NexusUi.textButton(activity, "Send question").apply { isEnabled = false }
    var terminal = false
    fun submit() {
        if (terminal) return
        when (val result = AssistantPluginService.submitPhoneQuestion(entry.id, field.text.toString())) {
            AssistantTextInputStatus.SENT -> {
                terminal = true
                dialog.dismiss()
                Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
            }
            AssistantTextInputStatus.EMPTY, AssistantTextInputStatus.TOO_LONG -> {
                status.text = result.message
            }
            else -> {
                terminal = true
                send.isEnabled = false
                status.text = result.message
            }
        }
    }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
            if (terminal) return
            send.isEnabled = !text.isNullOrBlank()
            status.text = "${text?.length ?: 0} / 512 characters · Answer on glasses"
        }
        override fun afterTextChanged(text: Editable?) = Unit
    })
    field.setOnEditorActionListener { _, action, event ->
        val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isShiftPressed
        if (action == EditorInfo.IME_ACTION_SEND || enter) {
            submit()
            true
        } else {
            false
        }
    }
    send.setOnClickListener { submit() }
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(activity, NexusUi.PANEL, NexusUi.LINE2, 16)
        val padding = NexusUi.dp(activity, 18)
        setPadding(padding, padding, padding, padding)
        addView(NexusUi.cardTitle(activity, "Write a question"))
        addView(BusTheme.gap(activity, 8))
        addView(
            NexusUi.cardBody(activity, "Uses your selected provider and conversation. No microphone needed."),
            NexusUi.block(),
        )
        addView(BusTheme.gap(activity, 12))
        addView(field, NexusUi.block())
        addView(BusTheme.gap(activity, 8))
        addView(status, NexusUi.block())
        addView(BusTheme.gap(activity, 10))
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.END
                addView(NexusUi.textButton(activity, "Cancel").apply {
                    setOnClickListener { dialog.dismiss() }
                })
                addView(send)
            },
            NexusUi.block(),
        )
    }
    dialog.setContentView(panel)
    dialog.setOnDismissListener {
        AssistantPluginService.cancelPhoneQuestion(entry.id)
        field.text?.clear()
    }
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
    )
    dialog.show()
    dialog.window?.setLayout(
        (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    field.requestFocus()
    return dialog
}
