package com.anezium.rokidbus.plugin.feeds.xtransaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class XClientTransactionTest {
    @Test
    fun generateTransactionId_matchesFixedQuaXAlgorithmVector() {
        val transaction = XClientTransaction.create(
            keyBytes = (0..7).toList(),
            animationKey = "abcdef",
            epochSeconds = { TIME_EPOCH_SECONDS + 123_456 },
            randomByte = { 42 },
        )

        val id = transaction.generateTransactionId(
            "GET",
            "/i/api/graphql/W4Tpu1uueTGK53paUgxF0Q/HomeTimeline",
        )

        assertEquals("KiorKCkuLywtasgrKpe0IoVYJP8ff6UFKXv8BcYp", id)
        assertFalse(id.contains('='))
        assertEquals(30, Base64.getDecoder().decode(id).size)
    }

    @Test
    fun initialization_parsesQuaXMetaBundleIndicesAndSvgFrames() {
        val keyBytes = MutableList(32) { it }.apply { this[5] = 0 }
        val key = Base64.getEncoder().encodeToString(keyBytes.map(Int::toByte).toByteArray())
        val rows = (0 until 16).joinToString("") { row ->
            "C${10 + row} ${20 + row} ${30 + row} ${40 + row} ${50 + row} ${60 + row} 70 80 90 100 110"
        }
        val frames = (0 until 4).joinToString("") { frame ->
            "<svg id=\"loading-x-anim-$frame\"><g><path d=\"unused\"></path>" +
                "<path d=\"123456789$rows\"></path></g></svg>"
        }
        val html = "<html><head><meta name=\"twitter-site-verification\" content=\"$key\"></head>" +
            "<body>$frames</body><script>,17:\"ondemand.s\",17:\"abc123\"</script></html>"
        val bundle = "x(a[0], 16);x(a[1], 16);x(a[2], 16);"

        val transaction = XClientTransaction.fromInitialization(
            homeHtml = html,
            onDemandText = bundle,
            epochSeconds = { TIME_EPOCH_SECONDS + 1 },
            randomByte = { 0 },
        )

        assertEquals(
            "https://abs.twimg.com/responsive-web/client-web/ondemand.s.abc123a.js",
            XClientTransaction.onDemandFileUrl(html),
        )
        assertFalse(transaction.animationKey.isBlank())
        assertFalse(transaction.generateTransactionId("GET", "/test").contains('='))
    }
}
