package com.anezium.rokidbus.phone

internal enum class GlassesAppRetry {
    QUERY,
    INSTALL,
}

internal sealed interface GlassesAppInstallState {
    data object Unknown : GlassesAppInstallState
    data object Querying : GlassesAppInstallState
    data object NotInstalled : GlassesAppInstallState
    data object Resolving : GlassesAppInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : GlassesAppInstallState
    data object Installing : GlassesAppInstallState
    data object Installed : GlassesAppInstallState
    data class Error(val message: String, val retry: GlassesAppRetry) : GlassesAppInstallState
}

internal sealed interface GlassesAppInstallEvent {
    data object QueryRequested : GlassesAppInstallEvent
    data class QueryCompleted(val installed: Boolean) : GlassesAppInstallEvent
    data object InstallRequested : GlassesAppInstallEvent
    data object DownloadStarted : GlassesAppInstallEvent
    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?) : GlassesAppInstallEvent
    data object UploadStarted : GlassesAppInstallEvent
    data class InstallCompleted(val success: Boolean) : GlassesAppInstallEvent
    data class Failed(val message: String, val retry: GlassesAppRetry) : GlassesAppInstallEvent
}

internal object GlassesAppInstallStateMachine {
    fun reduce(
        current: GlassesAppInstallState,
        event: GlassesAppInstallEvent,
    ): GlassesAppInstallState = when (event) {
        GlassesAppInstallEvent.QueryRequested -> when (current) {
            GlassesAppInstallState.Resolving,
            is GlassesAppInstallState.Downloading,
            GlassesAppInstallState.Installing,
            -> current
            else -> GlassesAppInstallState.Querying
        }
        is GlassesAppInstallEvent.QueryCompleted -> if (current != GlassesAppInstallState.Querying) {
            current
        } else if (event.installed) {
            GlassesAppInstallState.Installed
        } else {
            GlassesAppInstallState.NotInstalled
        }
        GlassesAppInstallEvent.InstallRequested -> when (current) {
            GlassesAppInstallState.NotInstalled -> GlassesAppInstallState.Resolving
            is GlassesAppInstallState.Error -> if (current.retry == GlassesAppRetry.INSTALL) {
                GlassesAppInstallState.Resolving
            } else {
                current
            }
            else -> current
        }
        GlassesAppInstallEvent.DownloadStarted -> if (current == GlassesAppInstallState.Resolving) {
            GlassesAppInstallState.Downloading(0L, null)
        } else {
            current
        }
        is GlassesAppInstallEvent.DownloadProgress -> if (current is GlassesAppInstallState.Downloading) {
            GlassesAppInstallState.Downloading(
                downloadedBytes = event.downloadedBytes.coerceAtLeast(0L),
                totalBytes = event.totalBytes?.takeIf { it >= 0L },
            )
        } else {
            current
        }
        GlassesAppInstallEvent.UploadStarted -> if (current is GlassesAppInstallState.Downloading) {
            GlassesAppInstallState.Installing
        } else {
            current
        }
        is GlassesAppInstallEvent.InstallCompleted -> if (current != GlassesAppInstallState.Installing) {
            current
        } else if (event.success) {
            // CXR's install callback only reports that the upload/install command succeeded.
            // Confirm the package through appIsInstalled before marking onboarding done.
            GlassesAppInstallState.Querying
        } else {
            GlassesAppInstallState.Error("The glasses rejected the APK install.", GlassesAppRetry.INSTALL)
        }
        is GlassesAppInstallEvent.Failed -> GlassesAppInstallState.Error(event.message, event.retry)
    }
}
