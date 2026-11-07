package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.LauncherFavourite
import com.anezium.rokidbus.shared.LauncherFavourites
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The applications the owner pinned to the glasses launcher root, in their order.
 *
 * The phone owns this list: the glasses only ever receive it whole. At most
 * [LauncherFavouritesContract.MAX_ENTRIES] entries, never the same application twice, and a
 * revision that only grows so the glasses can tell a newer list from a replay. An entry whose
 * application is gone stays pinned: the glasses skip what their catalog no longer lists, and the
 * phone screen shows it as unavailable with a way to remove it, so nothing disappears silently.
 */
class PhoneLauncherFavouritesStore(private val storage: PluginGrantStorage) {
    constructor(context: Context) : this(
        PreferenceStringStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            KEY_FAVOURITES,
        ),
    )

    private var cache: LauncherFavourites? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun favourites(): LauncherFavourites = cache ?: load().also { cache = it }

    @Synchronized
    fun entries(): List<LauncherFavourite> = favourites().entries

    @Synchronized
    fun isPinned(entry: LauncherFavourite): Boolean = entries().any { it.key == entry.key }

    @Synchronized
    fun isFull(): Boolean = entries().size >= LauncherFavouritesContract.MAX_ENTRIES

    /** Appends [entry]; refused when it is already pinned or the list is full. True when it changed. */
    @Synchronized
    fun pin(entry: LauncherFavourite): Boolean {
        val current = entries()
        if (current.any { it.key == entry.key } || current.size >= LauncherFavouritesContract.MAX_ENTRIES) return false
        write(current + entry)
        return true
    }

    @Synchronized
    fun unpin(entry: LauncherFavourite): Boolean {
        val current = entries()
        val next = current.filterNot { it.key == entry.key }
        if (next.size == current.size) return false
        write(next)
        return true
    }

    /**
     * Moves [entry] by [delta] positions, clamped to the list. True when the order changed; an
     * entry that is not pinned, or already at the end it is pushed towards, changes nothing.
     */
    @Synchronized
    fun move(entry: LauncherFavourite, delta: Int): Boolean {
        val current = entries()
        val from = current.indexOfFirst { it.key == entry.key }
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (to == from) return false
        val next = current.toMutableList()
        next.add(to, next.removeAt(from))
        write(next)
        return true
    }

    /** Called after every change, on the thread that made it. */
    fun addChangeListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun load(): LauncherFavourites {
        val stored = storage.read()
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let(LauncherFavouritesContract::parse)
        // Seeded by the clock so a phone whose data was cleared does not start below the revision
        // the glasses last accepted from it; every later write counts up from there.
        return stored ?: LauncherFavourites(System.currentTimeMillis(), emptyList())
    }

    private fun write(entries: List<LauncherFavourite>) {
        val next = LauncherFavourites(
            revision = maxOf(favourites().revision + 1, System.currentTimeMillis()),
            entries = entries,
        )
        storage.write(LauncherFavouritesContract.toPayload(next).toString())
        cache = next
        listeners.forEach { it() }
    }

    companion object {
        @Volatile private var instance: PhoneLauncherFavouritesStore? = null

        /** The process's one instance, so the hub and the settings screen never read stale caches. */
        fun shared(context: Context): PhoneLauncherFavouritesStore =
            instance ?: synchronized(this) { instance ?: PhoneLauncherFavouritesStore(context).also { instance = it } }

        private const val PREFERENCES_NAME = "nexus_launcher_favourites"
        private const val KEY_FAVOURITES = "favourites_v1"
    }
}
