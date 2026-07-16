package com.anezium.rokidbus.plugin.lens

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort process-wide installation of the unbundled non-Latin OCR modules. */
internal object PlayServicesOcrModules {
    private val installInFlight = AtomicBoolean(false)
    private val installRequestAccepted = AtomicBoolean(false)
    private val unavailableLogged = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        10L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, "lens-ocr-module-install").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    fun requestInstall(context: Context, logger: (String) -> Unit) {
        if (installRequestAccepted.get() || !installInFlight.compareAndSet(false, true)) return
        try {
            executor.execute { requestInstallOffMain(context.applicationContext, logger) }
        } catch (failure: Throwable) {
            installInFlight.set(false)
            reportUnavailable(logger, "installQueue type=${failure.javaClass.simpleName}")
        }
    }

    fun reportUnavailable(logger: (String) -> Unit, detail: String) {
        if (!unavailableLogged.compareAndSet(false, true)) return
        runCatching {
            logger("nonLatinOcrModules unavailable $detail; OCR pass treated as empty")
        }
    }

    private fun requestInstallOffMain(context: Context, logger: (String) -> Unit) {
        val recognizers = mutableListOf<TextRecognizer>()
        try {
            recognizers += TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build(),
            )
            recognizers += TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            recognizers += TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build(),
            )
            recognizers += TextRecognition.getClient(
                DevanagariTextRecognizerOptions.Builder().build(),
            )
            val requestBuilder = ModuleInstallRequest.newBuilder()
            recognizers.forEach(requestBuilder::addApi)
            ModuleInstall.getClient(context)
                .installModules(requestBuilder.build())
                .addOnCompleteListener(executor) { task ->
                    try {
                        if (task.isSuccessful) {
                            installRequestAccepted.set(true)
                            runCatching {
                                logger(
                                    "nonLatinOcrModules installRequested " +
                                        "alreadyInstalled=${task.result.areModulesAlreadyInstalled()}",
                                )
                            }
                        } else {
                            val failure = task.exception
                            reportUnavailable(
                                logger,
                                "installRequest type=${failure?.javaClass?.simpleName ?: "unknown"}",
                            )
                        }
                    } catch (failure: Throwable) {
                        reportUnavailable(
                            logger,
                            "installCallback type=${failure.javaClass.simpleName}",
                        )
                    } finally {
                        recognizers.forEach { runCatching { it.close() } }
                        installInFlight.set(false)
                    }
                }
        } catch (failure: Throwable) {
            recognizers.forEach { runCatching { it.close() } }
            installInFlight.set(false)
            reportUnavailable(logger, "installStart type=${failure.javaClass.simpleName}")
        }
    }
}

internal fun isPlayServicesOcrModuleUnavailable(failure: Throwable?): Boolean {
    var candidate = failure
    repeat(MAX_CAUSE_DEPTH) {
        val mlKitFailure = candidate as? MlKitException
        if (mlKitFailure != null) {
            val errorCode = mlKitFailure.errorCode
            if (errorCode == MlKitException.UNAVAILABLE || errorCode == moduleNotAvailableErrorCode) {
                return true
            }
        }
        candidate = candidate?.cause ?: return false
    }
    return false
}

private val moduleNotAvailableErrorCode: Int? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        MlKitException::class.java.getField("MODULE_NOT_AVAILABLE").getInt(null)
    }.getOrNull()
}

private const val MAX_CAUSE_DEPTH = 8
