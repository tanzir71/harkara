package io.github.tanzir71.harkara.core.links

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouBundle
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.core.wire.SmsCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HarkaraLinksTest {
    private val keys = TestData.keys(5)
    private val event = keys.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dkq7")), "Reached home", TestData.NOW)

    @Test
    fun `scheme and web links carry the same thing`() {
        val rest = HarkaraLinks.eventRest(event)
        assertEquals(HarkaraLink.EventLink(event), HarkaraLinks.parse("harkara:$rest"))
        assertEquals(HarkaraLink.EventLink(event), HarkaraLinks.parse(HarkaraLinks.web(rest)))
        assertEquals(HarkaraLink.EventLink(event), HarkaraLinks.parse("https://tanzir71.github.io/harkara/en/o/#$rest"))
        assertEquals(HarkaraLink.EventLink(event), HarkaraLinks.parse(QrTransport.encode(event).single()))
        assertNull(HarkaraLinks.parse("https://tanzir71.github.io/harkara/#$rest"), "only the o/ page carries payloads")
        assertNull(HarkaraLinks.parse("https://example.com/harkara/o/#$rest"))
    }

    @Test
    fun `task, area, gateway, handoff and IOU forms`() {
        val task = HarkaraLinks.parse(HarkaraLinks.web(HarkaraLinks.taskRest(keys.pubkeyHex, "8f14e45f-ceea-467a-9575-23a1b6f0c3d2")))
        assertEquals(HarkaraLink.Task(keys.pubkeyHex, "8f14e45f-ceea-467a-9575-23a1b6f0c3d2"), task)
        assertEquals("30402:${keys.pubkeyHex}:8f14e45f-ceea-467a-9575-23a1b6f0c3d2", (task as HarkaraLink.Task).address)
        assertEquals(HarkaraLink.Area("wh0r0"), HarkaraLinks.parse("harkara:g:wh0r0"))
        assertNull(HarkaraLinks.parse("harkara:g:WH0R0"))
        val card = GatewayCard("Tania", "01711000111")
        assertEquals(HarkaraLink.Gateway(card), HarkaraLinks.parse(card.toLink()))
        assertInstanceOf(HarkaraLink.Handoff::class.java, HarkaraLinks.parse("harkara:handoff:" + event.toJson()))

        val terms = IouTerms("iou-1", Party("Tania", keys.pubkeyHex), Party("Karim"), 500, createdAt = TestData.NOW, confirmCodeHash = Iou.codeHash(IouConfirm.code(keys, "iou-1")))
        val sig = Iou.sign(keys, terms, TestData.NOW)
        val bundle = IouBundle(terms, listOf(sig.toJson()))
        assertInstanceOf(HarkaraLink.IouBundle::class.java, HarkaraLinks.parse(Iou.toQr(bundle)))
        val confirm = HarkaraLinks.web(IouConfirm.PREFIX + IouConfirm.encode(terms, sig, IouConfirm.code(keys, "iou-1")))
        val reminder = "Reminder from Tania: Tk 500 for lunch. Confirm here: $confirm."
        val parsed = HarkaraLinks.parse(reminder)
        assertInstanceOf(HarkaraLink.IouConfirm::class.java, parsed)
        assertEquals(terms, (parsed as HarkaraLink.IouConfirm).link.terms)
    }

    @Test
    fun `pasted SMS text and junk`() {
        val sms = SmsCodec.text(event)
        assertEquals(HarkaraLink.Sms("got this: $sms"), HarkaraLinks.parse("got this: $sms"))
        assertInstanceOf(HarkaraLink.QrPart::class.java, HarkaraLinks.parse("harkara:q:0a0b0c0d:1/3:QUJD"))
        assertNull(HarkaraLinks.parse(""))
        assertNull(HarkaraLinks.parse("hello"))
        assertNull(HarkaraLinks.parse("harkara:e:broken"))
        assertNull(HarkaraLinks.parse("harkara:zz:1"))
    }
}
