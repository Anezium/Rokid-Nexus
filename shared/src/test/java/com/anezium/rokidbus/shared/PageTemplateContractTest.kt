package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTemplateContractTest {
    @Test
    fun `the four shipped bodies round trip through encode and decode`() {
        val bodies = listOf(
            PageBody.Summary(
                primary = "Turn right",
                secondary = "Rue de Rivoli",
                text = "Then continue straight\nfor two blocks.",
                facts = listOf(PageFact("distance", "Distance", "300 m"), PageFact("eta", "ETA", "12:41")),
                items = listOf(PageRow("commands", "Commands", target = PageTarget.Page("nav:commands"))),
            ),
            PageBody.SelectableList(
                items = listOf(
                    PageRow("t1", "Mika", sub = "2 messages", target = PageTarget.Page("relay:thread", """{"thread":"a1"}""")),
                    PageRow("t2", "Off", enabled = false),
                ),
                emptyText = "No threads",
            ),
            PageBody.Document(
                segments = listOf(
                    PageSegment("h", PageSegmentKind.HEADER, "Mika"),
                    PageSegment("p", PageSegmentKind.PROSE, "Línea uno\nстрока два 🙂"),
                    PageSegment("a", PageSegmentKind.ASIDE, "Captured 12:40"),
                ),
                items = listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open"))),
                footer = "End of thread",
            ),
            PageBody.Commands(items = listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open"))), text = "Choose"),
        )
        val namespaces = listOf("nav", "relay", "relay", "nav")
        bodies.zip(namespaces).forEach { (body, namespace) ->
            val encoded = PageTemplateContract.encode(body).toString()
            assertEquals(body, valid(PageTemplateContract.decode(body.template, encoded, setOf("open"), namespace)))
        }
    }

    @Test
    fun `enabled defaults true and unknown harmless fields are ignored`() {
        val body = JSONObject()
            .put("items", JSONArray().put(JSONObject().put("id", "a").put("label", "A").put("glow", 3)))
            .put("future", JSONObject().put("x", 1))
        val decoded = valid(PageTemplateContract.decode("selectableList", body.toString(), emptySet(), "nav")) as PageBody.SelectableList
        assertTrue(decoded.items.single().enabled)
        assertNull(decoded.items.single().target)
    }

    @Test
    fun `reserved templates stay valid envelopes but are unavailable at this renderer`() {
        listOf("conversation", "media", "route", "ink").forEach { template ->
            assertTrue(template in PageSurfaceContract.templates)
            assertFalse(PageTemplateContract.isSupported(template))
            invalid(PageTemplateContract.decode(template, "{}", emptySet(), "nav"))
            val envelope = PageSurfaceContract.correlationPayload(PageCorrelation("r", 1, 1, "nav:main"))
                .put("revision", 1).put("template", template).put("title", "T").put("body", JSONObject())
                .put("actions", JSONArray()).put("live", false)
            assertTrue(PageSurfaceContract.validateResponse(envelope) is PageSurfaceValidationResult.Valid)
        }
        assertEquals(setOf("summary", "selectableList", "document", "commands"), PageTemplateContract.supportedTemplates)
    }

    @Test
    fun `required arrays wrong types and malformed json are invalid without throwing`() {
        listOf(
            "selectableList" to "{}",
            "commands" to "{}",
            "document" to "{}",
            "document" to """{"segments":{}}""",
            "selectableList" to """{"items":[1]}""",
            "selectableList" to """{"items":[{"id":"a"}]}""",
            "selectableList" to """{"items":[{"id":"a","label":7}]}""",
            "selectableList" to """{"items":[{"id":"a b","label":"A"}]}""",
            "selectableList" to """{"items":[{"id":"a","label":"A","enabled":"no"}]}""",
            "summary" to """{"primary":1}""",
            "summary" to """{"facts":[{"id":"f","label":"F"}]}""",
            "document" to """{"segments":[{"id":"s","kind":"title","text":"x"}]}""",
            "document" to """{"segments":[{"id":"s","kind":"prose"}]}""",
            "summary" to "not json",
            "summary" to """{"items":"x"}""",
        ).forEach { (template, body) -> invalid(PageTemplateContract.decode(template, body, emptySet(), "nav")) }
    }

    @Test
    fun `item fact and segment ids are unique within their array`() {
        invalid(PageTemplateContract.decode("selectableList", """{"items":[{"id":"a","label":"A"},{"id":"a","label":"B"}]}""", emptySet(), "nav"))
        invalid(PageTemplateContract.decode("summary", """{"facts":[{"id":"f","label":"A","value":"1"},{"id":"f","label":"B","value":"2"}]}""", emptySet(), "nav"))
        invalid(PageTemplateContract.decode("document", """{"segments":[{"id":"s","kind":"prose","text":"a"},{"id":"s","kind":"prose","text":"b"}]}""", emptySet(), "nav"))
        // The same id may appear once in each array.
        valid(PageTemplateContract.decode("summary", """{"facts":[{"id":"x","label":"A","value":"1"}],"items":[{"id":"x","label":"B"}]}""", emptySet(), "nav"))
    }

    @Test
    fun `a target is exactly one page or one declared action`() {
        fun list(target: JSONObject) = JSONObject()
            .put("items", JSONArray().put(JSONObject().put("id", "a").put("label", "A").put("target", target))).toString()
        invalid(PageTemplateContract.decode("selectableList", list(JSONObject()), setOf("go"), "nav"))
        invalid(PageTemplateContract.decode("selectableList", list(JSONObject().put("pageId", "nav:x").put("actionId", "go")), setOf("go"), "nav"))
        invalid(PageTemplateContract.decode("selectableList", list(JSONObject().put("actionId", "missing")), setOf("go"), "nav"))
        invalid(PageTemplateContract.decode("selectableList", list(JSONObject().put("actionId", "go").put("params", JSONObject())), setOf("go"), "nav"))
        val action = valid(PageTemplateContract.decode("selectableList", list(JSONObject().put("actionId", "go")), setOf("go"), "nav"))
        assertEquals(PageTarget.Action("go"), action.items.single().target)
        invalid(PageTemplateContract.decode("selectableList", """{"items":[{"id":"a","label":"A","target":"nav:x"}]}""", emptySet(), "nav"))
    }

    @Test
    fun `navigation stays in the provider namespace while local pages may target providers`() {
        fun list(pageId: String) = JSONObject()
            .put("items", JSONArray().put(JSONObject().put("id", "a").put("label", "A").put("target", JSONObject().put("pageId", pageId)))).toString()
        valid(PageTemplateContract.decode("selectableList", list("nav:commands"), emptySet(), "nav"))
        listOf("relay:main", "nexus:applications", "navx:main", "nav", "nav:", ":main").forEach {
            invalid(PageTemplateContract.decode("selectableList", list(it), emptySet(), "nav"))
        }
        // A hub-authored local page names a provider the catalog authorizes separately.
        valid(PageTemplateContract.decode("selectableList", list("relay:main"), emptySet(), null))
        valid(PageTemplateContract.decode("selectableList", list("nexus:notifications"), emptySet(), null))
        invalid(PageTemplateContract.decode("selectableList", list("no-namespace"), emptySet(), null))
    }

    @Test
    fun `target params are bounded by serialized UTF-8 bytes`() {
        fun body(params: JSONObject) = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().put("id", "a").put("label", "A").put("target", JSONObject().put("pageId", "relay:t").put("params", params))),
        ).toString()
        // {"k":"…"} is 8 bytes of syntax; each 'é' is two UTF-8 bytes.
        val atLimit = JSONObject().put("k", "é".repeat((PageSurfaceContract.MAX_PARAMS_BYTES - 8) / 2))
        assertEquals(PageSurfaceContract.MAX_PARAMS_BYTES, atLimit.toString().toByteArray(Charsets.UTF_8).size)
        valid(PageTemplateContract.decode("selectableList", body(atLimit), emptySet(), "relay"))
        val over = JSONObject().put("k", "é".repeat((PageSurfaceContract.MAX_PARAMS_BYTES - 8) / 2) + "a")
        invalid(PageTemplateContract.decode("selectableList", body(over), emptySet(), "relay"))
        invalid(PageTemplateContract.decode("selectableList", body(JSONObject().put("k", "é".repeat(PageSurfaceContract.MAX_PARAMS_BYTES))), emptySet(), "relay"))
    }

    @Test
    fun `count and length limits sit exactly at their bounds`() {
        fun items(count: Int, label: String = "A") = JSONObject().put(
            "items",
            JSONArray().apply { repeat(count) { put(JSONObject().put("id", "i$it").put("label", label)) } },
        ).toString()
        valid(PageTemplateContract.decode("selectableList", items(PageTemplateContract.MAX_ITEMS), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("selectableList", items(PageTemplateContract.MAX_ITEMS + 1), emptySet(), "nav"))
        valid(PageTemplateContract.decode("selectableList", items(1, "x".repeat(120)), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("selectableList", items(1, "x".repeat(121)), emptySet(), "nav"))

        fun facts(count: Int) = JSONObject().put(
            "facts",
            JSONArray().apply { repeat(count) { put(JSONObject().put("id", "f$it").put("label", "L").put("value", "v")) } },
        ).toString()
        valid(PageTemplateContract.decode("summary", facts(16), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("summary", facts(17), emptySet(), "nav"))

        fun segments(count: Int, text: String = "t") = JSONObject().put(
            "segments",
            JSONArray().apply { repeat(count) { put(JSONObject().put("id", "s$it").put("kind", "prose").put("text", text)) } },
        ).toString()
        valid(PageTemplateContract.decode("document", segments(240), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("document", segments(241), emptySet(), "nav"))
        valid(PageTemplateContract.decode("document", segments(1, "x".repeat(4096)), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("document", segments(1, "x".repeat(4097)), emptySet(), "nav"))

        listOf(
            Triple("primary", 120, "summary"), Triple("secondary", 240, "summary"), Triple("text", 4096, "summary"),
            Triple("footer", 240, "document"), Triple("text", 4096, "commands"), Triple("emptyText", 240, "selectableList"),
        ).forEach { (key, limit, template) ->
            val base = JSONObject().apply {
                if (template == "document") put("segments", JSONArray())
                if (template == "commands" || template == "selectableList") put("items", JSONArray())
            }
            valid(PageTemplateContract.decode(template, JSONObject(base.toString()).put(key, "x".repeat(limit)).toString(), emptySet(), "nav"))
            invalid(PageTemplateContract.decode(template, JSONObject(base.toString()).put(key, "x".repeat(limit + 1)).toString(), emptySet(), "nav"))
        }
        val sub = { length: Int -> """{"items":[{"id":"a","label":"A","sub":"${"x".repeat(length)}"}]}""" }
        valid(PageTemplateContract.decode("selectableList", sub(240), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("selectableList", sub(241), emptySet(), "nav"))
        val value = { length: Int -> """{"facts":[{"id":"f","label":"L","value":"${"x".repeat(length)}"}]}""" }
        valid(PageTemplateContract.decode("summary", value(240), emptySet(), "nav"))
        invalid(PageTemplateContract.decode("summary", value(241), emptySet(), "nav"))
    }

    @Test
    fun `a decoded envelope uses its own declared actions`() {
        val payload = PageSurfaceContract.correlationPayload(PageCorrelation("r", 1, 1, "nav:main"))
            .put("revision", 1).put("template", "commands").put("title", "Commands")
            .put("body", JSONObject().put("items", JSONArray().put(JSONObject().put("id", "o").put("label", "Open").put("target", JSONObject().put("actionId", "open")))))
            .put("actions", JSONArray().put(JSONObject().put("id", "open").put("label", "Open").put("kind", "immersion").put("confirm", false)))
            .put("live", false)
        val page = (PageSurfaceContract.validateResponse(payload) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
        assertEquals(PageTarget.Action("open"), valid(PageTemplateContract.decode(page, "nav")).items.single().target)
        val withoutAction = JSONObject(payload.toString()).put("actions", JSONArray())
        val bare = (PageSurfaceContract.validateResponse(withoutAction) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
        invalid(PageTemplateContract.decode(bare, "nav"))
    }

    @Test
    fun `page namespaces name their owner and reserve the hub's`() {
        assertEquals("nav:main", PageTemplateContract.mainPageId("nav"))
        assertEquals("relay", PageTemplateContract.namespaceOf("relay:thread:a1"))
        assertNull(PageTemplateContract.namespaceOf("relay"))
        assertNull(PageTemplateContract.namespaceOf("relay:"))
        assertNull(PageTemplateContract.namespaceOf(":x"))
        assertNull(PageTemplateContract.namespaceOf("relay:" + "x".repeat(124)))
        assertTrue(PageTemplateContract.isProviderPageId("relay:main", "relay"))
        assertFalse(PageTemplateContract.isProviderPageId("nav:main", "relay"))
        assertFalse(PageTemplateContract.isProviderPageId("nexus:main", "nexus"))
        assertTrue(PageTemplateContract.isLocalPageId("nexus:applications"))
        assertFalse(PageTemplateContract.isLocalPageId("relay:main"))
    }

    private fun valid(result: PageSurfaceValidationResult<PageBody>): PageBody {
        assertTrue("$result", result is PageSurfaceValidationResult.Valid)
        return (result as PageSurfaceValidationResult.Valid).value
    }

    private fun invalid(result: PageSurfaceValidationResult<PageBody>) {
        assertTrue("$result", result is PageSurfaceValidationResult.Invalid)
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, (result as PageSurfaceValidationResult.Invalid).error)
    }
}
