package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.lens.LensTranslationPlugin
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBuiltInPluginsTest {
    @Test
    fun `create returns the complete in-process plugin set`() {
        val plugins = PhoneBuiltInPlugins.create(LensTranslationPlugin())

        assertEquals(listOf("lyrics", "media", "lens", "feeds", "transit"), plugins.map { it.id })
    }

    @Test
    fun `feeds and transit catalog specs are launchable host settings`() {
        val specs = PhoneBuiltInPlugins.specs("com.anezium.rokidbus.phone", emptyMap())

        assertEquals(true, specs.single { it.id == "feeds" }.launchable)
        assertEquals(true, specs.single { it.id == "transit" }.launchable)
        assertEquals(
            "com.anezium.rokidbus.phone.FeedsSettingsActivity",
            specs.single { it.id == "feeds" }.settingsTarget?.className,
        )
        assertEquals(
            "com.anezium.rokidbus.phone.TransitSettingsActivity",
            specs.single { it.id == "transit" }.settingsTarget?.className,
        )
    }
}
