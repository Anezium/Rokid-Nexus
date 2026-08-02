package com.anezium.rokidbus.plugin.t3code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class T3CatalogTest {
    @Test
    fun `effort descriptor accepts provider id variants and semantic labels`() {
        listOf("effort", "reasoningEffort", "thinkingLevel").forEach { id ->
            val descriptor = descriptor(id = id, label = "Mode")
            assertEquals(id, T3Catalog.effortDescriptor(model(descriptor))?.id)
        }
        val byLabel = descriptor(id = "custom-depth", label = "Reasoning depth")
        assertEquals("custom-depth", T3Catalog.effortDescriptor(model(byLabel))?.id)
    }

    @Test
    fun `non select and absent effort descriptors skip the reasoning step`() {
        assertNull(T3Catalog.effortDescriptor(model()))
        assertNull(T3Catalog.effortDescriptor(model(descriptor(type = "toggle"))))
        assertNull(T3Catalog.effortDescriptor(model(descriptor(id = "temperature", label = "Temperature"))))
    }

    @Test
    fun `effort default prefers current then marked then first`() {
        val options = listOf(
            T3ModelOption("low", "Low", false),
            T3ModelOption("medium", "Medium", true),
            T3ModelOption("high", "High", false),
        )
        assertEquals(2, descriptor(options = options, currentValue = "high").defaultOptionIndex())
        assertEquals(1, descriptor(options = options).defaultOptionIndex())
        assertEquals(0, descriptor(options = options.map { it.copy(isDefault = false) }).defaultOptionIndex())
    }

    @Test
    fun `model picker promotes project default and board models before applying cap`() {
        val models = (0 until 30).map { index -> T3Model("model-$index", "Model $index", emptyList()) }
        val provider = T3Provider("codex", "codex", "Codex", true, true, models)
        val project = T3Project(
            "p",
            "Project",
            "/project",
            T3ModelSelection("codex", "model-29"),
        )
        val current = boardThread("t", T3ModelSelection("codex", "model-28"))

        val reachable = T3Catalog.reachableModels(provider, project, listOf(current))

        assertEquals(24, reachable.size)
        assertEquals(listOf("model-29", "model-28"), reachable.take(2).map(T3Model::slug))
    }

    private fun model(vararg descriptors: T3OptionDescriptor): T3Model =
        T3Model("model", "Model", descriptors.toList())

    private fun descriptor(
        id: String = "reasoningEffort",
        label: String = "Reasoning effort",
        type: String = "select",
        options: List<T3ModelOption> = listOf(T3ModelOption("medium", "Medium", true)),
        currentValue: String? = null,
    ) = T3OptionDescriptor(id, label, type, options, currentValue)

    private fun boardThread(id: String, selection: T3ModelSelection) = T3BoardThread(
        id = id,
        projectId = "p",
        title = id,
        modelSelection = selection,
        updatedAt = "2026-07-29T12:00:00Z",
        archivedAt = null,
        deletedAt = null,
        hasPendingApprovals = false,
        hasPendingUserInput = false,
        session = null,
    )
}
