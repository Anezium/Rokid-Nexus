package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPagesTest {
    private fun entry(id: String, sender: String, text: String, at: Long = 1_000, app: String = "WhatsApp", live: Boolean = true) =
        RelayInboxEntry(
            RelayInboxSnapshot(id = id, sender = sender, appLabel = app, renderedText = text, capturedAtMs = at),
            if (live) RelayReplyAvailability.REPLIABLE else RelayReplyAvailability.READ_ONLY,
        )

    private val time: (Long) -> String = { "t$it" }

    private fun list(entries: List<RelayInboxEntry>, hide: Boolean = false) =
        (RelayPages.page(RelayPages.MAIN, 1, entries, hide, time) as NexusPage.Content)

    private fun thread(entries: List<RelayInboxEntry>, id: String) =
        RelayPages.page(RelayPages.threadPageId(id), 1, entries, false, time) as NexusPage.Content

    @Test
    fun `the list names each thread, its capture time and newest line, and opens its document`() {
        val entries = listOf(entry("k1", "Mika", "Mika: on est devant\nMika: tu arrives quand ?"), entry("k2", "Ana", "Ana: hi", at = 900))
        val page = list(entries)
        val rows = (page.body as PageBody.SelectableList).items
        assertEquals(listOf("Mika", "Ana"), rows.map { it.label })
        assertEquals("t1000 · Mika: tu arrives quand ?", rows.first().sub)
        assertEquals(PageTarget.Page(RelayPages.threadPageId("k1")), rows.first().target)
        assertTrue(page.actions.isEmpty())
        assertTrue(page.live)
        assertTrue(rows.all { it.id.length <= 128 && it.id.all { char -> char.code in 33..126 } })
        assertEquals(RelayPages.EMPTY_LIST_TEXT, (list(emptyList()).body as PageBody.SelectableList).emptyText)
    }

    @Test
    fun `thread ids are opaque digests that never merge two identities`() {
        val long = "x".repeat(400)
        val first = RelayPages.threadPageId(long + "a")
        val second = RelayPages.threadPageId(long + "b")
        assertNotEquals(first, second)
        assertTrue(first.length <= 128)
        assertFalse(first.contains("x"))
    }

    @Test
    fun `hidden previews hide the list text while opening the thread still reads it`() {
        val entries = listOf(entry("k1", "Mika", "Mika: the plan"))
        val sub = (list(entries, hide = true).body as PageBody.SelectableList).items.single().sub.orEmpty()
        assertFalse("the plan" in sub)
        assertTrue(RelayPrivacy.HIDDEN_BODY in sub)
        val document = thread(entries, "k1").body as PageBody.Document
        assertTrue(document.segments.any { it.text == "the plan" })
        // A sensitive body was redacted at capture and stays redacted here.
        val redacted = listOf(entry("k2", "Bank", RelayPrivacy.HIDDEN_BODY))
        assertTrue((thread(redacted, "k2").body as PageBody.Document).segments.none { "code" in it.text })
    }

    @Test
    fun `a thread document has speaker headers and the capture time, and invents no other time`() {
        val entries = listOf(entry("k1", "Mika", "Mika: on est devant\nYou: j'arrive\nMika: tu arrives quand ?", at = 5_000))
        val page = thread(entries, "k1")
        assertEquals("Mika", page.title)
        val segments = (page.body as PageBody.Document).segments
        assertEquals(PageSegment("captured", PageSegmentKind.HEADER, "WhatsApp · captured t5000"), segments.first())
        assertEquals(listOf("Mika", "on est devant", "You", "j'arrive", "Mika", "tu arrives quand ?"), segments.drop(1).map { it.text })
        assertEquals(1, segments.count { "t5000" in it.text })
        assertTrue(page.actions.isEmpty())
    }

    @Test
    fun `a removed thread is an honest empty page`() {
        val page = thread(listOf(entry("k1", "Mika", "hi")), "gone")
        assertEquals(listOf(RelayPages.MISSING_TEXT), (page.body as PageBody.Document).segments.map { it.text })
    }

    @Test
    fun `a long multibyte thread keeps its newest messages, marks the omission and fits the wire`() {
        val message = "Привет, как дела? 🙂 " .repeat(60)
        val text = (1..80).joinToString("\n") { "Mika: $it $message" }
        val entries = listOf(entry("k1", "Mika", text))
        val segments = (thread(entries, "k1").body as PageBody.Document).segments
        assertEquals(RelayPages.OMITTED_TEXT, segments[1].text)
        assertTrue(segments.last().text.startsWith("80 "))
        val bytes = segments.sumOf(RelayPages::bytesOf)
        assertTrue("bytes=$bytes", bytes <= RelayPages.SEGMENT_BUDGET_BYTES)
        // The estimate is an upper bound on the real UTF-8 size of each text.
        segments.forEach { assertTrue(it.text.toByteArray(Charsets.UTF_8).size <= RelayPages.bytesOf(it)) }
        // Only whole messages go: each kept message keeps all of its text.
        assertTrue(segments.filter { it.kind == PageSegmentKind.PROSE }.all { it.text.endsWith(message.trim()) })
    }

    @Test
    fun `segment ids stay stable when older messages fall off the start`() {
        val before = (thread(listOf(entry("k1", "Mika", "Mika: one\nMika: two\nMika: three")), "k1").body as PageBody.Document).segments
        val after = (thread(listOf(entry("k1", "Mika", "Mika: two\nMika: three\nMika: four")), "k1").body as PageBody.Document).segments
        val idOf = { segments: List<PageSegment>, text: String -> segments.first { it.text == text }.id }
        assertEquals(idOf(before, "two"), idOf(after, "two"))
        assertEquals(idOf(before, "three"), idOf(after, "three"))
        assertEquals(segments(after).distinct().size, segments(after).size)
    }

    private fun segments(list: List<PageSegment>) = list.map { it.id }

    @Test
    fun `a long title uses the page title limit, not the surface's`() {
        val name = "Mika " + "Lefèvre-".repeat(20)
        val page = thread(listOf(entry("k1", name, "hello")), "k1")
        assertTrue(page.title.length <= RelayPages.TITLE_CHARS)
    }

    private class Harness {
        data class Request(val pageId: String, val floor: Long? = null)

        var clock = 0L
        val published = mutableListOf<Pair<String, Long>>()
        var scheduled: Pair<Long, () -> Unit>? = null
        val provider = RelayPageProvider<Request>(
            pageIdOf = Request::pageId,
            floorOf = Request::floor,
            publish = { request, page ->
                published += request.pageId to (page as NexusPage.Content).revision
                NexusSdkResult.SENT
            },
            build = { pageId, revision -> RelayPages.page(pageId, revision, emptyList(), false) { "" } },
            now = { clock },
            schedule = { delay, task -> scheduled = delay to task },
            cancelScheduled = { scheduled = null },
        )
    }

    @Test
    fun `inbox changes reach only visible pages, twice a second at most, from above the floor`() {
        val harness = Harness()
        // Rebuilt without knowing what the glasses kept, a page never reuses their retained revision.
        assertEquals(8L, (harness.provider.onRequest(Harness.Request(RelayPages.MAIN, floor = 7)) as NexusPage.Content).revision)
        harness.provider.inboxChanged()
        assertTrue(harness.published.isEmpty())
        harness.provider.onVisibility(RelayPages.MAIN, true)
        assertEquals(listOf(RelayPages.MAIN to 9L), harness.published)
        harness.clock = 100
        harness.provider.inboxChanged()
        assertEquals(400L, harness.scheduled?.first)
        harness.provider.onVisibility(RelayPages.MAIN, false)
        assertEquals(null, harness.scheduled)
        harness.provider.onClosed(RelayPages.MAIN)
        harness.clock = 2_000
        harness.provider.inboxChanged()
        assertEquals(1, harness.published.size)
    }

    private class FakeBinder {
        var binds = 0
        var unbinds = 0
        var accept = true
    }

    private fun owner(binder: FakeBinder) = RelayClientOwner<String>(
        bind = {
            binder.binds += 1
            binder.accept
        },
        unbind = { binder.unbinds += 1 },
    )

    @Test
    fun `notice first then page demand share one client, and the notice ending keeps it`() {
        val binder = FakeBinder()
        val owner = owner(binder)
        val ready = mutableListOf<String>()
        owner.onClientReady = { ready += it }
        assertEquals(null, owner.holdForNotice())
        assertEquals(1, binder.binds)
        // The bound service creates its one client; the band hears it is ready.
        owner.serviceCreated("client-a")
        assertEquals(listOf("client-a"), ready)
        // Page demand binds the same service elsewhere: no second hold, no second client.
        assertEquals("client-a", owner.holdForNotice())
        assertEquals(1, binder.binds)
        owner.releaseNotice()
        assertEquals(1, binder.unbinds)
        assertEquals("client-a", owner.client)
        owner.releaseNotice()
        assertEquals(1, binder.unbinds)
    }

    @Test
    fun `page or immersion first then a notice reuses the live client`() {
        val binder = FakeBinder()
        val owner = owner(binder)
        owner.serviceCreated("client-a")
        assertEquals("client-a", owner.holdForNotice())
        assertEquals(1, binder.binds)
        assertTrue(owner.noticeHeld)
        owner.releaseNotice()
        assertEquals("client-a", owner.client)
    }

    @Test
    fun `a destroyed service clears the client without rebinding or looping`() {
        val binder = FakeBinder()
        val owner = owner(binder)
        owner.holdForNotice()
        owner.serviceCreated("client-a")
        owner.serviceDestroyed("client-b")
        assertEquals("client-a", owner.client)
        owner.serviceDestroyed("client-a")
        assertEquals(null, owner.client)
        assertEquals(1, binder.binds)
        // Reconnect is a new service with a new client; the band's existing hold is reused.
        owner.serviceCreated("client-c")
        assertEquals("client-c", owner.holdForNotice())
        assertEquals(1, binder.binds)
        // A refused bind is not retried by itself.
        val refusing = FakeBinder().apply { accept = false }
        val other = owner(refusing)
        other.holdForNotice()
        assertFalse(other.noticeHeld)
        assertEquals(1, refusing.binds)
    }
}
