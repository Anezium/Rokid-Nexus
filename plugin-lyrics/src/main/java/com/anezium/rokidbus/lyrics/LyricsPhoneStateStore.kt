package com.anezium.rokidbus.lyrics

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.lyrics.contracts.DeviceStatus
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot

class LyricsPhoneStateStore {
    private val lock = Any()
    private val listeners = linkedSetOf<(LyricsPhoneViewState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var state = LyricsPhoneViewState()

    fun current(): LyricsPhoneViewState = state

    fun subscribe(listener: (LyricsPhoneViewState) -> Unit): () -> Unit {
        val snapshot = synchronized(lock) {
            listeners += listener
            state
        }
        dispatch(listener, snapshot)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun updateStatus(transform: (DeviceStatus) -> DeviceStatus) {
        update { current -> current.copy(deviceStatus = transform(current.deviceStatus)) }
    }

    fun updateProviders(transform: (ProviderSettingsViewState) -> ProviderSettingsViewState) {
        update { current -> current.copy(providers = transform(current.providers)) }
    }

    fun updateLyrics(snapshot: LyricsSnapshot) {
        val stamped = snapshot.copy(capturedAtEpochMs = System.currentTimeMillis())
        update { current -> current.copy(lyrics = stamped) }
    }

    private fun update(transform: (LyricsPhoneViewState) -> LyricsPhoneViewState) {
        var nextState: LyricsPhoneViewState? = null
        val listenersSnapshot = synchronized(lock) {
            val next = transform(state)
            if (state == next) return
            state = next
            nextState = next
            listeners.toList()
        }
        val dispatched = nextState ?: return
        listenersSnapshot.forEach { listener -> dispatch(listener, dispatched) }
    }

    private fun dispatch(
        listener: (LyricsPhoneViewState) -> Unit,
        state: LyricsPhoneViewState,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(state)
        } else {
            mainHandler.post { listener(state) }
        }
    }
}
