package com.anezium.rokidbus.phone

/** The one primary action of the patch step: a prepared APK waiting for the glasses outranks patching again. */
internal enum class PatchStepAction(val label: String) {
    INSTALL_PREPARED("Install on glasses"),
    REINSTALL("Reinstall / update"),
    PATCH("Patch and install"),
    APPROVE("Get or approve Patcher"),
}

internal fun patchStepAction(prepared: Boolean, youtubeDone: Boolean, patcherApproved: Boolean): PatchStepAction = when {
    prepared -> PatchStepAction.INSTALL_PREPARED
    youtubeDone -> PatchStepAction.REINSTALL
    patcherApproved -> PatchStepAction.PATCH
    else -> PatchStepAction.APPROVE
}

/** One line of state while a prepared APK waits; without fresh glasses inventory the next move is to connect them. */
internal fun patchStepLine(preparedLabel: String, glassesChecked: Boolean): String =
    if (glassesChecked) "Ready to install — $preparedLabel is patched and waiting."
    else "Ready to install — $preparedLabel is patched and waiting. Connect the glasses, then install."
