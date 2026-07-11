package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.ConnectionState
import com.anezium.rokidbus.lyrics.contracts.DeviceStatus
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot

data class ProviderSettingsViewState(
    val spotifyConfigured: Boolean = false,
    val spotifyStatusLabel: String =
        "Add your Spotify sp_dc cookie to try Spotify's own synced lyrics first.",
    val musixmatchConfigured: Boolean = false,
    val musixmatchStatusLabel: String =
        "Musixmatch is not configured yet. LRCLIB remains available as fallback.",
    val neteaseStatusLabel: String =
        "Netease is enabled on this phone. No sign-in is required.",
)

data class LyricsPhoneViewState(
    val deviceStatus: DeviceStatus = DeviceStatus(
        connectionState = ConnectionState.CONNECTING,
        statusLabel = "Bluetooth server starting...",
    ),
    val lyrics: LyricsSnapshot = LyricsSnapshot(),
    val providers: ProviderSettingsViewState = ProviderSettingsViewState(),
)
