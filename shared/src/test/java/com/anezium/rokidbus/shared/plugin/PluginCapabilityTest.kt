package com.anezium.rokidbus.shared.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCapabilityTest {
    @Test
    fun `capabilities parse and serialize canonically`() {
        val result = PluginCapability.parseList("http_proxy; surfaces microphone camera")
        assertTrue(result is CapabilityParseResult.Valid)
        val capabilities = (result as CapabilityParseResult.Valid).capabilities
        assertEquals("surfaces,microphone,http_proxy,camera", PluginCapability.serialize(capabilities))
    }

    @Test
    fun `unknown capability is rejected`() {
        assertEquals(
            CapabilityParseResult.Invalid("UNKNOWN_CAPABILITY"),
            PluginCapability.parseList("surfaces,root"),
        )
    }

    @Test
    fun `every privileged route has its capability`() {
        assertEquals(PluginCapability.SURFACES, PathRules.requiredCapability("/surface/show"))
        assertEquals(PluginCapability.SURFACES, PathRules.requiredCapability("/surface/update"))
        assertEquals(PluginCapability.SURFACES, PathRules.requiredCapability("/surface/hide"))
        assertEquals(PluginCapability.MICROPHONE, PathRules.requiredCapability("/audio/lease/acquire"))
        assertEquals(PluginCapability.MICROPHONE, PathRules.requiredCapability("/audio/lease/release"))
        assertEquals(PluginCapability.HTTP_PROXY, PathRules.requiredCapability("/http/request"))
        assertEquals(PluginCapability.CAMERA, PathRules.requiredCapability("/camera/freeze/result"))
        assertEquals(PluginCapability.CAMERA, PathRules.requiredCapability("/camera/overlay"))
    }
}
