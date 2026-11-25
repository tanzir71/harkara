package io.github.tanzir71.harkara.core.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class NegentropyTest {
    private fun item(n: Int, ts: Long) = Negentropy.Item(ts, MessageDigest.getInstance("SHA-256").digest("event-$n".encodeToByteArray()))

    private fun hex(item: Negentropy.Item) = Negentropy.bytesToHex(item.id)

    /** Runs a full NIP-77 exchange in memory and returns (have, need, rounds). */
    private fun sync(client: List<Negentropy.Item>, relay: List<Negentropy.Item>, frameLimit: Int = 0): Triple<Set<String>, Set<String>, Int> {
        val initiator = Negentropy(client, frameLimit)
        val responder = Negentropy(relay, frameLimit)
        var msg: String? = initiator.initiate()
        var rounds = 0
        while (msg != null) {
            rounds++
            check(rounds < 50) { "did not converge" }
            val reply = responder.reconcile(msg)!!
            msg = initiator.reconcile(reply)
        }
        return Triple(initiator.haveIds, initiator.needIds, rounds)
    }

    @Test
    fun `small sets reconcile with id lists`() {
        val shared = (1..10).map { item(it, 1000L + it) }
        val mine = shared + item(100, 5000)
        val theirs = shared + item(200, 6000) + item(201, 6000)
        val (have, need, _) = sync(mine, theirs)
        assertEquals(setOf(hex(item(100, 5000))), have)
        assertEquals(setOf(hex(item(200, 6000)), hex(item(201, 6000))), need)
    }

    @Test
    fun `large overlapping sets reconcile exactly`() {
        val random = Random(7)
        val all = (1..3000).map { item(it, 1_700_000_000L + random.nextInt(0, 86_400)) }
        val mine = all.filterIndexed { i, _ -> i % 10 != 0 }
        val theirs = all.filterIndexed { i, _ -> i % 7 != 0 }
        val (have, need, rounds) = sync(mine, theirs)
        assertEquals((mine.toSet() - theirs.toSet()).map(::hex).toSet(), have)
        assertEquals((theirs.toSet() - mine.toSet()).map(::hex).toSet(), need)
        assertTrue(rounds >= 2)
    }

    @Test
    fun `identical sets finish in one round with nothing to do`() {
        val all = (1..500).map { item(it, 1000L + it / 3) }
        val (have, need, rounds) = sync(all, all.shuffled(Random(1)))
        assertTrue(have.isEmpty() && need.isEmpty())
        assertEquals(1, rounds)
    }

    @Test
    fun `frame size limit still converges`() {
        val all = (1..2000).map { item(it, 1000L + it) }
        val (have, need, _) = sync(all.take(1500), all.drop(400), frameLimit = 4096)
        assertEquals(400, have.size)
        assertEquals(500, need.size)
    }

    @Test
    fun `varints are most significant digit first`() {
        fun enc(v: Long) = ByteArrayOutputStream().also { Negentropy.writeVarInt(it, v) }.toByteArray().joinToString(",") { (it.toInt() and 0xff).toString() }
        assertEquals("0", enc(0))
        assertEquals("127", enc(127))
        assertEquals("129,0", enc(128))
        assertEquals("130,128,0", enc(32768))
    }

    @Test
    fun `empty initiator against empty relay`() {
        val i = Negentropy(emptyList())
        val r = Negentropy(emptyList())
        val reply = r.reconcile(i.initiate())!!
        assertNull(i.reconcile(reply))
    }
}
