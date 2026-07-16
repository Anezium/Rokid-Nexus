package com.anezium.rokidbus.plugin.lens

import android.graphics.Bitmap
import com.anezium.rokidbus.shared.LensFrozenOcrBlock
import com.anezium.rokidbus.shared.LensFrozenOcrLine
import com.anezium.rokidbus.shared.LensWireContract
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class PhoneOcrScript(val wireValue: String) {
    LATIN(LensWireContract.RECOGNIZER_MODE_LATIN),
    JAPANESE(LensWireContract.RECOGNIZER_MODE_JAPANESE),
    CHINESE(LensWireContract.RECOGNIZER_MODE_CHINESE),
    KOREAN(LensWireContract.RECOGNIZER_MODE_KOREAN),
    DEVANAGARI(LensWireContract.RECOGNIZER_MODE_DEVANAGARI),
}

internal data class PhoneFrozenOcrResult(
    val script: PhoneOcrScript,
    val text: Text,
) {
    fun wireBlocks(): List<LensFrozenOcrBlock> = text.textBlocks.mapNotNull { block ->
        val lines = block.lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            line.text.takeIf { it.isNotBlank() }?.let {
                LensFrozenOcrLine(it, intArrayOf(box.left, box.top, box.right, box.bottom))
            }
        }
        lines.takeIf { it.isNotEmpty() }?.let(::LensFrozenOcrBlock)
    }
}

internal data class PhoneOcrTextStats(
    val nonSpaceCharacters: Int,
    val matchingScriptCharacters: Int,
) {
    val matchingRatio: Double
        get() = if (nonSpaceCharacters == 0) 0.0 else matchingScriptCharacters.toDouble() / nonSpaceCharacters
}

internal fun phoneOcrTextStats(text: String, script: PhoneOcrScript): PhoneOcrTextStats {
    var nonSpace = 0
    var matching = 0
    text.codePoints().forEach { codePoint ->
        if (!Character.isWhitespace(codePoint)) {
            nonSpace += 1
            if (matchesPhoneOcrScript(codePoint, script)) matching += 1
        }
    }
    return PhoneOcrTextStats(nonSpace, matching)
}

internal fun isStrongPhoneOcrCandidate(text: String, script: PhoneOcrScript): Boolean {
    val stats = phoneOcrTextStats(text, script)
    return stats.nonSpaceCharacters >= MIN_USEFUL_CHARACTERS && stats.matchingRatio >= MIN_SCRIPT_RATIO
}

internal fun phoneFrozenOcrSweepOrder(
    targetScriptPlan: List<PhoneOcrScript>,
): List<PhoneOcrScript> = (targetScriptPlan + PhoneOcrScript.entries).distinct()

/**
 * A textless scene returns zero characters from every recognizer; two independent scripts
 * agreeing on emptiness is enough evidence to stop paying ~1s per remaining model.
 * Any recognized character keeps the sweep alive: short or ambiguous text still deserves
 * the full script search.
 */
internal fun shouldContinueFrozenSweep(passesCompleted: Int, sawAnyCharacters: Boolean): Boolean =
    sawAnyCharacters || passesCompleted < MIN_EMPTY_PASSES_BEFORE_BAIL

private fun matchesPhoneOcrScript(codePoint: Int, script: PhoneOcrScript): Boolean = when (script) {
    PhoneOcrScript.LATIN -> codePoint in 0x0041..0x024F
    PhoneOcrScript.JAPANESE -> isHan(codePoint) ||
        codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF
    PhoneOcrScript.CHINESE -> isHan(codePoint)
    PhoneOcrScript.KOREAN -> codePoint in 0x1100..0x11FF ||
        codePoint in 0x3130..0x318F || codePoint in 0xA960..0xA97F ||
        codePoint in 0xAC00..0xD7AF || codePoint in 0xD7B0..0xD7FF
    PhoneOcrScript.DEVANAGARI -> codePoint in 0x0900..0x097F ||
        codePoint in 0xA8E0..0xA8FF || codePoint in 0x11B00..0x11B5F
}

private fun isHan(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F

/** Lazy ML Kit recognizers; callbacks are serialized to preserve sweep order. */
internal class PhoneFrozenOcr(
    private val onModuleUnavailable: (PhoneOcrScript) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lens-phone-ocr").apply { isDaemon = true }
    }
    private val recognizers = mutableMapOf<PhoneOcrScript, TextRecognizer>()
    @Volatile private var closed = false

    fun recognize(
        bitmap: Bitmap,
        targetScriptPlan: List<PhoneOcrScript> = PhoneOcrScript.entries,
        callback: (Result<PhoneFrozenOcrResult>) -> Unit,
    ) {
        if (closed) {
            callback(Result.failure(IllegalStateException("Phone frozen OCR is closed")))
            return
        }
        val order = phoneFrozenOcrSweepOrder(targetScriptPlan)
        val successes = mutableListOf<PhoneFrozenOcrResult>()
        var sawAnyCharacters = false
        var index = 0

        fun finishBest() {
            val best = successes.maxByOrNull { phoneOcrTextStats(it.text.text, it.script).nonSpaceCharacters }
            if (best != null) callback(Result.success(best))
            else callback(Result.failure(IllegalStateException("All phone OCR recognizers failed")))
        }

        fun recognizeNext() {
            if (closed) return callback(Result.failure(IllegalStateException("Phone frozen OCR is closed")))
            if (index >= order.size) return finishBest()
            if (!shouldContinueFrozenSweep(successes.size, sawAnyCharacters)) return finishBest()
            val script = order[index++]
            val task = runCatching { recognizerFor(script).process(InputImage.fromBitmap(bitmap, 0)) }
                .getOrElse {
                    if (script != PhoneOcrScript.LATIN && isPlayServicesOcrModuleUnavailable(it)) {
                        onModuleUnavailable(script)
                    }
                    callbackExecutor.execute(::recognizeNext)
                    return
                }
            task.addOnCompleteListener(callbackExecutor) { completed ->
                val text = if (completed.isSuccessful) completed.result else null
                if (text == null) {
                    if (script != PhoneOcrScript.LATIN &&
                        isPlayServicesOcrModuleUnavailable(completed.exception)
                    ) {
                        onModuleUnavailable(script)
                    }
                    recognizeNext()
                    return@addOnCompleteListener
                }
                val result = PhoneFrozenOcrResult(script, text)
                successes += result
                val chars = phoneOcrTextStats(text.text, script).nonSpaceCharacters
                if (chars > 0) sawAnyCharacters = true
                val accepted = if (script == PhoneOcrScript.LATIN) {
                    chars >= MIN_USEFUL_CHARACTERS
                } else {
                    isStrongPhoneOcrCandidate(text.text, script)
                }
                if (accepted) callback(Result.success(result)) else recognizeNext()
            }
        }

        callbackExecutor.execute(::recognizeNext)
    }

    private fun recognizerFor(script: PhoneOcrScript): TextRecognizer = synchronized(lock) {
        check(!closed) { "Phone frozen OCR is closed" }
        recognizers.getOrPut(script) {
            when (script) {
                PhoneOcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                PhoneOcrScript.JAPANESE -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build(),
                )
                PhoneOcrScript.CHINESE -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build(),
                )
                PhoneOcrScript.KOREAN -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder().build(),
                )
                PhoneOcrScript.DEVANAGARI -> TextRecognition.getClient(
                    DevanagariTextRecognizerOptions.Builder().build(),
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            recognizers.values.forEach(TextRecognizer::close)
            recognizers.clear()
        }
        callbackExecutor.shutdownNow()
    }
}

private const val MIN_USEFUL_CHARACTERS = 8
private const val MIN_SCRIPT_RATIO = 0.30
private const val MIN_EMPTY_PASSES_BEFORE_BAIL = 2
