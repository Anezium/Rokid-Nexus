package com.anezium.rokidbus.plugin.patcher

enum class PatchPhase(val label: String) {
    READ_INPUT("Reading input"), SIGNATURE_CHECK("Checking stock signatures"), SPLIT_MERGE("Merging splits"),
    BUNDLE_LOAD("Loading bundle"), READ_APK("Reading APK"), APPLY_PATCHES("Applying patches"),
    COMPILE("Compiling patches"), ALIGN("Aligning APK"), WRITE("Writing APK"),
    SIGN("Signing APK"), VERIFY("Verifying output"), PUBLISH("Saving result"), HAND_OFF("Ready to install")
}

// Labels are the words the user reads while waiting: what the engine is doing, in plain terms.
enum class PatchSubstep(val label: String) {
    DECODE("Unpacking resources and code"), EXECUTE("Applying patches"),
    DEX("Compiling code"), STRIP("Removing replaced code"), RESOURCES("Compiling resources"),
    RESOURCE_APK("Packing resources"), COPY("Copying the original APK"),
    STAGE_RESOURCES("Adding resources"), STAGE_DEX("Adding compiled code"),
    COMPRESS("Compressing files"), WRITE_ENTRIES("Writing the APK"),
    WRITE_DIRECTORY("Finishing the APK")
}

data class PatchProgress(
    val phase: PatchPhase = PatchPhase.READ_INPUT,
    val fraction: Double? = null,
    val patchName: String? = null,
    val patchIndex: Int = 0,
    val patchTotal: Int = 0,
    val substep: PatchSubstep? = null,
    val completedBytes: Long? = null,
    val workTotal: Long? = null,
) {
    init {
        require(fraction == null || fraction.isFinite() && fraction in 0.0..1.0)
        require(completedBytes == null || completedBytes >= 0)
        require(workTotal == null || workTotal >= 0)
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
        substep?.let { append(" · ${it.label}") }
        completedBytes?.let { append(" · ${PatchPresentation.written(it)}") }
        patchName?.let { append("\nCompleted patch $patchIndex/$patchTotal: $it") }
    }
}
