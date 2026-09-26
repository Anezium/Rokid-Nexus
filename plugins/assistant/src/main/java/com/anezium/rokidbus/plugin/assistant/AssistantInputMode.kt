package com.anezium.rokidbus.plugin.assistant

/** How the wearer asks a question: the Input setting. */
internal enum class AssistantInputMode(val wireValue: String) {
    /** Ask out loud, as Assistant always has; the listening band offers nothing else. */
    VOICE_ONLY("voice_only"),

    /** Ask out loud, with a Type chip joining the listening band once past the tap's bounce. */
    VOICE_AND_TYPE("voice_and_type"),

    /** Every question opens the typed field in the band straight away; the microphone stays off. */
    TYPE_FIRST("type_first"),
    ;

    internal companion object {
        /** New and existing installs alike: nothing changes for anyone who never looks. */
        val DEFAULT = VOICE_ONLY

        fun fromWire(value: String?): AssistantInputMode =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT
    }
}

/**
 * The wearer's choice as these glasses can honour it right now. Typing needs a field the glasses
 * can draw in the band — the editable-surface bit and a notice band, both only while the data
 * link is up — and without them every choice falls back to voice, the way Relay's "Reply by
 * typing" does, rather than offering a keyboard that could never be committed.
 */
internal fun effectiveInputMode(chosen: AssistantInputMode, canType: Boolean): AssistantInputMode =
    if (canType) chosen else AssistantInputMode.VOICE_ONLY
