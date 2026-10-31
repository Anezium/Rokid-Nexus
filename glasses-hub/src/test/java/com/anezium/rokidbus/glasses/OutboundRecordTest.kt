package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/** The host test's outbound record counts attempts, never more and never fewer. */
class OutboundRecordTest {
    private val request = "/page/request"
    private val action = "/page/action"

    private fun failedCxr(path: String) = "CXR-S TX $path id=a1 result=null"
    private fun dropped(path: String) = "session send dropped path=$path"

    @Test
    fun `a failed CXR send and the session's drop of it are one attempt`() {
        assertEquals(listOf(request), OutboundRecord.paths(listOf(failedCxr(request), dropped(request))))
        assertEquals(listOf(request), OutboundRecord.paths(listOf("CXR-S TX $request id=a1 result=-2", dropped(request))))
    }

    @Test
    fun `two failed pairs of the same path are two attempts`() {
        val lines = listOf(failedCxr(request), dropped(request), failedCxr(request), dropped(request))
        assertEquals(listOf(request, request), OutboundRecord.paths(lines))
    }

    @Test
    fun `a successful send is never merged with a later drop of the same path`() {
        assertEquals(
            listOf(action, action),
            OutboundRecord.paths(listOf("SPP TX $action id=first", "link lost", dropped(action))),
        )
        assertEquals(listOf(action, action), OutboundRecord.paths(listOf("SPP TX $action id=first", dropped(action))))
        assertEquals(listOf(action, action), OutboundRecord.paths(listOf("CXR-S TX $action id=first result=0", dropped(action))))
    }

    @Test
    fun `a failed send pairs only with the drop on the very next line`() {
        assertEquals(listOf(request, request), OutboundRecord.paths(listOf(failedCxr(request), "phone link lost", dropped(request))))
        assertEquals(listOf(request, action), OutboundRecord.paths(listOf(failedCxr(request), dropped(action))))
    }

    @Test
    fun `standalone and repeated drops each count`() {
        assertEquals(listOf(request), OutboundRecord.paths(listOf(dropped(request))))
        assertEquals(listOf(request, request), OutboundRecord.paths(listOf(dropped(request), dropped(request))))
    }

    @Test
    fun `an SPP write failure names no path`() {
        assertEquals(listOf(request), OutboundRecord.paths(listOf("SPP TX failed IOException", failedCxr(request), dropped(request))))
    }
}
