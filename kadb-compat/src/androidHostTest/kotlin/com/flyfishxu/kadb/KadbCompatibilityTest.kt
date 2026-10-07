package com.flyfishxu.kadb

import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertException
import com.flyfishxu.kadb.cert.KadbCertPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class KadbCompatibilityTest {
    @Test fun rebuiltApiUsesJava11Bytecode() {
        DataInputStream(Kadb::class.java.getResourceAsStream("Kadb.class")!!).use {
            assertEquals(0xCAFEBABE.toInt(), it.readInt())
            it.readUnsignedShort()
            assertEquals(55, it.readUnsignedShort())
        }
    }

    @Test fun identitySurvivesReloadAndReturnedKeysAreCopies() {
        val store = InMemoryPrivateKeyStore()
        val policy = KadbCertPolicy(autoHealInvalidPrivateKey = false)
        KadbCert.configure(store, policy)
        val original = KadbCert.ensureReady().privateKeyPem.copyOf()
        KadbCert.exportPrivateKeyOrNull()!!.fill(0)
        assertArrayEquals(original, KadbCert.exportPrivateKeyOrNull())
        KadbCert.configure(store, policy)
        assertArrayEquals(original, KadbCert.ensureReady().privateKeyPem)
    }

    @Test fun malformedStoredIdentityFailsClosedWhenAutoHealIsDisabled() {
        val store = InMemoryPrivateKeyStore()
        val malformed = "not a private key".toByteArray()
        store.writePrivateKeyPemAtomic(malformed)
        KadbCert.configure(store, KadbCertPolicy(autoHealInvalidPrivateKey = false))
        assertThrows(KadbCertException.PrivateKeyParseFailed::class.java) { KadbCert.ensureReady() }
        assertArrayEquals(malformed, store.readPrivateKeyPem())
    }
}
