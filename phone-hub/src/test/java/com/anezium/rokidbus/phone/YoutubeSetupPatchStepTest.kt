package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test

class YoutubeSetupPatchStepTest {
    @Test fun aWaitingPreparedInstallOutranksEveryOtherPrimaryAction() {
        for (done in listOf(true, false)) for (approved in listOf(true, false)) {
            assertEquals(PatchStepAction.INSTALL_PREPARED, patchStepAction(prepared = true, youtubeDone = done, patcherApproved = approved))
        }
        assertEquals("Install on glasses", PatchStepAction.INSTALL_PREPARED.label)
    }

    @Test fun withoutAPreparedInstallTheStepFollowsWhatIsInstalledAndApproved() {
        assertEquals(PatchStepAction.REINSTALL, patchStepAction(prepared = false, youtubeDone = true, patcherApproved = true))
        assertEquals(PatchStepAction.REINSTALL, patchStepAction(prepared = false, youtubeDone = true, patcherApproved = false))
        assertEquals(PatchStepAction.PATCH, patchStepAction(prepared = false, youtubeDone = false, patcherApproved = true))
        assertEquals(PatchStepAction.APPROVE, patchStepAction(prepared = false, youtubeDone = false, patcherApproved = false))
    }

    @Test fun theStateLineTellsTheUserToConnectOnlyWhenTheGlassesAreUnchecked() {
        assertEquals("Ready to install — YouTube 21.04.223 is patched and waiting.", patchStepLine("YouTube 21.04.223", glassesChecked = true))
        assertEquals("Ready to install — YouTube 21.04.223 is patched and waiting. Connect the glasses, then install.",
            patchStepLine("YouTube 21.04.223", glassesChecked = false))
    }
}
