package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Yandex Maps 30.9.1, API 36.1 emulator, public route from Teatralnaya Square. */
class YandexMapsParserTest {
    private val driving = NavNotification(
        packageName = "ru.yandex.yandexmaps",
        channelId = "foreground_notification",
        category = "navigation",
        ongoing = true,
        title = "150 m",
        text = "Teatralny Drive",
        viewTexts = mapOf(
            "titleView" to "150 m",
            "descriptionView" to "Teatralny Drive",
            "traffic_light_data_expanded" to "8",
            "remainingDistanceView" to "4.8 km",
            "timeOfArrivalView" to "04:28 PM",
            "remainingTimeView" to "11 min",
            "actionButton" to "Finish the route",
        ),
    )

    @Test
    fun `captured driving reads next distance street and expanded arrival clock`() {
        val result = YandexMapsParser.parse(driving)!!
        assertEquals("150 m", result.primary)
        assertEquals("Teatralny Drive", result.secondary)
        assertEquals("4:28 PM", result.eta)
        assertEquals("route", result.glyph)
        assertFalse(result.imminent)
        assertFalse(result.arrived)
    }

    @Test
    fun `Russian close turn preserves Cyrillic units without guessing the bitmap arrow`() {
        // Locale changed during the trip: the street remained in English.
        val result = YandexMapsParser.parse(driving.copy(
            channelId = "bg_notification",
            title = "40 м",
            viewTexts = mapOf("timeOfArrivalView" to "16:29", "actionButton" to "Завершить маршрут"),
        ))!!
        val closer = YandexMapsParser.parse(driving.copy(title = "20 м"))!!
        assertEquals("40 м", result.primary)
        assertEquals("16:29", result.eta)
        assertEquals(result.stepKey, closer.stepKey)
        assertEquals("route", closer.glyph)
        assertFalse(closer.imminent)
        assertFalse(closer.arrived)
        // A fresh Russian trip also localizes the street and trip summary.
        val russian = YandexMapsParser.parse(driving.copy(
            title = "40 м", text = "Театральный проезд",
            viewTexts = mapOf("remainingDistanceView" to "4,7 км", "remainingTimeView" to "11 мин", "timeOfArrivalView" to "16:45"),
        ))!!
        assertEquals("Театральный проезд", russian.secondary)
        assertEquals("16:45", russian.eta)
    }

    @Test
    fun `view inflation failure loses only ETA and street words cannot become maneuvers`() {
        val result = YandexMapsParser.parse(driving.copy(
            text = "Turn Right Street", viewTexts = emptyMap(), subText = "11 min",
        ))!!
        assertEquals("route", result.glyph)
        assertEquals("Turn Right Street", result.secondary)
        assertNull(result.eta)
    }

    @Test
    fun `generic walking service and unrelated or disabled sources cannot masquerade as guidance`() {
        // The walking trip posted only this title, with no text or RemoteViews.
        assertNull(YandexMapsParser.parse(driving.copy(title = "Navigator is running", text = null, viewTexts = emptyMap())))
        // The first walking leg of the captured Moscow metro route was generic too.
        assertNull(YandexMapsParser.parse(driving.copy(title = "Навигатор запущен", text = null, viewTexts = emptyMap())))
        assertNull(YandexMapsParser.parse(driving.copy(title = null)))
        assertNull(YandexMapsParser.parse(driving.copy(title = "11 min")))
        assertNull(YandexMapsParser.parse(driving.copy(ongoing = false)))
        assertNull(YandexMapsParser.parse(driving.copy(category = "promo")))
        assertNull(YandexMapsParser.parse(driving.copy(channelId = "advertising")))
        assertNull(YandexMapsParser.parse(driving.copy(packageName = "ru.yandex.yandexnavi")))
        assertTrue(NavSwitches().allows(NavSource.YANDEX_MAPS))
        assertFalse(NavSwitches(yandexMaps = false).allows(NavSource.YANDEX_MAPS))
        assertFalse(NavSwitches(enabled = false).allows(NavSource.YANDEX_MAPS))
    }
}
