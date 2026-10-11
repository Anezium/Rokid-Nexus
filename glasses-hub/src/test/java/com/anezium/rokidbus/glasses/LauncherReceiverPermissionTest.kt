package com.anezium.rokidbus.glasses

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class LauncherReceiverPermissionTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun receiver(name: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = manifest.getElementsByTagName("receiver")
        val found = (0 until receivers.length).map { receivers.item(it) as Element }
            .firstOrNull { it.getAttributeNS(androidNamespace, "name") == name }
        assertNotNull("$name is declared", found)
        return found!!
    }

    @Test
    fun `the ring bridge can still open the launcher while only adb can switch its backend`() {
        // The R08 Access Bridge is an ordinary app: it opens the launcher with this broadcast.
        assertEquals("", receiver(".OpenLauncherReceiver").getAttributeNS(androidNamespace, "permission"))
        assertEquals("true", receiver(".OpenLauncherReceiver").getAttributeNS(androidNamespace, "exported"))
        assertEquals("android.permission.DUMP", receiver(".SetLauncherBackendReceiver").getAttributeNS(androidNamespace, "permission"))
    }
}
