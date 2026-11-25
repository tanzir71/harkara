package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.khata.Settlement
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.sms.SmsDm
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.core.sms.SmsLimits
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * M10 gate: every SMS-eligible event type, with every field at its limit (Bangla where Bangla is allowed,
 * the longest TrxID, the biggest amount), fits two GSM-7 parts (≤ 306 characters) and decodes back to the
 * identical, verifying event. Measurements go to build/sms-budget.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmsBudgetTest {
    private val now = TestData.NOW
    private val requester = KeyPair.generate()
    private val runner = KeyPair.generate()
    private val friend = KeyPair.generate()
    private val measured = mutableListOf<String>()

    // Realistic, non-repeating Bangla (with Bangla digits and a danda, which Text8 escapes).
    private val banglaSentence = "পানি বাড়ছে, আমরা তিনজন ছাদে আটকে আছি। মিরপুর ১০ গোলচত্বরের পাশের গলি, নীল গেটের বাড়ি, তিনতলা"
    private val banglaPlace = "ধানমন্ডি ২৭ নম্বর রোড, মসজিদের পাশের হলুদ বাড়ির সামনে"
    private val banglaName = "মোহাম্মদ আব্দুর রহমান চৌধুরী সিদ্দিকী তালুকদার সাহেব"

    private val offer = TaskOffers.parse(
        TaskOffers.build(
            requester, "8f14e45f-ceea-467a-9575-23a1b6f0c3d2", "ঔষধ এনে দিন", "buy", 999_999, 23.7465, 90.3760,
            "Napa Extra 2 pata", now,
        ),
    )!!

    private fun known(vararg extra: String) = PrefixResolver.of(
        listOf(requester.pubkeyHex, runner.pubkeyHex, friend.pubkeyHex, offer.event.id) + extra,
    )

    private fun check(label: String, event: Event, resolver: PrefixResolver = known()) {
        assertTrue(event.verify(), "$label: test event verifies")
        assertTrue(SmsEligibility.isEligibleType(event), "$label: eligible type")
        val text = SmsEligibility.smsText(event)
        val shortest = SmsCodec.text(event, SmsEligibility.refsFor(event))
        assertNotNull(text, "$label: fits two SMS parts (shortest form is ${shortest.length} chars)")
        text!!
        val parts = Gsm7.parts(text)
        assertTrue(Gsm7.isGsm7(text), "$label: GSM-7 only")
        assertTrue(parts <= 2, "$label: $parts parts")
        assertTrue(text.length <= SmsCodec.MAX_CHARS, "$label: ${text.length} chars")
        val decoded = SmsCodec.decodeText(text, resolver)
        assertEquals(event, decoded, "$label: round trip")
        val payload = SmsCodec.extract(text)!!.size
        val refs = if (text == SmsCodec.text(event)) "full" else "8-byte refs"
        measured += "%-34s %3d chars  %d part(s)  %3d payload bytes  %s".format(label, text.length, parts, payload, refs)
    }

    @Test
    fun `check-in with the longest Bangla place label and note`() {
        val event = friend.signEvent(
            Kinds.CHECK_IN,
            listOf(
                listOf("g", "wh0r0dkq7"),
                listOf("place", banglaPlace.take(SmsLimits.PLACE_LABEL)),
                listOf("expiration", (now + 12 * 3600).toString()),
            ),
            banglaSentence.take(SmsLimits.CHECK_IN_NOTE),
            now,
        )
        check("check-in (3476)", event)
    }

    @Test
    fun `SOS with the longest Bangla note`() {
        val event = friend.signEvent(
            Kinds.SOS,
            listOf(listOf("g", "wh0r0dkq7"), listOf("place", banglaPlace.take(SmsLimits.PLACE_LABEL))),
            banglaSentence.take(SmsLimits.NOTE),
            now,
        )
        check("SOS (3477)", event)
    }

    @Test
    fun `SOS update`() {
        val sos = friend.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7")), "help", now)
        val event = friend.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", sos.id), listOf("status", "false_alarm")), banglaSentence.take(SmsLimits.NOTE), now + 600)
        check("SOS update (3478)", event, known(sos.id))
    }

    @Test
    fun `IOU signature and the web confirmation`() {
        val terms = IouTerms("0f8fad5b-d9cb-469f-a165-70867728950e", Party(banglaName.take(40), requester.pubkeyHex), Party("Karim", runner.pubkeyHex), 9_999_999, note = banglaSentence, createdAt = now)
        check("IOU signature (3474)", Iou.sign(runner, terms, now), known(Iou.hash(terms)))
        val keyless = terms.copy(debtor = Party("Karim"), confirmCodeHash = Iou.codeHash(IouConfirm.code(requester, terms.id)))
        val confirmation = IouConfirm.confirm(friend, keyless, IouConfirm.code(requester, keyless.id), now)
        check("IOU web confirmation (3474+code)", confirmation, known(Iou.hash(keyless)))
    }

    @Test
    fun `settlement with the longest TrxID and amount`() {
        val terms = IouTerms("0f8fad5b-d9cb-469f-a165-70867728950e", Party("Tania", requester.pubkeyHex), Party("Rafi", runner.pubkeyHex), 9_999_999, createdAt = now)
        val s = Settlement("nagad", 9_999_999, "Z9".repeat(SmsLimits.TRXID / 2))
        assertEquals(SmsLimits.TRXID, s.trxId.length)
        check("settlement (3475)", Iou.settle(runner, terms, s, now), known(Iou.hash(terms)))
    }

    @Test
    fun `task steps a runner sends from the road`() {
        check("task accept + Bangla name (3471)", TaskSteps.accept(runner, offer, now, banglaName.take(SmsLimits.NAME)))
        check("task picked_up (3471)", TaskSteps.pickedUp(runner, offer, now))
        check("task decline (3471)", TaskSteps.decline(runner, offer, now))
        check("task delivered + code (3471)", TaskSteps.deliveredWithCode(runner, offer, "048213", now))
    }

    @Test
    fun `short encrypted DM`() {
        val text = "ভালো আছি"
        assertTrue(text.encodeToByteArray().size <= SmsLimits.DM_BYTES, "${text.encodeToByteArray().size} bytes")
        val longest = "Ami thik achi, chinta koro na!!!"
        assertEquals(SmsLimits.DM_BYTES, longest.encodeToByteArray().size)
        check("SMS DM, Bangla (3479)", SmsDm.build(runner, friend.pubkeyHex, text, now))
        val dm = SmsDm.build(runner, friend.pubkeyHex, longest, now)
        check("SMS DM, 32 bytes (3479)", dm)
        assertEquals(longest, SmsDm.open(dm, friend))
    }

    @Test
    fun `steps that are not SMS-eligible are refused`() {
        val token = requester.signEvent(Kinds.HANDOFF_TOKEN, listOf(listOf("a", offer.address)), "", now)
        val withToken = TaskSteps.deliveredWithToken(runner, offer, token, now)
        assertTrue(!SmsEligibility.isEligibleType(withToken))
        assertEquals(null, SmsEligibility.smsText(withToken))
        assertEquals(null, SmsEligibility.smsText(offer.event))
        assertEquals(null, SmsEligibility.smsText(TaskSteps.rate(runner, offer, requester.pubkeyHex, 5, now)))
    }

    @AfterAll
    fun writeReport() {
        val out = File("build/sms-budget.txt")
        out.parentFile.mkdirs()
        out.writeText(measured.sorted().joinToString("\n", postfix = "\n"))
    }
}
