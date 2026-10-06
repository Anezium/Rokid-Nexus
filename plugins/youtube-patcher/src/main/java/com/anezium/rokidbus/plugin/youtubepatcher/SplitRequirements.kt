package com.anezium.rokidbus.plugin.youtubepatcher

import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType

/** Manifest-declared completeness only: no claim that arbitrary store archives are complete. */
internal data class SplitRequirements(
    val name: String? = null,
    val configFor: String? = null,
    val required: Boolean = false,
    val requiredTypes: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val isFeature: Boolean = name != null && configFor == null && !name.startsWith("config.")
) {
    companion object {
        fun read(manifest: AndroidManifestBlock): SplitRequirements {
            val root = requireNotNull(manifest.manifestElement) { "APK manifest missing." }
            val elements = listOfNotNull(root, manifest.applicationElement)
            fun string(element: ResXmlElement, id: Int): String? {
                val attribute = element.searchAttributeByResourceId(id) ?: return null
                require(attribute.valueType == ValueType.STRING) { "Unresolvable split requirement." }
                return attribute.valueAsString
            }
            fun boolean(element: ResXmlElement, id: Int): Boolean {
                val attribute = element.searchAttributeByResourceId(id) ?: return false
                require(attribute.valueType == ValueType.BOOLEAN) { "Unresolvable split requirement." }
                return attribute.valueAsBoolean
            }
            fun tokens(text: String?): Set<String> {
                if (text.isNullOrBlank()) return emptySet()
                val parts = text.split(',').map { it.trim() }
                require(parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Malformed split types." }
                return parts.toSet()
            }
            val dependencies = root.listElements("uses-split").map { child ->
                requireNotNull(string(child as ResXmlElement, AndroidManifest.ID_name)) { "Missing uses-split name." }
            }.toSet()
            val legacyRequired = manifest.applicationElement?.listElements("meta-data")?.any { child ->
                val element = child as ResXmlElement
                string(element, AndroidManifest.ID_name) == "com.android.vending.splits.required" &&
                    boolean(element, AndroidManifest.ID_value)
            } ?: false
            val config = root.searchAttributeByName("configForSplit")?.let {
                require(it.valueType == ValueType.STRING) { "Unresolvable configForSplit." }
                it.valueAsString
            }
            return SplitRequirements(manifest.split?.takeIf { it.isNotBlank() }, config,
                legacyRequired || elements.map { boolean(it, AndroidManifest.ID_isSplitRequired) }.any { it },
                elements.flatMap { tokens(string(it, AndroidManifest.ID_requiredSplitTypes)) }.toSet(),
                tokens(string(root, AndroidManifest.ID_splitTypes)), dependencies,
                boolean(root, AndroidManifest.ID_isFeatureSplit))
        }

        fun validate(modules: Collection<SplitRequirements>) {
            require(modules.count { it.name == null } == 1) {
                "Only single-base APK archives are supported; alternative base/universal APKS sets are not supported."
            }
            val named = modules.mapNotNull { it.name }
            require(named.size == named.toSet().size) { "Duplicate split module." }
            val names = named.toSet()
            val byName = modules.filter { it.name != null }.associateBy { it.name!! }
            val visited = mutableSetOf<String>()
            fun visit(name: String, visiting: Set<String>) {
                require(name !in visiting) { "Cyclic split dependencies." }
                if (name in visited) return
                val module = byName[name] ?: return
                module.dependencies.forEach { visit(it, visiting + name) }
                visited += name
            }
            named.forEach { visit(it, emptySet()) }
            modules.forEach { module ->
                require(module.dependencies.all { it in names && it != module.name }) { "Missing required uses-split module." }
                val owner = module.configFor?.takeUnless { it.isBlank() || it == "base" }
                require(owner == null || (owner in names && owner != module.name &&
                    byName.getValue(owner).isFeature)) {
                    "Configuration split has no owning feature."
                }
                require(module.name != null || (module.configFor == null && !module.isFeature)) { "Invalid base split flags." }
                require(!module.isFeature || module.configFor == null) { "Feature cannot be a configuration split." }
                val configs = modules.filter { candidate ->
                    candidate.name != null && candidate !== module &&
                        !candidate.isFeature &&
                        if (module.name == null) candidate.configFor.isNullOrBlank() || candidate.configFor == "base"
                        else candidate.configFor == module.name
                }
                val provided = configs.flatMap { it.types }.toSet()
                require(provided.containsAll(module.requiredTypes)) { "Missing required split types for ${module.name ?: "base"}." }
                if (module.required && module.requiredTypes.isEmpty()) {
                    // Legacy manifests only say 'splits required'. A feature alone is not evidence
                    // of a complete base: require an actual configuration module, fail closed otherwise.
                    require(configs.isNotEmpty()) {
                        "Split-required base/module is incomplete; choose the complete split archive."
                    }
                }
            }
        }
    }
}
