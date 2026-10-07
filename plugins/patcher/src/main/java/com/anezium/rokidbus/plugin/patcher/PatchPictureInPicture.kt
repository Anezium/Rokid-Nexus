package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

/** Revert this file and its activity hooks to remove the scheduling experiment. */
internal class PatchPictureInPicture(private val activity: Activity) {
    private var compact: TextView? = null
    private fun enabled(state: PatchJobState) = shouldEnter(state.status,
        activity.resources.getBoolean(R.bool.patcher_pip_experiment),
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) && !activity.isFinishing

    fun update(state: PatchJobState) {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            activity.setPictureInPictureParams(params(enabled(state)))
        }
        compact?.text = "Patcher\n${PatchPresentation.phaseLine(state.progress)}\n${PatchPresentation.elapsed(state.elapsedMs)}"
        if (activity.isInPictureInPictureMode && state.status != PatchJobStatus.RUNNING) {
            // Keep the caller/result relationship alive while removing the finished floating task.
            activity.moveTaskToBack(true)
        }
    }
    fun leave(state: PatchJobState) {
        if (enabled(state) && !activity.isInPictureInPictureMode) runCatching {
            activity.enterPictureInPictureMode(params(true))
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
        fun shouldEnter(status: PatchJobStatus, enabled: Boolean, supported: Boolean): Boolean =
            enabled && supported && status == PatchJobStatus.RUNNING
        fun params(enter: Boolean): PictureInPictureParams = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9)).apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(enter)
            }.build()
    }
}
