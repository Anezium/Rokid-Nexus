package com.anezium.rokidbus.plugin.transit

import java.time.ZoneId

/**
 * The journey view: the whole itinerary, one leg per page, the leg in progress marked, and a
 * menu that keeps Near Me and favorites one action away and ends guidance only when asked.
 */
internal object TransitJourneyCards {
    enum class MenuItem(val label: String) {
        NEAR_ME("Near Me"),
        FAVORITES("Favorites"),
        STOP_GUIDANCE("Stop guidance"),
    }

    fun leg(journey: JourneyState, page: Int, zone: ZoneId): TransitCardContent {
        val legs = journey.itinerary.legs
        val index = page.coerceIn(0, legs.lastIndex)
        val leg = legs[index]
        val current = index == journey.legIndex && journey.phase != JourneyPhase.ARRIVED
        val done = index < journey.legIndex || journey.phase == JourneyPhase.ARRIVED
        val from = if (index == 0) "Your position" else placeName(leg.from.name)
        val to = if (index == legs.lastIndex) journey.destinationLabel else placeName(leg.to.name)
        val minutes = ((leg.end.toEpochMilli() - leg.start.toEpochMilli()) / 60_000L).coerceAtLeast(0L)
        val lines = buildList {
            if (leg.isWalk) {
                add(CardLine(text = "Walk $minutes min", badge = "WALK"))
            } else {
                add(
                    CardLine(
                        text = TransitJourneyGuide.fit(leg.headsign?.let { "to $it" } ?: "to $to", LINE_CHARS),
                        badge = TransitJourneyGuide.fit(leg.line ?: leg.mode.lowercase(), BADGE_CHARS),
                    ),
                )
            }
            add(
                CardLine(
                    text = TransitJourneyGuide.fit("From $from", LINE_CHARS),
                    trail = listOf(TransitSkillContract.localTime(leg.start, zone)),
                ),
            )
            add(
                CardLine(
                    text = TransitJourneyGuide.fit("To $to", LINE_CHARS),
                    trail = listOf(TransitSkillContract.localTime(leg.end, zone)),
                ),
            )
            if (!leg.isWalk) {
                val stops = leg.intermediateStops.size + 1
                val realtime = if (leg.realTime) "live times" else "timetable"
                add(CardLine(text = "$stops ${if (stops == 1) "stop" else "stops"} · $realtime"))
            }
            when {
                current -> add(CardLine(text = currentLine(journey)))
                done -> add(CardLine(text = "Done"))
            }
        }
        val marker = when {
            journey.phase == JourneyPhase.ARRIVED -> "arrived"
            current -> "now"
            else -> null
        }
        return TransitCardContent(
            title = listOfNotNull("Journey ${index + 1}/${legs.size}", marker).joinToString(" · "),
            lines = lines,
            footer = "swipe legs . tap menu",
        )
    }

    fun menu(selected: MenuItem, journey: JourneyState): TransitCardContent =
        TransitCardContent(
            title = TransitJourneyGuide.fit("To ${journey.destinationLabel}", TITLE_CHARS),
            lines = MenuItem.values().map { item ->
                CardLine(if (item == selected) "> ${item.label}" else "  ${item.label}")
            },
            footer = "swipe . tap . back",
        )

    private fun currentLine(journey: JourneyState): String = when (journey.phase) {
        JourneyPhase.WALK -> "You are walking this leg"
        JourneyPhase.BOARD -> "Waiting to board"
        JourneyPhase.RIDE -> "You are aboard"
        JourneyPhase.MISSED -> "Missed this departure"
        JourneyPhase.ARRIVED -> "Arrived"
    }

    private fun placeName(value: String): String =
        value.takeUnless { it.isBlank() || it == "START" || it == "END" } ?: "Your position"

    private const val LINE_CHARS = 30
    private const val BADGE_CHARS = 6
    private const val TITLE_CHARS = 40
}
