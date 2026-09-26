package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SurfaceHudSeeThroughTest {
    private val state = NoticeController::class.java.getDeclaredField("state")
        .apply { isAccessible = true }
        .get(NoticeController) as NoticeStateMachine
    private var seq = 0L

    @Before
    fun setUp() {
        state.close(NoticeCloseReason.DISCONNECT)
        val latest = NoticeStateMachine::class.java.getDeclaredField("latestSeq").apply { isAccessible = true }
        seq = maxOf(0L, latest.getLong(state)) + 10L
    }

    @After
    fun tearDown() {
        state.close(NoticeCloseReason.DISCONNECT)
    }

    @Test
    fun `a bare card under its own band draws nothing, background included`() {
        showBand(owner = "assistant")

        val view = SurfaceHudView(RuntimeEnvironment.getApplication())
        view.render(card())

        assertNull(view.background)
    }

    @Test
    fun `a bare card under another plugin's band stays a filled card`() {
        showBand(owner = "relay")

        val view = SurfaceHudView(RuntimeEnvironment.getApplication())
        view.render(card())

        assertNotNull(view.background)
    }

    @Test
    fun `a card with something to show keeps its fill under its own band`() {
        showBand(owner = "assistant")

        val view = SurfaceHudView(RuntimeEnvironment.getApplication())
        view.render(card(rows = listOf(SurfaceRow(text = "Ask out loud."))))

        assertNotNull(view.background)
    }

    @Test
    fun `a field typed inside its own band draws nothing`() {
        showBand(owner = "assistant")

        val view = SurfaceHudView(RuntimeEnvironment.getApplication())
        view.render(card(editable = EditableSurfaceField(placeholder = "Ask Assistant…", inNotice = true)))

        assertNull(view.background)
    }

    @Test
    fun `leaving a see-through card fills the next one again`() {
        showBand(owner = "assistant")
        val view = SurfaceHudView(RuntimeEnvironment.getApplication())
        view.render(card())

        view.render(card(rows = listOf(SurfaceRow(text = "Tokyo"))).copy(seq = 2L))

        assertNotNull(view.background)
    }

    private fun showBand(owner: String) {
        state.show(
            surfaceId = "$owner:notice",
            seq = ++seq,
            content = NoticeSurfaceContent(title = "Assistant", body = "Thinking…", footer = null, ttlMs = 30_000L),
            nowMs = 0L,
            ownerPluginId = owner,
        )
    }

    private fun card(
        rows: List<SurfaceRow> = emptyList(),
        editable: EditableSurfaceField? = null,
    ) = NexusSurface(
        surfaceId = "assistant:assistant",
        seq = 1L,
        kind = NexusSurface.KIND_CARD,
        contentKey = "holder",
        title = "Assistant",
        subtitle = "",
        footer = "",
        rows = rows,
        timedLines = emptyList(),
        anchor = null,
        handlesBack = true,
        ownerPluginId = "assistant",
        editable = editable,
    )
}
