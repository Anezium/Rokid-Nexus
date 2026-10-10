package com.anezium.rokidbus.phone

import android.content.Intent

/** A separate main-thread channel prevents a Reddit setup from adopting a YouTube result. */
internal object RedditSetupStateStore {
    var state = YoutubeSetupState()
        private set
    private val observers = linkedSetOf<(YoutubeSetupState) -> Unit>()
    fun update(value: YoutubeSetupState) {
        state = value
        observers.toList().forEach { it(value) }
    }
    fun observe(callback: (YoutubeSetupState) -> Unit): () -> Unit {
        observers += callback
        callback(state)
        return { observers -= callback }
    }
}

internal object RedditSetupCommands {
    private var handler: ((Intent) -> Unit)? = null
    private var pending: Intent? = null
    fun submit(intent: Intent) {
        val current = handler
        if (current == null) pending = intent else current(intent)
    }
    fun attach(callback: (Intent) -> Unit) {
        handler = callback
        val queued = pending
        pending = null
        queued?.let(callback)
    }
    fun detach() { handler = null; pending = null }
}
