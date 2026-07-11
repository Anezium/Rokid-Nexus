package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSettingsTextMatcherTest {
    @Test
    fun normalizePreservesCyrillicText() {
        assertEquals(
            "номер сборки",
            SelfArmSettingsTextMatcher.normalize("Номер сборки"),
        )
        // NFD folding maps й -> и; screen text and needles must fold identically.
        assertEquals(
            SelfArmSettingsTextMatcher.normalize("ПОДКЛЮЧЕНИЕ УСТРОЙСТВА С ПОМОЩЬЮ КОДА ПОДКЛЮЧЕНИЯ"),
            SelfArmSettingsTextMatcher.normalize("подключение устройства с помощью кода подключения"),
        )
    }

    @Test
    fun containsAnyMatchesRussianSettingsLabels() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Отладка по Wi-Fi",
                "отладка по wi-fi",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Использовать отладку по Wi\u2011Fi",
                "использовать отладку по wi-fi",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Подключение устройства с помощью кода подключения",
                "кода подключения",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Код подключения по Wi‑Fi",
                "код подключения",
            ),
        )
    }

    @Test
    fun containsAnyStillAsciiFoldsLatinAccents() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsAny(
                "Utiliser le debogage sans fil",
                "débogage sans fil",
            ),
        )
    }

    @Test
    fun containsBuildIdentifierMatchesLocaleIndependentBuildText() {
        assertTrue(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "SKQ1.240613.001 release-keys",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
        assertTrue(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "Номер сборки SKQ1.240613.001 release-keys",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
        assertFalse(
            SelfArmSettingsTextMatcher.containsBuildIdentifier(
                "Номер модели RKG-123",
                buildDisplay = "SKQ1.240613.001 release-keys",
                buildId = "SKQ1.240613.001",
            ),
        )
    }
}


