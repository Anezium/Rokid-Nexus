package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Contract decoding only: a row's own mark (bundle v7) reaches the typed row where the pair reads it. */
class RowIconDecodeTest {
    @Test fun `negotiated v7 carries a row icon onto its typed row and older pairs draw the plain row`() {
        var version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 6)
        val items = TypedPageItems({ false }, { true }, extended = { true }, launcherVersion = { version })
        val mark = LauncherArtwork(16, 16, Base64.getEncoder().encodeToString(ByteArray(32) { 0x3C }))
        val body = PageBody.SelectableList(
            listOf(
                PageRow("wa", "WhatsApp", target = PageTarget.Page("meta:service:wa"), icon = mark),
                PageRow("ig", "Instagram", target = PageTarget.Page("meta:service:ig")),
            ),
        )
        val page = PageSurfaceResponse.Page(PageCorrelation("r1", 1, 1, "meta:main"), 1, "selectableList", "Messages",
            PageTemplateContract.encode(body).toString(), emptyList(), true, "{}", 0)
        assertEquals(listOf(null, null), items.rows(page).map { it.icon })
        version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 7)
        assertEquals(listOf(mark, null), items.rows(page).map { it.icon })
        assertEquals(listOf("WhatsApp", "Instagram"), items.rows(page).map { it.label })
    }
}
