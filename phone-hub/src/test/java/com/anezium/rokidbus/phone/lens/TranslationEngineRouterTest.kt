package com.anezium.rokidbus.phone.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineRouterTest {
    @Test
    fun `AUTO tries keyed providers then Google and stops on first success`() {
        val order = mutableListOf<String>()
        val gemini = fakeFailure("gemini", order)
        val deepL = fakeFailure("deepl", order)
        val google = fakeSuccess("google", order)
        val mlKit = fakeSuccess("mlkit", order)
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
                geminiApiKey = "gemini-key",
            ),
            google = google,
            deepL = deepL,
            gemini = gemini,
            mlKit = mlKit,
        )
        try {
            val result = translate(router)

            assertEquals(listOf("deepl", "gemini", "google"), order)
            assertEquals("google-result", result.single().dst)
        } finally {
            router.close()
        }
    }

    @Test
    fun `AUTO without keys starts with Google`() {
        val order = mutableListOf<String>()
        val router = router(
            config = TranslationEngineConfig(),
            google = fakeSuccess("google", order),
            deepL = fakeFailure("deepl", order),
            gemini = fakeFailure("gemini", order),
            mlKit = fakeSuccess("mlkit", order),
        )
        try {
            translate(router)

            assertEquals(listOf("google"), order)
        } finally {
            router.close()
        }
    }

    @Test
    fun `online failure falls back to ML Kit`() {
        val order = mutableListOf<String>()
        val router = router(
            config = TranslationEngineConfig(engine = TranslationEngine.GOOGLE_WEB),
            google = fakeFailure("google", order, TranslationErrorCode.TIMEOUT),
            deepL = fakeFailure("deepl", order),
            gemini = fakeFailure("gemini", order),
            mlKit = fakeSuccess("mlkit", order),
        )
        try {
            val result = translate(router)

            assertEquals(listOf("google", "mlkit"), order)
            assertEquals("mlkit-result", result.single().dst)
        } finally {
            router.close()
        }
    }

    @Test
    fun `explicit engine tries only that online engine before ML Kit`() {
        val order = mutableListOf<String>()
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.DEEPL,
                deepLApiKey = "deepl-key",
                geminiApiKey = "gemini-key",
            ),
            google = fakeSuccess("google", order),
            deepL = fakeSuccess("deepl", order),
            gemini = fakeSuccess("gemini", order),
            mlKit = fakeSuccess("mlkit", order),
        )
        try {
            translate(router)

            assertEquals(listOf("deepl"), order)
        } finally {
            router.close()
        }
    }

    @Test
    fun `spent online budget skips remaining online engines and starts ML Kit`() {
        val order = mutableListOf<String>()
        var nowMs = 0L
        val gemini = FakeProvider { _, callback ->
            order += "gemini"
            nowMs += 3_000L
            callback.onError(TranslationErrorCode.TRANSLATION_FAILED)
        }
        val deepL = FakeProvider { _, callback ->
            order += "deepl"
            nowMs += 3_000L
            callback.onError(TranslationErrorCode.TRANSLATION_FAILED)
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
                geminiApiKey = "gemini-key",
            ),
            google = fakeSuccess("google", order),
            deepL = deepL,
            gemini = gemini,
            mlKit = fakeSuccess("mlkit", order),
            nowMs = { nowMs },
        )
        try {
            translate(router)

            assertEquals(listOf("deepl", "gemini", "mlkit"), order)
        } finally {
            router.close()
        }
    }

    @Test
    fun `blank key skips explicitly selected keyed engine`() {
        val order = mutableListOf<String>()
        val router = router(
            config = TranslationEngineConfig(engine = TranslationEngine.GEMINI),
            google = fakeSuccess("google", order),
            deepL = fakeSuccess("deepl", order),
            gemini = fakeSuccess("gemini", order),
            mlKit = fakeSuccess("mlkit", order),
        )
        try {
            translate(router)

            // The keyed engine is skipped; keyless Google Web now rescues before offline.
            assertEquals(listOf("google"), order)
        } finally {
            router.close()
        }
    }

    private fun router(
        config: TranslationEngineConfig,
        google: TranslationProvider,
        deepL: TranslationProvider,
        gemini: TranslationProvider,
        mlKit: TranslationProvider,
        nowMs: () -> Long = { 0L },
    ): TranslationEngineRouter =
        TranslationEngineRouter(
            configSupplier = { config },
            googleWebProvider = google,
            deepLProvider = deepL,
            geminiProvider = gemini,
            mlKitProvider = mlKit,
            nowMs = nowMs,
            logger = {},
        )

    private fun translate(router: TranslationEngineRouter): List<TranslationResult> {
        var success: List<TranslationResult>? = null
        var receivedError: TranslationErrorCode? = null
        router.translate(
            REQUEST,
            object : TranslationProvider.Callback {
                override fun onDownloading(status: TranslationDownloadStatus) = Unit

                override fun onSuccess(translations: List<TranslationResult>) {
                    success = translations
                }

                override fun onError(error: TranslationErrorCode) {
                    receivedError = error
                }
            },
        )
        assertTrue("Unexpected error: $receivedError", receivedError == null)
        return checkNotNull(success) { "Router did not complete synchronously with the fake providers" }
    }

    private fun fakeSuccess(
        name: String,
        order: MutableList<String>,
    ): FakeProvider = FakeProvider { request, callback ->
        order += name
        callback.onSuccess(
            request.strings.map { source ->
                TranslationResult(source, "$name-result", "en")
            },
        )
    }

    private fun fakeFailure(
        name: String,
        order: MutableList<String>,
        error: TranslationErrorCode = TranslationErrorCode.TRANSLATION_FAILED,
    ): FakeProvider = FakeProvider { _, callback ->
        order += name
        callback.onError(error)
    }

    private class FakeProvider(
        private val action: (TranslationRequest, TranslationProvider.Callback) -> Unit,
    ) : TranslationProvider {
        override fun translate(
            request: TranslationRequest,
            callback: TranslationProvider.Callback,
        ): TranslationCall {
            action(request, callback)
            return TranslationCall.NONE
        }

        override fun close() = Unit
    }

    private companion object {
        val REQUEST = TranslationRequest(
            id = "request-1",
            targetLang = "fr",
            mode = LensRecognizerMode.LATIN,
            strings = listOf("hello"),
        )
    }
}
