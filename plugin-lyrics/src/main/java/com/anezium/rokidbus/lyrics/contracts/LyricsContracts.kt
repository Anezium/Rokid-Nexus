package com.anezium.rokidbus.lyrics.contracts

object TransportConstants {
    const val PROTOCOL_VERSION = 1
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class DeviceStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val statusLabel: String = "Waiting for the phone runtime.",
    val bluetoothClientCount: Int = 0,
    val notificationAccessEnabled: Boolean = false,
    val lastError: String? = null,
)

enum class LyricsSessionState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    ERROR,
}

data class LyricsLine(
    val startTimeMs: Long = 0L,
    val text: String = "",
)

data class LyricsSnapshot(
    val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    val trackTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val durationSeconds: Int? = null,
    val provider: String = "",
    val sourceSummary: String = "Waiting for active media playback on the phone.",
    val synced: Boolean = false,
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val currentLineIndex: Int = -1,
    val lines: List<LyricsLine> = emptyList(),
    val plainLyrics: String = "",
    val errorMessage: String? = null,
)

data class LyricsPlaybackSync(
    val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val currentLineIndex: Int = -1,
)

data class ProtocolHello(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

data class ProtocolHelloAck(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

sealed interface GlassesToPhoneMessage {
    data class Hello(val hello: ProtocolHello) : GlassesToPhoneMessage
    data object RequestSnapshot : GlassesToPhoneMessage
    data object RequestStatus : GlassesToPhoneMessage
    data object TogglePlayback : GlassesToPhoneMessage
}

sealed interface LyricsEvent {
    data class Snapshot(val snapshot: LyricsSnapshot) : LyricsEvent
    data class Sync(val sync: LyricsPlaybackSync) : LyricsEvent
    data class Error(val message: String) : LyricsEvent
}

sealed interface PhoneToGlassesMessage {
    data class HelloAck(val ack: ProtocolHelloAck) : PhoneToGlassesMessage
    data class Status(val status: DeviceStatus) : PhoneToGlassesMessage
    data class Lyrics(val event: LyricsEvent) : PhoneToGlassesMessage
    data class Error(val message: String) : PhoneToGlassesMessage
}
