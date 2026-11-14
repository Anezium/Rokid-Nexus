package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Launcher bundle v9: a row's `section`, the caption that opens a group of rows. */
class RowSectionContractTest {
    private fun list(section: String? = "PINNED") = PageBody.SelectableList(
        listOf(
            PageRow("a", "Claire", target = PageTarget.Page("line:chat:a"), section = section),
            PageRow("b", "Tom", target = PageTarget.Page("line:chat:b")),
            PageRow("c", "Julie", target = PageTarget.Page("line:chat:c"), section = "RECENT"),
        ),
    )

    private fun decode(json: JSONObject, version: Int = LauncherInteractiveContract.ROW_SECTIONS_VERSION) =
        PageTemplateContract.decode(PageSurfaceContract.TEMPLATE_SELECTABLE_LIST, json.toString(), emptyList<PageSurfaceAction>(), "line", launcherVersion = version)

    private fun rows(result: PageSurfaceValidationResult<PageBody>): List<PageRow> =
        ((result as? PageSurfaceValidationResult.Valid)?.value ?: error("invalid: ${(result as PageSurfaceValidationResult.Invalid).reason}")).items

    @Test
    fun `a row section round trips at bundle v9 and only where it was sent`() {
        val encoded = PageTemplateContract.encode(list())
        val items = encoded.getJSONArray("items")
        assertEquals("PINNED", items.getJSONObject(0).getString("section"))
        assertFalse(items.getJSONObject(1).has("section"))
        val decoded = rows(decode(encoded))
        assertEquals(listOf("PINNED", null, "RECENT"), decoded.map { it.section })
        assertEquals(list(), PageBody.SelectableList(decoded))
    }

    @Test
    fun `below v9 the section is ignored as an unknown field and the order kept`() {
        val decoded = rows(decode(PageTemplateContract.encode(list()), version = LauncherInteractiveContract.FAVOURITES_VERSION))
        assertTrue(decoded.all { it.section == null })
        assertEquals(listOf("Claire", "Tom", "Julie"), decoded.map { it.label })
        // A malformed section is no concern of a pair that never reads it.
        val broken = PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("section", 7) }
        assertNull(rows(decode(broken, version = LauncherInteractiveContract.FAVOURITES_VERSION))[0].section)
    }

    @Test
    fun `at v9 a section that is not a short string makes the page invalid`() {
        fun invalid(json: JSONObject) = assertTrue(json.toString(), decode(json) is PageSurfaceValidationResult.Invalid)
        invalid(PageTemplateContract.encode(list()).apply { getJSONArray("items").getJSONObject(0).put("section", 7) })
        invalid(PageTemplateContract.encode(list("x".repeat(PageTemplateContract.MAX_SECTION_CHARS + 1))))
        assertEquals("x".repeat(PageTemplateContract.MAX_SECTION_CHARS),
            rows(decode(PageTemplateContract.encode(list("x".repeat(PageTemplateContract.MAX_SECTION_CHARS)))))[0].section)
    }

    @Test
    fun `stripping removes every row section below v9 and nothing else`() {
        val body = PageTemplateContract.encode(list())
        PageTemplateContract.stripRowSections(body, LauncherInteractiveContract.ROW_SECTIONS_VERSION)
        assertTrue(body.getJSONArray("items").getJSONObject(0).has("section"))
        PageTemplateContract.stripRowSections(body, LauncherInteractiveContract.FAVOURITES_VERSION)
        assertFalse(body.getJSONArray("items").getJSONObject(0).has("section"))
        assertFalse(body.getJSONArray("items").getJSONObject(2).has("section"))
        assertEquals("Claire", body.getJSONArray("items").getJSONObject(0).getString("label"))
        // A body without rows is left alone.
        val document = JSONObject().put("segments", JSONArray())
        PageTemplateContract.stripRowSections(document, 0)
        assertEquals(1, document.length())
    }

    @Test
    fun `the bundle version announces row sections`() {
        assertEquals(9, LauncherInteractiveContract.ROW_SECTIONS_VERSION)
        assertEquals(LauncherInteractiveContract.ROW_SECTIONS_VERSION, LauncherInteractiveContract.VERSION)
    }
}
