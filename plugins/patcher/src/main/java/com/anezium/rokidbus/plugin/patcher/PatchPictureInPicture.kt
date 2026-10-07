package com.anezium.rokidbus.plugin.patcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.text.TextUtils
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The floating window while a patch runs. It is read at a glance from the corner of
 * another app, so it holds four things only: a label, a clock that ticks, the live
 * line, and a thin bar that fills when the amount is known and sweeps when it is not.
 * At the end the bar closes in the outcome's colour for the moment before the window goes.
 */
internal class PatchPictureInPicture(private val activity: Activity) {
    internal class CompactViews(val root: View, val label: TextView, val dot: View, val clock: TextView, val line: TextView, val bar: PhosphorBar)
    internal var compact: CompactViews? = null
        private set
    private var pulse: ValueAnimator? = null

    private fun enabled(state: PatchJobState) = shouldEnter(state.status,
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) && !activity.isFinishing

    internal fun params(state: PatchJobState): PictureInPictureParams {
        val enter = enabled(state)
        val actions = if (enter) listOf(RemoteAction(
            Icon.createWithResource(activity, R.drawable.ic_patch_cancel), "Cancel", "Cancel patching",
            PendingIntent.getService(activity, 1, Intent(activity, PatchJobService::class.java)
                .setAction(PatchJobService.CANCEL).putExtra(PatchJobService.JOB_ID, state.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))) else emptyList()
        return params(enter, actions)
    }

    fun update(state: PatchJobState) {
        runCatching {
            activity.setPictureInPictureParams(params(state))
        }
        compact?.let { render(it, PatchPresentation.compact(state)) }
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
        val context = activity
        fun dp(value: Int) = NexusUi.dp(context, value)
        val label = NexusUi.metaLabel(context, "", NexusUi.GREEN_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val dot = NexusUi.dot(context)
        val clock = NexusUi.hero(context, 30f).apply { fontFeatureSettings = "tnum"; includeFontPadding = false; maxLines = 1 }
        val line = NexusUi.statusLine(context).apply { textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(NexusUi.INK) }
        val bar = PhosphorBar(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NexusUi.BG)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(8) })
            }, NexusUi.block())
            // The clock, line and bar sit on the baseline of the window whatever its size.
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(clock, NexusUi.block())
            addView(line, NexusUi.block().apply { topMargin = dp(2) })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(8) })
        }
        activity.setContentView(root)
        compact = CompactViews(root, label, dot, clock, line, bar)
        update(state)
    }
    fun expanded() {
        stopPulse(compact?.dot)
        compact = null
    }

    private fun render(views: CompactViews, compact: PatchPresentation.Compact) {
        val accent = when (compact.accent) {
            PatchPresentation.Accent.LIVE, PatchPresentation.Accent.OK -> NexusUi.GREEN
            PatchPresentation.Accent.WARN -> NexusUi.AMBER
            PatchPresentation.Accent.DANGER -> NexusUi.DANGER
        }
        views.label.text = compact.title.uppercase()
        views.clock.text = compact.clock
        views.line.text = compact.line
        views.line.setTextColor(if (compact.accent == PatchPresentation.Accent.LIVE) NexusUi.INK else accent)
        views.bar.tint(accent)
        views.bar.show(compact.fraction)
        NexusUi.setDotColor(views.dot, accent)
        if (compact.accent == PatchPresentation.Accent.LIVE) pulse(views.dot) else stopPulse(views.dot)
    }

    /** The dot breathes while the job is alive; with animations off it simply stays lit. */
    private fun pulse(dot: View) {
        if (pulse != null || !ValueAnimator.areAnimatorsEnabled()) return
        pulse = ValueAnimator.ofFloat(1f, .3f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }
    private fun stopPulse(dot: View?) {
        pulse?.cancel(); pulse = null
        dot?.alpha = 1f
    }

    companion object {
        fun shouldEnter(status: PatchJobStatus, supported: Boolean): Boolean =
            supported && status == PatchJobStatus.RUNNING
        fun params(enter: Boolean, actions: List<RemoteAction> = emptyList()): PictureInPictureParams = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9)).setActions(actions).apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(enter)
            }.build()
    }
}
