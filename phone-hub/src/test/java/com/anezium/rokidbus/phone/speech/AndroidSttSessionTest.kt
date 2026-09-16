package com.anezium.rokidbus.phone.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AndroidSttSessionTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun finishAudioClosesPipeToEofAndCancelIsIdempotent() {
        val recognizer = RecordingRecognizer()
        val pipe = TrackingPipe()
        val main = ManualMainThread()
        val foreground = RecordingForeground()
        val listener = RecordingListener()
        val session = session(
            recognizer = recognizer,
            pipeFactory = AndroidAudioPipeFactory { pipe },
            main = main,
            foreground = foreground,
            listener = listener,
            segmented = false,
        )
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertTrue(session.start())
        session.acceptPcm(pcm, 0, pcm.size)
        session.finishAudio()

        assertTrue(pipe.writeClosed)
        assertArrayEquals(pcm, pipe.readToEof())
        assertEquals(1, recognizer.stopCalls)

        val writesBeforeCancel = pipe.writeCalls
        session.cancel()
        session.cancel()
        session.acceptPcm(byteArrayOf(9, 9), 0, 2)

        assertEquals(writesBeforeCancel, pipe.writeCalls)
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(1, recognizer.destroyCalls)
        assertEquals(1, foreground.acquireCalls)
        assertEquals(1, foreground.releaseCalls)
    }

    @Test
    fun autoOmitsLanguageHintsAndSegmentedInputUsesEofWithoutStopListening() {
        val recognizer = RecordingRecognizer()
        val pipe = TrackingPipe()
        val session = session(
            recognizer = recognizer,
            pipeFactory = AndroidAudioPipeFactory { pipe },
            segmented = true,
        )

        assertTrue(session.start())
        val intent = recognizer.intent!!
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
        assertEquals(
            RecognizerIntent.EXTRA_AUDIO_SOURCE,
            intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
        )
        assertEquals(
            16_000,
            intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, -1),
        )
        assertEquals(
            1,
            intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, -1),
        )
        assertTrue(intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))

        // Timing extras must be Int: recognizers read them with getInt, and a Long is silently
        // dropped for the default 0 — observed on device with the Google recognizer.
        listOf(
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        ).forEach { key ->
            assertTrue("$key must be an Int extra", intent.extras?.get(key) is Int)
            assertTrue("$key must survive getInt", intent.getIntExtra(key, -1) > 0)
        }
        
        // Assert that the defaults are correctly mapped from SpeechPatience.NORMAL (8s wait -> 10s timeout)
        assertEquals(10_000, intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1))
        assertEquals(10_000, intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1))

        session.finishAudio()

        assertTrue(pipe.writeClosed)
        assertEquals(0, recognizer.stopCalls)
        session.cancel()
    }

    @Test
    fun bestPartialBecomesFinalWhenRecognizerReturnsNoMatchAfterEof() {
        val recognizer = RecordingRecognizer()
        val listener = RecordingListener()
        val session = session(
            recognizer = recognizer,
            pipeFactory = AndroidAudioPipeFactory { TrackingPipe() },
            listener = listener,
            segmented = false,
        )

        assertTrue(session.start())
        recognizer.listener!!.onPartialResults(
            Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf("partial result"),
                )
            },
        )
        session.finishAudio()
        recognizer.listener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(listOf("partial result"), listener.finals)
        assertNull(listener.error)
    }

    @Test
    fun serverDisconnectRetriesExactlyOnce() {
        val recognizers = mutableListOf<RecordingRecognizer>()
        val environment = ReadyEnvironment(
            listOf(
                AndroidRecognizerTarget(
                    id = "default",
                    reportName = "default:test",
                    segmentedSession = false,
                    stopListeningOnInputClose = true,
                    create = { RecordingRecognizer().also(recognizers::add) },
                ),
            ),
        )
        val main = ManualMainThread()
        val listener = RecordingListener()
        val session = AndroidSttSession(
            context = context,
            language = TranscriptionLanguage.AUTO,
            listener = listener,
            environment = environment,
            pipeFactory = AndroidAudioPipeFactory { TrackingPipe() },
            mainThread = main,
            foregroundController = RecordingForeground(),
        )

        assertTrue(session.start())
        recognizers.single().listener!!.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        assertEquals(1, main.pendingCount())
        main.runNext()
        assertEquals(2, recognizers.size)

        recognizers.last().listener!!.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)

        assertEquals(SttErrorKind.PROVIDER, listener.error?.kind)
        assertEquals(2, recognizers.size)
        assertEquals(0, main.pendingCount())
    }

    @Test
    fun clientErrorFallsBackToNextTargetAndCanReturnFinalResult() {
        val first = RecordingRecognizer()
        val second = RecordingRecognizer()
        val listener = RecordingListener()
        val session = sessionWithTargets(
            targets = listOf(
                target("default", "default:test") { first },
                target("component:google", "google:test") { second },
            ),
            listener = listener,
        )

        assertTrue(session.start())
        first.listener!!.onError(SpeechRecognizer.ERROR_CLIENT)

        assertEquals(1, first.destroyCalls)
        assertTrue(second.intent != null)
        assertNull(listener.error)

        second.listener!!.onResults(recognitionResults("fallback result"))

        assertEquals(listOf("fallback result"), listener.finals)
        assertNull(listener.error)
    }

    @Test
    fun clientErrorOnLastTargetFinishesWithInternalError() {
        val recognizer = RecordingRecognizer()
        val listener = RecordingListener()
        val session = sessionWithTargets(
            targets = listOf(
                target("on-device", "on-device:test") { recognizer },
            ),
            listener = listener,
        )

        assertTrue(session.start())
        recognizer.listener!!.onError(SpeechRecognizer.ERROR_CLIENT)

        assertEquals(SttErrorKind.INTERNAL, listener.error?.kind)
        assertEquals("Android speech recognizer client failed", listener.error?.detail)
        assertTrue(listener.finals.isEmpty())
    }

    @Test
    fun clientErrorAfterPartialFinishesPartialInsteadOfFallingBack() {
        val first = RecordingRecognizer()
        var secondCreates = 0
        val listener = RecordingListener()
        val session = sessionWithTargets(
            targets = listOf(
                target("default", "default:test") { first },
                target("component:google", "google:test") {
                    secondCreates += 1
                    RecordingRecognizer()
                },
            ),
            listener = listener,
        )

        assertTrue(session.start())
        first.listener!!.onPartialResults(recognitionResults("usable partial"))
        first.listener!!.onError(SpeechRecognizer.ERROR_CLIENT)

        assertEquals(listOf("usable partial"), listener.finals)
        assertNull(listener.error)
        assertEquals(0, secondCreates)
    }

    @Test
    fun startFailureFallsBackToNextTarget() {
        val second = RecordingRecognizer()
        val listener = RecordingListener()
        var pipeCreates = 0
        val session = sessionWithTargets(
            targets = listOf(
                target("default", "default:test") {
                    throw IllegalStateException("recognizer unavailable")
                },
                target("component:google", "google:test") { second },
            ),
            listener = listener,
            pipeFactory = AndroidAudioPipeFactory {
                pipeCreates += 1
                TrackingPipe()
            },
        )

        assertTrue(session.start())

        assertEquals(2, pipeCreates)
        assertTrue(second.intent != null)
        assertNull(listener.error)

        second.listener!!.onResults(recognitionResults("started on fallback"))
        assertEquals(listOf("started on fallback"), listener.finals)
    }

    @Test
    fun clientErrorWithoutGoogleOrOnDeviceTargetSuggestsCloudEngine() {
        val recognizer = RecordingRecognizer()
        val listener = RecordingListener()
        val session = sessionWithTargets(
            targets = listOf(
                target("default", "default:com.vivo.speech") { recognizer },
            ),
            listener = listener,
        )

        assertTrue(session.start())
        recognizer.listener!!.onError(SpeechRecognizer.ERROR_CLIENT)

        assertEquals(SttErrorKind.INTERNAL, listener.error?.kind)
        assertEquals(
            "Your phone's speech service does not accept external audio - choose a cloud engine " +
                "(OpenAI, ElevenLabs or Azure) in Speech settings",
            listener.error?.detail,
        )
    }

    @Test
    fun recognizerErrorsMapToStructuredKinds() {
        val cases = mapOf(
            SpeechRecognizer.ERROR_AUDIO to SttErrorKind.SOURCE_UNAVAILABLE,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SttErrorKind.SOURCE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to SttErrorKind.NO_SPEECH,
            SpeechRecognizer.ERROR_NO_MATCH to SttErrorKind.NO_SPEECH,
            SpeechRecognizer.ERROR_NETWORK to SttErrorKind.NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to SttErrorKind.TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to SttErrorKind.QUOTA_RATE,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to SttErrorKind.QUOTA_RATE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SttErrorKind.UNSUPPORTED_LANGUAGE,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SttErrorKind.UNSUPPORTED_LANGUAGE,
            SpeechRecognizer.ERROR_SERVER to SttErrorKind.PROVIDER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to SttErrorKind.PROVIDER,
            SpeechRecognizer.ERROR_CLIENT to SttErrorKind.INTERNAL,
        )

        cases.forEach { (code, expected) ->
            val error = androidRecognizerError(code)
            assertEquals("code=$code", expected, error.kind)
            assertEquals("Android", error.providerLabel)
        }
    }

    private fun session(
        recognizer: RecordingRecognizer,
        pipeFactory: AndroidAudioPipeFactory,
        main: ManualMainThread = ManualMainThread(),
        foreground: RecordingForeground = RecordingForeground(),
        listener: RecordingListener = RecordingListener(),
        segmented: Boolean,
    ): AndroidSttSession {
        val target = AndroidRecognizerTarget(
            id = "default",
            reportName = "default:test",
            segmentedSession = segmented,
            stopListeningOnInputClose = !segmented,
            create = { recognizer },
        )
        return AndroidSttSession(
            context = context,
            language = TranscriptionLanguage.AUTO,
            listener = listener,
            environment = ReadyEnvironment(listOf(target)),
            pipeFactory = pipeFactory,
            mainThread = main,
            foregroundController = foreground,
        )
    }

    private fun sessionWithTargets(
        targets: List<AndroidRecognizerTarget>,
        listener: RecordingListener,
        pipeFactory: AndroidAudioPipeFactory = AndroidAudioPipeFactory { TrackingPipe() },
    ): AndroidSttSession =
        AndroidSttSession(
            context = context,
            language = TranscriptionLanguage.AUTO,
            listener = listener,
            environment = ReadyEnvironment(targets),
            pipeFactory = pipeFactory,
            mainThread = ManualMainThread(),
            foregroundController = RecordingForeground(),
        )

    private fun target(
        id: String,
        reportName: String,
        create: () -> AndroidSpeechRecognizer,
    ): AndroidRecognizerTarget =
        AndroidRecognizerTarget(
            id = id,
            reportName = reportName,
            segmentedSession = false,
            stopListeningOnInputClose = true,
            create = create,
        )

    private fun recognitionResults(text: String): Bundle =
        Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf(text),
            )
        }

    private class ReadyEnvironment(
        private val targets: List<AndroidRecognizerTarget>,
    ) : AndroidRecognizerEnvironment {
        override val sdkInt: Int = 33
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun recognitionTargets(): List<AndroidRecognizerTarget> = targets
    }

    private class RecordingRecognizer : AndroidSpeechRecognizer {
        var listener: RecognitionListener? = null
        var intent: Intent? = null
        var stopCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            this.intent = intent
        }

        override fun stopListening() {
            stopCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun destroy() {
            destroyCalls += 1
        }
    }

    private class TrackingPipe : AndroidAudioPipe {
        private val descriptors = ParcelFileDescriptor.createPipe()
        override val readEnd: ParcelFileDescriptor = descriptors[0]
        private var writeEnd: ParcelFileDescriptor? = descriptors[1]
        private var output: FileOutputStream? = FileOutputStream(descriptors[1].fileDescriptor)
        var writeCalls = 0
        var writeClosed = false

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val stream = output ?: throw IOException("closed")
            writeCalls += 1
            stream.write(data, offset, length)
        }

        override fun closeWrite() {
            if (writeClosed) return
            writeClosed = true
            val stream = output
            val descriptor = writeEnd
            output = null
            writeEnd = null
            stream?.close()
            runCatching { descriptor?.close() }
        }

        override fun close() {
            runCatching { closeWrite() }
            runCatching { readEnd.close() }
        }

        fun readToEof(): ByteArray =
            FileInputStream(readEnd.fileDescriptor).use { it.readBytes() }
    }

    private class ManualMainThread : AndroidMainThread {
        private data class Pending(
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val pending = mutableListOf<Pending>()

        override fun <T> call(task: () -> T): T = task()

        override fun schedule(delayMs: Long, task: () -> Unit): AndroidScheduledTask {
            val entry = Pending(task)
            pending += entry
            return AndroidScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = pending.count { !it.cancelled }

        fun runNext() {
            val entry = pending.first { !it.cancelled }
            pending.remove(entry)
            entry.task()
        }
    }

    private class RecordingForeground : AndroidSttForegroundController {
        var acquireCalls = 0
        var releaseCalls = 0

        override fun acquire(): SttError? {
            acquireCalls += 1
            return null
        }

        override fun release() {
            releaseCalls += 1
        }
    }

    private class RecordingListener : SttSessionListener {
        val finals = mutableListOf<String>()
        var error: SttError? = null

        override fun onReady() = Unit
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) {
            finals += text
        }

        override fun onError(error: SttError) {
            this.error = error
        }
    }
}
