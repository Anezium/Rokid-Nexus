package com.anezium.rokidbus.plugin.youtubepatcher

enum class PatchPhase(val label: String) {
    READ_INPUT("Reading input"), SIGNATURE_CHECK("Checking stock signatures"), SPLIT_MERGE("Merging splits"),
    BUNDLE_LOAD("Loading bundle"), READ_APK("Reading APK"), APPLY_PATCHES("Applying patches"),
    COMPILE("Compiling patches"), WRITE("Writing APK"), ALIGN("Aligning APK"),
    SIGN("Signing APK"), VERIFY("Verifying output"), PUBLISH("Saving result"), HAND_OFF("Ready to install")
}

data class PatchProgress(
    val phase: PatchPhase = PatchPhase.READ_INPUT,
    val fraction: Double? = null,
    val patchName: String? = null,
    val patchIndex: Int = 0,
    val patchTotal: Int = 0,
) {
    init {
        require(fraction == null || fraction.isFinite() && fraction in 0.0..1.0)
        require(patchIndex >= 0 && patchTotal >= patchIndex)
        require(patchName == null || phase == PatchPhase.APPLY_PATCHES)
    }
    val phaseIndex get() = phase.ordinal + 1
    val phaseTotal get() = PatchPhase.entries.size
    // Phase count is not a time-based percentage: compilation and patch costs vary.
    val estimatedRemainingMs: Long? get() = null
    fun display(): String = buildString {
        append("Phase $phaseIndex/$phaseTotal: ${phase.label}")
        fraction?.let { append(" · ${(it * 100).toInt()}%") }
        patchName?.let { append("\nCompleted patch $patchIndex/$patchTotal: $it") }
    }
}
