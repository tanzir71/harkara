package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QrTransportTest {
    private val keys = TestData.keys(7)

    private fun noise(n: Int, seed: Int): String {
        val r = kotlin.random.Random(seed)
        return String(CharArray(n) { ('a' + r.nextInt(26)) })
    }

    @Test
    fun `a small event is one code and reads back verified`() {
        val event = TestData.post(keys, text = "Fuel available at the Kalabagan pump, short queue")
        val codes = QrTransport.encode(event)
        assertEquals(1, codes.size)
        assertTrue(codes[0].startsWith("harkara:e:"))
        assertTrue(codes[0].length <= QrTransport.MAX_QR_CHARS)
        assertEquals(event, QrTransport.decodeSingle(codes[0]))
        val assembler = QrAssembler()
        assertEquals(QrAssembler.Result.Complete(event), assembler.add(codes[0]))
    }

    @Test
    fun `a long event becomes several codes that reassemble in any order`() {
        // Random-looking text so DEFLATE cannot shrink it into one code.
        val noise = noise(4000, 1)
        val event = keys.signEvent(Kinds.FEED_POST, listOf(listOf("g", "wh0r0d")), noise, TestData.NOW)
        val codes = QrTransport.encode(event)
        assertTrue(codes.size > 2, "${codes.size} codes")
        assertTrue(codes.all { it.length <= QrTransport.MAX_QR_CHARS && it.startsWith("harkara:q:") })
        val assembler = QrAssembler()
        val shuffled = codes.reversed() + codes[1]
        var last: QrAssembler.Result? = null
        for ((i, code) in shuffled.withIndex()) {
            last = assembler.add(code)
            if (i < codes.size - 1) assertInstanceOf(QrAssembler.Result.Progress::class.java, last)
            if (i == codes.size - 1) break
        }
        assertEquals(QrAssembler.Result.Complete(event), last)
        assertEquals(0, assembler.pendingSets)
    }

    @Test
    fun `a tampered part or a mixed set is refused`() {
        val noise = noise(3000, 2)
        val event = keys.signEvent(Kinds.FEED_POST, emptyList(), noise, TestData.NOW)
        val codes = QrTransport.encode(event, maxChars = 400)
        val bad = codes.toMutableList()
        val last = bad.last()
        val at = last.length - 10
        bad[bad.size - 1] = last.substring(0, at) + (if (last[at] == 'A') 'B' else 'A') + last.substring(at + 1)
        val assembler = QrAssembler()
        val results = bad.map { assembler.add(it) }
        assertEquals(QrAssembler.Result.Invalid, results.last())
        assertEquals(QrAssembler.Result.Invalid, QrAssembler().add("harkara:q:zzzz:1/2:abc"))
        assertEquals(QrAssembler.Result.Invalid, QrAssembler().add("harkara:q:0a0b0c0d:3/2:abc"))
        assertNull(QrTransport.decodeSingle("harkara:e:AAAA"))
    }

    @Test
    fun `parts older than the keep window are forgotten`() {
        var now = 0L
        val noise = noise(3000, 3)
        val event = keys.signEvent(Kinds.FEED_POST, emptyList(), noise, TestData.NOW)
        val codes = QrTransport.encode(event, maxChars = 400)
        val assembler = QrAssembler(nowMillis = { now }, keepMillis = 1000)
        assembler.add(codes[0])
        now = 5000
        val r = assembler.add(codes[1])
        assertEquals(QrAssembler.Result.Progress(QrTransport.parsePart(codes[1])!!.set, 1, codes.size), r)
    }
}
