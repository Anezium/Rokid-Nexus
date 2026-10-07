package com.anezium.rokidbus.phone

import android.content.Intent
import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubePackage

internal data class YoutubeSetupState(
    val busy: Boolean = false,
    val message: String = "Connect your glasses, then refresh their installed apps.",
    val inventory: YoutubeInventory? = null,
    val preparedLabel: String? = null,
    val canInstall: Boolean = false,
    val youtubeApkReady: Boolean = false,
    val latestMicroGVersionCode: Long? = null,
)

internal enum class MicroGSetupAction(val label: String) {
    INSTALL("Install MicroG"), OPEN("Open MicroG"), UPDATE("Update MicroG");

    companion object {
        fun choose(installed: YoutubePackage?, latestVersionCode: Long?): MicroGSetupAction = when {
            installed?.installed != true -> INSTALL
            !installed.launchable || latestVersionCode != null && latestVersionCode > installed.versionCode -> UPDATE
            else -> OPEN
        }
    }
}

/** Main-thread state shared by the service and its private setup screen, never persisted. */
internal object YoutubeSetupStateStore {
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

/** In-process UI edge: the exported plugin bus service must not accept installer commands. */
internal object YoutubeSetupCommands {
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
