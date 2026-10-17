package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.RedditSetupContract
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.json.JSONObject

/** Fixed phone-owned setup targets share import, validation, transfer and confirmation. */
internal enum class NativeSetupTarget(val id: String, val label: String) {
    YOUTUBE("youtube", "YouTube"), REDDIT("reddit", "Reddit");

    fun request(id: String) = if (this == REDDIT) RedditSetupContract.request(id) else YoutubeSetupContract.request(id)
    fun parseResult(json: JSONObject) = if (this == REDDIT) RedditSetupContract.parseResult(json) else YoutubeSetupContract.parseResult(json)
    fun publish(state: YoutubeSetupState) {
        if (this == REDDIT) RedditSetupStateStore.update(state) else YoutubeSetupStateStore.update(state)
    }
    fun attach(handler: (android.content.Intent) -> Unit) {
        if (this == REDDIT) RedditSetupCommands.attach(handler) else YoutubeSetupCommands.attach(handler)
    }
    fun detach() {
        if (this == REDDIT) RedditSetupCommands.detach() else YoutubeSetupCommands.detach()
    }
}
