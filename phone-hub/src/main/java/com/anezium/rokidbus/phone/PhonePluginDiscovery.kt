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
import java.security.MessageDigest

data class PhonePluginPrincipal(
    val packageName: String,
    val serviceComponent: ComponentName,
    val uid: Int,
    val signingDigestSha256: String,
    val descriptor: PluginDescriptor,
)

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
        return PackageRecord(
            packageName = packageName,
            serviceClassName = service.name,
            uid = service.applicationInfo?.uid ?: packageInfo?.applicationInfo?.uid ?: -1,
            exported = service.exported,
            signingCertificates = certificates,
            metadata = metadata,
        )
    }

    companion object {
        private val METADATA_KEYS = listOf(
            BusConstants.META_PLUGIN_ID,
            BusConstants.META_PLUGIN_DISPLAY_NAME,
            BusConstants.META_PLUGIN_API_VERSION,
            BusConstants.META_PLUGIN_CAPABILITIES,
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES,
            BusConstants.META_PLUGIN_SETTINGS_ACTIVITY,
            BusConstants.META_PLUGIN_LAUNCHABLE,
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
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(record.signingCertificates.single())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return PhonePluginCandidate.Valid(
                PhonePluginPrincipal(
                    packageName = record.packageName,
                    serviceComponent = component,
                    uid = record.uid,
                    signingDigestSha256 = digest,
                    descriptor = descriptor,
                ),
            )
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
