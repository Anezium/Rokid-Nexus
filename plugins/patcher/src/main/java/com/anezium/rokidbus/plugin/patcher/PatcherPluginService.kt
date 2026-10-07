package com.anezium.rokidbus.plugin.patcher
import com.anezium.rokidbus.client.plugin.NexusPluginService
/** Phone-only plugin: no surfaces, routes, background jobs or additional grants. */
class PatcherPluginService : NexusPluginService() {
    // LAUNCHABLE=false: the hub must not create a glasses session for this plugin.
    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit
    override fun onNexusInput(event: com.anezium.rokidbus.shared.plugin.NexusInputEvent) = Unit
}
