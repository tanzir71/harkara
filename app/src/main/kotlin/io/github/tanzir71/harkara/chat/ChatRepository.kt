package io.github.tanzir71.harkara.chat

import androidx.room.withTransaction
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.chat.ChatCard
import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.chat.ChatRumor
import io.github.tanzir71.harkara.core.chat.ChatVerdict
import io.github.tanzir71.harkara.core.chat.ReceiptStatus
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.core.chat.VoiceNotes
import io.github.tanzir71.harkara.core.chat.VoicePart
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.nostr.Schnorr
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.sms.SmsDm
import io.github.tanzir71.harkara.core.sms.SmsLimits
import io.github.tanzir71.harkara.data.ChatMessageEntity
import io.github.tanzir71.harkara.data.ChatReceiptEntity
import io.github.tanzir71.harkara.data.ChatWrapEntity
import io.github.tanzir71.harkara.data.ContactEntity
import io.github.tanzir71.harkara.data.ConversationEntity
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.MuteEntity
import io.github.tanzir71.harkara.data.VoicePartEntity
import io.github.tanzir71.harkara.lan.LanRoom
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64

enum class ConversationType { DM, GROUP, AREA, WIFI }

enum class MessageType { TEXT, CARD, VOICE, SYSTEM }

/** One message ready for the thread screen: the stored row plus what was worked out about it. */
data class ThreadMessage(
    val message: ChatMessageEntity,
    val card: ChatCard?,
    /** Our messages only. */
    val delivery: Delivery?,
    val carrier: Carrier,
    /** Best name for the author (pet name, their name, or null for "use the key"). */
    val senderName: String?,
)

/** A conversation with the names needed to title it. */
data class ConversationSummary(val conversation: ConversationEntity, val names: Map<String, String>)

/** Outcome of sending. */
enum class SendResult { SENT, EMPTY, TOO_LONG, PHONE_NUMBER, NO_CONVERSATION, FAILED }

/**
 * Chats: NIP-17 DMs and groups (gift-wrapped to every member and to ourselves), the anonymous area channel
 * and the "everyone on this Wi-Fi" room. Gift wraps go to the internet relays through the shared outbox
 * and, at the same time, to the Wi-Fi room when it is running; whichever carrier gets there first wins and
 * the receipts tell the sender. Everything is stored in Room first, so threads work offline.
 */
class ChatRepository(
    private val db: HarkaraDatabase,
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val lan: LanRoom?,
    private val profile: () -> KeyPair,
    private val areaKey: (geohash5: String) -> KeyPair,
    val prefs: ChatPrefs = InMemoryChatPrefs(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.chatDao()
    private val lock = Mutex()
    private val seenWraps = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 10_000
    }
    @Volatile private var area: String? = null
    private val random = SecureRandom()

    private val me: String get() = profile().pubkeyHex
    private fun nowSeconds() = nowMillis() / 1000

    /** Listens to the relays and the Wi-Fi room. Call once with a long-lived scope. */
    fun start(scope: CoroutineScope) {
        scope.launch { pool.incoming.collect { safely { ingest(it.event, Carrier.ONLINE) } } }
        scope.launch {
            pool.eose.collect { (_, sub) -> if (sub == SUB_DM) prefs.dmSyncedAt = nowSeconds() }
        }
        val room = lan ?: return
        scope.launch { room.incoming.collect { safely { ingest(it, Carrier.NODE) } } }
        scope.launch {
            room.acks.collect { ack -> if (ack.accepted) safely { raiseLan(ack.eventId, Delivery.ReachedNetwork) } }
        }
        scope.launch { room.peerCount.collect { if (it > 0) safely { flushLan() } } }
    }

    /** One bad event (or a database that is shutting down) must not stop the listener for good. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dropped; the event will come again from another carrier or the next fetch.
        }
    }

    /**
     * Subscribes to our gift wraps (relays and the Wi-Fi room), the Wi-Fi room's messages and the area
     * channel for [geohash5]. Wrap subscriptions reach two days behind (NIP-59 back-dating).
     */
    fun subscribe(geohash5: String?) {
        val now = nowSeconds()
        val synced = prefs.dmSyncedAt.takeIf { it > 0 } ?: (now - FIRST_FETCH_SECONDS)
        val dm = Filter(kinds = listOf(Kinds.GIFT_WRAP, Kinds.SMS_DM), tags = mapOf("p" to listOf(me)), since = synced - ChatProtocol.WRAP_LOOKBACK_SECONDS)
        pool.subscribe(SUB_DM, listOf(dm), ChatProtocol.WRAP_LOOKBACK_SECONDS)
        lan?.subscribe(SUB_DM, listOf(dm.copy(since = now - FIRST_FETCH_SECONDS - ChatProtocol.WRAP_LOOKBACK_SECONDS)), ChatProtocol.WRAP_LOOKBACK_SECONDS)
        lan?.subscribe(SUB_WIFI, listOf(Filter(kinds = listOf(Kinds.AREA_CHAT), tags = mapOf("t" to listOf(ChatProtocol.WIFI_TOPIC)), since = now - ChatProtocol.PUBLIC_TTL_SECONDS)))
        area = geohash5
        if (geohash5 != null) {
            pool.subscribe(SUB_AREA, listOf(Filter(kinds = listOf(Kinds.AREA_CHAT), tags = mapOf("g" to listOf(geohash5)), since = now - ChatProtocol.PUBLIC_TTL_SECONDS)))
        }
    }

    // ---- Reading ----

    fun conversations(): Flow<List<ConversationSummary>> =
        combine(dao.observeConversations(), dao.observeContacts()) { list, contacts ->
            val names = names(contacts)
            list.map { ConversationSummary(it, names) }
        }

    fun conversation(id: String): Flow<ConversationEntity?> = dao.observeConversation(id)

    fun contacts(): Flow<List<ContactEntity>> = dao.observeContacts()

    fun thread(conversationId: String): Flow<List<ThreadMessage>> = combine(
        dao.observeMessages(conversationId),
        dao.observeWrapStates(conversationId),
        dao.observeReceipts(conversationId),
        combine(dao.observeConversation(conversationId), dao.observeContacts(), db.muteDao().observeAll()) { c, k, m -> Triple(c, k, m) },
    ) { messages, wraps, receipts, (conversation, contacts, mutes) ->
        val myKey = me
        val members = conversation?.memberList().orEmpty()
        val names = names(contacts)
        val blocked = mutes.filter { it.type == MuteEntity.AUTHOR }.map { it.value }.toSet()
        val wrapsByMessage = wraps.filterNot { it.wrapId.startsWith(SMS_ALIAS_PREFIX) }.groupBy { it.messageId }
        val smsConfirmed = wraps.filter { it.wrapId.startsWith(SMS_ALIAS_PREFIX) &&
            runCatching { Delivery.valueOf(it.lanState) >= Delivery.Delivered }.getOrDefault(false) }.mapNotNull { it.messageId }.toSet()
        val receiptsByMessage = receipts.groupBy { it.messageId }
        messages.filter { it.own || it.author !in blocked }.map { m ->
            val card = m.card?.let(::decodeTag)?.let(ChatCard::parse)
            if (m.own && m.type != MessageType.SYSTEM.name) {
                val r = DeliveryRules.compute(myKey, members, wrapsByMessage[m.id].orEmpty(), receiptsByMessage[m.id].orEmpty())
                ThreadMessage(m, card, r.delivery, if (m.id in smsConfirmed) Carrier.SMS else r.carrier, null)
            } else {
                ThreadMessage(m, card, null, runCatching { Carrier.valueOf(m.carrier) }.getOrDefault(Carrier.ONLINE), names[m.author] ?: m.authorName)
            }
        }
    }

    /** Delivery state of one of our messages (tests, and the thread's ticks). */
    fun delivery(conversationId: String, messageId: String): Flow<Delivery?> =
        thread(conversationId).map { list -> list.firstOrNull { it.message.id == messageId }?.delivery }.distinctUntilChanged()

    /** The joined audio of a complete voice note, or null. */
    suspend fun voiceClip(messageId: String): VoiceClip? = VoiceNotes.assemble(dao.voiceParts(messageId).map { it.toPart() })

    /**
     * Compact encrypted representation for an explicit SMS action. Never queues or claims transmission;
     * SmsComposer must still check the final two-part budget. Keep the original timestamp and text.
     */
    suspend fun smsEventFor(messageId: String): Event? = withContext(Dispatchers.Default) {
        lock.withLock {
            val sender = profile()
            val message = dao.message(messageId) ?: return@withLock null
            val conversation = dao.conversation(message.conversationId) ?: return@withLock null
            val members = conversation.memberList().toSet()
            if (!message.own || message.author != sender.pubkeyHex || message.type != MessageType.TEXT.name ||
                message.card != null || message.voiceMime != null || message.replyTo != null ||
                conversation.type != ConversationType.DM.name || members.size != 2 || sender.pubkeyHex !in members ||
                message.text.isBlank() || message.text.encodeToByteArray().size > SmsLimits.DM_BYTES) return@withLock null
            val recipient = members.single { it != sender.pubkeyHex }
            if (isBlocked(recipient) || !Schnorr.isValidPubkey(recipient)) return@withLock null
            dao.wrapsFor(messageId).filter { it.wrapId.startsWith(SMS_ALIAS_PREFIX) }.forEach { alias ->
                val cached = runCatching { Event.fromJson(alias.json) }.getOrNull()
                if (cached != null && cached.pubkey == sender.pubkeyHex && cached.tagValues("p") == listOf(recipient) &&
                    cached.createdAt == message.createdAt && SmsDm.open(cached, sender) == message.text) return@withLock cached
            }
            val event = runCatching { SmsDm.build(sender, recipient, message.text, message.createdAt) }.getOrNull() ?: return@withLock null
            rememberSmsAlias(event, message.id, conversation.id, recipient)
            event
        }
    }

    // ---- Starting chats ----

    /** The room id a DM with [npubOrHex] has (or will have), or null for a bad key. */
    fun dmRoomId(npubOrHex: String): String? = parsePubkey(npubOrHex)?.let { ChatProtocol.roomId(setOf(me, it)) }

    /** The room id a group of [members] (plus us) has, or null when fewer than two others are valid. */
    fun groupRoomId(members: Collection<String>): String? =
        (members.mapNotNull(::parsePubkey) + me).toSet().takeIf { it.size >= 3 }?.let(ChatProtocol::roomId)

    /** Opens (or finds) the DM with [npubOrHex]; [petName] is ours for them and never leaves the phone. */
    suspend fun startDm(npubOrHex: String, petName: String? = null): String? {
        val pubkey = parsePubkey(npubOrHex) ?: return null
        val id = lock.withLock { ensureRoom(setOf(me, pubkey), nowSeconds()).id }
        val name = petName?.trim()?.takeIf { ChatProtocol.checkName(it) == ChatVerdict.OK }
        if (name != null || dao.contact(pubkey) == null) setPetName(pubkey, name ?: dao.contact(pubkey)?.petName)
        return id
    }

    /** A group of [members] (plus us) named [subject]; nobody is told until the first message. */
    suspend fun createGroup(subject: String, members: Collection<String>): String? {
        val all = (members.mapNotNull(::parsePubkey) + me).toSet()
        if (all.size < 3) return null
        val now = nowSeconds()
        return lock.withLock {
            val c = ensureRoom(all, now)
            subject.trim().take(80).takeIf { it.isNotEmpty() }?.let { dao.setSubject(c.id, it, now) }
            all.filter { it != me }.forEach { if (dao.contact(it) == null) dao.upsertContact(ContactEntity(it, null, null, nowMillis())) }
            c.id
        }
    }

    suspend fun setPetName(pubkey: String, name: String?) {
        val existing = dao.contact(pubkey)
        dao.upsertContact(ContactEntity(pubkey, name?.trim()?.ifEmpty { null }, existing?.theirName, nowMillis()))
    }

    /** The area channel for [geohash5] (created on first use). Messages older than a day are dropped. */
    suspend fun areaChannel(geohash5: String): String = lock.withLock {
        val id = AREA_PREFIX + geohash5
        ensurePublic(id, ConversationType.AREA, geohash5)
        dao.purgeBefore(id, nowSeconds() - ChatProtocol.PUBLIC_TTL_SECONDS)
        id
    }

    /** The Wi-Fi room (created on first use). Messages older than a day are dropped. */
    suspend fun wifiRoom(): String = lock.withLock {
        ensurePublic(WIFI_ID, ConversationType.WIFI, null)
        dao.purgeBefore(WIFI_ID, nowSeconds() - ChatProtocol.PUBLIC_TTL_SECONDS)
        WIFI_ID
    }

    suspend fun blockAuthor(pubkey: String) = db.muteDao().add(MuteEntity(MuteEntity.AUTHOR, pubkey, nowMillis()))

    /** A local line in the thread, e.g. "You made that message a task". Never sent. */
    suspend fun addSystemNote(conversationId: String, text: String) {
        val now = nowSeconds()
        dao.insertMessage(
            ChatMessageEntity(
                "sys-" + Hex.encode(ByteArray(12).also(random::nextBytes)), conversationId, me, null, now,
                MessageType.SYSTEM.name, text, null, null, null, null, true, Carrier.SAVED.name, nowMillis(), nowMillis(),
            ),
        )
    }

    // ---- Sending ----

    suspend fun sendText(conversationId: String, text: String, card: ChatCard? = null): SendResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val c = dao.conversation(conversationId) ?: return@withLock SendResult.NO_CONVERSATION
            val now = nowSeconds()
            val name = prefs.displayName
            when (ConversationType.valueOf(c.type)) {
                ConversationType.DM, ConversationType.GROUP -> {
                    verdict(ChatProtocol.checkText(text))?.let { return@withLock it }
                    val subject = if (c.type == ConversationType.GROUP.name) c.subject else null
                    val rumor = ChatProtocol.textRumor(me, c.memberList(), text.trim(), now, subject, name, card)
                    storeMessage(c, rumor.id, me, name, now, if (card != null) MessageType.CARD else MessageType.TEXT, text.trim(), card, null, own = true, Carrier.SAVED)
                    sendRumor(rumor, c.memberList(), rumor.id, c.id)
                }
                ConversationType.AREA -> {
                    verdict(ChatProtocol.checkPublicText(text))?.let { return@withLock it }
                    val g5 = c.geohash ?: return@withLock SendResult.NO_CONVERSATION
                    val event = ChatProtocol.areaMessage(areaKey(g5), g5, text.trim(), now, name)
                    storeMessage(c, event.id, event.pubkey, name, now, MessageType.TEXT, event.content, null, null, own = true, Carrier.SAVED)
                    dao.insertWrap(ChatWrapEntity(event.id, event.id, c.id, PUBLIC, event.toJson(), lanOnly = false, nowMillis(), Delivery.Saved.name))
                    outbox.enqueue(event, Priority.MESSAGE)
                }
                ConversationType.WIFI -> {
                    verdict(ChatProtocol.checkPublicText(text))?.let { return@withLock it }
                    val event = ChatProtocol.wifiMessage(profile(), text.trim(), now, name)
                    storeMessage(c, event.id, event.pubkey, name, now, MessageType.TEXT, event.content, null, null, own = true, Carrier.SAVED)
                    dao.insertWrap(ChatWrapEntity(event.id, event.id, c.id, PUBLIC, event.toJson(), lanOnly = true, nowMillis(), Delivery.Saved.name))
                    lanSend(event)
                }
            }
            SendResult.SENT
        }
    }

    suspend fun sendCard(conversationId: String, card: ChatCard): SendResult = sendText(conversationId, card.summary(), card)

    /** A voice note (≤ 15 s) to a DM or group: one gift wrap per part per member. */
    suspend fun sendVoice(conversationId: String, clip: VoiceClip): SendResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val c = dao.conversation(conversationId) ?: return@withLock SendResult.NO_CONVERSATION
            if (c.type != ConversationType.DM.name && c.type != ConversationType.GROUP.name) return@withLock SendResult.FAILED
            val now = nowSeconds()
            val voiceId = VoiceNotes.newVoiceId()
            val parts = runCatching { VoiceNotes.split(clip, me, c.memberList(), now, voiceId, prefs.displayName) }.getOrElse { return@withLock SendResult.TOO_LONG }
            parts.mapNotNull(VoiceNotes::parsePart).forEach { dao.insertVoicePart(it.toEntity(nowMillis())) }
            storeMessage(c, voiceId, me, prefs.displayName, now, MessageType.VOICE, "", null, clip, own = true, Carrier.SAVED)
            parts.forEach { sendRumor(it, c.memberList(), voiceId, c.id) }
            SendResult.SENT
        }
    }

    /** Marks a thread read and tells each author (read receipts, private chats only). */
    suspend fun markRead(conversationId: String) = withContext(Dispatchers.Default) {
        lock.withLock {
            dao.clearUnread(conversationId)
            val unread = dao.unreadIncoming(conversationId)
            if (unread.isEmpty()) return@withLock
            dao.markRead(unread.map { it.id }, nowMillis())
            val c = dao.conversation(conversationId) ?: return@withLock
            if (c.type != ConversationType.DM.name && c.type != ConversationType.GROUP.name) return@withLock
            unread.groupBy { it.author }.forEach { (author, list) -> sendReceipt(author, list.map { it.id }, ReceiptStatus.READ) }
        }
    }

    /** Re-sends what hasn't reached the Wi-Fi room yet (when a peer appears). */
    suspend fun flushLan() {
        val room = lan ?: return
        if (!room.running.value) return
        val since = nowMillis() - LAN_RESEND_MILLIS
        for (w in dao.lanPending(since)) {
            if (w.wrapId.startsWith(SMS_ALIAS_PREFIX)) continue
            val event = runCatching { Event.fromJson(w.json) }.getOrNull() ?: continue
            if (room.publish(event) > 0) raiseLan(w.wrapId, Delivery.LeftPhone)
        }
    }

    // ---- Receiving ----

    /** Every event from every carrier comes through here; anything that fails a check is dropped. */
    suspend fun ingest(event: Event, carrier: Carrier) {
        when {
            event.kind == Kinds.SMS_DM -> ingestSms(event, carrier)
            event.kind == Kinds.GIFT_WRAP -> {
                val myKey = profile()
                if (event.tagValue("p") != myKey.pubkeyHex) return
                synchronized(seenWraps) { if (seenWraps.put(event.id, true) != null) return }
                val rumor = withContext(Dispatchers.Default) { runCatching { GiftWrap.unwrap(event, myKey) }.getOrNull() }
                if (rumor == null) {
                    synchronized(seenWraps) { seenWraps.remove(event.id) }
                    return
                }
                try {
                    lock.withLock { onRumor(rumor, carrier) }
                } catch (e: Exception) {
                    // Let a later copy of the same wrap try again.
                    synchronized(seenWraps) { seenWraps.remove(event.id) }
                    throw e
                }
            }
            ChatProtocol.isWifiMessage(event) -> onPublic(event, WIFI_ID, ConversationType.WIFI, null, carrier)
            ChatProtocol.isAreaMessage(event) -> {
                val g5 = event.tagValue("g") ?: return
                if (g5 != area) return
                onPublic(event, AREA_PREFIX + g5, ConversationType.AREA, g5, carrier)
            }
        }
    }

    /**
     * Accepts only an authenticated, bounded kind-3480 DM addressed to us (or authored by us).
     * Plaintext stays in the private chat database; any receipts are NIP-59 encrypted gift wraps.
     * Returns true only for a newly stored bubble, false for invalid, blocked or duplicate input.
     */
    suspend fun ingestSms(event: Event, carrier: Carrier): Boolean = withContext(Dispatchers.Default) {
        val keys = profile()
        if (event.kind != Kinds.SMS_DM || event.tags.count { it.firstOrNull() == "p" } != 1 ||
            event.tags.singleOrNull { it.firstOrNull() == "p" }?.size != 2 ||
            event.isExpired(nowSeconds()) || !event.validate()) return@withContext false
        val recipient = event.tagValue("p") ?: return@withContext false
        if (!Schnorr.isValidPubkey(recipient) || (recipient != keys.pubkeyHex && event.pubkey != keys.pubkeyHex)) return@withContext false
        val other = if (event.pubkey == keys.pubkeyHex) recipient else event.pubkey
        if (other == keys.pubkeyHex) return@withContext false
        val text = SmsDm.open(event, keys) ?: return@withContext false
        if (text.isBlank() || text.encodeToByteArray().size > SmsLimits.DM_BYTES) return@withContext false
        lock.withLock {
            if (isBlocked(other) || dao.messageExists(event.id) || dao.wrap(SMS_ALIAS_PREFIX + event.id) != null) return@withLock false
            val conversation = ensureRoom(setOf(keys.pubkeyHex, other), event.createdAt)
            val own = event.pubkey == keys.pubkeyHex
            val existing = matchingPlainText(conversation.id, event.pubkey, event.createdAt, text)
            val stored = existing == null && storeMessage(conversation, event.id, event.pubkey, null, event.createdAt,
                MessageType.TEXT, text, null, null, own, carrier)
            if (existing != null) rememberSmsAlias(event, existing, conversation.id, recipient)
            if (!own) sendReceipt(event.pubkey, listOf(event.id), ReceiptStatus.DELIVERED)
            stored
        }
    }

    private suspend fun onPublic(event: Event, id: String, type: ConversationType, geohash: String?, carrier: Carrier) {
        if (!withContext(Dispatchers.Default) { event.validate() }) return
        if (event.isExpired(nowSeconds())) return
        if (ChatProtocol.checkPublicText(event.content) != ChatVerdict.OK) return
        lock.withLock {
            if (isBlocked(event.pubkey) || dao.messageExists(event.id)) return
            val c = ensurePublic(id, type, geohash)
            val own = event.pubkey == me
            storeMessage(c, event.id, event.pubkey, ChatProtocol.nameOf(event.tags), event.createdAt, MessageType.TEXT, event.content, null, null, own, carrier)
        }
    }

    private suspend fun onRumor(rumor: Rumor, carrier: Carrier) {
        val myKey = me
        when (val parsed = ChatProtocol.parse(rumor)) {
            is ChatRumor.Text -> {
                if (myKey !in parsed.members || (rumor.pubkey != myKey && isBlocked(rumor.pubkey))) return
                val c = ensureRoom(parsed.members, rumor.createdAt)
                parsed.subject?.let { dao.setSubject(c.id, it, rumor.createdAt) }
                val own = rumor.pubkey == myKey
                val type = if (parsed.card != null) MessageType.CARD else MessageType.TEXT
                if (ChatProtocol.checkText(parsed.text) == ChatVerdict.TOO_LONG) return
                val duplicate = if (parsed.card == null && parsed.members.size == 2 && parsed.text.encodeToByteArray().size <= SmsLimits.DM_BYTES)
                    matchingPlainText(c.id, rumor.pubkey, rumor.createdAt, parsed.text) else null
                val stored = duplicate == null && storeMessage(c, rumor.id, rumor.pubkey, parsed.name, rumor.createdAt, type, parsed.text, parsed.card, null, own, carrier)
                if ((stored || duplicate != null) && !own) {
                    noteName(rumor.pubkey, parsed.name)
                    sendReceipt(rumor.pubkey, listOf(rumor.id), ReceiptStatus.DELIVERED)
                }
            }
            is ChatRumor.Voice -> {
                if (myKey !in parsed.members || (rumor.pubkey != myKey && isBlocked(rumor.pubkey))) return
                val part = parsed.part
                dao.insertVoicePart(part.toEntity(nowMillis()))
                if (dao.messageExists(part.voiceId)) return
                val parts = dao.voiceParts(part.voiceId)
                if (parts.size < part.count) return
                val clip = VoiceNotes.assemble(parts.map { it.toPart() }) ?: return
                val c = ensureRoom(parsed.members, rumor.createdAt)
                val own = rumor.pubkey == myKey
                val stored = storeMessage(c, part.voiceId, rumor.pubkey, parsed.name, rumor.createdAt, MessageType.VOICE, "", null, clip, own, carrier)
                if (stored && !own) {
                    noteName(rumor.pubkey, parsed.name)
                    sendReceipt(rumor.pubkey, listOf(part.voiceId), ReceiptStatus.DELIVERED)
                }
            }
            is ChatRumor.Receipt -> onReceipt(rumor.pubkey, parsed)
            null -> Unit
        }
    }

    private suspend fun onReceipt(member: String, receipt: ChatRumor.Receipt) = db.withTransaction {
        val status = if (receipt.status == ReceiptStatus.READ) Delivery.Read else Delivery.Delivered
        for (id in receipt.messageIds) {
            val smsAlias = dao.wrap(SMS_ALIAS_PREFIX + id)
            val m = dao.message(id) ?: smsAlias?.messageId?.let { dao.message(it) } ?: continue
            if (!m.own) continue
            val c = dao.conversation(m.conversationId) ?: continue
            if (member !in c.memberList() || member == me) continue
            val before = dao.receipt(m.id, member)?.status?.let { runCatching { Delivery.valueOf(it) }.getOrNull() }
            if (before == null || status > before) dao.upsertReceipt(ChatReceiptEntity(m.id, c.id, member, status.name, nowMillis()))
            if (smsAlias != null) raiseLan(smsAlias.wrapId, status)
            // Delivered: stop retrying that member's copies on every carrier.
            for (w in dao.wrapsFor(m.id).filter { it.recipient == member && !it.wrapId.startsWith(SMS_ALIAS_PREFIX) }) {
                val row = db.outboxDao().get(w.wrapId)
                if (row != null && (row.state == Delivery.Saved.name || row.state == Delivery.LeftPhone.name)) {
                    db.outboxDao().setState(w.wrapId, Delivery.Delivered.name, row.carrier)
                }
                raiseLan(w.wrapId, Delivery.Delivered)
            }
        }
    }

    // ---- Internals ----

    /** SMS aliases are local metadata, not pending transport copies and never advance delivery ticks. */
    private suspend fun rememberSmsAlias(event: Event, messageId: String, conversationId: String, recipient: String) {
        dao.insertWrap(ChatWrapEntity(SMS_ALIAS_PREFIX + event.id, messageId, conversationId, recipient,
            event.toJson(), lanOnly = true, nowMillis(), Delivery.Saved.name))
    }

    /** Match the same text arriving as SMS and NIP-17 without loading an unbounded thread into memory. */
    private suspend fun matchingPlainText(conversationId: String, author: String, at: Long, text: String): String? = withContext(Dispatchers.IO) {
        db.query("SELECT id FROM chat_messages WHERE conversationId = ? AND author = ? AND createdAt = ? " +
            "AND type = 'TEXT' AND card IS NULL AND text = ? LIMIT 1", arrayOf<Any>(conversationId, author, at, text)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun verdict(v: ChatVerdict): SendResult? = when (v) {
        ChatVerdict.OK -> null
        ChatVerdict.EMPTY -> SendResult.EMPTY
        ChatVerdict.TOO_LONG -> SendResult.TOO_LONG
        ChatVerdict.PHONE_NUMBER -> SendResult.PHONE_NUMBER
    }

    /** Wraps [rumor] for every member and ourselves, then hands each wrap to the outbox and the Wi-Fi room. */
    private suspend fun sendRumor(rumor: Rumor, members: List<String>, messageId: String?, conversationId: String?) {
        val sender = profile()
        val now = nowSeconds()
        for (recipient in (members + sender.pubkeyHex).distinct()) {
            val seal = GiftWrap.seal(rumor, sender, recipient, GiftWrap.randomPastTime(now))
            val wrap = GiftWrap.wrap(seal, recipient, GiftWrap.randomPastTime(now))
            check(wrap.jsonSize() < Event.MAX_JSON_BYTES) { "gift wrap over 16 KB" }
            synchronized(seenWraps) { seenWraps[wrap.id] = true }
            dao.insertWrap(ChatWrapEntity(wrap.id, messageId, conversationId, recipient, wrap.toJson(), lanOnly = false, nowMillis(), Delivery.Saved.name))
            outbox.enqueue(wrap, Priority.MESSAGE)
            lanSend(wrap)
        }
    }

    private suspend fun sendReceipt(author: String, ids: List<String>, status: ReceiptStatus) {
        val sender = profile()
        val now = nowSeconds()
        val rumor = ChatProtocol.receiptRumor(sender.pubkeyHex, author, ids, status, now)
        val wrap = GiftWrap.wrap(GiftWrap.seal(rumor, sender, author, GiftWrap.randomPastTime(now)), author, GiftWrap.randomPastTime(now))
        dao.insertWrap(ChatWrapEntity(wrap.id, null, null, author, wrap.toJson(), lanOnly = false, nowMillis(), Delivery.Saved.name))
        outbox.enqueue(wrap, Priority.MESSAGE)
        lanSend(wrap)
    }

    private suspend fun lanSend(event: Event) {
        val room = lan ?: return
        if (room.publish(event) > 0) raiseLan(event.id, Delivery.LeftPhone)
    }

    private suspend fun raiseLan(wrapId: String, state: Delivery) {
        dao.raiseLanState(wrapId, state.name, Delivery.entries.filter { it < state }.map { it.name })
    }

    private suspend fun storeMessage(
        c: ConversationEntity,
        id: String,
        author: String,
        authorName: String?,
        createdAt: Long,
        type: MessageType,
        text: String,
        card: ChatCard?,
        voice: VoiceClip?,
        own: Boolean,
        carrier: Carrier,
    ): Boolean = db.withTransaction {
        val inserted = dao.insertMessage(
            ChatMessageEntity(
                id = id,
                conversationId = c.id,
                author = author,
                authorName = authorName,
                createdAt = createdAt,
                type = type.name,
                text = text,
                card = card?.toTag()?.let(::encodeTag),
                voiceMime = voice?.mime,
                voiceDurationMs = voice?.durationMs,
                replyTo = null,
                own = own,
                carrier = carrier.name,
                receivedAt = nowMillis(),
                readAt = if (own) nowMillis() else null,
            ),
        ) != -1L
        if (inserted) {
            val preview = when (type) {
                MessageType.VOICE -> PREVIEW_VOICE
                MessageType.CARD -> card?.summary() ?: text
                else -> text
            }.take(160)
            dao.bumpConversation(c.id, createdAt, preview, 0)
            if (!own) dao.addUnread(c.id, 1)
        }
        inserted
    }

    private suspend fun ensureRoom(members: Set<String>, at: Long): ConversationEntity {
        val sorted = members.toSortedSet()
        val id = ChatProtocol.roomId(sorted)
        dao.conversation(id)?.let { return it }
        val type = if (sorted.size <= 2) ConversationType.DM else ConversationType.GROUP
        val entity = ConversationEntity(id, type.name, sorted.joinToString(","), null, 0, null, at, "", 0, nowMillis())
        dao.insertConversation(entity)
        sorted.filter { it != me }.forEach { if (dao.contact(it) == null) dao.upsertContact(ContactEntity(it, null, null, nowMillis())) }
        return dao.conversation(id) ?: entity
    }

    private suspend fun ensurePublic(id: String, type: ConversationType, geohash: String?): ConversationEntity {
        dao.conversation(id)?.let { return it }
        val entity = ConversationEntity(id, type.name, "", null, 0, geohash, 0, "", 0, nowMillis())
        dao.insertConversation(entity)
        return dao.conversation(id) ?: entity
    }

    private suspend fun noteName(pubkey: String, name: String?) {
        if (name == null) return
        val existing = dao.contact(pubkey)
        if (existing?.theirName != name) dao.upsertContact(ContactEntity(pubkey, existing?.petName, name, nowMillis()))
    }

    private suspend fun isBlocked(pubkey: String): Boolean = dao.isBlocked(pubkey)

    private fun names(contacts: List<ContactEntity>): Map<String, String> =
        contacts.mapNotNull { c -> (c.petName ?: c.theirName)?.let { c.pubkey to it } }.toMap()

    companion object {
        const val SUB_DM = "harkara-dm"
        const val SUB_AREA = "harkara-area-chat"
        const val SUB_WIFI = "harkara-wifi"
        const val WIFI_ID = "wifi"
        const val AREA_PREFIX = "area:"
        const val PUBLIC = "*"
        const val PREVIEW_VOICE = "\uD83C\uDFA4"
        private const val SMS_ALIAS_PREFIX = "sms-alias:"

        /** With no sync yet, fetch a week of wraps. */
        private const val FIRST_FETCH_SECONDS = 7L * 24 * 3600

        /** The Wi-Fi room re-sends what peers haven't confirmed for 3 days. */
        private const val LAN_RESEND_MILLIS = 3L * 24 * 3600 * 1000

        fun parsePubkey(value: String): String? {
            val v = value.trim().removePrefix("nostr:")
            val hex = when {
                v.startsWith("npub1") -> runCatching { Bech32.decodeNpub(v) }.getOrNull()
                v.length == 64 -> v.lowercase()
                else -> null
            } ?: return null
            return hex.takeIf { Schnorr.isValidPubkey(it) }
        }

        fun encodeTag(tag: List<String>): String = JsonArray(tag.map(::JsonPrimitive)).toString()

        fun decodeTag(json: String): List<String>? = runCatching { Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content } }.getOrNull()

        private fun VoicePart.toEntity(now: Long) = VoicePartEntity(voiceId, index, count, mime, durationMs, sha256, Base64.getEncoder().encodeToString(data), now)

        private fun VoicePartEntity.toPart() = VoicePart(voiceId, idx, count, mime, durationMs, sha256, Base64.getDecoder().decode(data))
    }
}
