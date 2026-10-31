package com.anezium.rokidbus.glasses.input

import com.anezium.rokidbus.shared.LauncherInteractiveContract

/**
 * Which launcher answers the global gesture (plan 028a §8.3): the phone's explicit choice, then
 * the glasses' own stored choice, then the default for the launcher bundle the phone last
 * announced. Every input is persisted and none follows the live link, so a phone or data-plane
 * loss, or a boot before the phone connects, keeps the launcher the wearer is in. Only a fresh
 * phone capabilities announcement moves the phone's choice or the announced bundle; nothing
 * before a successful announcement opts the glasses into the bundle's default.
 */
internal class LauncherBackendSelection(
    private val store: Store,
    private val switchTo: (LauncherBackend) -> Unit,
    private val bundleDefault: LauncherBackend = LauncherBackend.BUNDLE_DEFAULT,
) {
    /** What persists between announcements and boots; no peer or device identity is kept. */
    interface Store {
        fun phoneBackend(): String?
        fun setPhoneBackend(value: String?)
        fun glassesBackend(): String?
        fun setGlassesBackend(value: String)

        /** The bundle version the last phone announcement negotiated with this hub; null before any. */
        fun announcedBundleVersion(): Int?
        fun setAnnouncedBundleVersion(version: Int)
    }

    fun current(): LauncherBackend =
        LauncherBackend.resolve(store.phoneBackend(), store.glassesBackend(), store.announcedBundleVersion(), bundleDefault)

    /**
     * A phone capabilities announcement: [phoneBackend] is its explicit choice (null for no
     * opinion) and [negotiatedBundleVersion] the bundle version both hubs share by it. An older
     * phone negotiates 0, which brings the default back to LEGACY.
     */
    fun onPhoneAnnounced(phoneBackend: String?, negotiatedBundleVersion: Int) {
        store.setPhoneBackend(LauncherBackend.fromPhone(phoneBackend)?.name)
        store.setAnnouncedBundleVersion(negotiatedBundleVersion.coerceAtLeast(0))
        switchTo(current())
    }

    /** The glasses' own explicit choice; returns the backend that applies, which the phone's may outrank. */
    fun onGlassesChoice(next: LauncherBackend): LauncherBackend {
        store.setGlassesBackend(next.name)
        return current().also(switchTo)
    }

    companion object {
        fun bundleActive(version: Int?): Boolean = version != null && LauncherInteractiveContract.isActive(version)
    }
}
