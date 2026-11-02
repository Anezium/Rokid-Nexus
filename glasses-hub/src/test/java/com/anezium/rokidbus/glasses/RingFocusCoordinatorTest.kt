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
    fun `notice ownership joins the focus union without duplicate edges`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setNoticeOwnsRing(true)
        coordinator.setNoticeOwnsRing(true)
        coordinator.setSurfaceActive(true)
        coordinator.setNoticeOwnsRing(false)
        coordinator.setSurfaceActive(false)

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `the launcher page holds the ring until it leaves the front or a reset`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setLauncherPageShown(true)
        coordinator.setSurfaceActive(true)
        coordinator.setSurfaceActive(false)
        coordinator.setLauncherPageShown(false)
        coordinator.setLauncherPageShown(true)
        coordinator.reset()

        assertEquals(listOf(true, false, true, false), published)
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
    fun `reset clears notice ownership and releases focus exactly once`() {
        val published = mutableListOf<Boolean>()
        val coordinator = RingFocusCoordinator { published += it }

        coordinator.setNoticeOwnsRing(true)
        coordinator.reset()
        coordinator.reset()

        assertEquals(listOf(true, false), published)
    }
}
