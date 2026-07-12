package com.anezium.rokidbus.plugin.transit

object TransitCards {
    fun chooser(selectedMode: TransitMode): TransitCardContent =
        TransitCardContent(
            title = "Transit",
            lines = TransitMode.values().map { mode ->
                CardLine(if (mode == selectedMode) "> ${mode.rowLabel}" else "  ${mode.rowLabel}")
            },
            footer = "swipe . tap opens",
        )
}
