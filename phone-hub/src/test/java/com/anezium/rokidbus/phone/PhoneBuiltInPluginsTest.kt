package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.lens.LensTranslationPlugin
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBuiltInPluginsTest {
    @Test
    fun `create returns the three in-process plugins`() {
        val plugins = PhoneBuiltInPlugins.create(LensTranslationPlugin())

        assertEquals(listOf("lyrics", "media", "lens"), plugins.map { it.id })
    }

    @Test
    fun `catalog specs describe only the three in-process plugins`() {
        val specs = PhoneBuiltInPlugins.specs("com.anezium.rokidbus.phone", emptyMap())

        assertEquals(listOf("lyrics", "media", "lens"), specs.map { it.id })
        assertEquals(true, specs.single { it.id == "lyrics" }.launchable)
        assertEquals(true, specs.single { it.id == "media" }.launchable)
        assertEquals(false, specs.single { it.id == "lens" }.launchable)
        assertEquals(
            "com.anezium.rokidbus.phone.LyricsSettingsActivity",
            specs.single { it.id == "lyrics" }.settingsTarget?.className,
        )
        assertEquals(
            "com.anezium.rokidbus.phone.MediaDeckSettingsActivity",
            specs.single { it.id == "media" }.settingsTarget?.className,
        )
        assertEquals(
            "com.anezium.rokidbus.phone.LensSettingsActivity",
            specs.single { it.id == "lens" }.settingsTarget?.className,
        )
    }
}
