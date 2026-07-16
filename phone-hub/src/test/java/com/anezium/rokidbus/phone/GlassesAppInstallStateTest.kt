package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesAppInstallStateTest {
    @Test
    fun `query moves unknown state to installed`() {
        val querying = GlassesAppInstallStateMachine.reduce(
            GlassesAppInstallState.Unknown,
            GlassesAppInstallEvent.QueryRequested,
        )
        val installed = GlassesAppInstallStateMachine.reduce(
            querying,
            GlassesAppInstallEvent.QueryCompleted(installed = true),
        )

        assertEquals(GlassesAppInstallState.Querying, querying)
        assertEquals(GlassesAppInstallState.Installed, installed)
    }

    @Test
    fun `not installed app moves through download and install to done`() {
        var state: GlassesAppInstallState = GlassesAppInstallState.Querying
        state = GlassesAppInstallStateMachine.reduce(
            state,
            GlassesAppInstallEvent.QueryCompleted(installed = false),
        )
        assertEquals(GlassesAppInstallState.NotInstalled, state)

        state = GlassesAppInstallStateMachine.reduce(state, GlassesAppInstallEvent.InstallRequested)
        assertEquals(GlassesAppInstallState.Resolving, state)
        state = GlassesAppInstallStateMachine.reduce(state, GlassesAppInstallEvent.DownloadStarted)
        assertEquals(GlassesAppInstallState.Downloading(0L, null), state)
        state = GlassesAppInstallStateMachine.reduce(
            state,
            GlassesAppInstallEvent.DownloadProgress(75L, 100L),
        )
        assertEquals(GlassesAppInstallState.Downloading(75L, 100L), state)
        state = GlassesAppInstallStateMachine.reduce(state, GlassesAppInstallEvent.UploadStarted)
        assertEquals(GlassesAppInstallState.Installing, state)
        state = GlassesAppInstallStateMachine.reduce(
            state,
            GlassesAppInstallEvent.InstallCompleted(success = true),
        )
        assertEquals(GlassesAppInstallState.Querying, state)
        state = GlassesAppInstallStateMachine.reduce(
            state,
            GlassesAppInstallEvent.QueryCompleted(installed = true),
        )
        assertEquals(GlassesAppInstallState.Installed, state)
    }

    @Test
    fun `install failure keeps a retryable error`() {
        val state = GlassesAppInstallStateMachine.reduce(
            GlassesAppInstallState.Installing,
            GlassesAppInstallEvent.Failed("Connection lost", GlassesAppRetry.INSTALL),
        )

        assertTrue(state is GlassesAppInstallState.Error)
        assertEquals(GlassesAppRetry.INSTALL, (state as GlassesAppInstallState.Error).retry)
        assertEquals("Connection lost", state.message)
    }

    @Test
    fun `installed app can enter the existing install flow for an update`() {
        val state = GlassesAppInstallStateMachine.reduce(
            GlassesAppInstallState.Installed,
            GlassesAppInstallEvent.InstallRequested,
        )

        assertEquals(GlassesAppInstallState.Resolving, state)
    }

    @Test
    fun `update progress does not regress confirmed installed presence`() {
        var installed = GlassesAppPresencePolicy.reduce(
            currentlyInstalled = false,
            state = GlassesAppInstallState.Installed,
        )
        installed = GlassesAppPresencePolicy.reduce(installed, GlassesAppInstallState.Resolving)
        installed = GlassesAppPresencePolicy.reduce(installed, GlassesAppInstallState.Installing)
        installed = GlassesAppPresencePolicy.reduce(
            installed,
            GlassesAppInstallState.Error("Connection lost", GlassesAppRetry.INSTALL),
        )

        assertTrue(installed)
        assertFalse(GlassesAppPresencePolicy.reduce(installed, GlassesAppInstallState.NotInstalled))
    }
}
