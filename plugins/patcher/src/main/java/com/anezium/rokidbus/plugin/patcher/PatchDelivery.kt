package com.anezium.rokidbus.plugin.patcher

internal object PatchDelivery {
    fun canAutoDeliver(state: PatchJobState, watchedJobId: String?, readyJobId: String?): Boolean =
        state.status == PatchJobStatus.SUCCESS && !state.delivered &&
            (state.id == watchedJobId || state.id == readyJobId)
}
