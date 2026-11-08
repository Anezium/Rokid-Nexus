package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

internal fun showAssistantModelPicker(
    activity: Activity,
    models: List<AvailableModel>,
    selectedId: String,
    onSelect: (AvailableModel) -> Unit,
) {
    val dialog = Dialog(activity)
    val search = NexusUi.field(activity, "Search models")
    val rows = models.toMutableList()
    val adapter = object : ArrayAdapter<AvailableModel>(activity, android.R.layout.simple_list_item_1, rows) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                val model = getItem(position)!!
                text = (if (model.id == selectedId) "✓ " else "") + model.title +
                    if (model.title == model.id) "" else "\n${model.id}"
                textSize = 15f
                setTextColor(NexusUi.INK)
                setPadding(NexusUi.dp(activity, 14), NexusUi.dp(activity, 12), NexusUi.dp(activity, 14), NexusUi.dp(activity, 12))
            }
    }
    val list = ListView(activity).apply {
        this.adapter = adapter
        setOnItemClickListener { _, _, position, _ ->
            val model = adapter.getItem(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            onSelect(model)
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val query = s?.toString().orEmpty().trim()
            adapter.clear()
            adapter.addAll(models.filter { it.id.contains(query, true) || it.title.contains(query, true) })
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    val content = NexusUi.contentColumn(activity).apply {
        addView(NexusUi.rowTitle(activity, "Choose model"), NexusUi.block())
        addView(BusTheme.gap(activity, 12))
        addView(search, NexusUi.block())
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(NexusUi.textButton(activity, "Close").apply { setOnClickListener { dialog.dismiss() } })
    }
    dialog.setContentView(content)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    content.setBackgroundColor(NexusUi.BG)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, activity.resources.displayMetrics.heightPixels * 4 / 5)
    dialog.show()
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, activity.resources.displayMetrics.heightPixels * 4 / 5)
}
