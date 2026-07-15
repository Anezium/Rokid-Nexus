package com.anezium.rokidbus.phone

data class PluginGrantReconciliation(
    val candidates: List<PhonePluginCandidate>,
    val validPrincipals: List<PhonePluginPrincipal>,
)

class PluginGrantReconciler(
    private val discoverCandidates: () -> List<PhonePluginCandidate>,
    private val reconcileGrants: (Collection<PhonePluginPrincipal>) -> Unit,
) {
    fun reconcile(): PluginGrantReconciliation {
        val candidates = discoverCandidates()
        val validPrincipals = candidates.mapNotNull { candidate ->
            (candidate as? PhonePluginCandidate.Valid)?.principal
        }
        reconcileGrants(validPrincipals)
        return PluginGrantReconciliation(candidates, validPrincipals)
    }
}
