package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

data class LensFrozenOcrLine(
    val text: String,
    val box: IntArray,
)

data class LensFrozenOcrBlock(val lines: List<LensFrozenOcrLine>)

data class LensFrozenOcrResult(
    val freezeId: Long,
    val stage: String,
    val script: String,
    val blocks: List<LensFrozenOcrBlock>,
)

object LensWireContract {
    const val MAX_STRING_COUNT = 24
    const val MAX_STRING_CHARS = 1_024
    const val MAX_TOTAL_SOURCE_BYTES = 16 * 1_024
    const val DEFAULT_TARGET_LANG = "fr"

    const val RECOGNIZER_MODE_FIELD = "mode"
    const val RECOGNIZER_MODE_LATIN = "LATIN"
    const val RECOGNIZER_MODE_JAPANESE = "JAPANESE"
    const val RECOGNIZER_MODE_CHINESE = "CHINESE"
    const val RECOGNIZER_MODE_KOREAN = "KOREAN"
    const val RECOGNIZER_MODE_DEVANAGARI = "DEVANAGARI"

    const val FROZEN_STAGE_FAST = "FAST"
    const val FROZEN_STAGE_HD = "HD"
    const val MAX_FROZEN_OCR_BLOCKS = 64
    const val MAX_FROZEN_OCR_LINES_PER_BLOCK = 64
    const val MAX_FROZEN_OCR_TOTAL_LINES = 384
    const val MAX_FROZEN_OCR_LINE_CHARS = 256
    const val MAX_FROZEN_OCR_RESULT_BYTES = 384 * 1024

    const val PHONE_REQUEST_TIMEOUT_MS = 8_000L
    const val PHONE_MODEL_DOWNLOAD_TIMEOUT_MS = 130_000L
    const val GLASSES_TIMEOUT_GRACE_MS = 5_000L
    const val GLASSES_REQUEST_TIMEOUT_MS = PHONE_REQUEST_TIMEOUT_MS + GLASSES_TIMEOUT_GRACE_MS
    const val GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS =
        PHONE_MODEL_DOWNLOAD_TIMEOUT_MS + GLASSES_TIMEOUT_GRACE_MS

    private val whitespaceRegex = Regex("\\s+")

    fun normalizeText(text: String): String =
        text.trim().replace(whitespaceRegex, " ")

    fun frozenOcrResultToJson(result: LensFrozenOcrResult): JSONObject {
        require(result.stage == FROZEN_STAGE_FAST || result.stage == FROZEN_STAGE_HD)
        require(result.script in recognizerModes)
        var totalLines = 0
        val blocksJson = JSONArray()
        result.blocks.take(MAX_FROZEN_OCR_BLOCKS).forEach { block ->
            if (totalLines >= MAX_FROZEN_OCR_TOTAL_LINES) return@forEach
            val linesJson = JSONArray()
            block.lines.take(MAX_FROZEN_OCR_LINES_PER_BLOCK).forEach lineLoop@ { line ->
                if (totalLines >= MAX_FROZEN_OCR_TOTAL_LINES || line.box.size != 4) return@lineLoop
                val text = line.text.take(MAX_FROZEN_OCR_LINE_CHARS)
                if (text.isBlank()) return@lineLoop
                linesJson.put(
                    JSONObject()
                        .put("text", text)
                        .put(
                            "box",
                            JSONArray().put(line.box[0]).put(line.box[1]).put(line.box[2]).put(line.box[3]),
                        ),
                )
                totalLines += 1
            }
            if (linesJson.length() > 0) blocksJson.put(JSONObject().put("lines", linesJson))
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("freezeId", result.freezeId)
            .put("stage", result.stage)
            .put("script", result.script)
            .put("blocks", blocksJson)
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_FROZEN_OCR_RESULT_BYTES) {
            "Frozen OCR result exceeds bus cap"
        }
        return payload
    }

    fun parseFrozenOcrResult(payload: JSONObject): LensFrozenOcrResult? {
        if (payload.optInt("version", 1) != 1) return null
        val stage = payload.optString("stage")
        val script = payload.optString("script")
        if (stage != FROZEN_STAGE_FAST && stage != FROZEN_STAGE_HD) return null
        if (script !in recognizerModes) return null
        val blocksJson = payload.optJSONArray("blocks") ?: return null
        if (blocksJson.length() > MAX_FROZEN_OCR_BLOCKS) return null
        var totalLines = 0
        val blocks = buildList {
            repeat(blocksJson.length()) { blockIndex ->
                val linesJson = blocksJson.optJSONObject(blockIndex)?.optJSONArray("lines") ?: return null
                if (linesJson.length() > MAX_FROZEN_OCR_LINES_PER_BLOCK) return null
                val lines = buildList {
                    repeat(linesJson.length()) { lineIndex ->
                        totalLines += 1
                        if (totalLines > MAX_FROZEN_OCR_TOTAL_LINES) return null
                        val line = linesJson.optJSONObject(lineIndex) ?: return null
                        val text = line.optString("text")
                        val boxJson = line.optJSONArray("box") ?: return null
                        if (text.isBlank() || text.length > MAX_FROZEN_OCR_LINE_CHARS || boxJson.length() != 4) {
                            return null
                        }
                        add(
                            LensFrozenOcrLine(
                                text = text,
                                box = IntArray(4) { boxJson.optInt(it) },
                            ),
                        )
                    }
                }
                if (lines.isNotEmpty()) add(LensFrozenOcrBlock(lines))
            }
        }
        return LensFrozenOcrResult(
            freezeId = payload.optLong("freezeId", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
                ?: return null,
            stage = stage,
            script = script,
            blocks = blocks,
        )
    }

    private val recognizerModes = setOf(
        RECOGNIZER_MODE_LATIN,
        RECOGNIZER_MODE_JAPANESE,
        RECOGNIZER_MODE_CHINESE,
        RECOGNIZER_MODE_KOREAN,
        RECOGNIZER_MODE_DEVANAGARI,
    )
}
