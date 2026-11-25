package io.github.tanzir71.harkara.core.links

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.tasks.Handoff
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import io.github.tanzir71.harkara.core.wire.BinaryEventCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

/**
 * Payloads signed by `core` for the `site/o/` Playwright test (`tools/fixtures/o-payloads.json`).
 * Regenerate with `HARKARA_WRITE_FIXTURES=1 ./gradlew :core:test --tests *WebPageFixturesTest*`; every run
 * checks that the committed file still decodes and verifies here, so the page and core cannot drift apart.
 */
class WebPageFixturesTest {
    private val file = File("../tools/fixtures/o-payloads.json")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val now = TestData.NOW

    private fun obj(e: Event): JsonElement = Json.parseToJsonElement(e.toJson())

    private fun generate(): JsonObject {
        val b64 = Base64.getUrlEncoder().withoutPadding()

        // A signed SOS, and the same SOS with its text changed after signing.
        val friend = TestData.keys(21)
        val sos = friend.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7"), listOf("place", "Mirpur 10")), "পানি বাড়ছে, ছাদে আটকে আছি", now)
        val tampered = sos.copy(content = "পানি কমেছে, সবাই ঠিক আছে")

        // An IOU with someone not on Harkara: the confirm link, and one with the amount changed.
        val tania = TestData.keys(22)
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val code = IouConfirm.code(tania, id)
        val terms = IouTerms(id, Party("Tania", tania.pubkeyHex), Party("Karim"), 1250, note = "Cha-er dokan, November", createdAt = now, confirmCodeHash = Iou.codeHash(code))
        val sig = Iou.sign(tania, terms, now)

        // A task with its steps, and a step whose text was changed after signing.
        val requester = TestData.keys(23)
        val runner = TestData.keys(24)
        val offer = TaskOffers.parse(
            TaskOffers.build(
                requester, "8f14e45f-ceea-467a-9575-23a1b6f0c3d2", "Napa Extra ene din", "buy", 120, 23.7465, 90.3760,
                "2 pata Napa Extra, Lazz Pharma theke", now, mapOf("store" to "Lazz Pharma, Dhanmondi"), name = "Tania",
            ),
        )!!
        val accept = TaskSteps.accept(runner, offer, now + 60, "Rafi")
        val token = Handoff.token(requester, offer, runner.pubkeyHex, now + 120)
        val confirm = TaskSteps.confirm(requester, offer, accept, Handoff.commitment(Handoff.code(token), offer.address, runner.pubkeyHex), now + 120)
        val picked = TaskSteps.pickedUp(runner, offer, now + 600)
        val forgedStep = TaskSteps.deliveredWithCode(runner, offer, "123456", now + 900).let { it.copy(tags = it.tags.map { t -> if (t[0] == "code") listOf("code", "654321") else t }) }

        // Area feed around wh0r0: two live posts, one expired, one forged.
        val area = TestData.keys(25)
        val area2 = TestData.keys(26)
        val g = { lat: Double, lng: Double -> listOf(7, 6, 5).map { listOf("g", GeoHash.encode(lat, lng, it)) } }
        val here = 23.7465 to 90.3760
        val food = area.signEvent(Kinds.FEED_POST, g(here.first, here.second) + listOf(listOf("t", "food")), "Khichuri at the hall canteen till 9 pm", now - 1800)
        val traffic = area2.signEvent(Kinds.FEED_POST, g(here.first, here.second) + listOf(listOf("t", "traffic"), listOf("expiration", (now + 3 * 3600).toString())), "Science Lab mor e jam, Nilkhet diye jan", now - 600)
        val expired = area.signEvent(Kinds.FEED_POST, g(here.first, here.second) + listOf(listOf("t", "traffic"), listOf("expiration", (now - 2 * 3600).toString())), "Old jam, long gone", now - 5 * 3600)
        val forgedPost = area2.signEvent(Kinds.FEED_POST, g(here.first, here.second) + listOf(listOf("t", "alert")), "Water rising on Road 27", now - 300).copy(content = "Everyone leave now")

        return buildJsonObject {
            put("_note", "Signed by core (WebPageFixturesTest). Regenerate: HARKARA_WRITE_FIXTURES=1 ./gradlew :core:test --tests *WebPageFixturesTest*")
            put("now", now)
            put("event", buildJsonObject {
                put("fragment", HarkaraLinks.eventRest(sos))
                put("kind", sos.kind)
                put("content", sos.content)
                put("pubkey", sos.pubkey)
                put("id", sos.id)
            })
            put("eventTampered", buildJsonObject { put("fragment", "e:" + b64.encodeToString(BinaryEventCodec.encode(tampered, includeId = false))) })
            put("confirm", buildJsonObject {
                put("fragment", IouConfirm.PREFIX + IouConfirm.encode(terms, sig, code))
                put("id", id)
                put("hash", Iou.hash(terms))
                put("code", code)
                put("amount", terms.amount)
                put("creditor", terms.creditor.name)
                put("debtor", terms.debtor.name)
                put("note", terms.note)
            })
            put("confirmTampered", buildJsonObject { put("fragment", IouConfirm.PREFIX + IouConfirm.encodeRaw(terms.copy(amount = 12_500), sig.createdAt, sig.sig, code, sig.pubkey)) })
            put("task", buildJsonObject {
                put("fragment", HarkaraLinks.taskRest(requester.pubkeyHex, offer.d))
                put("address", offer.address)
                put("title", offer.title)
                put("offer", obj(offer.event))
                put("steps", buildJsonArray { listOf(accept, confirm, picked).forEach { add(obj(it)) } })
                put("forged", obj(forgedStep))
            })
            put("area", buildJsonObject {
                put("fragment", HarkaraLinks.areaRest(GeoHash.encode(here.first, here.second, 5)))
                put("events", buildJsonArray { listOf(food, traffic, expired, forgedPost).forEach { add(obj(it)) } })
                put("visible", buildJsonArray { listOf(food, traffic).forEach { add(Json.parseToJsonElement("\"${it.id}\"")) } })
            })
        }
    }

    @Test
    fun `committed web page fixtures are signed by core and still check out`() {
        if (System.getenv("HARKARA_WRITE_FIXTURES") == "1" || !file.exists()) {
            file.writeText(json.encodeToString(JsonObject.serializer(), generate()) + "\n")
        }
        val f = Json.parseToJsonElement(file.readText()).jsonObject
        fun frag(key: String) = f[key]!!.jsonObject["fragment"]!!.jsonPrimitive.content
        fun event(e: JsonElement) = Event.fromJson(e.toString())

        val link = HarkaraLinks.fromRest(frag("event"))
        assertInstanceOf(HarkaraLink.EventLink::class.java, link)
        assertEquals(f["event"]!!.jsonObject["id"]!!.jsonPrimitive.content, (link as HarkaraLink.EventLink).event.id)

        assertNull(HarkaraLinks.fromRest(frag("eventTampered")), "a tampered event never verifies")
        val tampered = BinaryEventCodec.decode(Base64.getUrlDecoder().decode(frag("eventTampered").removePrefix("e:")))
        assertFalse(tampered.verify())

        val confirm = HarkaraLinks.fromRest(frag("confirm")) as HarkaraLink.IouConfirm
        assertTrue(confirm.link.codeMatches)
        assertEquals(f["confirm"]!!.jsonObject["hash"]!!.jsonPrimitive.content, Iou.hash(confirm.link.terms))
        assertNull(HarkaraLinks.fromRest(frag("confirmTampered")))

        val task = f["task"]!!.jsonObject
        assertInstanceOf(HarkaraLink.Task::class.java, HarkaraLinks.fromRest(frag("task")))
        assertTrue(event(task["offer"]!!).verify())
        assertTrue(task["steps"]!!.jsonArray.all { event(it).verify() })
        assertFalse(event(task["forged"]!!).verify())

        val area = f["area"]!!.jsonObject
        val visible = (area["visible"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        val events = area["events"]!!.jsonArray.map(::event)
        assertEquals(visible, events.filter { it.verify() && !it.isExpired(f["now"]!!.jsonPrimitive.content.toLong() + 3600) }.map { it.id }.toSet())
    }
}
