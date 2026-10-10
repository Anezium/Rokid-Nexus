package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Glasses apps is inventory only: every app-specific setup tutorial lives under Patcher.
 * Hub unit tests run without Android resources, so this reads the screen's source and
 * strings rather than inflating it; device QA covers the rendered screen.
 */
class NativeAppsActivityTest {
    private val source = File("src/main/java/com/anezium/rokidbus/phone/NativeAppsActivity.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test fun `no YouTube or Reddit setup entry, only a passive pointer to Patcher`() {
        for (setup in listOf("RedditSetupActivity", "YoutubeSetupActivity", "PatcherSetupEntryActivity", "Set up Reddit",
            "Set up YouTube", "Reddit on glasses", "YouTube on glasses")) {
            assertFalse(setup, source.contains(setup))
        }
        assertTrue(source.contains("R.string.native_apps_patcher_hint"))
        assertTrue(strings.contains(">YouTube and Reddit setup and updates live in Patcher.<"))
        // Inventory, loading, empty and error states stay.
        for (state in listOf("NativeAppsUiState.Loading", "NativeAppsUiState.Empty", "NativeAppsUiState.Error", "appRow(app)")) {
            assertTrue(state, source.contains(state))
        }
    }
}
