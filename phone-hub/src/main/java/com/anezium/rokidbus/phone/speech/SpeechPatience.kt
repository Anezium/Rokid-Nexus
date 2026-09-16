package com.anezium.rokidbus.phone.speech

/**
 * Controls the timeouts used to decide when a spoken utterance begins and ends.
 */
enum class SpeechPatience(
    val wireValue: String,
    val initialWaitMs: Long,
    val pauseMs: Long,
) {
    /**
     * Quick: waits 5 seconds for speech to start, and closes after 1.5 seconds of silence.
     */
    QUICK("quick", 5_000L, 1_500L),

    /**
     * Normal: waits 8 seconds for speech to start, and closes after 2.5 seconds of silence.
     * This matches the default historical behaviour.
     */
    NORMAL("normal", 8_000L, 2_500L),

    /**
     * Patient: waits 15 seconds for speech to start, and closes after 4 seconds of silence.
     */
    PATIENT("patient", 15_000L, 4_000L);

    /**
     * Returns a VoiceActivityConfig overriding only the initial wait and pause parameters.
     */
    fun voiceActivityConfig(): VoiceActivityConfig =
        VoiceActivityConfig(
            initialNoSpeechTimeoutMs = initialWaitMs,
            silenceAfterSpeechMs = pauseMs,
        )

    /**
     * The silence timeout to pass to the Android speech recognizer.
     * This is strictly derived as [initialWaitMs] + 2000ms.
     * The built-in Google recognizer applies its 'complete silence' timeout before any speech is
     * detected too. Therefore, to ensure our VAD dictates the timeout instead of the engine
     * preemptively failing (which usually happens at ~3 seconds), this value is set as an upper
     * bound that safely exceeds our VAD's initial budget.
     */
    val androidSilenceMs: Long
        get() = initialWaitMs + 2_000L

    companion object {
        fun fromWireValue(value: String?): SpeechPatience =
            entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}
