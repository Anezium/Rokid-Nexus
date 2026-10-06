package com.anezium.rokidbus.shared

/**
 * Hand-off between the phone hub's YouTube setup and the YouTube Patcher plugin.
 *
 * The hub starts the plugin's patch activity by explicit component with
 * `startActivityForResult`; the plugin answers `RESULT_OK` with a `content://` URI of the
 * patched APK from its own FileProvider plus `FLAG_GRANT_READ_URI_PERMISSION`. The hub then
 * feeds that URI to its existing import path, which copies, hashes and validates the bytes as
 * it does for a hand-picked file. No bus route, capability, or AIDL change is involved, and
 * the hub trusts nothing from the plugin beyond the bytes it reads back.
 */
object YoutubePatcherContract {
    const val PACKAGE = "com.anezium.rokidbus.plugin.youtubepatcher"
    const val PLUGIN_ID = "youtube_patcher"
    const val PATCH_ACTIVITY = "com.anezium.rokidbus.plugin.youtubepatcher.PatchActivity"

    /** Action the hub sets on its explicit intent; the activity has no intent filter. */
    const val ACTION_PATCH = "com.anezium.rokidbus.plugin.youtubepatcher.action.PATCH"

    /** Informational result extras; the hub re-reads all of them from the APK itself. */
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_VERSION_NAME = "versionName"
    const val EXTRA_SHA256 = "sha256"

    const val STOCK_PACKAGE = "com.google.android.youtube"

    /** SHA-256 of Google's YouTube signing certificate, as APKMirror lists it. */
    const val STOCK_SIGNER_SHA256 = "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"

    const val PATCHES_REPO = "Anezium/morphe-patches"
    const val BUNDLE_METADATA_URL =
        "https://raw.githubusercontent.com/$PATCHES_REPO/rokid/patches-bundle.json"
    const val BUNDLE_DOWNLOAD_PREFIX =
        "https://github.com/$PATCHES_REPO/releases/download/"
}
