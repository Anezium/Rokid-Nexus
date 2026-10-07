package com.anezium.rokidbus.plugin.patcher

import kotlinx.serialization.json.*
import java.io.File

class SelectionStore(private val file: File) {
    private fun read(): JsonObject = if (file.exists()) Json.parseToJsonElement(file.readText()).jsonObject else buildJsonObject {}
    fun load(version: String, defaults: Map<String, Boolean>): Map<String, Boolean> {
        val saved = read()
        val previous = (saved[version] ?: saved["latest"])?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap()
        return PatchPolicy.mergeSelection(defaults, previous)
    }
    fun save(version: String, choices: Map<String, Boolean>) {
        val previous = read().toMutableMap()
        val selection = buildJsonObject { choices.forEach { (name, selected) -> put(name, selected) } }
        previous[version] = selection; previous["latest"] = selection
        file.parentFile!!.mkdirs()
        val pending = File(file.parentFile, "selection.tmp")
        pending.writeText(JsonObject(previous).toString())
        require(pending.renameTo(file)) { "Cannot save patch choices." }
    }
}
