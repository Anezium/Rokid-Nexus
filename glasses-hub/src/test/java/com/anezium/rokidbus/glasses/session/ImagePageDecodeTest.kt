package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Contract decoding only: image layout belongs to the launcher renderer. */
class ImagePageDecodeTest {
    @Test fun `negotiated v6 decodes image pages and keeps photo actions separate from composer send`() {
        var version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 5)
        val items = TypedPageItems({ false }, { true }, extended = { true }, launcherVersion = { version })
        val image = LauncherArtwork(512, 512, Base64.getEncoder().encodeToString(ByteArray(32_768)))
        val body = PageBody.Image(image, "Preview")
        val page = PageSurfaceResponse.Page(PageCorrelation("r1", 1, 1, "photo:main"), 1, "image", "Photo",
            PageTemplateContract.encode(body).toString(), listOf("send-photo", "retake", "cancel").map {
                PageSurfaceAction(it, it, "plugin", true)
            }, true, "{}", 0)
        assertNull(items.body(page))
        version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 6)
        assertEquals(body, items.body(page))
        assertEquals(listOf("send-photo", "retake", "cancel"), items.rows(page).map { it.actionId })
        assertTrue(items.rows(page).all { it.item is PageItem.PluginAction })
    }
}
