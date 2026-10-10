package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

internal class WorkspaceFolderHelp(private val activity: Activity) {
    fun show(onChooseFolder: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(activity, NexusUi.PANEL, NexusUi.LINE2, 16)
            setPadding(NexusUi.dp(activity, 18), NexusUi.dp(activity, 18),
                NexusUi.dp(activity, 18), NexusUi.dp(activity, 14))
            addView(NexusUi.cardTitle(activity, "Workspace folder help"))
            addView(BusTheme.gap(activity, 12))
            addView(ScrollView(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.cardBody(activity,
                        "1. In the Android folder picker, open Download or Documents.\n\n" +
                            "2. Create or open a subfolder, such as NexusWorkspace.\n\n" +
                            "3. While inside that folder, tap Use this folder, then Allow. " +
                            "If Workspace is off, turn it on to index your documents."), NexusUi.block())
                    addView(BusTheme.gap(activity, 18))
                    addView(NexusUi.cardTitle(activity, "If the folder is blocked"))
                    addView(BusTheme.gap(activity, 6))
                    addView(NexusUi.cardBody(activity,
                        "Android does not allow selecting the storage root, Download itself, " +
                            "or Android/data and Android/obb. Choose a subfolder outside those Android directories.\n\n" +
                            "If even a new subfolder is blocked or the list is unexpectedly empty, " +
                            "close the picker and try again. Restarting your phone or installing available " +
                            "Android system updates may help. If the problem continues, try another local folder.\n\n" +
                            "If a folder was moved, deleted, or its access was removed, choose an available " +
                            "folder again. Assistant will ask Android for read access. For a temporary check " +
                            "failure, return to Workspace and tap Re-index now."), NexusUi.block())
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(BusTheme.gap(activity, 12))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.END
                addView(NexusUi.textButton(activity, "Close").apply {
                    setOnClickListener { dialog.dismiss() }
                })
                addView(NexusUi.textButton(activity, "Choose folder").apply {
                    setOnClickListener {
                        dialog.dismiss()
                        onChooseFolder()
                    }
                })
            }, NexusUi.block())
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.8f).toInt())
    }

    companion object {
        const val SETUP_HINT = "Create or choose a local subfolder, such as Download/NexusWorkspace. " +
            "Open it, tap Use this folder, then Allow. Download itself cannot be selected."

        fun selectionMessage(result: WorkspaceFolderResult): String? = when (result) {
            WorkspaceFolderResult.SELECTED -> null
            WorkspaceFolderResult.LOCAL_FOLDER_REQUIRED -> "Choose a folder stored on the phone or SD card. See Folder help."
            WorkspaceFolderResult.NO_READ_GRANT -> "Folder read access was not granted. Choose the folder again and tap Allow."
            WorkspaceFolderResult.UNAVAILABLE -> "Folder unavailable. It may have been moved or deleted. Choose an available folder."
            WorkspaceFolderResult.CHECK_FAILED -> "Folder could not be checked. Try again or see Folder help."
            WorkspaceFolderResult.STORE_FAILED -> "Could not save the folder choice. Try again."
        }
    }
}
