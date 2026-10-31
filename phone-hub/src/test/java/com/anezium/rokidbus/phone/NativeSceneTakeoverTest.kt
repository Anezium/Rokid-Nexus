package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSceneTakeoverTest {
    @Test
    fun `an empty or missing report has nothing running`() {
        assertEquals(emptySet<String>(), NativeSceneTakeover.running(null))
        assertEquals(emptySet<String>(), NativeSceneTakeover.running(""))
        assertEquals(emptySet<String>(), NativeSceneTakeover.running("  "))
    }

    @Test
    fun `an array names the running scenes, ignoring names outside the list`() {
        assertEquals(
            setOf("phoneCallRunning", "videoRecordRunning"),
            NativeSceneTakeover.running("""["videoRecordRunning","launcher","phoneCallRunning"]"""),
        )
        assertEquals(emptySet<String>(), NativeSceneTakeover.running("""["launcher","jsai"]"""))
    }

    @Test
    fun `an object counts only entries set to true, 1 or the string true`() {
        assertEquals(
            setOf("navigationRunning", "otaRunning", "paymentRunning"),
            NativeSceneTakeover.running(
                """{"phoneCallRunning":false,"navigationRunning":true,"otaRunning":1,""" +
                    """"paymentRunning":"true","notesRunning":0,"translateRunning":"idle"}""",
            ),
        )
    }

    @Test
    fun `Global Hi Rokid names the active scenes as comma separated text`() {
        assertEquals(setOf("wordTipsRunning"), NativeSceneTakeover.running("wordTipsRunning"))
        assertEquals(
            setOf("phoneCallRunning", "videoRecordRunning"),
            NativeSceneTakeover.running(" phoneCallRunning, videoRecordRunning,unknownScene "),
        )
        assertEquals(emptySet<String>(), NativeSceneTakeover.running("aiChatRunning"))
        assertEquals("names[1]", NativeSceneTakeover.shape("wordTipsRunning"))
        assertEquals("names[2]", NativeSceneTakeover.shape("phoneCallRunning,videoRecordRunning"))
    }

    @Test
    fun `text scenes match whole names and do not treat false assignments as active`() {
        listOf(
            "notwordTipsRunning", "wordTipsRunningEnded", "wordTipsRunning=false",
            "wordTipsRunning:false", "wordTipsRunning stopped", "wordTipsRunning,",
        ).forEach { report ->
            assertEquals(report, emptySet<String>(), NativeSceneTakeover.running(report))
        }
    }

    @Test
    fun `any other shape reads as nothing running`() {
        listOf(
            """{"scenes":["cameraPageRunning"]}""",
            """{"scenes":{"phoneCallRunning":true}}""",
            """{"scene":"phoneCallRunning","state":"end"}""",
            """{"phoneCallRunningState":"idle"}""",
            """{"phoneCallRunning":""",
        ).forEach { report ->
            assertEquals(report, emptySet<String>(), NativeSceneTakeover.running(report))
        }
    }

    @Test
    fun `the shape carries structure and identifier-like keys, never values`() {
        assertEquals("empty", NativeSceneTakeover.shape(null))
        assertEquals("array[2]", NativeSceneTakeover.shape("""["a","b"]"""))
        assertEquals(
            "object{phoneCallRunning,scene}",
            NativeSceneTakeover.shape("""{"scene":"Navigating to 12 Rue X","phoneCallRunning":true}"""),
        )
        assertEquals("object{a,+1}", NativeSceneTakeover.shape("""{"a":1,"has space":2}"""))
        assertEquals("names[1]", NativeSceneTakeover.shape("otaRunning"))
        assertEquals("other", NativeSceneTakeover.shape("""{"broken":"""))
    }

    @Test
    fun `only native scenes other than accessibility keep the assist button with Rokid`() {
        assertFalse(NativeSceneTakeover.blocksAssistantTakeover(emptySet()))
        assertFalse(NativeSceneTakeover.blocksAssistantTakeover(setOf("accessibilityRunning")))
        assertTrue(NativeSceneTakeover.blocksAssistantTakeover(setOf("phoneCallRunning")))
        assertTrue(
            NativeSceneTakeover.blocksAssistantTakeover(
                setOf("accessibilityRunning", "translateRunning"),
            ),
        )
    }

    @Test
    fun `the takeover list is SCENES_TAKEOVER from client-l 1_1_2, in order`() {
        assertEquals(
            listOf(
                "audioRecordRunning",
                "brightnessRunning",
                "navigationRunning",
                "notesRunning",
                "phoneCallRunning",
                "translateRunning",
                "videoRecordRunning",
                "wordTipsRunning",
                "paymentRunning",
                "cityGuideRunning",
                "otaRunning",
                "liveBroadcastRunning",
                "accessibilityRunning",
                "cameraPageRunning",
            ),
            NativeSceneTakeover.SCENES,
        )
    }
}
