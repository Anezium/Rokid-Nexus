package com.anezium.rokidbus.lens

import java.util.Locale

/** One rendered chip line; [alert] switches the line to the warning color. */
internal data class HudChipLine(
    val text: String,
    val alert: Boolean = false,
)

/**
 * Lay-user HUD chip: two stable lines (state, route) plus an optional transient status line.
 * Debug telemetry (track counts, Hz, cache sizes) stays in logcat only.
 */
internal fun composeHudChip(state: LensHudState): List<HudChipLine> = buildList {
    add(HudChipLine(stateLine(state)))
    add(routeLine(state))
    transientStatusLine(state)?.let(::add)
}

private fun stateLine(state: LensHudState): String {
    if (!state.frozen) {
        val zoomSuffix = state.zoomLabel?.let { " · $it" }.orEmpty()
        return "LIVE$zoomSuffix"
    }
    val sourceSuffix = state.frozenSource?.let { " · $it" }.orEmpty()
    return "FROZEN$sourceSuffix"
}

private fun routeLine(state: LensHudState): HudChipLine {
    if (!state.linkUp) return HudChipLine("PHONE LINK OFF", alert = true)
    val target = state.targetLang.uppercase(Locale.US)
    val engineLabel = engineLabel(state.engine)
    return when {
        engineLabel == null -> HudChipLine(target)
        else -> HudChipLine(
            "$target · $engineLabel",
            alert = state.engine == "MLKIT_OFFLINE",
        )
    }
}

private fun engineLabel(engine: String?): String? = when (engine) {
    "DEEPL" -> "DeepL"
    "GEMINI" -> "Gemini"
    "GOOGLE_WEB" -> "Google"
    "MLKIT_OFFLINE" -> "Offline"
    null, "" -> null
    else -> engine
}

/** Only statuses a lay user should see; everything else is plumbing noise. */
private fun transientStatusLine(state: LensHudState): HudChipLine? {
    val status = state.status
    return when {
        status.startsWith("FREEZE") -> HudChipLine("CAPTURING…")
        status.startsWith("DOWNLOADING") -> HudChipLine("DOWNLOADING LANGUAGE…")
        status.startsWith("DENSE") -> HudChipLine("DENSE · FREEZE ADVISED")
        status.startsWith("ZOOM") -> HudChipLine(status)
        status.startsWith("WAITING") -> HudChipLine("STARTING…")
        else -> null
    }
}
