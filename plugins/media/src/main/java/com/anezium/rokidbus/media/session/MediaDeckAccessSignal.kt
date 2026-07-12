package com.anezium.rokidbus.media.session

import java.util.concurrent.CopyOnWriteArraySet

internal object MediaDeckAccessSignal {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun notifyChanged() {
        listeners.forEach { listener -> runCatching(listener) }
    }
}
