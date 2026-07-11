package com.anezium.rokidbus.plugin.feeds.xtransaction

import com.anezium.rokidbus.plugin.feeds.FeedHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.math.floor

/** Faithful Kotlin reimplementation of QuaX's x_client_transaction_id module. */
internal class XClientTransaction private constructor(
    private val keyBytes: List<Int>,
    internal val animationKey: String,
    private val randomKeyword: String,
    private val randomNumber: Int,
    private val epochSeconds: () -> Long,
    private val randomByte: () -> Int,
) {
    fun generateTransactionId(method: String, path: String): String {
        val timeNow = epochSeconds() - TIME_EPOCH_SECONDS
        val timeNowBytes = List(4) { index -> ((timeNow shr (index * 8)) and 0xff).toInt() }
        val hashInput = "$method!$path!$timeNow$randomKeyword$animationKey"
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8))
        val mask = randomByte() and 0xff
        val source = buildList {
            addAll(keyBytes)
            addAll(timeNowBytes)
            addAll(hashBytes.take(16).map { it.toInt() and 0xff })
            add(randomNumber)
        }
        val output = ByteArray(source.size + 1)
        output[0] = mask.toByte()
        source.forEachIndexed { index, byte -> output[index + 1] = (byte xor mask).toByte() }
        return Base64.getEncoder().encodeToString(output).trimEnd('=')
    }

    companion object {
        private const val HOME_URL = "https://x.com/home"
        private val INIT_HEADERS = mapOf(
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Referer" to "https://x.com",
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
            "X-Twitter-Active-User" to "yes",
            "X-Twitter-Client-Language" to "en",
        )

        fun initialize(httpClient: FeedHttpClient): XClientTransaction {
            val homeHtml = httpClient.get(HOME_URL, INIT_HEADERS)
            val onDemandText = httpClient.get(onDemandFileUrl(homeHtml), emptyMap())
            return fromInitialization(homeHtml, onDemandText)
        }

        internal fun fromInitialization(
            homeHtml: String,
            onDemandText: String,
            epochSeconds: () -> Long = { Instant.now().epochSecond },
            randomByte: () -> Int = { SecureRandom().nextInt(256) },
        ): XClientTransaction {
            val indices = INDICES_REGEX.findAll(onDemandText)
                .map { it.groupValues[2].toInt() }
                .toList()
            require(indices.isNotEmpty()) { "Couldn't get KEY_BYTE indices" }
            val keyBytes = keyBytes(siteVerificationKey(homeHtml))
            val animationKey = computeAnimationKey(
                keyBytes = keyBytes,
                rowIndex = indices.first(),
                keyByteIndices = indices.drop(1),
                homeHtml = homeHtml,
            )
            return create(keyBytes, animationKey, epochSeconds, randomByte)
        }

        internal fun create(
            keyBytes: List<Int>,
            animationKey: String,
            epochSeconds: () -> Long,
            randomByte: () -> Int,
        ): XClientTransaction = XClientTransaction(
            keyBytes = keyBytes,
            animationKey = animationKey,
            randomKeyword = DEFAULT_KEYWORD,
            randomNumber = ADDITIONAL_RANDOM_NUMBER,
            epochSeconds = epochSeconds,
            randomByte = randomByte,
        )

        internal fun onDemandFileUrl(html: String): String {
            val fileIndex = ON_DEMAND_FILE_REGEX.find(html)?.groupValues?.get(1)
                ?: error("Couldn't find ondemand file index")
            val filename = Regex(",${Regex.escape(fileIndex)}:\"([0-9a-f]+)\"")
                .find(html)?.groupValues?.get(1)
                ?: error("Couldn't find ondemand file hash")
            return ON_DEMAND_FILE_URL_TEMPLATE.replace("{filename}", filename)
        }

        private fun siteVerificationKey(html: String): String {
            val tag = Regex("""<meta\b[^>]*\bname\s*=\s*["']twitter-site-verification["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html)?.value
                ?: error("Couldn't get [twitter-site-verification] key from the page source")
            return Regex("""\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)
                ?: error("twitter-site-verification meta has no content")
        }

        private fun keyBytes(key: String): List<Int> =
            Base64.getDecoder().decode(key).map { it.toUByte().toInt() }

        private fun computeAnimationKey(
            keyBytes: List<Int>,
            rowIndex: Int,
            keyByteIndices: List<Int>,
            homeHtml: String,
        ): String {
            val frameRowIndex = keyBytes[rowIndex] % 16
            val frameTimeProduct = keyByteIndices.fold(1) { value, index -> value * (keyBytes[index] % 16) }
            val frameTime = javascriptRound(frameTimeProduct / 10.0) * 10
            val frameRow = animationRows(keyBytes, homeHtml)[frameRowIndex]
            return animate(frameRow, frameTime / 4096.0)
        }

        private fun animationRows(keyBytes: List<Int>, html: String): List<List<Int>> {
            val frames = Regex(
                """<([a-z][\w:-]*)\b[^>]*\bid\s*=\s*["']loading-x-anim[^"']*["'][^>]*>(.*?)</\1\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(html).toList()
            val frame = frames[keyBytes[5] % 4].groupValues[2]
            val pathValues = Regex("""<path\b[^>]*\bd\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(frame).map { it.groupValues[1] }.toList()
            val path = pathValues[1].drop(9)
            return path.split('C').mapNotNull { segment ->
                val values = segment.replace(Regex("""[^\d]+"""), " ").trim()
                values.takeIf(String::isNotEmpty)?.split(Regex("""\s+"""))?.map(String::toInt)
            }
        }

        private fun solve(value: Double, min: Double, max: Double, rounding: Boolean): Double {
            val result = value * (max - min) / 255.0 + min
            return if (rounding) floor(result) else roundToTwo(result)
        }

        private fun animate(frames: List<Int>, targetTime: Double): String {
            val fromColor = listOf(frames[0].toDouble(), frames[1].toDouble(), frames[2].toDouble(), 1.0)
            val toColor = listOf(frames[3].toDouble(), frames[4].toDouble(), frames[5].toDouble(), 1.0)
            val toRotation = solve(frames[6].toDouble(), 60.0, 360.0, true)
            val curves = frames.drop(7).mapIndexed { index, value ->
                solve(value.toDouble(), oddLowerBound(index), 1.0, false)
            }
            val fraction = CubicCurve(curves).getValue(targetTime)
            val color = interpolate(fromColor, toColor, fraction).map { it.coerceIn(0.0, 255.0) }
            val matrix = convertRotationToMatrix(interpolate(listOf(0.0), listOf(toRotation), fraction)[0])
            val parts = buildList {
                color.dropLast(1).forEach { add(dartRound(it).toString(16)) }
                matrix.forEach { matrixValue ->
                    val hexadecimal = floatToHex(kotlin.math.abs(roundToTwo(matrixValue)))
                    add(
                        when {
                            hexadecimal.startsWith('.') -> "0${hexadecimal.lowercase()}"
                            hexadecimal.isNotEmpty() -> hexadecimal
                            else -> "0"
                        },
                    )
                }
                add("0")
                add("0")
            }
            return parts.joinToString("").replace(Regex("""[.\-]"""), "")
        }
    }
}
