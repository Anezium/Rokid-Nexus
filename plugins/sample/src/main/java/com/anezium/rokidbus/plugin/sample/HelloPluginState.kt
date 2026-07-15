package com.anezium.rokidbus.plugin.sample

internal class HelloPluginState(
    private val choices: List<String> = listOf("Hello", "SDK", "Open platform"),
) {
    var selectedIndex: Int = 0
        private set

    var activated: Boolean = false
        private set

    fun move(delta: Int) {
        selectedIndex = Math.floorMod(selectedIndex + delta, choices.size)
        activated = false
    }

    fun activate() {
        activated = true
    }

    fun lines(): List<String> = choices.mapIndexed { index, choice ->
        val marker = if (index == selectedIndex) ">" else " "
        "$marker $choice${if (activated && index == selectedIndex) " ✓" else ""}"
    }
}
