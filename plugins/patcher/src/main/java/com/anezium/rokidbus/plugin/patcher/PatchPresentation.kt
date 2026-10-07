package com.anezium.rokidbus.plugin.patcher

/** Words for the progress model: the engine owns the phases, the screen and notifications read them through here. */
object PatchPresentation {
    enum class StageState { DONE, CURRENT, UPCOMING }
    data class Stage(val label: String, val phases: List<PatchPhase>)
    data class StageRow(val index: Int, val label: String, val state: StageState)
    data class Notice(val title: String, val text: String)

    // Ten patch phases read as five stages: a stepper stays legible at five rows and
    // the sub-phase still shows as the live line, so nothing is hidden.
    val patchStages = listOf(
        Stage("Load", listOf(PatchPhase.BUNDLE_LOAD, PatchPhase.READ_APK)),
        Stage("Patch", listOf(PatchPhase.APPLY_PATCHES)),
        Stage("Build", listOf(PatchPhase.COMPILE, PatchPhase.ALIGN, PatchPhase.WRITE)),
        Stage("Sign", listOf(PatchPhase.SIGN, PatchPhase.VERIFY)),
        Stage("Save", listOf(PatchPhase.PUBLISH, PatchPhase.HAND_OFF)),
    )

    /** Reading, checking and merging the file belong to the stock step, not to the patch step. */
    fun isPreparePhase(phase: PatchPhase): Boolean = phase.ordinal < PatchPhase.BUNDLE_LOAD.ordinal

    fun stages(phase: PatchPhase, finished: Boolean = false): List<StageRow> {
        val current = patchStages.indexOfFirst { phase in it.phases }
        return patchStages.mapIndexed { index, stage ->
            StageRow(index + 1, stage.label, when {
                finished || index < current -> StageState.DONE
                index == current -> StageState.CURRENT
                else -> StageState.UPCOMING
            })
        }
    }

    /** What is happening right now, with a number only when the model measured one. */
    fun phaseLine(progress: PatchProgress): String {
        val percent = progress.fraction?.let { "${(it * 100).toInt()}%" }
        return when (progress.phase) {
            PatchPhase.READ_INPUT -> listOfNotNull("Reading your file", percent).joinToString(" · ")
            PatchPhase.SIGNATURE_CHECK -> listOfNotNull("Checking the stock signature", percent).joinToString(" · ")
            PatchPhase.SPLIT_MERGE -> "Merging the splits"
            PatchPhase.BUNDLE_LOAD -> "Loading the patch bundle"
            PatchPhase.READ_APK -> "Reading the APK"
            PatchPhase.APPLY_PATCHES -> when {
                progress.patchIndex > 0 -> "Applying patches · ${progress.patchIndex} of ${progress.patchTotal}"
                progress.patchTotal > 0 -> "Applying ${progress.patchTotal} patches"
                else -> "Applying patches"
            }
            PatchPhase.COMPILE -> "Compiling the patched code"
            PatchPhase.WRITE -> "Writing the APK"
            PatchPhase.ALIGN -> "Aligning the APK"
            PatchPhase.SIGN -> "Signing the APK"
            PatchPhase.VERIFY -> "Verifying the signature"
            PatchPhase.PUBLISH -> "Saving the result"
            PatchPhase.HAND_OFF -> "Ready to install"
        }
    }

    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    fun headline(status: PatchJobStatus, targetName: String): String = when (status) {
        PatchJobStatus.RUNNING -> "Patching $targetName"
        PatchJobStatus.SUCCESS -> "Patched"
        PatchJobStatus.FAILURE -> "Patch failed"
        PatchJobStatus.CANCELLED -> "Patch cancelled"
        PatchJobStatus.INTERRUPTED -> "Patch interrupted"
        else -> "Patch"
    }

    fun notice(state: PatchJobState, target: PatchTarget): Notice = when (state.status) {
        PatchJobStatus.PREPARING -> Notice("Checking your ${target.displayName} file", phaseLine(state.progress))
        PatchJobStatus.RUNNING -> Notice("Patching ${target.displayName}", phaseLine(state.progress))
        PatchJobStatus.SUCCESS -> Notice("${target.displayName} is ready to install", "Tap to come back. Nexus installs it on your glasses.")
        PatchJobStatus.FAILURE -> Notice("Patching ${target.displayName} failed", state.message)
        PatchJobStatus.CANCELLED -> Notice("Patching cancelled", "Nothing was installed. Patch ${target.displayName} again whenever you want.")
        PatchJobStatus.INTERRUPTED -> Notice("Patching was interrupted", "The patch did not finish and nothing was installed. Tap to retry.")
        PatchJobStatus.IDLE, PatchJobStatus.READY -> Notice("Patcher", state.message)
    }
}
