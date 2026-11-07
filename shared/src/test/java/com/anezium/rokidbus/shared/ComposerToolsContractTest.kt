package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Launcher bundle v5: composer tools, configurable built-in buttons and message thumbnails. */
class ComposerToolsContractTest {
    private val actions = listOf(
        PageSurfaceAction("send", "Send", PageSurfaceContract.KIND_PLUGIN, confirm = false, typed = PageActionType.ComposeSend),
        PageSurfaceAction("photo", "Photo", PageSurfaceContract.KIND_IMMERSION, confirm = false),
        PageSurfaceAction("like", "Like", PageSurfaceContract.KIND_PLUGIN, confirm = false),
        PageSurfaceAction("hub", "Hub", PageSurfaceContract.KIND_HUB, confirm = false),
    )
    private val thumb = LauncherArtwork(8, 8, Base64.getEncoder().encodeToString(ByteArray(8) { 0x55 }))

    private fun composer(
        tools: List<ComposerTool> = listOf(ComposerTool("photo", "Photo", "photo", glyph = "camera"), ComposerTool("like", "Like", "like")),
        layout: List<ComposerSlot>? = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Type, ComposerSlot.Send, ComposerSlot.Tool("like")),
        replyable: Boolean = true,
    ) = ConversationComposer(
        "Mika", "send", 200, replyable,
        tools = tools, layout = layout, dictateLabel = "Speak", typeLabel = "Write", sendLabel = "Go",
    )

    private fun body(composer: ConversationComposer? = composer(), image: LauncherArtwork? = thumb) = PageBody.Conversation(
        threadKey = "t1",
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "Look", sender = "Mika", image = image),
            ConversationMessage("m2", ConversationRole.OURS, "", image = image),
        ),
        composer = composer,
    )

    private fun decode(json: JSONObject, version: Int = LauncherInteractiveContract.COMPOSER_TOOLS_VERSION) =
        PageTemplateContract.decode(PageSurfaceContract.TEMPLATE_CONVERSATION, json.toString(), actions, "meta", launcherVersion = version)

    private fun valid(result: PageSurfaceValidationResult<PageBody>): PageBody.Conversation =
        ((result as? PageSurfaceValidationResult.Valid)?.value ?: error("invalid: ${(result as PageSurfaceValidationResult.Invalid).reason}")) as PageBody.Conversation

    private fun invalid(json: JSONObject, version: Int = LauncherInteractiveContract.COMPOSER_TOOLS_VERSION) {
        assertTrue(json.toString(), decode(json, version) is PageSurfaceValidationResult.Invalid)
    }

    @Test
    fun `tools layout labels and thumbnails round trip at bundle v5`() {
        val body = body()
        val decoded = valid(decode(PageTemplateContract.encode(body)))
        assertEquals(body, decoded)
        assertEquals(listOf(ComposerSlot.Tool("photo"), ComposerSlot.Type, ComposerSlot.Send, ComposerSlot.Tool("like")), decoded.composer!!.slots())
        assertFalse(decoded.composer!!.showsDictate)
        assertTrue(decoded.composer!!.showsType)
        assertEquals("camera", decoded.composer!!.tool("photo")?.glyph)
    }

    @Test
    fun `the default bar is Send Dictate Type then the tools and a layout without Send puts it first`() {
        assertEquals(
            listOf(ComposerSlot.Send, ComposerSlot.Dictate, ComposerSlot.Type, ComposerSlot.Tool("photo"), ComposerSlot.Tool("like")),
            composer(layout = null).slots(),
        )
        assertEquals(
            listOf(ComposerSlot.Send, ComposerSlot.Tool("photo"), ComposerSlot.Tool("like"), ComposerSlot.Dictate),
            composer(layout = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Tool("like"), ComposerSlot.Dictate)).slots(),
        )
    }

    @Test
    fun `a pair below v5 ignores the new fields and keeps the default bar`() {
        val json = PageTemplateContract.encode(body())
        val decoded = valid(decode(json, version = 4))
        assertEquals(ConversationComposer("Mika", "send", 200, true), decoded.composer)
        assertTrue(decoded.messages.all { it.image == null })
        // The boolean form means this build's whole bundle.
        assertEquals(body(), valid(PageTemplateContract.decode(PageSurfaceContract.TEMPLATE_CONVERSATION, json.toString(), actions, "meta", extended = true)))
    }

    @Test
    fun `stripping for an older peer removes exactly the v5 fields`() {
        val json = PageTemplateContract.encode(body())
        val kept = JSONObject(json.toString())
        PageTemplateContract.stripConversationExtras(kept, 5)
        assertEquals(json.toString(), kept.toString())
        PageTemplateContract.stripConversationExtras(json, 4)
        val composer = json.getJSONObject("composer")
        listOf("tools", "layout", "dictateLabel", "typeLabel", "sendLabel").forEach { assertFalse(it, composer.has(it)) }
        assertEquals("Mika", composer.getString("recipient"))
        val messages = json.getJSONArray("messages")
        for (index in 0 until messages.length()) assertFalse(messages.getJSONObject(index).has("image"))
        assertEquals("Look", messages.getJSONObject(0).getString("text"))
    }

    @Test
    fun `a tool names a plugin or immersion action of the page and never the send`() {
        fun with(tool: ComposerTool) = PageTemplateContract.encode(body(composer(tools = listOf(tool), layout = null)))
        valid(decode(with(ComposerTool("a", "Like", "like"))))
        valid(decode(with(ComposerTool("a", "Photo", "photo"))))
        invalid(with(ComposerTool("a", "Send", "send")))
        invalid(with(ComposerTool("a", "Hub", "hub")))
        invalid(with(ComposerTool("a", "Ghost", "missing")))
        invalid(with(ComposerTool("send", "Again", "like")))
        invalid(with(ComposerTool("type", "Again", "like")))
        invalid(with(ComposerTool("a", "", "like")))
        invalid(with(ComposerTool("a", "A label too long", "like")))
        invalid(with(ComposerTool("a", "Like", "like", glyph = "Camera!")))
        // Kinds unknown to the caller pass as plugin actions; the type still refuses a send.
        val idsOnly = PageTemplateContract.decode(
            PageSurfaceContract.TEMPLATE_CONVERSATION, with(ComposerTool("a", "Photo", "photo")).toString(), setOf("send", "photo", "like"), "meta", extended = true,
        )
        assertTrue(idsOnly is PageSurfaceValidationResult.Invalid) // no types known: the composer's send cannot be checked
    }

    @Test
    fun `at most three tools with unique ids`() {
        val three = (1..3).map { ComposerTool("t$it", "T$it", "like") }
        valid(decode(PageTemplateContract.encode(body(composer(tools = three, layout = null)))))
        invalid(PageTemplateContract.encode(body(composer(tools = three + ComposerTool("t4", "T4", "like"), layout = null))))
        val json = PageTemplateContract.encode(body(composer(tools = listOf(ComposerTool("t", "T", "like"), ComposerTool("t", "U", "like")), layout = null)))
        invalid(json)
    }

    @Test
    fun `a layout names every tool once and only known slots`() {
        val tools = listOf(ComposerTool("photo", "Photo", "photo"))
        fun with(layout: JSONArray) = PageTemplateContract.encode(body(composer(tools = tools, layout = null))).apply {
            getJSONObject("composer").put("layout", layout)
        }
        valid(decode(with(JSONArray().put("photo").put("dictate"))))
        invalid(with(JSONArray().put("dictate").put("type")))
        invalid(with(JSONArray().put("photo").put("photo").put("dictate")))
        invalid(with(JSONArray().put("photo").put("dictate").put("dictate")))
        invalid(with(JSONArray().put("photo").put("dictate").put("video")))
        invalid(with(JSONArray().put("photo").put(3)))
    }

    @Test
    fun `a replyable composer keeps Dictate or Type and a read-only one may hide both`() {
        val tools = listOf(ComposerTool("photo", "Photo", "photo"))
        invalid(PageTemplateContract.encode(body(composer(tools = tools, layout = listOf(ComposerSlot.Tool("photo"))))))
        invalid(PageTemplateContract.encode(body(composer(tools = tools, layout = listOf(ComposerSlot.Send, ComposerSlot.Tool("photo"))))))
        valid(decode(PageTemplateContract.encode(body(composer(tools = tools, layout = listOf(ComposerSlot.Tool("photo")), replyable = false)))))
        valid(decode(PageTemplateContract.encode(body(composer(tools = tools, layout = listOf(ComposerSlot.Dictate, ComposerSlot.Tool("photo")))))))
    }

    @Test
    fun `built-in labels are bounded and nonblank`() {
        fun with(key: String, value: String) = PageTemplateContract.encode(body(composer(layout = null))).apply {
            getJSONObject("composer").put(key, value)
        }
        valid(decode(with("sendLabel", "Send to Mika")))
        invalid(with("sendLabel", "Send to Mika!"))
        invalid(with("dictateLabel", " "))
        invalid(with("typeLabel", "x".repeat(13)))
    }

    @Test
    fun `thumbnails are bounded one by one and together`() {
        fun jpeg(width: Int, height: Int, bytes: Int): LauncherArtwork {
            val header = byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 17, 8,
                (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
                3, 1, 17, 0, 2, 17, 0, 3, 17, 0,
            )
            val filler = ByteArray(bytes - header.size - 2)
            return LauncherArtwork(width, height, Base64.getEncoder().encodeToString(header + filler + byteArrayOf(0xff.toByte(), 0xd9.toByte())), LauncherArtwork.JPEG)
        }
        fun page(vararg images: LauncherArtwork) = PageTemplateContract.encode(
            PageBody.Conversation(
                "t1",
                images.mapIndexed { index, image -> ConversationMessage("m$index", ConversationRole.RECEIVED, "", image = image) },
                composer = null,
            ),
        )
        val fine = jpeg(160, 120, 12_288)
        assertEquals(fine, valid(decode(page(fine))).messages.single().image)
        invalid(page(jpeg(161, 120, 4_096)))
        invalid(page(jpeg(120, 161, 4_096)))
        invalid(page(jpeg(160, 120, 12_289)))
        valid(decode(page(jpeg(100, 100, 8_192), jpeg(100, 100, 8_192), jpeg(100, 100, 8_192), jpeg(100, 100, 8_192))))
        invalid(page(fine, fine, fine, fine, jpeg(10, 10, 100)))
        invalid(page(fine, fine, fine))
        // A malformed image is a malformed page, never a missing one.
        val malformed = page(fine).apply { getJSONArray("messages").getJSONObject(0).put("image", JSONObject().put("uri", "content://x")) }
        invalid(malformed)
        // Mono1 keeps its own rules and counts the same way.
        assertEquals(thumb, valid(decode(page(thumb))).messages.single().image)
    }

    @Test
    fun `the open context round trips and fails closed on anything malformed`() {
        val context = ComposerToolContext("meta:thread", """{"thread":"t1"}""", "t1", "photo", "photo")
        assertEquals(context, ComposerToolContract.parse(ComposerToolContract.encode(context)))
        val bare = ComposerToolContext("meta:thread", null, "t1", "photo", "photo")
        assertEquals(bare, ComposerToolContract.parse(ComposerToolContract.encode(bare)))
        assertFalse(ComposerToolContract.encode(bare).has("params"))
        assertTrue(context.isOwnedBy("meta"))
        assertFalse(context.isOwnedBy("relay"))

        assertNull(ComposerToolContract.parse(null))
        assertNull(ComposerToolContract.parse("meta:thread"))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).put("pageId", "nexus:main")))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).put("pageId", "no-namespace")))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).put("toolId", "bad id")))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).apply { remove("threadKey") }))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).put("params", "x")))
        assertNull(ComposerToolContract.parse(ComposerToolContract.encode(context).put("params", JSONObject().put("blob", "x".repeat(2_100)))))
    }

    @Test
    fun `the bundle version announces composer tools`() {
        assertEquals(5, LauncherInteractiveContract.VERSION)
        assertEquals(5, LauncherInteractiveContract.COMPOSER_TOOLS_VERSION)
        assertEquals(4, LauncherInteractiveContract.negotiated(5, 4))
        assertEquals("composer_tool", com.anezium.rokidbus.shared.plugin.PluginOpenTypes.COMPOSER_TOOL)
    }
}
