package io.github.tanzir71.harkara.core.sms

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.core.wire.SmsCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsGatewayTest {
    private val policy = GatewayPolicy()
    private val config = GatewayConfig(
        groupName = "Hall 3 floor 2",
        members = listOf(GatewayMember("Rafi", "+8801711000111"), GatewayMember("Nabila", "01822 000 222", "ab".repeat(32))),
    )

    @Test
    fun `only members, in any number format, and at most 100 a day`() {
        assertEquals(GatewayPolicy.Decision.ALLOW, policy.decide(config, "01711000111", 0))
        assertEquals(GatewayPolicy.Decision.ALLOW, policy.decide(config, "8801822000222", 99))
        assertEquals(GatewayPolicy.Decision.CAP_REACHED, policy.decide(config, "+880 1822-000222", 100))
        assertEquals(GatewayPolicy.Decision.NOT_A_MEMBER, policy.decide(config, "01999000999", 0))
        assertEquals(GatewayPolicy.Decision.NOT_A_MEMBER, policy.decide(config, "", 0))
        assertEquals("Nabila", policy.memberForKey(config, "ab".repeat(32))?.name)
        assertEquals(100, GatewayPolicy.DAILY_CAP)
    }

    @Test
    fun `the daily counter turns over at midnight in Dhaka`() {
        // 2026-11-25 17:59:59Z is 23:59:59 in Dhaka (UTC+6); one second later is the next day there.
        val before = java.time.Instant.parse("2026-11-25T17:59:59Z").toEpochMilli()
        assertEquals("2026-11-25", policy.day(before))
        assertEquals("2026-11-26", policy.day(before + 1000))
    }

    @Test
    fun `gateway card round trips and rejects junk`() {
        val card = GatewayCard("Tania (hall 3)", "01711000111", "cd".repeat(32))
        assertEquals(card, GatewayCard.parse(card.toLink()))
        assertNull(GatewayCard.parse("harkara:gw:bm90IGpzb24"))
        assertNull(GatewayCard.parse(GatewayCard("x", "12", null).toLink()))
    }

    @Test
    fun `data SMS frames carry a two-part payload and reassemble in any order`() {
        val keys = TestData.keys(9)
        val event = keys.signEvent(
            Kinds.SOS,
            listOf(listOf("g", "wh0r0dkq7"), listOf("place", "Mirpur 10, near the roundabout, blue gate")),
            "পানি বাড়ছে, আমরা তিনজন ছাদে আটকে আছি। মিরপুর ১০ গোলচত্বরের পাশের গলি",
            TestData.NOW,
        )
        val payload = SmsCodec.encode(event)
        val frames = DataSmsFraming.split(payload, set = 42)
        assertTrue(frames.size >= 2, "payload ${payload.size} B should need two data SMS")
        assertEquals((payload.size + 129) / 130, frames.size)
        frames.forEach { assertTrue(it.size <= DataSmsFraming.MAX_FRAME) }
        val assembler = DataSmsAssembler()
        assertNull(assembler.add("+8801711000111", frames.last()))
        val whole = frames.dropLast(1).map { assembler.add("01711000111", it) }.last()
        assertEquals(event, SmsCodec.decode(whole!!, PrefixResolver.of(listOf(keys.pubkeyHex))))
        assertNull(DataSmsFraming.parse(byteArrayOf(0x00, 1, 0x11)))
        assertNull(DataSmsFraming.parse(byteArrayOf(0x48, 1, 0x32)))
    }
}
