package com.anezium.rokidbus.glasses

/**
 * The one place that knows the HUD's window stacking order.
 *
 * Accessibility overlay windows stack in the order they are added, so every
 * time a full-screen window is created — the launcher, a plugin surface — the
 * ambient layers above it have to be re-added or they end up buried. The
 * launcher panel in particular is opaque and top-anchored, exactly where the
 * notice band lives.
 *
 * This used to be each renderer's job to remember, and the notice was
 * forgotten: a band was covered by the launcher for the rest of its life even
 * though opening the launcher over a notice is explicitly supported. Adding a
 * third ambient layer (plan 012's activity) to that arrangement would have made
 * the same omission twice as likely, so the order lives here instead, in the
 * order it renders: ambient first, most interruptive last.
 */
internal object HudOverlayStack {

    /**
     * Re-assert the ambient layers above a window that was just added.
     *
     * Call this immediately after adding any full-screen overlay window. Each
     * renderer no-ops when it has nothing on screen, so calling it
     * unconditionally is correct and cheap.
     */
    fun reassert() {
        LyricsWidgetOverlayRenderer.ensureOnTop()
        PinOverlayRenderer.ensureOnTop()
        ActivityOverlayRenderer.ensureOnTop()
        NoticeOverlayRenderer.ensureOnTop()
        RemotePointerOverlayRenderer.ensureOnTop()
    }
}
