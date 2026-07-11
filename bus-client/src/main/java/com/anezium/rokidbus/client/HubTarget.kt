package com.anezium.rokidbus.client

import com.anezium.rokidbus.shared.BusConstants

data class HubTarget(
    val packageName: String,
    val serviceClassName: String,
    val action: String = BusConstants.ACTION_HUB,
) {
    init {
        require(packageName.isNotBlank())
        require(serviceClassName.isNotBlank())
        require(action.isNotBlank())
    }

    companion object {
        val PHONE = HubTarget(
            packageName = "com.anezium.rokidbus.phone",
            serviceClassName = "com.anezium.rokidbus.phone.BusHubService",
        )
        val GLASSES = HubTarget(
            packageName = "com.anezium.rokidbus.glasses",
            serviceClassName = "com.anezium.rokidbus.glasses.BusHubService",
        )
    }
}

data class HubServiceRecord(
    val packageName: String,
    val serviceClassName: String,
    val actions: Set<String>,
)

object HubServiceResolver {
    fun select(target: HubTarget, records: Collection<HubServiceRecord>): HubServiceRecord? {
        val matches = records.filter { record ->
            record.packageName == target.packageName &&
                record.serviceClassName == target.serviceClassName &&
                target.action in record.actions
        }
        return matches.singleOrNull()
    }
}
