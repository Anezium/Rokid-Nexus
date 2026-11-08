package com.anezium.rokidbus.plugin.meta

/**
 * The current pages whose content moved on while they could not be published to: a page is
 * answered, then its service becomes ready before the hub says the page is visible, so the
 * publish for that change finds nothing to publish to. Remembering it lets the page get the
 * ready content the moment it is shown, instead of staying on "Connecting…" until the wearer
 * navigates. A fresh answer to a request carries the latest state and clears the debt.
 */
internal class StalePages {
    private val stale = HashSet<String>()

    /** A publish was skipped for [pageId] because it is not visible yet. */
    fun skipped(pageId: String) {
        stale += pageId
    }

    /** [pageId] was just answered from the current state. */
    fun answered(pageId: String) {
        stale -= pageId
    }

    /** [pageId] is visible now: true when it owes a publish, once. */
    fun shown(pageId: String): Boolean = stale.remove(pageId)

    fun forget(pageId: String) {
        stale -= pageId
    }

    fun clear() = stale.clear()
}
