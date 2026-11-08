package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.PageTextSize
import java.util.concurrent.CopyOnWriteArrayList

/** The wearer's launcher page text size, kept beside the HUD position so an offline boot keeps it. */
internal object HudTextSize {
    private const val KEY_PAGE_TEXT_SIZE = "page_text_size"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(PageTextSize) -> Unit>()

    fun current(context: Context): PageTextSize = synchronized(lock) { read(preferences(context)) }

    fun set(context: Context, size: PageTextSize) {
        val changed = synchronized(lock) {
            val preferences = preferences(context)
            (read(preferences) != size).also { if (it) preferences.edit().putString(KEY_PAGE_TEXT_SIZE, size.wire).apply() }
        }
        if (changed) dispatch { listeners.forEach { listener -> runCatching { listener(size) } } }
    }

    fun observe(context: Context, listener: (PageTextSize) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            listeners.add(listener)
            read(preferences(context))
        }
        dispatch { if (listener in listeners) listener(initial) }
        return { listeners.remove(listener) }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(HudTopInset.PREFS, Context.MODE_PRIVATE)

    private fun read(preferences: SharedPreferences): PageTextSize =
        PageTextSize.parse(runCatching { preferences.getString(KEY_PAGE_TEXT_SIZE, null) }.getOrNull())

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}
