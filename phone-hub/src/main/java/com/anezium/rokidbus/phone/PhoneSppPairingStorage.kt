package com.anezium.rokidbus.phone

import android.content.Context
import android.util.AtomicFile
import com.anezium.rokidbus.shared.SppKeyStore
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

internal class PhoneSppPairingStorage internal constructor(
    private val file: AtomicFile,
    private val keyStore: (String) -> SppPairingKeyStore,
) : PhoneSppPairingStore {
    constructor(context: Context) : this(
        AtomicFile(File(context.applicationContext.noBackupFilesDir, "spp-cxr-pairings")),
        { SppKeyStore(context.applicationContext, it) },
    )

    override fun keys(identity: String) = keyStore(identity)

    @Synchronized
    override fun lastIdentity(): String? = read().optString("last").takeIf { it.isNotBlank() }

    @Synchronized
    override fun boundIdentity(address: String): String? =
        read().optJSONObject("bindings")?.optString(address.uppercase(Locale.ROOT))?.takeIf { it.isNotBlank() }

    @Synchronized
    override fun remember(identity: String) {
        val state = read()
        if (state.optString("last") != identity) write(state.put("last", identity))
    }

    @Synchronized
    override fun bind(address: String, identity: String) {
        val state = read()
        val bindings = state.optJSONObject("bindings") ?: JSONObject()
        bindings.put(address.uppercase(Locale.ROOT), identity)
        write(state.put("bindings", bindings).put("last", identity))
    }

    private fun read(): JSONObject = try {
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    } catch (_: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw IOException("SPP pairing state unavailable")
        JSONObject()
    } catch (_: Exception) {
        throw IOException("SPP pairing state unavailable")
    }

    private fun write(state: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
            throw IOException("SPP pairing state unavailable")
        }
    }
}
