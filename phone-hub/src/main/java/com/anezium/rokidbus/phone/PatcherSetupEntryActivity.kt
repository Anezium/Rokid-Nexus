package com.anezium.rokidbus.phone

import android.app.Activity
import android.os.Bundle
import com.anezium.rokidbus.shared.PatcherContract

/**
 * Patcher → YouTube or Reddit lands here. Each tutorial and every privileged step behind it
 * stay in the hub's non-exported [YoutubeSetupActivity] or [RedditSetupActivity]; this
 * exported, undiscoverable entry only decides whether the authenticated, approved Patcher
 * may open the one its allowlisted target names.
 */
class PatcherSetupEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (savedInstanceState == null) {
            when (PatcherHandoff.setupEntry(intent, callingPackage, PatcherHandoff.authenticatedIdentity(this))) {
                PatcherHandoff.SetupEntry.OPEN -> startActivity(
                    if (intent.getStringExtra(PatcherContract.EXTRA_TARGET_ID) == PatcherContract.TARGET_REDDIT) {
                        RedditSetupActivity.intent(this)
                    } else {
                        YoutubeSetupActivity.intent(this, PatcherContract.jobState(intent.getStringExtra(PatcherContract.EXTRA_JOB_STATE)))
                    },
                )
                PatcherHandoff.SetupEntry.APPROVE -> startActivity(PatcherHandoff.approvalIntent(this))
                PatcherHandoff.SetupEntry.REFUSE -> Unit
            }
        }
        finish()
    }
}
