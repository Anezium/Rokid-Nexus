package com.anezium.rokidbus.plugin.assistant

/** Which Ink pages the model may draw for an answer: the Visual answers setting. */
internal enum class AssistantVisualAnswers(val wireValue: String) {
    /** Answers stay text in the band; the model is offered no page at all. */
    OFF("off"),

    /** Only the fixed, tested layouts: the model fills one with data and lays out nothing itself. */
    TEMPLATES("templates"),

    /** The fixed layouts, plus pages the model lays out itself. */
    FREE_PAGES("free_pages"),
    ;

    val allowsTemplates: Boolean
        get() = this != OFF

    val allowsFreePages: Boolean
        get() = this == FREE_PAGES

    internal companion object {
        /**
         * A page the model lays out itself can come out oversized, clipped or half empty on the
         * HUD, where a template cannot. So everyone starts on the templates, and free pages are
         * a choice.
         */
        val DEFAULT = TEMPLATES

        fun fromWire(value: String?): AssistantVisualAnswers =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT
    }
}
