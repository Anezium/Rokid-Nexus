package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensHudChipTest {
    @Test
    fun liveWithDeepLShowsTwoCalmLines() {
        val lines = composeHudChip(
            LensHudState(
                frozen = false,
                linkUp = true,
                engine = "DEEPL",
                targetLang = "fr",
                status = "TRANSLATED 5",
            ),
        )

        assertEquals(listOf("LIVE", "FR · DeepL"), lines.map { it.text })
        assertTrue(lines.none { it.alert })
    }

    @Test
    fun liveZoomAppendsToStateLine() {
        val lines = composeHudChip(
            LensHudState(frozen = false, linkUp = true, zoomLabel = "1.5x", status = ""),
        )

        assertEquals("LIVE · 1.5x", lines.first().text)
    }

    @Test
    fun frozenShowsSourceQuality() {
        val lines = composeHudChip(
            LensHudState(frozen = true, linkUp = true, frozenSource = "HD", engine = "GOOGLE_WEB", status = ""),
        )

        assertEquals(listOf("FROZEN · HD", "FR · Google"), lines.map { it.text })
    }

    @Test
    fun offlineEngineIsAnAlert() {
        val lines = composeHudChip(
            LensHudState(frozen = false, linkUp = true, engine = "MLKIT_OFFLINE", status = ""),
        )

        val route = lines[1]
        assertEquals("FR · Offline", route.text)
        assertTrue(route.alert)
    }

    @Test
    fun missingLinkBeatsEverythingOnRouteLine() {
        val lines = composeHudChip(
            LensHudState(frozen = false, linkUp = false, engine = "DEEPL", status = ""),
        )

        assertEquals("PHONE LINK OFF", lines[1].text)
        assertTrue(lines[1].alert)
    }

    @Test
    fun onlyAllowlistedStatusesSurface() {
        val capturing = composeHudChip(
            LensHudState(frozen = true, linkUp = true, status = "FREEZE NEXT FRAME..."),
        )
        val plumbing = composeHudChip(
            LensHudState(frozen = false, linkUp = true, status = "TRANSLATED 12"),
        )

        assertEquals("CAPTURING…", capturing.last().text)
        assertFalse(plumbing.any { it.text.contains("TRANSLATED") })
        assertEquals(2, plumbing.size)
    }
}
