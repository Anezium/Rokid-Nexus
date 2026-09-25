package com.anezium.rokidbus.phone

import android.util.AtomicFile
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneSppPairingStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lastIdentityAndConfirmedAddressBindingsSurviveRecreation() {
        val file = File(temporary.root, "pairings")
        val first = storage(file)
        assertNull(first.lastIdentity())
        assertNull(first.boundIdentity("AA:BB"))
        first.remember("cxr:serial:a")
        assertNull(first.boundIdentity("AA:BB"))
        first.bind("aa:bb", "cxr:serial:a")
        first.bind("cc:dd", "cxr:serial:b")
        val reopened = storage(file)
        assertEquals("cxr:serial:a", reopened.boundIdentity("AA:BB"))
        assertEquals("cxr:serial:b", reopened.boundIdentity("cc:dd"))
        assertEquals("cxr:serial:b", reopened.lastIdentity())
        reopened.remember("cxr:name:c")
        assertEquals("cxr:name:c", storage(file).lastIdentity())
        assertEquals("cxr:serial:a", storage(file).boundIdentity("aa:bb"))
    }

    @Test fun corruptMetadataDoesNotEraseConfirmedBindingsOrInventFallbackIdentity() {
        val file = File(temporary.root, "pairings")
        file.writeText("broken record")
        val store = storage(file)
        assertThrows(IOException::class.java) { store.lastIdentity() }
        assertThrows(IOException::class.java) { store.boundIdentity("AA:BB") }
        assertThrows(IOException::class.java) { store.remember("cxr:current") }
        assertThrows(IOException::class.java) { store.bind("AA:BB", "cxr:current") }
        assertEquals("broken record", file.readText())
    }

    private fun storage(file: File) = PhoneSppPairingStorage(AtomicFile(file)) {
        object : SppPairingKeyStore {
            override fun load(): ByteArray? = error("Metadata must not load key material")
            override fun save(key: ByteArray): Boolean = error("Metadata must not save key material")
        }
    }
}
