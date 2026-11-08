package com.anezium.rokidbus.plugin.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStatusTest {
    private fun status(account: Boolean = true, login: String = "idle", connected: Boolean = false, link: String = "disconnected") =
        AccountStatus(MetaAccount.INSTAGRAM, account, "test_user", login, connected, link)

    @Test fun savedSessionConnectsOnceWithoutALoginInProgress() {
        assertTrue(status().wantsConnection)
        assertFalse(status(link = "connecting").wantsConnection)
        assertFalse(status(link = "failed_timeout").wantsConnection)
        assertFalse(status(account = false).wantsConnection)
    }

    @Test fun sendsNeedAVerifiedConnection() {
        assertFalse(status().canSend)
        assertTrue(status(connected = true, link = "connected").canSend)
    }

    @Test fun loginIsOfferedOnlyWithoutASessionOrPendingStep() {
        assertTrue(status(account = false).canLogIn)
        assertFalse(status(account = false, login = "approval_required").canLogIn)
        assertFalse(status().canLogIn)
    }

    @Test fun pendingStepsAndFailuresExplainWhatToDo() {
        assertEquals("Approve this login in the Instagram app within 3 minutes, then come back here.",
            status(account = false, login = "approval_required").describe())
        assertEquals("Connected as @test_user.", status(connected = true, link = "connected").describe())
        assertEquals("Instagram refused the saved session for @test_user. Forget it and log in again.",
            status(link = "failed_authentication").describe())
    }

    @Test fun headlineStaysShortAndTonesFollowTheState() {
        assertEquals("Not signed in", status(account = false).headline())
        assertEquals(AccountStatus.Tone.IDLE, status(account = false).tone)
        assertEquals("Login failed", status(account = false, login = "failed_timeout").headline())
        assertEquals(AccountStatus.Tone.BAD, status(account = false, login = "failed_timeout").tone)
        assertEquals("Waiting for approval", status(account = false, login = "approval_required").headline())
        assertEquals(AccountStatus.Tone.BUSY, status(account = false, login = "approval_required").tone)
        assertEquals("Connecting…", status(link = "connecting").headline())
        assertEquals(AccountStatus.Tone.BUSY, status(link = "connecting").tone)
        assertEquals("Connected", status(connected = true, link = "connected").headline())
        assertEquals(AccountStatus.Tone.GOOD, status(connected = true, link = "connected").tone)
        assertEquals("Session refused", status(link = "failed_authentication").headline())
        assertEquals(AccountStatus.Tone.BAD, status(link = "failed_authentication").tone)
        assertEquals("Not connected", status().headline())
    }

    @Test fun fastPollingOnlyWhileALoginOrConnectionIsInFlight() {
        assertTrue(status(account = false, login = "running").inFlight)
        assertTrue(status(account = false, login = "code_required").inFlight)
        assertTrue(status(link = "connecting").inFlight)
        assertFalse(status(connected = true, link = "connected").inFlight)
        assertFalse(status().inFlight)
        assertFalse(status(account = false).inFlight)
    }
}
