package com.anezium.rokidbus.glasses

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the wearer is typing into an editable field that asked to live in its
 * owner's notice band (`EditableSurfaceField.inNotice`).
 *
 * The band cannot hold the field itself: it is an accessibility overlay that is
 * never focusable (plan 015), so no IME can ever attach to it. The real field
 * stays on the surface activity, drawn black — invisible on the additive optics
 * — and the band draws this copy of its text and caret instead. The copy is
 * local to the glasses: nothing here crosses the bus, and the plugin still gets
 * exactly one commit, as with any editable field.
 *
 * Main thread only, like both of its callers.
 */
internal object NoticeComposeMirror {
    data class Line(
        val ownerPluginId: String,
        val text: String,
        val cursor: Int,
        val placeholder: String,
    )

    private val listeners = CopyOnWriteArrayList<(Line?) -> Unit>()

    var current: Line? = null
        private set

    fun publish(line: Line) {
        if (current == line) return
        current = line
        listeners.forEach { listener -> runCatching { listener(line) } }
    }

    fun clear(ownerPluginId: String) {
        if (current?.ownerPluginId != ownerPluginId) return
        current = null
        listeners.forEach { listener -> runCatching { listener(null) } }
    }

    fun observe(listener: (Line?) -> Unit): () -> Unit {
        listeners += listener
        listener(current)
        return { listeners.remove(listener) }
    }
}

/**
 * Whether an editable field is drawn in the band rather than as a card: only
 * when it asked to, and only while its own plugin's band is the one on screen.
 * Another plugin's band must never display what is typed into this field.
 */
internal fun editableDrawsInNotice(
    inNotice: Boolean,
    surfaceOwnerPluginId: String,
    visibleNoticeOwnerPluginId: String?,
): Boolean = inNotice &&
    surfaceOwnerPluginId.isNotBlank() &&
    surfaceOwnerPluginId == visibleNoticeOwnerPluginId

/**
 * A card with nothing but its title. A plugin shows one to keep its session open while its band
 * does the talking — hiding its last card would read as the plugin closing.
 */
internal fun NexusSurface.isBareCard(): Boolean =
    kind == NexusSurface.KIND_CARD &&
        editable == null &&
        subtitle.isBlank() &&
        footer.isBlank() &&
        rows.none { it.text.isNotBlank() || it.isStructured }

/**
 * Whether a bare card steps aside for its own plugin's band, drawing nothing at all, so the band
 * sits over whatever the wearer was looking at instead of over a black screen.
 */
internal fun cardHoldsUnderBand(surface: NexusSurface, visibleNoticeOwnerPluginId: String?): Boolean =
    surface.isBareCard() &&
        surface.ownerPluginId.isNotBlank() &&
        surface.ownerPluginId == visibleNoticeOwnerPluginId

/**
 * The mirrored line as the band draws it: the text with a block caret, which is
 * one highlighted character rather than a glyph, so no font has to carry it.
 * At the end of the text the caret sits on an added space; in an empty field it
 * leads the dimmed placeholder.
 */
internal data class NoticeComposeRender(
    val text: String,
    val caretStart: Int,
    val caretEnd: Int,
    val placeholderStart: Int,
)

internal fun noticeComposeRender(line: NoticeComposeMirror.Line): NoticeComposeRender {
    if (line.text.isEmpty()) {
        return NoticeComposeRender(
            text = " " + line.placeholder,
            caretStart = 0,
            caretEnd = 1,
            placeholderStart = 1,
        )
    }
    val cursor = line.cursor.coerceIn(0, line.text.length)
    if (cursor == line.text.length) {
        val shown = line.text + " "
        return NoticeComposeRender(shown, cursor, cursor + 1, shown.length)
    }
    val caretEnd = cursor + Character.charCount(line.text.codePointAt(cursor))
    return NoticeComposeRender(line.text, cursor, caretEnd, line.text.length)
}
