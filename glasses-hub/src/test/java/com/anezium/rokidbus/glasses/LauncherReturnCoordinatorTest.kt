package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherReturnCoordinatorTest {
    @Test
    fun builtInSurfaceReturnsOnlyOnceAfterMatchingLauncherOpen() {
        val coordinator = LauncherReturnCoordinator()

        coordinator.recordLauncherOpen("feeds")

        assertTrue(coordinator.onSurfaceShown("feeds"))
        assertTrue(coordinator.consumeReturnOnHide("feeds"))
        assertFalse(coordinator.consumeReturnOnHide("feeds"))
    }

    @Test
    fun externalPluginSurfaceMatchesNamespacedSurfaceId() {
        val coordinator = LauncherReturnCoordinator()

        coordinator.recordLauncherOpen("weather")

        assertTrue(coordinator.onSurfaceShown("weather:forecast"))
        assertTrue(coordinator.consumeReturnOnHide("weather:forecast"))
    }

    @Test
    fun automaticAndUnrelatedSurfacesNeverAcquireLauncherReturn() {
        val coordinator = LauncherReturnCoordinator()

        assertFalse(coordinator.onSurfaceShown("lyrics"))
        assertFalse(coordinator.consumeReturnOnHide("lyrics"))

        coordinator.recordLauncherOpen("feeds")
        assertFalse(coordinator.onSurfaceShown("lyrics"))
        assertFalse(coordinator.consumeReturnOnHide("lyrics"))
        assertTrue(coordinator.onSurfaceShown("feeds"))
        assertTrue(coordinator.consumeReturnOnHide("feeds"))
    }

    @Test
    fun reopeningLauncherClearsAnUnclaimedIntent() {
        val coordinator = LauncherReturnCoordinator()

        coordinator.recordLauncherOpen("feeds")
        coordinator.clearPendingLauncherOpen()

        assertFalse(coordinator.onSurfaceShown("feeds"))
        assertFalse(coordinator.consumeReturnOnHide("feeds"))
    }
}
