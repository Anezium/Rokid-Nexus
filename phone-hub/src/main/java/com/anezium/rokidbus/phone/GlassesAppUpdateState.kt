package com.anezium.rokidbus.phone

internal sealed interface GlassesAppUpdateState {
    data object Unknown : GlassesAppUpdateState

    data class UpToDate(
        val installed: NexusSemVersion,
        val latest: NexusSemVersion,
    ) : GlassesAppUpdateState

    data class UpdateAvailable(
        val installed: NexusSemVersion,
        val latest: NexusSemVersion,
    ) : GlassesAppUpdateState
}

internal object GlassesAppUpdatePolicy {
    fun compare(
        installedVersionName: String?,
        latestRelease: NexusReleaseAsset?,
    ): GlassesAppUpdateState {
        val installed = installedVersionName?.let(NexusSemVersion::parse)
            ?: return GlassesAppUpdateState.Unknown
        val latest = latestRelease?.version ?: return GlassesAppUpdateState.Unknown
        return if (latest > installed) {
            GlassesAppUpdateState.UpdateAvailable(installed, latest)
        } else {
            GlassesAppUpdateState.UpToDate(installed, latest)
        }
    }
}
