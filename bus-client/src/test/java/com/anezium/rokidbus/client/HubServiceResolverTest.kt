package com.anezium.rokidbus.client

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubServiceResolverTest {
    private val target = HubTarget.PHONE
    private val valid = HubServiceRecord(
        target.packageName,
        target.serviceClassName,
        setOf(BusConstants.ACTION_HUB),
    )

    @Test
    fun `malicious earlier query result is ignored`() {
        val malicious = HubServiceRecord("dev.malicious", "dev.malicious.Hub", setOf(BusConstants.ACTION_HUB))
        assertEquals(valid, HubServiceResolver.select(target, listOf(malicious, valid)))
    }

    @Test
    fun `missing expected package is rejected`() {
        assertNull(
            HubServiceResolver.select(
                target,
                listOf(HubServiceRecord("dev.other", target.serviceClassName, setOf(BusConstants.ACTION_HUB))),
            ),
        )
    }

    @Test
    fun `wrong service action is rejected`() {
        assertNull(HubServiceResolver.select(target, listOf(valid.copy(actions = setOf("wrong.action")))))
    }

    @Test
    fun `exact explicit target resolves`() {
        assertEquals(valid, HubServiceResolver.select(target, listOf(valid)))
    }
}
