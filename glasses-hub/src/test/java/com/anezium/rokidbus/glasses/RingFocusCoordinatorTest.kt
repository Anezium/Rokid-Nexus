package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class RingFocusCoordinatorTest {
    @Test
    fun `launcher and surface changes publish only union edges`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setLauncherShown(true)
        coordinator.setLauncherShown(true)
        coordinator.setSurfaceActive(true)
        coordinator.setLauncherShown(false)
        coordinator.setSurfaceActive(false)
        coordinator.setSurfaceActive(false)

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `launcher to matching surface handoff has no false blip`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setLauncherShown(true)
        coordinator.beginSurfaceHandoff()
        coordinator.setLauncherShown(false)
        coordinator.setSurfaceActive(true, completesHandoff = true)
        coordinator.setSurfaceActive(false)

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `outgoing surface updates do not complete a pending handoff`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setLauncherShown(true)
        coordinator.setSurfaceActive(true)
        coordinator.beginSurfaceHandoff()
        coordinator.setLauncherShown(false)
        coordinator.setSurfaceActive(true, completesHandoff = false)
        coordinator.setSurfaceActive(false)

        assertEquals(listOf(true), published)

        coordinator.setSurfaceActive(true, completesHandoff = true)
        coordinator.setSurfaceActive(false)

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `failed surface handoff expires and releases focus`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setLauncherShown(true)
        coordinator.beginSurfaceHandoff()
        coordinator.setLauncherShown(false)
        coordinator.expireSurfaceHandoff()
        coordinator.expireSurfaceHandoff()

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `reset releases active focus exactly once`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setSurfaceActive(true)
        coordinator.reset()
        coordinator.reset()

        assertEquals(listOf(true, false), published)
    }
}
