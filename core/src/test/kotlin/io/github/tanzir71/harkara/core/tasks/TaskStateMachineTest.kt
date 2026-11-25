package io.github.tanzir71.harkara.core.tasks

import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.nostr.Kinds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskStateMachineTest {
    private val now = 1_764_000_000L
    private val requester = KeyPair.generate()
    private val runner = KeyPair.generate()
    private val stranger = KeyPair.generate()
    private val offerEvent = TaskOffers.build(
        requester, "task-1", "Two insulin pens from the Road 8 pharmacy to House 12", "pickup", 120,
        23.7465, 90.376, "Ask for the cold pack.", now, mapOf("from" to "Road 8 pharmacy", "to" to "House 12"),
    )
    private val offer = TaskOffers.parse(offerEvent)!!

    @BeforeAll
    fun fastScrypt() {
        Handoff.scryptN = 1 shl 10
    }

    private fun reduce(vararg steps: io.github.tanzir71.harkara.core.nostr.Event, at: Long = now + 10_000) =
        TaskStateMachine.reduce(offerEvent, steps.toList(), at)!!

    private fun acceptedSteps(): Triple<List<io.github.tanzir71.harkara.core.nostr.Event>, io.github.tanzir71.harkara.core.nostr.Event, String> {
        val accept = TaskSteps.accept(runner, offer, now + 10)
        val token = Handoff.token(requester, offer, runner.pubkeyHex, now + 20)
        val code = Handoff.code(token)
        val confirm = TaskSteps.confirm(requester, offer, accept, Handoff.commitment(code, offer.address, runner.pubkeyHex), now + 20)
        return Triple(listOf(accept, confirm), token, code)
    }

    @Test
    fun `offer parses with price, type, fields and area`() {
        assertEquals(120, offer.price)
        assertEquals("pickup", offer.type)
        assertEquals("Road 8 pharmacy", offer.fields["from"])
        assertEquals(7, offer.geohash!!.length)
        assertEquals("30402:${requester.pubkeyHex}:task-1", offer.address)
    }

    @Test
    fun `happy path with countersigned accept, typed code, payment and ratings`() {
        val (start, _, code) = acceptedSteps()
        val steps = start + listOf(
            TaskSteps.pickedUp(runner, offer, now + 30),
            TaskSteps.deliveredWithCode(runner, offer, code, now + 40),
            TaskSteps.paid(requester, offer, runner.pubkeyHex, "encrypted", now + 50),
            TaskSteps.paidAck(runner, offer, now + 60),
            TaskSteps.rate(requester, offer, runner.pubkeyHex, 5, now + 70),
            TaskSteps.rate(runner, offer, requester.pubkeyHex, 4, now + 71),
        )
        val state = reduce(*steps.toTypedArray())
        assertEquals(TaskStatus.PAID, state.status)
        assertEquals(runner.pubkeyHex, state.runner)
        assertEquals(HandoffProof.CODE, state.proof)
        assertEquals(mapOf(requester.pubkeyHex to 5, runner.pubkeyHex to 4), state.ratings)
        assertTrue(state.dropped.isEmpty(), "${state.dropped.map { it.second }}")
        assertTrue(state.done)
    }

    @Test
    fun `a countersign in the same second or from a slow clock still counts`() {
        val accept = TaskSteps.accept(runner, offer, now + 10)
        val token = Handoff.token(requester, offer, runner.pubkeyHex, now)
        val commitment = Handoff.commitment(Handoff.code(token), offer.address, runner.pubkeyHex)
        val sameSecond = TaskSteps.confirm(requester, offer, accept, commitment, now + 10)
        val slowClock = TaskSteps.confirm(requester, offer, accept, commitment, now + 2)
        assertEquals(TaskStatus.ACCEPTED, reduce(sameSecond, accept).status)
        assertEquals(TaskStatus.ACCEPTED, reduce(slowClock, accept).status)
    }

    @Test
    fun `accept alone does not give anyone the task`() {
        val state = reduce(TaskSteps.accept(runner, offer, now + 10), TaskSteps.pickedUp(runner, offer, now + 20))
        assertEquals(TaskStatus.OPEN, state.status)
        assertNull(state.runner)
        assertEquals(1, state.pendingAccepts.size)
        assertEquals("only the runner picks up", state.dropped.single().second)
    }

    @Test
    fun `only allowed roles can make each step`() {
        val (start, _, code) = acceptedSteps()
        val state = reduce(
            *(start + listOf(
                TaskSteps.accept(requester, offer, now + 5),
                TaskSteps.pickedUp(stranger, offer, now + 30),
                TaskSteps.deliveredWithCode(stranger, offer, code, now + 31),
                TaskSteps.cancel(runner.let { it }, offer, now + 32),
                TaskSteps.paid(runner, offer, runner.pubkeyHex, "x", now + 33),
                TaskSteps.dispute(stranger, offer, runner.pubkeyHex, DisputeReason.OTHER, now + 34),
            )).toTypedArray(),
        )
        assertEquals(TaskStatus.ACCEPTED, state.status)
        assertEquals(
            listOf(
                "requester can't accept own task", "only the runner picks up", "only the runner delivers",
                "only the requester cancels", "only the requester records payment", "only the two sides dispute",
            ),
            state.dropped.map { it.second },
        )
    }

    @Test
    fun `forged and tampered steps are dropped`() {
        val (start, _, _) = acceptedSteps()
        val forged = TaskSteps.pickedUp(runner, offer, now + 30).let { it.copy(content = "tampered") }
        val otherTask = runner.signEvent(Kinds.TASK_STEP, listOf(listOf("a", "30402:x:y"), listOf("step", "picked_up")), "", now + 31)
        val state = reduce(*(start + listOf(forged, otherTask)).toTypedArray())
        assertEquals(TaskStatus.ACCEPTED, state.status)
        assertEquals(setOf("bad signature", "other task"), state.dropped.map { it.second }.toSet())
    }

    @Test
    fun `handoff token works offline and only once`() {
        val (start, token, _) = acceptedSteps()
        // Offline check: nothing but the token, the offer and the runner's own key.
        assertTrue(Handoff.verifyToken(token, offer, runner.pubkeyHex))
        assertFalse(Handoff.verifyToken(token, offer, stranger.pubkeyHex))
        val first = TaskSteps.deliveredWithToken(runner, offer, token, now + 40)
        val again = TaskSteps.deliveredWithToken(runner, offer, token, now + 41)
        val state = reduce(*(start + listOf(first, again)).toTypedArray())
        assertEquals(TaskStatus.DELIVERED, state.status)
        assertEquals(HandoffProof.TOKEN, state.proof)
        assertEquals("not in progress", state.dropped.single().second)
    }

    @Test
    fun `a token from someone else or for another runner is refused`() {
        val (start, _, _) = acceptedSteps()
        val fake = Handoff.token(stranger, offer, runner.pubkeyHex, now + 25)
        val wrongRunner = Handoff.token(requester, offer, stranger.pubkeyHex, now + 25)
        val state = reduce(
            *(start + listOf(TaskSteps.deliveredWithToken(runner, offer, fake, now + 40), TaskSteps.deliveredWithToken(runner, offer, wrongRunner, now + 41))).toTypedArray(),
        )
        assertEquals(TaskStatus.ACCEPTED, state.status)
        assertTrue(state.dropped.all { it.second == "token not valid for this task and runner" })
    }

    @Test
    fun `typed code is checked against the commitment and wrong codes are dropped`() {
        val (start, _, code) = acceptedSteps()
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertTrue(Handoff.verifyCode(code, start[1].tagValue("handoff")!!, offer, runner.pubkeyHex))
        val state = reduce(*(start + TaskSteps.deliveredWithCode(runner, offer, wrong, now + 40)).toTypedArray())
        assertEquals(TaskStatus.ACCEPTED, state.status)
        assertEquals("wrong handoff code", state.dropped.single().second)
        assertEquals(7, Handoff.displayCode("482913").length)
    }

    @Test
    fun `decline reopens, cancel and dispute end the task, open offers expire`() {
        val (start, _, _) = acceptedSteps()
        assertEquals(TaskStatus.OPEN, reduce(*(start + TaskSteps.decline(runner, offer, now + 30)).toTypedArray()).status)
        assertEquals(TaskStatus.CANCELLED, reduce(*(start + TaskSteps.cancel(requester, offer, now + 30)).toTypedArray()).status)
        val disputed = reduce(*(start + TaskSteps.dispute(runner, offer, requester.pubkeyHex, DisputeReason.PAYMENT_ISSUE, now + 30)).toTypedArray())
        assertEquals(TaskStatus.DISPUTED, disputed.status)
        assertEquals("payment_issue", disputed.disputeReason)
        assertEquals(TaskStatus.EXPIRED, reduce(at = now + 25 * 3600).status)
    }

    @Test
    fun `reputation counts only people within two steps of verified contacts`() {
        val (start, _, code) = acceptedSteps()
        val done = reduce(
            *(start + listOf(TaskSteps.deliveredWithCode(runner, offer, code, now + 40), TaskSteps.rate(requester, offer, runner.pubkeyHex, 5, now + 50))).toTypedArray(),
        )
        val viewer = KeyPair.generate().pubkeyHex
        val friend = KeyPair.generate().pubkeyHex
        val lists = mapOf(viewer to setOf(friend), friend to setOf(requester.pubkeyHex))
        val trusted = ReputationRules.trusted(viewer, lists)
        assertEquals(setOf(viewer, friend, requester.pubkeyHex), trusted)
        assertEquals(Reputation(1, 1, 5.0), ReputationRules.of(runner.pubkeyHex, listOf(done), trusted))
        assertEquals(Reputation(0, 0, null), ReputationRules.of(runner.pubkeyHex, listOf(done), ReputationRules.trusted(viewer, mapOf(viewer to setOf(friend)))))
        assertEquals(1, ReputationRules.mutualContacts(viewer, "x", mapOf(viewer to setOf(friend), "x" to setOf(friend))))
        assertNotNull(done.at(StepType.DELIVERED))
    }
}
