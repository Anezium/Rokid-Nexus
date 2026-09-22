package com.anezium.rokidbus.glasses

/** A node, rather than its action name, identifies one choice in a repeated list. */
internal class InkActionSelection {
    var selectedId: String? = null
        private set
    private var order = emptyList<String>()

    fun reconcile(next: List<String>) {
        val previousIndex = order.indexOf(selectedId).coerceAtLeast(0)
        selectedId = selectedId?.takeIf(next::contains)
            ?: next.getOrNull(previousIndex.coerceAtMost(next.lastIndex))
        order = next
    }

    fun select(id: String): Boolean {
        if (id !in order) return false
        selectedId = id
        return true
    }

    fun adjacent(delta: Int): String? = order.getOrNull(order.indexOf(selectedId) + delta)

    fun boundary(delta: Int): String? = if (delta > 0) order.firstOrNull() else order.lastOrNull()

    fun clear() {
        selectedId = null
        order = emptyList()
    }
}
