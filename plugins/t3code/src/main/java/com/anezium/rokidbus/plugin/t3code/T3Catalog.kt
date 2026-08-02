package com.anezium.rokidbus.plugin.t3code

internal object T3Catalog {
    private val effortIds = setOf("effort", "reasoningeffort", "thinkinglevel")

    fun effortDescriptor(model: T3Model): T3OptionDescriptor? = model.optionDescriptors.firstOrNull { descriptor ->
        descriptor.type.equals("select", ignoreCase = true) &&
            descriptor.options.isNotEmpty() &&
            (
                descriptor.id.lowercase() in effortIds ||
                    descriptor.label.lowercase().let { label ->
                        "reasoning" in label || "effort" in label || "thinking" in label
                    }
                )
    }

    fun reachableModels(
        provider: T3Provider,
        project: T3Project,
        boardThreads: List<T3BoardThread>,
        limit: Int = MODEL_PICKER_LIMIT,
    ): List<T3Model> {
        val bySlug = provider.models.associateBy(T3Model::slug)
        val priority = LinkedHashSet<String>()
        project.defaultModelSelection
            ?.takeIf { it.instanceId == provider.instanceId }
            ?.model
            ?.let(priority::add)
        boardThreads.asSequence()
            .filter { it.modelSelection.instanceId == provider.instanceId }
            .map { it.modelSelection.model }
            .forEach(priority::add)

        val ordered = LinkedHashMap<String, T3Model>()
        priority.forEach { slug -> bySlug[slug]?.let { ordered[slug] = it } }
        provider.models.forEach { model -> ordered.putIfAbsent(model.slug, model) }
        return ordered.values.take(limit)
    }

    const val MODEL_PICKER_LIMIT = 24
}
