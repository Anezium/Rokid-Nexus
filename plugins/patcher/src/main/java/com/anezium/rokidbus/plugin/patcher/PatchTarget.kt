package com.anezium.rokidbus.plugin.patcher

import com.anezium.rokidbus.shared.PatcherContract

data class PatchBundleSource(
    val metadataUrl: String,
    val downloadPrefix: String,
    val pinnedSourceSha256: String,
    val projectUrl: String,
    val asset: String = "bundled.mpp",
    val assetMetadata: String = "bundled.json",
    val updatesEnabled: Boolean = true,
)

data class PatchWarning(val patchName: String, val message: String)

data class PatchTarget(
    val id: String,
    val displayName: String,
    val icon: Int,
    val description: String,
    val stockPackage: String,
    val acceptedVersions: Set<String>,
    val stockSigners: Set<String>,
    val minVerificationApi: Int,
    val maxVerificationApi: Int,
    val bundle: PatchBundleSource,
    val defaultSelection: Map<String, Boolean>,
    val featuredPatches: List<String>,
    val outputPackage: String,
    val packageChangingPatch: String?,
    val warnings: List<PatchWarning>,
    val acceptedVersionCodes: Set<Long> = emptySet(),
    /** Multi-tone app marks keep their own greens; single-colour marks take the accent tint. */
    val iconTinted: Boolean = true,
    val preview: Boolean = false,
) {
    init {
        require(PatcherContract.isTargetId(id))
        require(acceptedVersions.isNotEmpty() && stockSigners.isNotEmpty())
        require(acceptedVersionCodes.all { it > 0 })
        require(stockSigners.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(minVerificationApi >= 30 && maxVerificationApi >= minVerificationApi)
        require(bundle.pinnedSourceSha256.matches(Regex("[a-f0-9]{64}")))
    }
    val versionLabel get() = acceptedVersions.sorted().joinToString(" / ")
    fun priority(name: String) = featuredPatches.indexOf(name).takeIf { it >= 0 } ?: featuredPatches.size
    fun expectedOutput(selected: Set<String>) = if (packageChangingPatch == null || packageChangingPatch in selected) outputPackage else stockPackage
}

object PatchTargets {
    // YouTube's API 33+ v3.1 rotation must not replace the certificate checked by
    // the API 32 glasses. Verify the stock APK only over the glasses' API range.
    val youtube = PatchTarget(
        id = PatcherContract.TARGET_YOUTUBE,
        displayName = "YouTube",
        icon = R.drawable.ic_app_youtube,
        description = "Adds the glasses controls to YouTube.",
        stockPackage = "com.google.android.youtube",
        acceptedVersions = setOf("21.04.223"),
        stockSigners = setOf("3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"),
        minVerificationApi = 30, maxVerificationApi = 32,
        bundle = PatchBundleSource(
            "https://raw.githubusercontent.com/Anezium/morphe-patches/rokid/patches-bundle.json",
            "https://github.com/Anezium/morphe-patches/releases/download/",
            "d2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7",
            "https://github.com/Anezium/morphe-patches",
        ),
        defaultSelection = mapOf("Rokid controls" to true, "GmsCore support" to true, "Hide ads" to true, "SponsorBlock" to true),
        featuredPatches = listOf("Rokid controls", "GmsCore support", "Hide ads", "SponsorBlock"),
        outputPackage = "app.morphe.android.youtube",
        packageChangingPatch = "GmsCore support",
        warnings = listOf(
            PatchWarning("GmsCore support", "GmsCore support is off: sign-in will not work."),
            PatchWarning("Rokid controls", "Rokid controls is off: the glasses cannot drive stock YouTube."),
        ),
    )
    val default get() = youtube
    val reddit = PatchTarget(
        id = PatcherContract.TARGET_REDDIT,
        displayName = "Reddit",
        icon = R.drawable.ic_app_reddit,
        description = "Adds a glasses HUD and reviewed post and comment replies to official Reddit.",
        stockPackage = "com.reddit.frontpage",
        acceptedVersions = setOf("2026.14.0"),
        stockSigners = setOf("970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f"),
        minVerificationApi = 30, maxVerificationApi = 32,
        bundle = PatchBundleSource(
            metadataUrl = "", downloadPrefix = "",
            pinnedSourceSha256 = "92b8fcf4d176ce9ca3ca518483eecb214ac34441ce899c538213da1948642f30",
            projectUrl = "https://github.com/Anezium/morphe-patches",
            asset = "reddit.mpp", assetMetadata = "reddit.json", updatesEnabled = false,
        ),
        defaultSelection = mapOf("Rokid Reddit controls" to true, "Spoof signature" to true, "Hide ads" to true,
            "Change package name" to false),
        featuredPatches = listOf("Rokid Reddit controls", "Spoof signature", "Hide ads"),
        outputPackage = "com.reddit.frontpage", packageChangingPatch = null,
        warnings = listOf(
            PatchWarning("Rokid Reddit controls", "Rokid Reddit controls is off: the glasses HUD and replies will be unavailable."),
            PatchWarning("Spoof signature", "Spoof signature is off: official Reddit can reject the patched APK."),
        ),
        acceptedVersionCodes = setOf(2614001),
        iconTinted = false,
        preview = true,
    )
    val all = listOf(youtube, reddit)
    private val targets = all.associateBy { it.id }
    fun find(id: String?): PatchTarget? = targets[id]
    fun require(id: String): PatchTarget = requireNotNull(find(id)) { "Unknown patch target. Update Patcher and try again." }
}
