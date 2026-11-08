package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Launcher bundle v7: a row's own `mono1` mark, drawn left of its label. */
class RowIconContractTest {
    private val mark = LauncherArtwork(16, 16, Base64.getEncoder().encodeToString(ByteArray(32) { 0x3C }))
    private val large = LauncherArtwork(48, 16, Base64.getEncoder().encodeToString(ByteArray(96) { 0x3C }))
    private val photo = jpeg(16, 16)

    private fun list(icon: LauncherArtwork? = mark) = PageBody.SelectableList(
        listOf(
            PageRow("wa", "WhatsApp", sub = "3 conversations", target = PageTarget.Page("meta:service:wa"), icon = icon),
            PageRow("ig", "Instagram", target = PageTarget.Page("meta:service:ig")),
        ),
    )

    private fun decode(json: JSONObject, version: Int = LauncherInteractiveContract.ROW_ICONS_VERSION) =
        PageTemplateContract.decode(PageSurfaceContract.TEMPLATE_SELECTABLE_LIST, json.toString(), emptyList<PageSurfaceAction>(), "meta", launcherVersion = version)

    private fun rows(result: PageSurfaceValidationResult<PageBody>): List<PageRow> =
        ((result as? PageSurfaceValidationResult.Valid)?.value ?: error("invalid: ${(result as PageSurfaceValidationResult.Invalid).reason}")).items

    @Test
    fun `a row icon round trips at bundle v7 and only where it was sent`() {
        val encoded = PageTemplateContract.encode(list())
        val items = encoded.getJSONArray("items")
        assertEquals("mono1", items.getJSONObject(0).getJSONObject("icon").getString("encoding"))
        assertFalse(items.getJSONObject(1).has("icon"))
        val decoded = rows(decode(encoded))
        assertEquals(mark, decoded[0].icon)
        assertNull(decoded[1].icon)
        assertEquals(list(), PageBody.SelectableList(decoded))
    }

    @Test
    fun `below v7 the icon is ignored as an unknown field`() {
        val decoded = rows(decode(PageTemplateContract.encode(list()), version = LauncherInteractiveContract.IMAGE_PAGES_VERSION))
        assertNull(decoded[0].icon)
        assertEquals("WhatsApp", decoded[0].label)
        // A malformed icon is no concern of a pair that never reads it.
        val broken = PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("icon", "nope") }
        assertNull(rows(decode(broken, version = LauncherInteractiveContract.IMAGE_PAGES_VERSION))[0].icon)
    }

    @Test
    fun `at v7 a malformed oversized or jpeg icon makes the page invalid`() {
        fun invalid(json: JSONObject) = assertTrue(json.toString(), decode(json) is PageSurfaceValidationResult.Invalid)
        invalid(PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("icon", "nope") })
        invalid(PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("icon", JSONObject().put("encoding", "mono1")) })
        invalid(PageTemplateContract.encode(list(large)))
        invalid(PageTemplateContract.encode(list(photo)))
        // An explicit null is no icon.
        val cleared = PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("icon", JSONObject.NULL) }
        assertNull(rows(decode(cleared))[0].icon)
    }

    @Test
    fun `stripping removes every row icon below v7 and nothing else`() {
        val body = PageTemplateContract.encode(list())
        PageTemplateContract.stripRowIcons(body, LauncherInteractiveContract.ROW_ICONS_VERSION)
        assertTrue(body.getJSONArray("items").getJSONObject(0).has("icon"))
        PageTemplateContract.stripRowIcons(body, LauncherInteractiveContract.IMAGE_PAGES_VERSION)
        assertFalse(body.getJSONArray("items").getJSONObject(0).has("icon"))
        assertEquals("3 conversations", body.getJSONArray("items").getJSONObject(0).getString("sub"))
        // A body without rows is left alone.
        val document = JSONObject().put("segments", JSONArray())
        PageTemplateContract.stripRowIcons(document, 0)
        assertEquals(1, document.length())
    }

    @Test
    fun `the bundle version announces row icons`() {
        assertEquals(7, LauncherInteractiveContract.ROW_ICONS_VERSION)
        assertEquals(LauncherInteractiveContract.ROW_ICONS_VERSION + 1, LauncherInteractiveContract.FAVOURITES_VERSION)
        assertEquals(32, PageTemplateContract.MAX_ROW_ICON_EDGE)
    }

    /** A JPEG header the contract reads for its dimensions, with filler for a body. */
    private fun jpeg(width: Int, height: Int): LauncherArtwork {
        val header = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 17, 8,
            (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
            3, 1, 17, 0, 2, 17, 0, 3, 17, 0,
        )
        return LauncherArtwork(width, height, Base64.getEncoder().encodeToString(header + ByteArray(32) + byteArrayOf(0xff.toByte(), 0xd9.toByte())), LauncherArtwork.JPEG)
    }
}
