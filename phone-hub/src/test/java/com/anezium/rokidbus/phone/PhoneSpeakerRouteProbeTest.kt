package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSpeakerRouteProbeTest {
    @Test
    fun `attribute-aware winner distinguishes external glasses and phone routes`() {
        val glassesAddress = "AA:BB:CC:DD:EE:01"

        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(bluetooth("AA:BB:CC:DD:EE:02")),
                glassesAddress,
            ),
        )
        assertEquals(
            PhoneTtsRoute.GLASSES_LINK,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(bluetooth("aa:bb:cc:dd:ee:01")),
                glassesAddress,
            ),
        )
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE)),
                glassesAddress,
            ),
        )
    }

    @Test
    fun `empty unknown and unidentified bluetooth winners fail safe to phone speaker`() {
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyWinningDevice(emptyList(), "glasses"),
        )
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.UNKNOWN)),
                "glasses",
            ),
        )
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(bluetooth("AA:BB:CC:DD:EE:02")),
                glassesAddress = null,
            ),
        )
    }

    @Test
    fun `non bluetooth external winner does not require glasses identity`() {
        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyWinningDevice(
                listOf(PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.OTHER_EXTERNAL)),
                glassesAddress = null,
            ),
        )
    }

    @Test
    fun `legacy scan prefers a present glasses link over other bluetooth outputs`() {
        assertEquals(
            PhoneTtsRoute.GLASSES_LINK,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(
                    PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE),
                    bluetooth("AA:BB:CC:DD:EE:02"),
                    bluetooth("AA:BB:CC:DD:EE:01"),
                ),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
    }

    @Test
    fun `legacy scan accepts identified bluetooth and physical external sinks`() {
        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(
                    PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE),
                    bluetooth("AA:BB:CC:DD:EE:02"),
                ),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.OTHER_EXTERNAL)),
                glassesAddress = null,
            ),
        )
    }

    @Test
    fun `legacy scan fails safe when no known external route can be identified`() {
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(
                    PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE),
                    PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.UNKNOWN),
                ),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(bluetooth(address = null)),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
    }

    @Test
    fun `relay scan starts when earbuds are listed alongside the glasses`() {
        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyRelayScan(
                listOf(
                    PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE),
                    bluetooth("AA:BB:CC:DD:EE:01"),
                    bluetooth("AA:BB:CC:DD:EE:02"),
                ),
                glassesAddress = "aa:bb:cc:dd:ee:01",
            ),
        )
        assertEquals(
            PhoneTtsRoute.EXTERNAL_SINK,
            PhoneTtsRouteClassifier.classifyRelayScan(
                listOf(bluetooth("AA:BB:CC:DD:EE:01"), PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.OTHER_EXTERNAL)),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
    }

    @Test
    fun `relay scan does not start on the glasses alone or on unidentified bluetooth`() {
        assertEquals(
            PhoneTtsRoute.GLASSES_LINK,
            PhoneTtsRouteClassifier.classifyRelayScan(
                listOf(PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.PHONE), bluetooth("AA:BB:CC:DD:EE:01")),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
        assertEquals(
            PhoneTtsRoute.GLASSES_LINK,
            PhoneTtsRouteClassifier.classifyRelayScan(
                listOf(bluetooth(address = null), bluetooth("AA:BB:CC:DD:EE:01")),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
        assertEquals(
            PhoneTtsRoute.PHONE_SPEAKER,
            PhoneTtsRouteClassifier.classifyRelayScan(
                listOf(bluetooth("AA:BB:CC:DD:EE:02")),
                glassesAddress = null,
            ),
        )
    }

    @Test
    fun `tts scan still prefers the glasses when earbuds are listed`() {
        assertEquals(
            PhoneTtsRoute.GLASSES_LINK,
            PhoneTtsRouteClassifier.classifyScannedDevices(
                listOf(bluetooth("AA:BB:CC:DD:EE:01"), PhoneTtsRouteDevice(PhoneTtsRouteDeviceType.OTHER_EXTERNAL)),
                glassesAddress = "AA:BB:CC:DD:EE:01",
            ),
        )
    }

    private fun bluetooth(address: String?) = PhoneTtsRouteDevice(
        type = PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL,
        address = address,
    )
}
