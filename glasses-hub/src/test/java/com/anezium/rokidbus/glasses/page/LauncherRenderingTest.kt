package com.anezium.rokidbus.glasses.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.glasses.session.Bubble
import com.anezium.rokidbus.glasses.session.BubbleSide
import com.anezium.rokidbus.glasses.session.BubbleStyle
import com.anezium.rokidbus.glasses.session.CommandSlot
import com.anezium.rokidbus.glasses.session.CommandStrip
import com.anezium.rokidbus.glasses.session.DocumentAnchor
import com.anezium.rokidbus.glasses.session.FooterButton
import com.anezium.rokidbus.glasses.session.FrameIdentity
import com.anezium.rokidbus.glasses.session.IconPlace
import com.anezium.rokidbus.glasses.session.IconRef
import com.anezium.rokidbus.glasses.session.InlineFooter
import com.anezium.rokidbus.glasses.session.MapSize
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.glasses.session.ScreenBlock
import com.anezium.rokidbus.glasses.session.ScreenHeader
import com.anezium.rokidbus.glasses.session.ScreenHint
import com.anezium.rokidbus.glasses.session.SessionEvent
import com.anezium.rokidbus.glasses.session.SessionScreen
import com.anezium.rokidbus.glasses.session.StatusLineStyle
import com.anezium.rokidbus.shared.MediaPlaybackState
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import com.anezium.rokidbus.shared.RouteRibbonBetween
import com.anezium.rokidbus.shared.RouteRibbonStop
import com.anezium.rokidbus.shared.RouteStopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The launcher's blocks, footer, icons and maps drawn by real Views at the HUD's 480x640 pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherRenderingTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Every icon resolves to one stroke, so slots are filled without the hub's resources. */
    private val icons = SessionIconSource { _, _ -> GlyphDrawable("M4,12 h16") }
    private val view = SessionScreenView(context, icons)
    private val paged = mutableListOf<SessionEvent.DocumentPaged>()
    private val frame = FrameIdentity(1, 0)

    init {
        view.onDocumentPaged = { paged += it }
    }

    private fun render(screen: SessionScreen) {
        view.show(screen)
        layout()
    }

    private fun layout() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PageStyle.WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PageStyle.HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PageStyle.WIDTH_PX, PageStyle.HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun draw(target: View): Bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }

    private fun visibleInViewport(key: String): Boolean {
        val block = view.blockViews.getValue(key)
        return block.top >= 0 && block.bottom <= view.viewport.height
    }

    private fun <T : View> find(root: View, type: Class<T>): List<T> = when {
        type.isInstance(root) -> listOf(type.cast(root)!!)
        root is ViewGroup -> (0 until root.childCount).flatMap { find(root.getChildAt(it), type) }
        else -> emptyList()
    }

    private inline fun <reified T : View> find(root: View): List<T> = find(root, T::class.java)

    private fun texts(root: View): List<String> = find<TextView>(root).map { it.text.toString() }

    private fun conversationScreen(
        bubbles: List<Bubble>,
        anchor: DocumentAnchor? = null,
        footer: InlineFooter? = InlineFooter.Buttons(listOf(FooterButton("compose dictate", "Dictate", "mic"), FooterButton("compose type", "Type", "keyboard"))),
    ) = SessionScreen(
        ScreenHeader("RELAY · MIKA", null, IconRef.Plugin("relay")),
        listOf(ScreenBlock.Conversation("conversation", "Messages · captured 14:11", bubbles, frame = frame, pageId = "relay:thread", revision = 1, anchor = anchor)),
        ScreenHint("tap reply", "back root"),
        focusKey = "conversation",
        footer = footer,
    )

    private fun card(key: String, selected: Boolean = false, ended: Boolean = false, icon: IconRef? = IconRef.Plugin("nav"), commands: CommandStrip? = null) =
        ScreenBlock.Card(key, "NAVIGATION", "14:09", "120 m", "Rue de Rivoli", "1.2 km", figure = true, ended = ended, selected = selected, icon = icon, commands = commands)

    @Test
    fun `the composer footer sits outside the viewport and above the hint, with two or three fixed buttons`() {
        render(conversationScreen(listOf(Bubble("m:1", BubbleSide.LEFT, "tu arrives quand ?", latest = true))))
        val footer = view.footer
        assertEquals(View.VISIBLE, footer.visibility)
        assertEquals(PageStyle.FOOTER_HEIGHT_PX, footer.height)
        assertEquals(view.viewport.bottom + PageStyle.FOOTER_TOP_GAP_PX, footer.top)
        val hintLine = view.getChildAt(view.childCount - 2)
        assertEquals(footer.bottom + PageStyle.FOOTER_BOTTOM_GAP_PX, hintLine.top)
        assertEquals(listOf(217, 217), footer.buttonViews.map { it.width })
        // In reading, no button is selected.
        assertTrue(footer.buttonViews.none { (it.tag as InlineFooterView.FooterTag).selected })

        val three = InlineFooter.Buttons(
            listOf(
                FooterButton("compose send", "Send", "send", selected = true),
                FooterButton("compose dictate", "Dictate", "mic"),
                FooterButton("compose type", "Type", "keyboard"),
            ),
        )
        render(conversationScreen(listOf(Bubble("m:1", BubbleSide.LEFT, "ok")), footer = three))
        assertTrue(footer.buttonViews.all { it.width in 141..142 })
        assertEquals("compose send", footer.buttonViews.single { (it.tag as InlineFooterView.FooterTag).selected }.let { (it.tag as InlineFooterView.FooterTag).key })
        // Each button draws its glyph and its label.
        assertTrue(footer.buttonViews.all { find<ImageView>(it).size == 1 && find<TextView>(it).size == 1 })

        // Without a footer the viewport takes its room back.
        render(conversationScreen(listOf(Bubble("m:1", BubbleSide.LEFT, "ok")), footer = null))
        assertEquals(View.GONE, footer.visibility)
        assertEquals(hintLine.top, view.viewport.bottom)
    }

    @Test
    fun `a capture line draws its glyph and an unavailable line never does`() {
        val capture = InlineFooter.StatusLine("Glasses mic", "listening", glyph = "mic")
        render(conversationScreen(listOf(Bubble("live", BubbleSide.RIGHT, "dans dix", style = BubbleStyle.LIVE)), footer = capture))
        val captureLine = requireNotNull(view.footer.statusView)
        assertEquals(InlineFooterView.STATUS_CAPTURE, (captureLine.tag as InlineFooterView.FooterTag).key)
        assertEquals(1, find<ImageView>(captureLine).size)
        assertEquals(listOf("Glasses mic", "listening"), texts(captureLine))

        val off = InlineFooter.StatusLine("Glasses mic unavailable", "use Type", glyph = "mic", style = StatusLineStyle.OFF)
        render(conversationScreen(listOf(Bubble("m:1", BubbleSide.LEFT, "ok")), footer = off))
        val offLine = requireNotNull(view.footer.statusView)
        assertEquals(InlineFooterView.STATUS_OFF, (offLine.tag as InlineFooterView.FooterTag).key)
        assertTrue(find<ImageView>(offLine).isEmpty())
        assertTrue(find<TextView>(offLine).all { it.currentTextColor == PageStyle.muted })
    }

    @Test
    fun `a conversation draws each side as r17 does and sits against the footer`() {
        val bubbles = listOf(
            Bubble("m:1", BubbleSide.LEFT, "Salut"),
            Bubble("m:2", BubbleSide.RIGHT, "Oui ?"),
            Bubble("m:3", BubbleSide.NONE, "A message whose author is not known"),
            Bubble("m:4", BubbleSide.LEFT, "tu arrives quand ?", latest = true),
            Bubble("draft", BubbleSide.RIGHT, "dans 10 min", style = BubbleStyle.DASHED, meta = "draft · not sent yet"),
        )
        render(conversationScreen(bubbles))
        val conversation = view.blockViews.getValue("conversation") as ConversationView
        draw(conversation)
        val drawn = conversation.drawn.associateBy { it.key }
        val width = conversation.width.toFloat()
        assertEquals(bubbles.map { it.key }, conversation.drawn.map { it.key })

        assertTrue(drawn.getValue("m:1").rect.left < 2f)
        assertTrue(drawn.getValue("m:1").rect.width() <= width * ConversationView.MAX_WIDTH_FRACTION)
        assertTrue(drawn.getValue("m:2").rect.right > width - 2f)
        assertTrue(drawn.getValue("m:2").rect.width() <= width * ConversationView.MAX_WIDTH_FRACTION)
        assertTrue(drawn.getValue("m:3").rect.width() >= width - 2f)

        assertEquals(PageStyle.outline, drawn.getValue("m:1").outlineColor)
        assertEquals(PageStyle.dim, drawn.getValue("m:2").outlineColor)
        assertEquals(PageStyle.phosphor, drawn.getValue("draft").outlineColor)
        assertEquals(PageStyle.ink, drawn.getValue("m:1").textColor)
        assertEquals(PageStyle.phosphor, drawn.getValue("m:4").textColor)

        // Oldest on top, newest at the bottom, the whole stack against the footer.
        val tops = conversation.drawn.map { it.rect.top }
        assertEquals(tops.sorted(), tops)
        val meta = 4 + PageStyle.CAPTION_PX * 2
        assertTrue(conversation.height - drawn.getValue("draft").rect.bottom <= meta)
        assertTrue(drawn.getValue("m:1").rect.top > PageStyle.CAPTION_PX * 3)
        assertTrue(conversation.captionShown)
        assertEquals(1, paged.size)
        assertEquals(listOf(DocumentAnchor("m:1", 0)), paged.single().pageStarts)
    }

    @Test
    fun `a long conversation pages by bubble keys and opens on its newest page`() {
        val bubbles = (1..12).map {
            Bubble("m:$it", if (it % 2 == 0) BubbleSide.RIGHT else BubbleSide.LEFT, "Message $it " + "lorem ipsum dolor sit amet ".repeat(4), latest = it == 12)
        } + Bubble("draft", BubbleSide.RIGHT, "on my way", style = BubbleStyle.DASHED, meta = "draft · not sent yet")
        render(conversationScreen(bubbles))
        val conversation = view.blockViews.getValue("conversation") as ConversationView
        assertTrue(conversation.pageStarts.size > 1)
        // No anchor yet: the newest page, the draft nearest the footer.
        assertEquals(conversation.pageStarts.lastIndex, conversation.page)
        assertTrue("draft" in conversation.visibleKeys())
        val report = paged.last()
        assertEquals(frame, report.frame)
        assertEquals("relay:thread", report.pageId)
        assertEquals(1L, report.revision)
        assertEquals(conversation.pageStarts, report.pageStarts)
        assertTrue(report.pageStarts.all { it.segmentId.startsWith("m:") || it.segmentId == "draft" })

        // The reducer's anchor for the first page brings the oldest messages and the caption.
        render(conversationScreen(bubbles, anchor = report.pageStarts.first()))
        assertEquals(0, conversation.page)
        assertTrue("m:1" in conversation.visibleKeys())
        assertFalse("draft" in conversation.visibleKeys())
        assertTrue(conversation.captionShown)
        assertEquals(0, paged.last().anchorPage)

        // An anchor whose bubble is gone falls back to the newest page.
        render(conversationScreen(bubbles, anchor = DocumentAnchor("live", 0)))
        assertEquals(conversation.pageStarts.lastIndex, conversation.page)
    }

    @Test
    fun `a message taller than a page is cut between its lines without losing any`() {
        val text = (1..400).joinToString(" ") { "w$it" }
        render(conversationScreen(listOf(Bubble("m:1", BubbleSide.LEFT, text, latest = true))))
        val conversation = view.blockViews.getValue("conversation") as ConversationView
        val starts = conversation.pageStarts
        assertTrue(starts.size > 1)
        assertTrue(starts.all { it.segmentId == "m:1" })
        assertEquals(starts.map { it.offset }.sorted(), starts.map { it.offset })
        assertEquals(0, starts.first().offset)
        assertEquals(starts.size, starts.map { it.offset }.distinct().size)
    }

    @Test
    fun `a card in command focus outlines its focused slot and sets the hub's Open app apart`() {
        val strip = CommandStrip(
            listOf(
                CommandSlot("prev", "Previous", "prev"),
                CommandSlot("toggle", "Pause", "pause"),
                CommandSlot("next", "Next", "next"),
                CommandSlot("hub open-app", "Open app", "grid", hub = true),
            ),
            focusedIndex = 1,
        )
        render(SessionScreen(ScreenHeader("NEXUS"), listOf(card("media:local", selected = true, commands = strip)), ScreenHint("swipe choose · tap run", "back cards"), focusKey = "media:local"))
        val stripView = find<CommandStripView>(view.blockViews.getValue("media:local")).single()
        assertEquals(4, stripView.slotRects.size)
        assertNotNull(stripView.separatorX)
        // Four slots do not all fit their labels: the focused one keeps its own.
        assertEquals(listOf(false, true, false, false), stripView.labelsShown)
        val bitmap = draw(stripView)
        fun topEdge(index: Int) = bitmap.getPixel(stripView.slotRects[index].centerX().toInt(), 0)
        assertEquals(PageStyle.phosphor, topEdge(1))
        assertEquals(PageStyle.dim, topEdge(0))
        assertEquals(PageStyle.outline, topEdge(3))

        // Three plugin commands keep every label.
        val three = strip.copy(items = strip.items.take(3), focusedIndex = 0)
        render(SessionScreen(ScreenHeader("NEXUS"), listOf(card("media:local", selected = true, commands = three)), null, focusKey = "media:local"))
        val threeView = find<CommandStripView>(view.blockViews.getValue("media:local")).single()
        assertEquals(listOf(true, true, true), threeView.labelsShown)
        assertNull(threeView.separatorX)
    }

    @Test
    fun `checklist rows draw an outlined box, a line check and a pending box, never a fill`() {
        val rows = listOf(
            ScreenBlock.Row("row:milk", "Milk", checked = true, selected = true),
            ScreenBlock.Row("row:eggs", "Eggs", checked = false),
            ScreenBlock.Row("row:bread", "Bread", checked = false, pending = true),
            ScreenBlock.Row("row:plain", "Plain"),
        )
        render(SessionScreen(ScreenHeader("COURSES"), rows, ScreenHint("tap choose", "back cards"), focusKey = "row:milk"))
        val milk = find<CheckboxMarkView>(view.blockViews.getValue("row:milk")).single()
        val eggs = find<CheckboxMarkView>(view.blockViews.getValue("row:eggs")).single()
        val bread = find<CheckboxMarkView>(view.blockViews.getValue("row:bread")).single()
        assertTrue(milk.checked && !milk.pending)
        assertFalse(eggs.checked)
        assertTrue(bread.pending)
        assertTrue(find<CheckboxMarkView>(view.blockViews.getValue("row:plain")).isEmpty())
        // A pending checkbox says so with its own dashed box, not a second mark.
        assertTrue(find<PendingMarkView>(view.blockViews.getValue("row:bread")).isEmpty())

        val checked = draw(milk)
        val unchecked = draw(eggs)
        fun lit(bitmap: Bitmap, x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y)) > 0 && bitmap.getPixel(x, y) != PageStyle.black
        val centre = CheckboxMarkView.SIZE_PX / 2
        assertFalse(lit(checked, centre, centre - 3))
        assertFalse(lit(unchecked, centre, centre))
        // The check's lower vertex is drawn on the checked box only.
        assertTrue(lit(checked, 9, 14))
        assertFalse(lit(unchecked, 9, 14))
    }

    @Test
    fun `icons are grey at rest, phosphor when selected or in the header, grey when ended`() {
        val blocks = listOf(
            card("a", selected = true),
            card("b"),
            card("c", ended = true),
            ScreenBlock.Row("notifications", "Notifications", trail = "3", icon = IconRef.Place(IconPlace.BELL)),
            ScreenBlock.Row("applications", "Applications", icon = IconRef.Place(IconPlace.GRID), selected = false),
        )
        render(SessionScreen(ScreenHeader("NAVIGATION", "back: root", IconRef.Plugin("nav")), blocks, ScreenHint("swipe next", "back close"), focusKey = "a"))
        fun tone(key: String) = find<ImageView>(view.blockViews.getValue(key)).mapNotNull { it.tag as? IconTag }.single().tone
        assertEquals(IconTone.BRIGHT, tone("a"))
        assertEquals(IconTone.REST, tone("b"))
        assertEquals(IconTone.ENDED, tone("c"))
        assertEquals(IconTone.REST, tone("notifications"))
        val header = find<ImageView>(view.getChildAt(0)).single()
        assertEquals(View.VISIBLE, header.visibility)
        assertEquals(IconTone.BRIGHT, (header.tag as IconTag).tone)
        assertEquals(PageStyle.muted, IconTint.colorOf(IconTone.REST))
        assertEquals(PageStyle.phosphor, IconTint.colorOf(IconTone.BRIGHT))

        val selectedRow = blocks.map { if (it is ScreenBlock.Row && it.key == "notifications") it.copy(selected = true) else if (it is ScreenBlock.Card) it.copy(selected = false) else it }
        render(SessionScreen(ScreenHeader("NEXUS"), selectedRow, null, focusKey = "notifications"))
        assertEquals(IconTone.BRIGHT, tone("notifications"))
        // A header without an icon keeps no stale mark.
        assertEquals(View.GONE, find<ImageView>(view.getChildAt(0)).single().visibility)
    }

    @Test
    fun `long Activities and Notifications lists follow the selection`() {
        val cards = (0 until 12).map { card("act:$it", selected = false, icon = IconRef.Plugin("p$it")) }
        render(SessionScreen(ScreenHeader("ACTIVITIES", "12/12"), cards.mapIndexed { i, c -> c.copy(selected = i == 11) }, null, focusKey = "act:11"))
        assertTrue(visibleInViewport("act:11"))
        assertFalse(visibleInViewport("act:0"))
        render(SessionScreen(ScreenHeader("ACTIVITIES", "1/12"), cards.mapIndexed { i, c -> c.copy(selected = i == 0) }, null, focusKey = "act:0"))
        assertTrue(visibleInViewport("act:0"))

        val rows = (0 until 30).map {
            ScreenBlock.Row("row:$it", "Mika · message $it", trail = "${it + 1} min", icon = IconRef.Plugin("relay"), tag = if (it % 3 == 0) "SEEN IN NEXUS" else null)
        }
        render(SessionScreen(ScreenHeader("NOTIFICATIONS", "23/30"), rows.mapIndexed { i, r -> r.copy(selected = i == 22) }, null, focusKey = "row:22"))
        assertTrue(visibleInViewport("row:22"))
        assertEquals(listOf("Mika · message 21", "SEEN IN NEXUS", "22 min"), texts(view.blockViews.getValue("row:21")))
        assertEquals(1, find<ImageView>(view.blockViews.getValue("row:22")).size)
    }

    @Test
    fun `media and a degraded manoeuvre say their state without inventing words`() {
        val media = ScreenBlock.Media("media", "Reptilia", "The Strokes", "Room on Fire", "Media Deck", MediaPlaybackState.PLAYING, "Controls on the phone")
        val maneuver = ScreenBlock.Maneuver("maneuver", "turn-left", "120 m", "Rue de Rivoli", "arrival 12:24", approximate = true)
        render(SessionScreen(ScreenHeader("MEDIA DECK"), listOf(media, maneuver), null, focusKey = "media"))
        val mediaView = view.blockViews.getValue("media")
        assertEquals(listOf("Media Deck", "Reptilia", "The Strokes", "Room on Fire", "Controls on the phone"), texts(mediaView))
        assertEquals(MediaPlaybackState.PLAYING, find<ImageView>(mediaView).mapNotNull { it.tag as? LauncherBlocks.MediaStateTag }.single().state)
        assertEquals(PageStyle.phosphor, find<TextView>(mediaView).first { it.text.toString() == "Reptilia" }.currentTextColor)
        val distance = find<TextView>(view.blockViews.getValue("maneuver")).first()
        assertEquals("~120 m", distance.text.toString())
        assertEquals(PageStyle.muted, distance.currentTextColor)

        render(SessionScreen(ScreenHeader("MEDIA DECK"), listOf(media.copy(state = MediaPlaybackState.UNAVAILABLE)), null))
        val unavailable = view.blockViews.getValue("media")
        assertTrue(find<ImageView>(unavailable).none { it.tag is LauncherBlocks.MediaStateTag })
        assertEquals(PageStyle.muted, find<TextView>(unavailable).first { it.text.toString() == "Reptilia" }.currentTextColor)
    }

    @Test
    fun `the chromeless full map is drawn under the whole HUD with its furniture clear of the overlay`() {
        val geometry = RouteGeometry(
            spanMeters = 500.0,
            paths = listOf(
                RoutePath(RoutePathRole.STREET, listOf(RoutePoint(0, 500), RoutePoint(1000, 500))),
                RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 500), RoutePoint(500, 100))),
            ),
            position = RoutePosition(RoutePositionQuality.LIVE, RoutePoint(500, 500)),
            turn = RoutePoint(500, 300),
        )
        val screen = SessionScreen(
            ScreenHeader("NAVIGATION · OVERVIEW", "1/2"),
            listOf(
                ScreenBlock.Maneuver("maneuver", "turn-left", "120 m", "Rue de Rivoli"),
                ScreenBlock.Map("map", geometry, headingDeg = 0f, size = MapSize.FULL, view = MapView.FOLLOW),
            ),
            ScreenHint("swipe next view · tap commands", "back page"),
            focusKey = "map",
            chromeless = true,
        )
        render(screen)
        val input = requireNotNull(view.fullMap)
        assertEquals(MapForm.FULL, input.form)
        // No rules under the floating header or over the floating hint.
        assertEquals(View.GONE, view.getChildAt(1).visibility)
        assertEquals(View.GONE, view.getChildAt(view.childCount - 2).visibility)
        assertEquals(0, view.blockViews.getValue("map").height)
        val maneuver = view.blockViews.getValue("maneuver")
        assertTrue(input.insets.top >= view.viewport.top + maneuver.bottom)
        assertTrue(input.insets.bottom >= PageStyle.HINT_HEIGHT_PX)
        val plan = requireNotNull(view.fullMapPlan())
        assertTrue(requireNotNull(plan.compassY) - RouteMapPainter.COMPASS_RADIUS_PX > input.insets.top)
        assertEquals(PageStyle.WIDTH_PX / 2f, plan.transform.x(500f, 500f), 0.5f)
        val wearerY = plan.transform.y(500f, 500f)
        assertTrue(wearerY > input.insets.top)
        // Heading-up: the route ahead runs straight up from the wearer, bright.
        val bitmap = draw(view)
        assertEquals(PageStyle.phosphor, bitmap.getPixel(PageStyle.WIDTH_PX / 2, (wearerY - 40f).toInt()))

        // Leaving the full map clears the backdrop.
        render(screen.copy(chromeless = false, blocks = screen.blocks.take(1)))
        assertNull(view.fullMap)
        assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
    }

    @Test
    fun `a compact map is a card with its caption and a measured map`() {
        val geometry = RouteGeometry(500.0, listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 500), RoutePoint(500, 100)))))
        render(SessionScreen(ScreenHeader("NAVIGATION"), listOf(ScreenBlock.Map("map", geometry, selected = true, label = "MAP · FOLLOW", trail = "1/2")), null, focusKey = "map"))
        val card = view.blockViews.getValue("map") as MapCardView
        assertEquals(listOf("MAP · FOLLOW", "1/2"), texts(card))
        assertEquals(MapForm.COMPACT.naturalHeightPx, card.map.height)
        assertTrue(visibleInViewport("map"))
    }

    @Test
    fun `a ribbon places the vehicle between its stops and keeps its note`() {
        val stops = listOf(
            RouteRibbonStop("Gare du Nord", RouteStopState.PASSED, "12:40"),
            RouteRibbonStop("Châtelet–Les Halles", RouteStopState.PASSED, "12:44"),
            RouteRibbonStop("Saint-Michel", RouteStopState.UPCOMING, "12:46"),
            RouteRibbonStop("Luxembourg", RouteStopState.ALIGHT, "12:48"),
        )
        val block = ScreenBlock.Ribbon("ribbon", "RER B", stops, RouteRibbonBetween(1, 0.4), "exit Jardin · then walk 3 min")
        render(SessionScreen(ScreenHeader("NAVIGATION · RER B"), listOf(block), null, focusKey = "ribbon"))
        val ribbon = find<RibbonView>(view.blockViews.getValue("ribbon")).single()
        draw(ribbon)
        assertEquals(ribbon.yOf(1) + 0.4f * (ribbon.yOf(2) - ribbon.yOf(1)), requireNotNull(ribbon.vehicleY), 0.01f)
        assertEquals(requireNotNull(ribbon.vehicleY), ribbon.cutY, 0.01f)
        assertTrue(texts(view.blockViews.getValue("ribbon")).containsAll(listOf("RER B", "exit Jardin · then walk 3 min")))
        assertTrue(visibleInViewport("ribbon"))
    }

    @Test
    fun `islands keep r17's widths and map sizes, and a lost position says so`() {
        val geometry = RouteGeometry(
            500.0,
            listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 500), RoutePoint(500, 100)))),
            position = RoutePosition(RoutePositionQuality.STALE, RoutePoint(500, 500), fixAgeS = 40),
        )
        fun measured(form: IslandForm, content: IslandContent): NavIslandView = NavIslandView(context, icons).apply {
            bind(form, content)
            measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val lost = IslandContent.Guidance(
            HudGlyphs.GPS_LOST, "120 m", street = "Rue de Rivoli", approximate = true,
            map = MapInput(geometry, MapForm.MINIMAP, degraded = true),
            foot = IslandFoot("GPS lost · last fix 40 s", "12:24"),
        )
        val minimap = measured(IslandForm.MINIMAP, lost)
        assertEquals(262, minimap.width)
        val map = requireNotNull(minimap.mapView)
        assertEquals(MapForm.MINIMAP.naturalHeightPx, map.height)
        assertEquals(PositionMark.LOST, requireNotNull(map.planNow()).style.position)
        assertTrue(requireNotNull(map.planNow()).style.northUp)
        assertTrue(minimap.texts().containsAll(listOf("~120 m", "GPS lost · last fix 40 s", "12:24")))
        draw(minimap)

        val panel = measured(IslandForm.PANEL, lost.copy(approximate = false, glyph = "turn-left", time = "12:24", then = "then Châtelet on your left · 1.2 km", thenStrong = "Châtelet", map = MapInput(geometry, MapForm.PANEL)))
        assertEquals(374, panel.width)
        assertEquals(MapForm.PANEL.naturalHeightPx, requireNotNull(panel.mapView).height)
        assertEquals(MapForm.PANEL, requireNotNull(panel.mapView).input?.form)

        val text = measured(IslandForm.TEXT, lost.copy(approximate = false, glyph = "turn-left"))
        assertEquals(232, text.width)
        assertNull(text.mapView)
        assertFalse(text.texts().contains("GPS lost · last fix 40 s"))

        val wait = measured(IslandForm.PANEL, IslandContent.Departure("RER B", "Robinson", "Platform 2", "3 min", "then 11 · 18", "Get off at Luxembourg · 3 stops · 7 min", "Luxembourg"))
        assertEquals(374, wait.width)
        assertNull(wait.mapView)
        assertTrue(wait.texts().containsAll(listOf("RER B", "→ Robinson", "3 min")))
    }
}
