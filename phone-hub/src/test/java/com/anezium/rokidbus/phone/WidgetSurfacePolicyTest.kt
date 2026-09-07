package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ForegroundSurfacePathPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Internal check that the ambient widget channel never enters the foreground-surface
 * policy. The `SURFACE_BUSY` gate in [BusHubService] rejects only show/update paths that
 * [ForegroundSurfacePathPolicy.isShowOrUpdate] recognizes as foreground; because the
 * widget is ambient it must be routed before that gate and never recognized by it.
 */
class WidgetSurfacePolicyTest {
    @Test
    fun `widget paths are not recognized as foreground by the surface busy gate`() {
        listOf(BusPaths.WIDGET_SHOW, BusPaths.WIDGET_UPDATE, BusPaths.WIDGET_HIDE).forEach { path ->
            assertFalse(path, ForegroundSurfacePathPolicy.isShowOrUpdate(path))
            assertFalse(path, ForegroundSurfacePathPolicy.isShow(path))
        }
    }

    @Test
    fun `the hub assigns the widget a single owned slot like pins`() {
        listOf(BusPaths.WIDGET_SHOW, BusPaths.WIDGET_UPDATE, BusPaths.WIDGET_HIDE).forEach { path ->
            assertTrue(
                path,
                PluginRoutePolicy.authorize(
                    PluginRouteCaller.Plugin("lyrics", setOf(com.anezium.rokidbus.shared.plugin.PluginCapability.SURFACES)),
                    path,
                ) is PluginRouteDecision.Allowed,
            )
        }
    }
}