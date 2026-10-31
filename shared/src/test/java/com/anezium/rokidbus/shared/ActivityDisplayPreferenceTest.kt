package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActivityDisplayPreferenceTest {
    @Test fun `display policy round trips and older phones retain automatic behavior`() {
        val policy = AmbientPolicy(1, emptyMap(), activityDisplays = mapOf("media" to ActivityDisplayPreference(ActivityDisplayMode.EVENTS, 3_000)))
        assertEquals(policy, AmbientPreferencesContract.parsePolicy(AmbientPreferencesContract.policyPayload(policy)))
        val old = JSONObject().put("revision", 1).put("modes", JSONObject())
        assertTrue(AmbientPreferencesContract.parsePolicy(old)!!.activityDisplays.isEmpty())
        val malformed = AmbientPreferencesContract.policyPayload(policy).apply {
            getJSONObject("activityDisplays").getJSONObject("media").put("durationMs", 60_000)
        }
        assertNull(AmbientPreferencesContract.parsePolicy(malformed))
        val unknown = AmbientPreferencesContract.policyPayload(policy).apply {
            getJSONObject("activityDisplays").getJSONObject("media").put("mode", "future")
        }
        assertTrue(AmbientPreferencesContract.parsePolicy(unknown)!!.activityDisplays.isEmpty())
    }
}
