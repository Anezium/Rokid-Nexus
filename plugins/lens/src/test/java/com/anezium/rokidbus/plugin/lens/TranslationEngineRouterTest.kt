package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineRouterTest {
    @Test
    fun `AUTO tries keyed providers then Google and stops when coverage is complete`() {
        val order = mutableListOf<String>()
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
                geminiApiKey = "gemini-key",
            ),
            google = fakeSuccess("google", order),
            deepL = fakeFailure("deepl", order),
            gemini = fakeFailure("gemini", order),
            mlKit = fakeSuccess("mlkit", order),
        )
        try {
            val result = translate(router)

            assertEquals(listOf("deepl", "gemini", "google"), order)
            assertEquals("google-result", result.single().dst)
            assertEquals(TranslationEngine.GOOGLE_WEB, result.single().engine)
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
    fun `partial primary success continues with only missing unique sources`() {
        val requests = mutableListOf<Pair<String, List<String>>>()
        val deepL = FakeProvider { request, callback ->
            requests += "deepl" to request.strings
            callback.onSuccess(listOf(result("one", "un")))
        }
        val google = FakeProvider { request, callback ->
            requests += "google" to request.strings
            callback.onSuccess(request.strings.map { result(it, "$it-google") })
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
            ),
            google = google,
            deepL = deepL,
            gemini = unusedProvider(),
            mlKit = unusedProvider(),
        )
        try {
            val results = translate(
                router,
                REQUEST.copy(strings = listOf("one", "two", "one", "three")),
            )

            assertEquals(
                listOf(
                    "deepl" to listOf("one", "two", "three"),
                    "google" to listOf("two", "three"),
                ),
                requests,
            )
            assertEquals(listOf("one", "two", "three"), results.map { it.src })
            assertEquals(listOf("un", "two-google", "three-google"), results.map { it.dst })
        } finally {
            router.close()
        }
    }

    @Test
    fun `online fallback and failure records remain missing for retry`() {
        val fallbackRequests = mutableListOf<List<String>>()
        val deepL = FakeProvider { _, callback ->
            callback.onSuccess(
                listOf(
                    result("one", "un"),
                    TranslationResult(
                        src = "two",
                        dst = "two",
                        srcLang = "en",
                        fallback = true,
                        failure = TranslationErrorCode.TRANSLATION_FAILED,
                    ),
                    TranslationResult(
                        src = "three",
                        dst = "invalid",
                        srcLang = "en",
                        failure = TranslationErrorCode.RESPONSE_TOO_LARGE,
                    ),
                ),
            )
        }
        val google = FakeProvider { request, callback ->
            fallbackRequests += request.strings
            callback.onSuccess(request.strings.map { result(it, "$it-google") })
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
            ),
            google = google,
            deepL = deepL,
            gemini = unusedProvider(),
            mlKit = unusedProvider(),
        )
        try {
            val results = translate(
                router,
                REQUEST.copy(strings = listOf("one", "two", "three")),
            )

            assertEquals(listOf(listOf("two", "three")), fallbackRequests)
            assertEquals(listOf("un", "two-google", "three-google"), results.map { it.dst })
            assertEquals(
                listOf(
                    TranslationEngine.DEEPL,
                    TranslationEngine.GOOGLE_WEB,
                    TranslationEngine.GOOGLE_WEB,
                ),
                results.map { it.engine },
            )
        } finally {
            router.close()
        }
    }

    @Test
    fun `unknown and duplicate provider results are ignored`() {
        val fallbackRequests = mutableListOf<List<String>>()
        val deepL = FakeProvider { _, callback ->
            callback.onSuccess(
                listOf(
                    result("unknown", "inconnu"),
                    result("one", "first"),
                    result("one", "duplicate"),
                ),
            )
        }
        val google = FakeProvider { request, callback ->
            fallbackRequests += request.strings
            callback.onSuccess(request.strings.map { result(it, "$it-google") })
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
            ),
            google = google,
            deepL = deepL,
            gemini = unusedProvider(),
            mlKit = unusedProvider(),
        )
        try {
            val results = translate(router, REQUEST.copy(strings = listOf("one", "two")))

            assertEquals(listOf(listOf("two")), fallbackRequests)
            assertEquals(listOf("first", "two-google"), results.map { it.dst })
        } finally {
            router.close()
        }
    }

    @Test
    fun `mixed serving engines retain original source order`() {
        val deepL = FakeProvider { _, callback ->
            callback.onSuccess(listOf(result("two", "deux")))
        }
        val gemini = FakeProvider { _, callback ->
            callback.onSuccess(listOf(result("one", "un")))
        }
        val google = FakeProvider { _, callback ->
            callback.onSuccess(listOf(result("three", "trois")))
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
                geminiApiKey = "gemini-key",
            ),
            google = google,
            deepL = deepL,
            gemini = gemini,
            mlKit = unusedProvider(),
        )
        try {
            val results = translate(
                router,
                REQUEST.copy(strings = listOf("one", "two", "three")),
            )

            assertEquals(listOf("one", "two", "three"), results.map { it.src })
            assertEquals(
                listOf(
                    TranslationEngine.GEMINI,
                    TranslationEngine.DEEPL,
                    TranslationEngine.GOOGLE_WEB,
                ),
                results.map { it.engine },
            )
        } finally {
            router.close()
        }
    }

    @Test
    fun `exhausted providers return successful results plus explicit fallback records`() {
        val deepL = FakeProvider { _, callback ->
            callback.onSuccess(listOf(result("one", "un")))
        }
        val router = router(
            config = TranslationEngineConfig(
                engine = TranslationEngine.AUTO,
                deepLApiKey = "deepl-key",
            ),
            google = FakeProvider { _, callback ->
                callback.onError(TranslationErrorCode.TIMEOUT)
            },
            deepL = deepL,
            gemini = unusedProvider(),
            mlKit = FakeProvider { request, callback ->
                assertEquals(listOf("two"), request.strings)
                callback.onError(TranslationErrorCode.MODEL_UNAVAILABLE)
            },
        )
        try {
            val results = translate(router, REQUEST.copy(strings = listOf("one", "two")))

            assertEquals(listOf("one", "two"), results.map { it.src })
            assertEquals("un", results[0].dst)
            assertFalse(results[0].fallback)
            assertEquals(TranslationEngine.DEEPL, results[0].engine)
            assertEquals("two", results[1].dst)
            assertTrue(results[1].fallback)
            assertEquals(TranslationErrorCode.MODEL_UNAVAILABLE, results[1].failure)
            assertEquals(TranslationEngine.MLKIT_OFFLINE, results[1].engine)
        } finally {
            router.close()
        }
    }

    @Test
    fun `online budget is shared across partial attempts and skips remaining online engines`() {
        val requests = mutableListOf<Pair<String, List<String>>>()
        var nowMs = 0L
        val deepL = FakeProvider { request, callback ->
            requests += "deepl" to request.strings
            nowMs += 3_000L
            callback.onSuccess(listOf(result("one", "un")))
        }
        val gemini = FakeProvider { request, callback ->
            requests += "gemini" to request.strings
            nowMs += 3_000L
            callback.onSuccess(listOf(result("two", "deux")))
        }
        val google = FakeProvider { request, callback ->
            requests += "google" to request.strings
            callback.onSuccess(request.strings.map { result(it, "online") })
        }
        val mlKit = FakeProvider { request, callback ->
            requests += "mlkit" to request.strings
            callback.onSuccess(request.strings.map { result(it, "$it-offline") })
        }
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
            nowMs = { nowMs },
        )
        try {
            val results = translate(
                router,
                REQUEST.copy(strings = listOf("one", "two", "three")),
            )

            assertEquals(
                listOf(
                    "deepl" to listOf("one", "two", "three"),
                    "gemini" to listOf("two", "three"),
                    "mlkit" to listOf("three"),
                ),
                requests,
            )
            assertEquals("three-offline", results[2].dst)
            assertEquals(TranslationEngine.MLKIT_OFFLINE, results[2].engine)
        } finally {
            router.close()
        }
    }

    @Test
    fun `cancellation suppresses late provider callbacks and cancels active call`() {
        lateinit var providerCallback: TranslationProvider.Callback
        var providerCancelled = false
        var terminalCallbacks = 0
        val google = object : TranslationProvider {
            override fun translate(
                request: TranslationRequest,
                callback: TranslationProvider.Callback,
            ): TranslationCall {
                providerCallback = callback
                return TranslationCall { providerCancelled = true }
            }

            override fun close() = Unit
        }
        val router = router(
            config = TranslationEngineConfig(engine = TranslationEngine.GOOGLE_WEB),
            google = google,
            deepL = unusedProvider(),
            gemini = unusedProvider(),
            mlKit = unusedProvider(),
        )
        try {
            val call = router.translate(
                REQUEST,
                object : TranslationProvider.Callback {
                    override fun onDownloading(status: TranslationDownloadStatus) = Unit
                    override fun onSuccess(translations: List<TranslationResult>) {
                        terminalCallbacks += 1
                    }

                    override fun onError(error: TranslationErrorCode) {
                        terminalCallbacks += 1
                    }
                },
            )

            call.cancel()
            providerCallback.onSuccess(listOf(result("hello", "bonjour")))
            providerCallback.onError(TranslationErrorCode.TIMEOUT)

            assertTrue(providerCancelled)
            assertEquals(0, terminalCallbacks)
        } finally {
            router.close()
        }
    }

    @Test
    fun `ML Kit normalized results map back to distinct raw sources in order`() {
        val router = router(
            config = TranslationEngineConfig(engine = TranslationEngine.MLKIT_OFFLINE),
            google = unusedProvider(),
            deepL = unusedProvider(),
            gemini = unusedProvider(),
            mlKit = FakeProvider { request, callback ->
                assertEquals(listOf(" one ", "one", " two "), request.strings)
                callback.onSuccess(
                    listOf(
                        result("one", "un"),
                        TranslationResult(
                            src = "two",
                            dst = "two",
                            srcLang = "en",
                            fallback = true,
                            failure = TranslationErrorCode.TRANSLATION_FAILED,
                        ),
                    ),
                )
            },
        )
        try {
            val results = translate(
                router,
                REQUEST.copy(strings = listOf(" one ", "one", " two ")),
            )

            assertEquals(listOf(" one ", "one", " two "), results.map { it.src })
            assertEquals(listOf("un", "un", "two"), results.map { it.dst })
            assertFalse(results[0].fallback)
            assertFalse(results[1].fallback)
            assertTrue(results[2].fallback)
            assertEquals(TranslationErrorCode.TRANSLATION_FAILED, results[2].failure)
            assertTrue(results.all { it.engine == TranslationEngine.MLKIT_OFFLINE })
        } finally {
            router.close()
        }
    }

    @Test
    fun `offline partial success creates fallback for its unresolved sources exactly once`() {
        var callbackCount = 0
        var callbackResults: List<TranslationResult>? = null
        lateinit var mlKitCallback: TranslationProvider.Callback
        val router = router(
            config = TranslationEngineConfig(engine = TranslationEngine.MLKIT_OFFLINE),
            google = unusedProvider(),
            deepL = unusedProvider(),
            gemini = unusedProvider(),
            mlKit = FakeProvider { _, callback ->
                mlKitCallback = callback
                callback.onSuccess(listOf(result("one", "un")))
            },
        )
        try {
            router.translate(
                REQUEST.copy(strings = listOf("one", "two")),
                object : TranslationProvider.Callback {
                    override fun onDownloading(status: TranslationDownloadStatus) = Unit
                    override fun onSuccess(translations: List<TranslationResult>) {
                        callbackCount += 1
                        callbackResults = translations
                    }

                    override fun onError(error: TranslationErrorCode) {
                        callbackCount += 1
                    }
                },
            )
            mlKitCallback.onSuccess(listOf(result("two", "deux")))

            assertEquals(1, callbackCount)
            val results = checkNotNull(callbackResults)
            assertEquals(listOf("one", "two"), results.map { it.src })
            assertTrue(results[1].fallback)
            assertEquals(TranslationErrorCode.TRANSLATION_FAILED, results[1].failure)
            assertEquals(TranslationEngine.MLKIT_OFFLINE, results[1].engine)
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

    private fun translate(
        router: TranslationEngineRouter,
        request: TranslationRequest = REQUEST,
    ): List<TranslationResult> {
        var success: List<TranslationResult>? = null
        var receivedError: TranslationErrorCode? = null
        router.translate(
            request,
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
        assertNull("Unexpected error", receivedError)
        return checkNotNull(success) { "Router did not complete synchronously with the fake providers" }
    }

    private fun result(source: String, translation: String): TranslationResult =
        TranslationResult(source, translation, "en")

    private fun fakeSuccess(
        name: String,
        order: MutableList<String>,
    ): FakeProvider = FakeProvider { request, callback ->
        order += name
        callback.onSuccess(
            request.strings.map { source ->
                result(source, "$name-result")
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

    private fun unusedProvider(): FakeProvider = FakeProvider { _, _ ->
        error("Unexpected provider invocation")
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
