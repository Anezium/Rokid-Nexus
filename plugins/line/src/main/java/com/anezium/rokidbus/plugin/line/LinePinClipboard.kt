package com.anezium.rokidbus.plugin.line

/** What to do about a PIN this plugin put on the clipboard once the sign-in no longer needs it. */
internal enum class PinClipboardStep {
    /** The clipboard still holds our PIN: clear it and give up ownership. */
    CLEAR,
    /** Someone copied something else since: nothing of ours is left, give up ownership. */
    DROP,
    /** The clipboard could not be read: keep ownership and try again on the next chance. */
    KEEP,
}

/**
 * The one decision behind [LineSettingsActivity]'s clipboard cleanup, kept pure so it can be
 * tested. Android only lets the focused app read the clipboard, and an unreadable clipboard
 * comes back as `null` exactly like an empty one, so `null` never counts as "nothing to do":
 * dropping ownership on it would leave the PIN on the clipboard after a rotation or a sign-in
 * that finished while another app was in front.
 */
internal object PinClipboard {
    fun step(owned: String, current: String?): PinClipboardStep = when (current) {
        null -> PinClipboardStep.KEEP
        owned -> PinClipboardStep.CLEAR
        else -> PinClipboardStep.DROP
    }
}
