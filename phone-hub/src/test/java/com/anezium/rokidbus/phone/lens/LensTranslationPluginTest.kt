package com.anezium.rokidbus.phone.lens

import android.content.Context
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.NexusSubscription
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LensTranslationPluginTest {
    @Test
    fun `configured target language overrides wire value at provider seam`() {
        val provider = RecordingProvider()
        val host = RecordingHost()
        val plugin = LensTranslationPlugin(provider) { "ja" }
        try {
            plugin.onRegister(host)

            host.dispatch(
                path = BusPaths.LENS_TRANSLATE_REQUEST,
                id = "request-1",
                payload = JSONObject()
                    .put("targetLang", "fr")
                    .put("strings", JSONArray().put("hello")),
            )

            assertEquals("ja", provider.lastRequest?.targetLang)
        } finally {
            plugin.close()
        }
    }

    @Test
    fun `reply path envelope is ignored despite request prefix collision`() {
        val provider = RecordingProvider()
        val host = RecordingHost()
        val plugin = LensTranslationPlugin(provider)
        try {
            plugin.onRegister(host)

            host.dispatch(
                path = BusPaths.LENS_TRANSLATE_REPLY,
                id = "request-1",
                payload = JSONObject().put("strings", org.json.JSONArray().put("hello")),
            )

            assertEquals(BusPaths.LENS_TRANSLATE_REQUEST, host.subscribedPrefix)
            assertEquals(0, provider.translateCalls)
            assertEquals(0, host.sendCount)
        } finally {
            plugin.close()
        }
    }

    private class RecordingProvider : TranslationProvider {
        var translateCalls = 0
        var lastRequest: TranslationRequest? = null

        override fun translate(
            request: TranslationRequest,
            callback: TranslationProvider.Callback,
        ): TranslationCall {
            translateCalls += 1
            lastRequest = request
            return TranslationCall.NONE
        }

        override fun close() = Unit
    }

    private class RecordingHost : NexusPluginHost {
        override val context: Context
            get() = error("Injected provider must not request an Android context")

        var subscribedPrefix: String? = null
        var sendCount = 0
        private var handler: ((String, String, JSONObject) -> Unit)? = null

        override fun send(path: String, payload: JSONObject) {
            sendCount += 1
        }

        override fun sendBinary(path: String, payload: JSONObject, data: ByteArray) = Unit

        override fun supportsImageSurface(): Boolean = false

        override fun subscribe(
            pathPrefix: String,
            handler: (path: String, id: String, payload: JSONObject) -> Unit,
        ): NexusSubscription {
            subscribedPrefix = pathPrefix
            this.handler = handler
            return NexusSubscription { this.handler = null }
        }

        override fun post(action: () -> Unit) = action()

        override fun log(message: String) = Unit

        fun dispatch(path: String, id: String, payload: JSONObject) {
            handler?.invoke(path, id, payload)
        }
    }
}
