package com.anezium.rokidbus.plugin.patcher

/** Words for the progress model: the engine owns the phases, the screen and notifications read them through here. */
object PatchPresentation {
    enum class StageState { DONE, CURRENT, UPCOMING }
    data class Stage(val label: String, val phases: List<PatchPhase>)
    data class StageRow(val index: Int, val label: String, val state: StageState)
    data class Notice(val title: String, val text: String)
    /** The colour a surface takes for a job: alive, finished well, stopped by the user, or broken. */
    enum class Accent { LIVE, OK, WARN, DANGER }
    /** Everything the small floating window shows; the bar fills for a known fraction and closes in the accent colour at the end. */
    data class Compact(val title: String, val line: String, val clock: String, val fraction: Double?, val accent: Accent)

    private const val SEPARATOR = " · "

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
        progress.substep?.let { step ->
            return listOfNotNull(step.label, progress.completedBytes?.let(::written), progress.workTotal?.let(::classes))
                .joinToString(SEPARATOR)
        }
        val percent = progress.fraction?.let { "${(it * 100).toInt()}%" }
        return when (progress.phase) {
            PatchPhase.READ_INPUT -> listOfNotNull("Reading your file", percent).joinToString(SEPARATOR)
            PatchPhase.SIGNATURE_CHECK -> listOfNotNull("Checking the stock signature", percent).joinToString(SEPARATOR)
            PatchPhase.SPLIT_MERGE -> "Merging the splits"
            PatchPhase.BUNDLE_LOAD -> "Loading the patch bundle"
            PatchPhase.READ_APK -> "Reading the APK"
            PatchPhase.APPLY_PATCHES -> when {
                progress.patchIndex > 0 -> "Applying patches${SEPARATOR}${progress.patchIndex} of ${progress.patchTotal}"
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

    /** Measured writes with no known total: a growing size is the honest number. Decimal megabytes, as Android shows them. */
    fun written(bytes: Long): String = "${bytes / 1_000_000} MB written"

    /** The engine reports how many classes it compiles, not how many are done; the total alone says how big the job is. */
    fun classes(count: Long): String = "${"%,d".format(java.util.Locale.ROOT, count)} classes"

    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** How long ago a saved result was made, in the coarse units a person uses. */
    fun age(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val hours = minutes / 60
        return when {
            minutes < 1 -> "just now"
            hours < 1 -> "$minutes min ago"
            minutes % 60 == 0L -> "$hours h ago"
            else -> "$hours h ${minutes % 60} min ago"
        }
    }

    /** A bar only when the model measured real movement: a 0% bar reads as frozen. */
    fun notificationPercent(progress: PatchProgress): Int? =
        progress.fraction?.takeIf { it > 0.0 }?.let { (it * 100).toInt().coerceIn(1, 100) }

    fun headline(status: PatchJobStatus, targetName: String): String = when (status) {
        PatchJobStatus.RUNNING -> "Patching $targetName"
        PatchJobStatus.SUCCESS -> "Patched"
        PatchJobStatus.FAILURE -> "Patch failed"
        PatchJobStatus.CANCELLED -> "Patch cancelled"
        PatchJobStatus.INTERRUPTED -> "Patch interrupted"
        else -> "Patch"
    }

    fun accent(status: PatchJobStatus): Accent = when (status) {
        PatchJobStatus.SUCCESS, PatchJobStatus.READY -> Accent.OK
        PatchJobStatus.CANCELLED -> Accent.WARN
        PatchJobStatus.FAILURE, PatchJobStatus.INTERRUPTED -> Accent.DANGER
        else -> Accent.LIVE
    }

    /** The floating window: a label, the live line, the clock, and a bar that closes in the accent colour once the job ends. */
    fun compact(state: PatchJobState, target: PatchTarget = PatchTargets.find(state.targetId) ?: PatchTargets.default): Compact {
        val title = "Patcher · ${target.displayName}"
        val clock = elapsed(state.elapsedMs)
        return when (state.status) {
            PatchJobStatus.PREPARING, PatchJobStatus.RUNNING -> Compact(title, phaseLine(state.progress), clock, state.progress.fraction, Accent.LIVE)
            PatchJobStatus.SUCCESS -> Compact(title, "Ready to install", clock, 1.0, Accent.OK)
            PatchJobStatus.FAILURE, PatchJobStatus.CANCELLED, PatchJobStatus.INTERRUPTED ->
                Compact(title, headline(state.status, target.displayName), clock, 1.0, accent(state.status))
            PatchJobStatus.IDLE, PatchJobStatus.READY -> Compact(title, state.message, clock, null, accent(state.status))
        }
    }

    /** Active notifications say what is happening and for how long; the bar is added only when a percentage is real. */
    private fun liveText(state: PatchJobState): String = phaseLine(state.progress) + SEPARATOR + elapsed(state.elapsedMs)

    fun notice(state: PatchJobState, target: PatchTarget): Notice = when (state.status) {
        PatchJobStatus.PREPARING -> Notice("Checking your ${target.displayName} file", liveText(state))
        PatchJobStatus.RUNNING -> Notice("Patching ${target.displayName}", liveText(state))
        PatchJobStatus.SUCCESS -> Notice("${target.displayName} is ready to install", "Tap to come back; keep the glasses connected to install.")
        PatchJobStatus.FAILURE -> Notice("Patching ${target.displayName} failed", state.message)
        PatchJobStatus.CANCELLED -> Notice("Patching cancelled", "Nothing was installed. Patch ${target.displayName} again whenever you want.")
        PatchJobStatus.INTERRUPTED -> Notice("Patching was interrupted", "It did not finish and nothing was installed. Tap to try again.")
        PatchJobStatus.IDLE, PatchJobStatus.READY -> Notice("Patcher", state.message)
    }

    /** What to expect, said once on the idle card and again while running. The small window is the fast path; without it, honesty about speed. */
    fun idleAdvice(pipSupported: Boolean): String = if (pipSupported)
        "Patching takes about 6–7 minutes and keeps going in a small window while you use other apps. The result is signed with this plugin's key."
    else "Patching takes about 6–7 minutes with this screen open, and much longer in the background. The result is signed with this plugin's key."

    fun runningAdvice(pipSupported: Boolean, notificationsAllowed: Boolean): String {
        val speed = if (pipSupported)
            "About 6–7 minutes. Use other apps meanwhile: patching keeps going at full speed in a small window. Locking the phone or closing that window slows it down a lot, but it keeps going."
        else "About 6–7 minutes with this screen open. Leaving it or locking the phone slows patching down a lot, but it keeps going."
        return if (notificationsAllowed) "$speed The notification brings you back when it is ready."
        else "$speed Notifications are off for Patcher, so come back here to check on it."
    }
}
