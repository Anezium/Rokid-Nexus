package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.LauncherFavourites
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import com.anezium.rokidbus.shared.PageTemplateContract

/** How a favourite opens: exactly as its Applications row does. */
internal sealed interface FavouriteOpen {
    /** A pages plugin's main page. */
    data class Page(val pageId: String) : FavouriteOpen

    /** An immersion plugin's full app. */
    data class Launch(val pluginId: String) : FavouriteOpen

    /** A native app or the camera, launched by its catalog key. */
    data class Local(val key: String, val label: String) : FavouriteOpen
}

/** A pinned application as the root draws it, resolved against the catalog of the moment. */
internal data class FavouriteStop(
    val key: String,
    val label: String,
    val icon: IconRef?,
    val open: FavouriteOpen,
)

internal object Favourites {
    /**
     * The owner's [keys] in their order, each resolved against [apps]. An entry the catalog no
     * longer lists, or cannot open yet, is left out rather than drawn dead.
     */
    fun resolve(keys: List<String>, apps: List<AppEntry>): List<FavouriteStop> =
        keys.distinct().take(LauncherFavouritesContract.MAX_ENTRIES).mapNotNull { key ->
            val entry = apps.firstOrNull { it.key == key } ?: return@mapNotNull null
            val open = when (entry.kind) {
                AppKind.PAGES -> entry.pluginId?.let { FavouriteOpen.Page(PageTemplateContract.mainPageId(it)) }
                AppKind.IMMERSION -> entry.pluginId?.let(FavouriteOpen::Launch)
                AppKind.NATIVE, AppKind.CAMERA -> FavouriteOpen.Local(entry.key, entry.label)
            } ?: return@mapNotNull null
            FavouriteStop(key, entry.label, entry.icon ?: IconRef.Place(IconPlace.GRID), open)
        }
}

/**
 * The favourites the phone last sent. The keys outlive restarts (the caller persists them); the
 * revision guards only one link, since a reinstalled phone starts counting again.
 */
internal class FavouritesState(initial: List<String> = emptyList()) {
    var keys: List<String> = initial
        private set
    private var lastRevision: Long? = null
    private var restored = false

    /** Whether the stored keys were read in this process; a list is compared only against them. */
    val needsRestore: Boolean get() = !restored

    /** True when [favourites] was taken: the caller stores it, even when it equals what was shown. */
    fun apply(favourites: LauncherFavourites): Boolean {
        val last = lastRevision
        if (last != null && favourites.revision <= last) return false
        lastRevision = favourites.revision
        restored = true
        keys = favourites.entries.map { it.key }
        return true
    }

    /** The keys kept from an earlier run, read once per process and never over a list already taken. */
    fun restore(stored: List<String>) {
        if (restored) return
        restored = true
        keys = stored
    }

    /** The link ended: the next phone may count from zero. The keys stay. */
    fun linkReset() {
        lastRevision = null
    }
}
