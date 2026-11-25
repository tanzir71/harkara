package io.github.tanzir71.harkara.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.lan.LanDiscovery
import io.github.tanzir71.harkara.lan.LanRoom
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.InetAddress

/** Simulated phones for chat tests: separate databases, identities, relay pools and (optionally) Wi-Fi rooms. */
class ChatTestPhones(private val clock: () -> Long) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = mutableListOf<Phone>()

    inner class Phone(name: String, relayUrl: String?, discovery: LanDiscovery?) {
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope, now = clock)
        val outbox = Outbox(db, pool, clock)
        val keys = KeyPair.generate()
        val npub: String = Bech32.encodeNpub(keys.pubkeyHex)
        private val areaKeys = mutableMapOf<String, KeyPair>()
        val lan: LanRoom? = discovery?.let { LanRoom(scope, it, InetAddress.getLoopbackAddress(), now = clock) }
        val chat = ChatRepository(db, outbox, pool, lan, { keys }, { g5 -> areaKeys.getOrPut(g5) { KeyPair.generate() } }, InMemoryChatPrefs(displayName = name), clock)

        init {
            outbox.start(scope)
            chat.start(scope)
            chat.subscribe(AREA)
            if (relayUrl != null) pool.setRelays(listOf(relayUrl))
            lan?.start()
        }

        suspend fun awaitRelay() = withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }

        suspend fun awaitLanPeers(n: Int) = withTimeout(10_000) {
            lan!!.pool.status.first { s -> s.values.count { it == RelayStatus.CONNECTED } >= n }
        }

        /** The thread once [predicate] holds. */
        suspend fun threadWhen(conversationId: String, predicate: (List<ThreadMessage>) -> Boolean): List<ThreadMessage> =
            withTimeout(15_000) { chat.thread(conversationId).first(predicate) }

        suspend fun awaitDelivery(conversationId: String, messageId: String, state: Delivery): ThreadMessage =
            threadWhen(conversationId) { list -> list.any { it.message.id == messageId && it.delivery == state } }.single { it.message.id == messageId }

        suspend fun lastOwn(conversationId: String): ThreadMessage = chat.thread(conversationId).first().last { it.message.own }
    }

    fun phone(name: String, relayUrl: String? = null, discovery: LanDiscovery? = null) = Phone(name, relayUrl, discovery).also { phones += it }

    fun close() {
        phones.forEach { p ->
            p.lan?.stop()
            p.pool.stop()
            p.db.close()
        }
        scope.cancel()
    }

    companion object {
        /** The area channel every test phone joins (a geohash-5 cell). */
        const val AREA = "wh0r0"
    }
}
