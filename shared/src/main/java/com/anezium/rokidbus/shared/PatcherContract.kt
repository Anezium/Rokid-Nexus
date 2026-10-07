package com.anezium.rokidbus.shared

/**
 * Target-aware hand-off between a phone hub setup screen and the Patcher plugin.
 *
 * The hub starts the plugin's patch activity by explicit component with
 * `startActivityForResult`; the plugin answers `RESULT_OK` with a `content://` URI of the
 * patched APK from its own FileProvider plus `FLAG_GRANT_READ_URI_PERMISSION`. The hub then
 * feeds that URI to its existing import path, which copies, hashes and validates the bytes as
 * it does for a hand-picked file. The optional [EXTRA_RETURN_TO_HUB] lets the hub bring its
 * waiting setup screen forward after successful delivery from a separate task.
 * No bus route, capability, or AIDL change is involved, and
 * the hub trusts nothing from the plugin beyond the bytes it reads back.
 */
object PatcherContract {
    const val PACKAGE = "com.anezium.rokidbus.plugin.patcher"
    const val PLUGIN_ID = "patcher"
    const val PATCH_ACTIVITY = "com.anezium.rokidbus.plugin.patcher.PatchActivity"

    /** Action the hub sets on its explicit intent; the activity has no intent filter. */
    const val ACTION_PATCH = "com.anezium.rokidbus.plugin.patcher.action.PATCH"

    /** Informational result extras; the hub re-reads all of them from the APK itself. */
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_VERSION_NAME = "versionName"
    const val EXTRA_SHA256 = "sha256"

    /** Required on requests and results; unknown target ids must fail closed. */
    const val EXTRA_TARGET_ID = "targetId"

    /**
     * Optional request-only `android.app.PendingIntent`, created by the phone hub for its
     * own non-exported setup activity with `FLAG_IMMUTABLE | FLAG_ONE_SHOT` and activity flags
     * `NEW_TASK | REORDER_TO_FRONT`. Patcher checks `creatorPackage` against the authenticated hub caller
     * and sends it only after returning a successful result from a separate task. It never
     * sends it for standalone opens or visible failed/cancelled outcomes.
     * Recreation retains the unused token; its first successful send consumes it.
     * The sender opts in to activity launch privileges on Android 14+, including the
     * just-finished activity's grace period on Android 16. Only an absent extra permits
     * the older-hub launcher fallback.
     */
    const val EXTRA_RETURN_TO_HUB = "returnToHub"
    const val TARGET_YOUTUBE = "youtube"

    fun isTargetId(id: String): Boolean = id.matches(Regex("[a-z][a-z0-9._-]{0,63}"))
}
