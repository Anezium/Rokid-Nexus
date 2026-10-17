package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PatcherContract as Contract

/** App-target presentation and the one hub hand-off Patcher starts itself. */
internal object AppTargets {
    fun mark(context: Context, target: PatchTarget, sizeDp: Int): View =
        if (target.iconTinted) NexusUi.iconTileImage(context, target.icon, sizeDp)
        else NexusUi.iconTileDrawable(context, requireNotNull(context.getDrawable(target.icon)), sizeDp)

    /** The hub's wording hint for [target]; another target's job is invisible to it. */
    fun jobHint(state: PatchJobState, target: PatchTarget, result: Boolean): String = when {
        state.targetId != target.id -> Contract.JOB_IDLE
        state.active -> Contract.JOB_RUNNING
        state.status == PatchJobStatus.SUCCESS && result -> Contract.JOB_READY
        state.stock != null -> Contract.JOB_SOURCE_READY
        else -> Contract.JOB_IDLE
    }

    fun hubSetup(target: PatchTarget, hint: String): Intent = Intent(Contract.ACTION_OPEN_SETUP)
        .setComponent(ComponentName(Contract.HUB_PACKAGE, Contract.HUB_SETUP_ACTIVITY))
        .putExtra(Contract.EXTRA_TARGET_ID, target.id)
        .putExtra(Contract.EXTRA_JOB_STATE, hint)

    /** Only a hub whose entry declares [target] opens it; an older hub would silently refuse. */
    fun hasHubSetup(context: Context, target: PatchTarget): Boolean {
        if (target.id !in Contract.SETUP_TARGETS) return false
        val entry = runCatching {
            context.packageManager.getActivityInfo(ComponentName(Contract.HUB_PACKAGE, Contract.HUB_SETUP_ACTIVITY), PackageManager.GET_META_DATA)
        }.getOrNull()?.takeIf { it.exported } ?: return false
        return target.id in Contract.hubSetupTargets(entry.metaData?.getString(Contract.META_SETUP_TARGETS))
    }

    /**
     * For a result so Android stamps Patcher as the caller the hub authenticates. The hub
     * answers at once; the setup screen it opens is its own and sits above the caller.
     */
    fun openHubSetup(activity: Activity, target: PatchTarget, hint: String, requestCode: Int): Boolean =
        runCatching { activity.startActivityForResult(hubSetup(target, hint), requestCode) }.isSuccess
}
