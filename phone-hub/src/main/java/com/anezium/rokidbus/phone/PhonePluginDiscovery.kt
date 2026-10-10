package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParseResult
import com.anezium.rokidbus.shared.plugin.PluginDescriptorParser
import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillLimits

/** What discovery found of a plugin's skill catalog. An invalid catalog never invalidates the plugin. */
sealed interface PluginSkillsState {
    data object Absent : PluginSkillsState
    data class Invalid(val reason: String) : PluginSkillsState
    data class Valid(val catalog: SkillCatalog) : PluginSkillsState
}

data class PhonePluginPrincipal(
    val packageName: String,
    val serviceComponent: ComponentName,
    val uid: Int,
    val signingDigestSha256: String,
    val descriptor: PluginDescriptor,
    val guardianServiceComponent: ComponentName? = null,
    val skills: PluginSkillsState = PluginSkillsState.Absent,
    /** Changes with every install of the package; skill references and invocations are bound to it. */
    val packageRevision: Long = 0L,
) {
    val skillCatalog: SkillCatalog?
        get() = (skills as? PluginSkillsState.Valid)?.catalog
}

sealed interface PhonePluginCandidate {
    val packageName: String
    val displayName: String

    data class Valid(val principal: PhonePluginPrincipal) : PhonePluginCandidate {
        override val packageName: String = principal.packageName
        override val displayName: String = principal.descriptor.displayName
    }

    data class Invalid(
        override val packageName: String,
        override val displayName: String,
        val serviceComponent: ComponentName?,
        val reason: String,
    ) : PhonePluginCandidate
}

class PhonePluginDiscovery(private val packageManager: PackageManager) {
    data class PackageRecord(
        val packageName: String,
        val serviceClassName: String,
        val uid: Int,
        val exported: Boolean,
        val signingCertificates: List<ByteArray>,
        val metadata: List<Pair<String, String?>>,
        val skillsCatalog: ByteArray? = null,
        val skillsCatalogError: String? = null,
        val packageRevision: Long = 0L,
    )

    fun discover(): List<PhonePluginCandidate> = evaluate(loadRecords())

    fun discoverPackage(packageName: String): List<PhonePluginCandidate> =
        discover().filter { it.packageName == packageName }

    private fun loadRecords(): List<PackageRecord> {
        val flags = PackageManager.GET_META_DATA
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                Intent(BusConstants.ACTION_PLUGIN),
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(Intent(BusConstants.ACTION_PLUGIN), flags)
        }
        return matches.mapNotNull(::toRecord)
    }

    private fun toRecord(resolveInfo: ResolveInfo): PackageRecord? {
        val service = resolveInfo.serviceInfo ?: return null
        val packageName = service.packageName
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        }.getOrNull()

        @Suppress("DEPRECATION")
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            packageInfo?.signatures.orEmpty().map { it.toByteArray() }
        }
        val metadata = buildList {
            val bundle = service.metaData
            METADATA_KEYS.forEach { key ->
                if (bundle?.containsKey(key) == true) add(key to bundle.get(key)?.toString())
            }
        }
        // The catalog is read from the package's resources, which never starts the plugin.
        val catalogResId = service.metaData?.getInt(BusConstants.META_PLUGIN_SKILLS, 0) ?: 0
        var catalogError: String? = null
        val catalogBytes = if (catalogResId != 0) {
            runCatching {
                val applicationInfo = service.applicationInfo ?: error("no application info")
                packageManager.getResourcesForApplication(applicationInfo)
                    .openRawResource(catalogResId)
                    .use { input -> readCapped(input, SkillLimits.MAX_CATALOG_BYTES + 1) }
            }.onFailure { catalogError = "CATALOG_UNREADABLE" }.getOrNull()
        } else {
            null
        }
        return PackageRecord(
            packageName = packageName,
            serviceClassName = service.name,
            uid = service.applicationInfo?.uid ?: packageInfo?.applicationInfo?.uid ?: -1,
            exported = service.exported,
            signingCertificates = certificates,
            metadata = metadata,
            skillsCatalog = catalogBytes,
            skillsCatalogError = catalogError,
            packageRevision = packageInfo?.lastUpdateTime ?: 0L,
        )
    }

    private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buffer.size() < maxBytes) {
            val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    companion object {
        private val METADATA_KEYS = listOf(
            BusConstants.META_PLUGIN_ID,
            BusConstants.META_PLUGIN_DISPLAY_NAME,
            BusConstants.META_PLUGIN_ICON,
            BusConstants.META_PLUGIN_ICON_DRAWABLE,
            BusConstants.META_PLUGIN_GLYPHS,
            BusConstants.META_PLUGIN_API_VERSION,
            BusConstants.META_PLUGIN_CAPABILITIES,
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES,
            BusConstants.META_PLUGIN_SETTINGS_ACTIVITY,
            BusConstants.META_PLUGIN_LAUNCHABLE,
            BusConstants.META_PLUGIN_GUARDIAN_SERVICE,
            BusConstants.META_PLUGIN_SKILLS,
            BusConstants.META_PLUGIN_SKILLS_CLIENT,
        )

        fun evaluate(records: List<PackageRecord>): List<PhonePluginCandidate> {
            val initial = records.groupBy(PackageRecord::packageName).flatMap { (packageName, packageRecords) ->
                if (packageRecords.size != 1) {
                    listOf(
                        PhonePluginCandidate.Invalid(
                            packageName = packageName,
                            displayName = packageName,
                            serviceComponent = null,
                            reason = "MULTIPLE_PLUGIN_SERVICES",
                        ),
                    )
                } else {
                    listOf(evaluateRecord(packageRecords.single()))
                }
            }.toMutableList()

            invalidateConflicts(
                candidates = initial,
                key = { candidate -> (candidate as? PhonePluginCandidate.Valid)?.principal?.uid },
                isConflict = { candidates -> candidates.size > 1 },
                reason = { "SHARED_UID_UNSUPPORTED" },
            )

            invalidateConflicts(
                candidates = initial,
                key = { candidate ->
                    (candidate as? PhonePluginCandidate.Valid)?.principal?.descriptor?.id
                },
                isConflict = { candidates -> candidates.size > 1 },
                reason = { "DUPLICATE_PLUGIN_ID" },
            )

            return initial.sortedWith(
                compareBy<PhonePluginCandidate>({ it.displayName.lowercase() }, { it.packageName }),
            )
        }

        private fun evaluateRecord(record: PackageRecord): PhonePluginCandidate {
            val component = ComponentName(record.packageName, record.serviceClassName)
            fun invalid(reason: String, displayName: String = record.packageName) =
                PhonePluginCandidate.Invalid(record.packageName, displayName, component, reason)

            if (!record.exported) return invalid("SERVICE_NOT_EXPORTED")
            if (record.uid < 0) return invalid("MISSING_UID")
            if (record.signingCertificates.size != 1) return invalid("SIGNER_SET_UNSUPPORTED")
            val descriptorResult = PluginDescriptorParser.parse(record.metadata)
            val descriptor = when (descriptorResult) {
                is PluginDescriptorParseResult.Valid -> descriptorResult.descriptor
                is PluginDescriptorParseResult.Invalid -> return invalid(descriptorResult.reason)
            }
            if (descriptor.apiVersion != BusConstants.API_VERSION) {
                return invalid("UNSUPPORTED_API", descriptor.displayName)
            }
            val digest = signingCertificateSha256(record.signingCertificates.single())
            val guardianServiceComponent = record.metadata
                .lastOrNull { (key, _) -> key == BusConstants.META_PLUGIN_GUARDIAN_SERVICE }
                ?.second
                ?.let { className -> guardianComponent(record.packageName, className) }
            return PhonePluginCandidate.Valid(
                PhonePluginPrincipal(
                    packageName = record.packageName,
                    serviceComponent = component,
                    uid = record.uid,
                    signingDigestSha256 = digest,
                    descriptor = descriptor,
                    guardianServiceComponent = guardianServiceComponent,
                    skills = skillsState(descriptor, record),
                    packageRevision = record.packageRevision,
                ),
            )
        }

        internal fun skillsState(descriptor: PluginDescriptor, record: PackageRecord): PluginSkillsState {
            if (!descriptor.skillsDeclared) return PluginSkillsState.Absent
            if (descriptor.skillsCatalogResId == null) return PluginSkillsState.Invalid("CATALOG_RESOURCE_MISSING")
            record.skillsCatalogError?.let { return PluginSkillsState.Invalid(it) }
            val bytes = record.skillsCatalog ?: return PluginSkillsState.Invalid("CATALOG_UNREADABLE")
            return when (val parsed = SkillCatalogParser.parse(bytes)) {
                is SkillCatalogParseResult.Valid -> PluginSkillsState.Valid(parsed.catalog)
                is SkillCatalogParseResult.Invalid -> PluginSkillsState.Invalid(parsed.reason)
            }
        }

        internal fun guardianComponent(packageName: String, declaredClassName: String): ComponentName? {
            val className = declaredClassName.trim()
            if (className.isEmpty() || className.any(Char::isWhitespace) || '/' in className) return null
            val qualifiedClassName = when {
                className.startsWith('.') -> packageName + className
                '.' in className -> className
                else -> "$packageName.$className"
            }
            return ComponentName(packageName, qualifiedClassName)
        }

        private fun <K> invalidateConflicts(
            candidates: MutableList<PhonePluginCandidate>,
            key: (PhonePluginCandidate) -> K?,
            isConflict: (List<PhonePluginCandidate>) -> Boolean,
            reason: () -> String,
        ) {
            candidates.mapNotNull { candidate -> key(candidate)?.let { it to candidate } }
                .groupBy({ it.first }, { it.second })
                .values
                .filter(isConflict)
                .flatten()
                .forEach { candidate ->
                    val valid = candidate as? PhonePluginCandidate.Valid ?: return@forEach
                    val index = candidates.indexOf(candidate)
                    candidates[index] = PhonePluginCandidate.Invalid(
                        packageName = valid.packageName,
                        displayName = valid.displayName,
                        serviceComponent = valid.principal.serviceComponent,
                        reason = reason(),
                    )
                }
        }
    }
}
