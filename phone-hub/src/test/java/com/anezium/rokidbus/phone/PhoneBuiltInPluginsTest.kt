package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.lens.LensTranslationPlugin
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBuiltInPluginsTest {
    @Test
    fun `create returns only the lens in-process plugin`() {
        val plugins = PhoneBuiltInPlugins.create(LensTranslationPlugin())

        assertEquals(listOf("lens"), plugins.map { it.id })
    }

    @Test
    fun `catalog specs describe only the lens in-process plugin`() {
        val specs = PhoneBuiltInPlugins.specs("com.anezium.rokidbus.phone", emptyMap())

        assertEquals(listOf("lens"), specs.map { it.id })
        assertEquals(false, specs.single { it.id == "lens" }.launchable)
        assertEquals(
            "com.anezium.rokidbus.phone.LensSettingsActivity",
            specs.single { it.id == "lens" }.settingsTarget?.className,
        )
    }
}
