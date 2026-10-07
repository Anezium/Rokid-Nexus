package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

internal class PatchPictureInPicture(private val activity: Activity) {
    private var compact: TextView? = null
    private fun enabled(state: PatchJobState) = shouldEnter(state.status,
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) && !activity.isFinishing

    internal fun params(state: PatchJobState): PictureInPictureParams {
        val enter = enabled(state)
        val actions = if (enter) listOf(RemoteAction(
            Icon.createWithResource(activity, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", "Cancel patching",
            PendingIntent.getService(activity, 1, Intent(activity, PatchJobService::class.java)
                .setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, state.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))) else emptyList()
        return params(enter, actions)
    }

    fun update(state: PatchJobState) {
        runCatching {
            activity.setPictureInPictureParams(params(state))
        }
        compact?.text = "Patcher\n${PatchPresentation.phaseLine(state.progress)}\n${PatchPresentation.elapsed(state.elapsedMs)}"
        if (activity.isInPictureInPictureMode && state.status != PatchJobStatus.RUNNING) {
            // Keep the caller/result relationship alive while removing the finished floating task.
            activity.moveTaskToBack(true)
        }
    }
    fun leave(state: PatchJobState) {
        if (enabled(state) && !activity.isInPictureInPictureMode) runCatching {
            activity.enterPictureInPictureMode(params(state))
        }
    }
    fun show(state: PatchJobState) {
        compact = TextView(activity).apply {
            gravity = Gravity.CENTER; textSize = 14f
            setTextColor(NexusUi.INK); setBackgroundColor(NexusUi.BG)
            setPadding(12, 8, 12, 8)
        }
        activity.setContentView(compact)
        update(state)
    }
    fun expanded() { compact = null }

    companion object {
        fun shouldEnter(status: PatchJobStatus, supported: Boolean): Boolean =
            supported && status == PatchJobStatus.RUNNING
        fun params(enter: Boolean, actions: List<RemoteAction> = emptyList()): PictureInPictureParams = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9)).setActions(actions).apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(enter)
            }.build()
    }
}
