package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONObject

interface NexusPluginCallbacks {
    fun onOpen()

    /**
     * [onOpen] with the reason the hub gave, one of [PluginOpenTypes]. The default forwards
     * to [onOpen], so a plugin that does not care which way it was opened keeps working.
     */
    fun onOpen(openType: String) = onOpen()
    fun onBackground() = Unit
    fun onClose()
    fun onInput(event: NexusInputEvent)
    fun onLinkState(state: Int)
    fun onGlassesAiButton(active: Boolean) = Unit

    /**
     * The notice this plugin raised is no longer visible, and why. Delivered
     * once per notice, including when this plugin hid it itself, so a plugin
     * has exactly one place to clean up whatever the banner was standing for.
     *
     * Not delivered when the plugin is what disappeared.
     */
    fun onNoticeClosed(reason: NexusNoticeCloseReason) = Unit

    /**
     * The wearer answered this plugin's interactive notice. Arrives whether or
     * not the plugin has a surface open, which is what the notice tier exists
     * for: a plugin can be dormant, say one thing, and be answered.
     *
     * Fires **at most once per question**, like [onNoticeAction]: a notice
     * takes exactly one answer, after which the band is an inert display and
     * confirm reaches whatever is underneath it. Ask again by sending an update
     * that carries `interactive` or a new action row, or by showing a new
     * notice. This is a deliberate change from 1.0.46, where an interactive
     * band replied on every confirm.
     *
     * Back is never delivered here. It dismisses the band, always, and a plugin
     * cannot take it.
     */
    fun onNoticeInput(event: NexusInputEvent) = Unit

    /**
     * The wearer picked one of this plugin's notice actions, by its id.
     *
     * Fires instead of [onNoticeInput], never alongside it: a band that offers
     * answers is answered by which one was chosen, and a band that offers none
     * keeps the single confirming gesture. Fires at most once per question, for
     * the same reason and with the same way of asking again. Back is still
     * never delivered to either -- it dismisses the band, and no plugin can
     * take it.
     */
    fun onNoticeAction(id: String) = Unit

    /** The wearer fired one of this plugin's current activity actions. */
    fun onActivityAction(id: String) = Unit

    /**
     * This plugin's activity ended. Reasons are strings so a newer hub can add
     * one without an older SDK silently dropping the callback.
     */
    fun onActivityClosed(reason: String) = Unit

    /**
     * The wearer submitted or cancelled this card's editable field (see
     * [NexusCard.editable]). Fires at most once per field: ask again by
     * showing a new card, or an update that carries a fresh `editable` block.
     * [text] is empty when [cancelled] is true.
     */
    fun onSurfaceTextCommitted(surfaceId: String, text: String, cancelled: Boolean) = Unit

    fun onInkReady(surfaceId: String) = Unit
    fun onInkAction(surfaceId: String, actionId: String, dataset: JSONObject) = Unit
    fun onInkClosed(surfaceId: String, reason: NexusInkCloseReason) = Unit
    fun onInkError(surfaceId: String, problems: List<NexusInkProblem>) = Unit

    fun onRegistrationState(result: Int)

    /**
     * The hub answered this plugin's assist-button request: `true` when the button hands over
     * to the approved assistant plugin, `false` when it stays with Rokid's own. Needs the
     * `assistant` grant; see [NexusPluginClient.requestAssistantTakeover].
     */
    fun onAssistantTakeover(enabled: Boolean) = Unit

    /** The hub rejected an assist-button request; [code] is the stable bus error code. */
    fun onAssistantTakeoverError(code: String) = Unit
    fun onMessage(path: String, id: String, payload: JSONObject) = Unit
    fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = Unit
}
