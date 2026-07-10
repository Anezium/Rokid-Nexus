package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.plugin.NexusInputEvent

interface NexusPluginCallbacks {
    fun onOpen()
    fun onClose()
    fun onInput(event: NexusInputEvent)
    fun onLinkState(state: Int)
    fun onRegistrationState(result: Int)
}
