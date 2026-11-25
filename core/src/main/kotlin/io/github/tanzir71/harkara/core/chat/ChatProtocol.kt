package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.nostr.signEvent

/** Receipt states carried by [Kinds.RECEIPT] rumors. */
enum class ReceiptStatus(val wire: String) {
    DELIVERED("delivered"),
    READ("read"),
    ;

    companion object {
        fun of(wire: String?): ReceiptStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** A chat rumor after parsing: text (maybe with a card), a voice-note part, or a receipt. */
sealed interface ChatRumor {
    val rumor: Rumor

    data class Text(
        override val rumor: Rumor,
        val members: Set<String>,
        val text: String,
        val subject: String?,
        val name: String?,
        val card: ChatCard?,
        val replyTo: String?,
    ) : ChatRumor

    data class Voice(override val rumor: Rumor, val members: Set<String>, val part: VoicePart, val name: String?) : ChatRumor

    data class Receipt(override val rumor: Rumor, val messageIds: List<String>, val status: ReceiptStatus) : ChatRumor
}

/** Why a display name or a public message was refused. */
enum class ChatVerdict { OK, EMPTY, TOO_LONG, PHONE_NUMBER }

/**
 * Harkara chats on NIP-17: kind 14 rumors (text and cards), kind 15 rumors (voice-note parts) and
 * [Kinds.RECEIPT] rumors, sealed and gift-wrapped to every member and to the sender. Public rooms are
 * signed kind 20000 events: the area channel (`g` = geohash-5, weekly area key) and the Wi-Fi room
 * (`t` = `wifi`, profile key). See docs/PROTOCOL.md §13.
 */
object ChatProtocol {
    /** Display name tag (bitchat's nickname convention); inside the encryption for private chats. */
    const val NAME_TAG = "n"
    const val WIFI_TOPIC = "wifi"
    const val MAX_NAME_CHARS = 32

    /** UTF-8 bytes of message text; keeps every gift wrap well under 16 KB. */
    const val MAX_TEXT_BYTES = 6_000

    /** Public room messages are ephemeral and kept 24 h on the phone. */
    const val PUBLIC_TTL_SECONDS = 24L * 3600

    /** Gift wraps are back-dated up to 2 days; subscriptions reach this far behind their `since`. */
    const val WRAP_LOOKBACK_SECONDS = GiftWrap.MAX_TIME_TWEAK_SECONDS + 3600

    /**
     * NIP-17 room id: the set of members (sender + `p` tags) defines the room, so a changed member list is a
     * new room. Hex of the SHA-256 of the sorted, comma-joined pubkeys, first 16 bytes.
     */
    fun roomId(members: Collection<String>): String =
        Hex.encode(Hashes.sha256(members.toSortedSet().joinToString(",").encodeToByteArray()).copyOf(16))

    fun membersOf(rumor: Rumor): Set<String> = (rumor.tagValues("p") + rumor.pubkey).toSortedSet()

    /** Kind 14 text (and optional card) for [members] (the sender is added automatically). */
    fun textRumor(
        sender: String,
        members: Collection<String>,
        text: String,
        createdAt: Long,
        subject: String? = null,
        name: String? = null,
        card: ChatCard? = null,
        replyTo: String? = null,
    ): Rumor {
        val extra = buildList {
            nameTag(name)?.let(::add)
            card?.let { add(it.toTag()) }
        }
        val recipients = members.filter { it != sender }.distinct().ifEmpty { listOf(sender) }
        return GiftWrap.chatMessage(sender, recipients, text, replyTo, subject?.trim()?.take(80)?.ifEmpty { null }, extra, createdAt)
    }

    /** Receipt for [messageIds] by [sender], addressed to the messages' [author]. */
    fun receiptRumor(sender: String, author: String, messageIds: List<String>, status: ReceiptStatus, createdAt: Long): Rumor {
        require(messageIds.isNotEmpty()) { "a receipt names at least one message" }
        val tags = buildList {
            add(listOf("p", author))
            messageIds.distinct().forEach { add(listOf("e", it)) }
            add(listOf("status", status.wire))
        }
        return GiftWrap.createRumor(sender, Kinds.RECEIPT, "", tags, createdAt)
    }

    /** Area channel message: kind 20000, `g` = geohash-5, signed by the weekly area key, expires after 24 h. */
    fun areaMessage(areaKey: KeyPair, geohash5: String, text: String, createdAt: Long, name: String? = null): Event {
        require(geohash5.length == 5) { "area channels are geohash-5 cells" }
        return areaKey.signEvent(Kinds.AREA_CHAT, publicTags(listOf(listOf("g", geohash5)), name, createdAt), text, createdAt)
    }

    /** "Everyone on this Wi-Fi" message: kind 20000, `t` = `wifi`, profile key, expires after 24 h. Never sent to relays. */
    fun wifiMessage(profile: KeyPair, text: String, createdAt: Long, name: String? = null): Event =
        profile.signEvent(Kinds.AREA_CHAT, publicTags(listOf(listOf("t", WIFI_TOPIC)), name, createdAt), text, createdAt)

    fun isWifiMessage(event: Event): Boolean = event.kind == Kinds.AREA_CHAT && event.tagValues("t").contains(WIFI_TOPIC)

    fun isAreaMessage(event: Event): Boolean = event.kind == Kinds.AREA_CHAT && !isWifiMessage(event) && event.tagValue("g") != null

    /** What a Wi-Fi room server accepts: gift wraps (DMs, groups, receipts) and the room's own messages. */
    fun carriedByLanRoom(event: Event): Boolean = event.kind == Kinds.GIFT_WRAP || isWifiMessage(event)

    /** The sender's display name from an `n` tag, or null when absent or unsafe to show. */
    fun nameOf(tags: List<List<String>>): String? =
        tags.firstOrNull { it.size > 1 && it[0] == NAME_TAG }?.get(1)?.trim()?.take(MAX_NAME_CHARS)
            ?.takeIf { it.isNotEmpty() && !containsPhoneNumber(it) }

    /** Parses a rumor opened from a gift wrap. Unknown kinds and malformed rumors give null. */
    fun parse(rumor: Rumor): ChatRumor? = when (rumor.kind) {
        Kinds.CHAT_MESSAGE -> ChatRumor.Text(
            rumor = rumor,
            members = membersOf(rumor),
            text = rumor.content,
            subject = rumor.tagValue("subject")?.trim()?.take(80)?.ifEmpty { null },
            name = nameOf(rumor.tags),
            card = ChatCard.from(rumor.tags),
            replyTo = rumor.tagValue("e"),
        )
        Kinds.FILE_MESSAGE -> VoiceNotes.parsePart(rumor)?.let { ChatRumor.Voice(rumor, membersOf(rumor), it, nameOf(rumor.tags)) }
        Kinds.RECEIPT -> {
            val ids = rumor.tagValues("e").filter { it.length in 1..64 }.take(500)
            val status = ReceiptStatus.of(rumor.tagValue("status"))
            if (ids.isEmpty() || status == null) null else ChatRumor.Receipt(rumor, ids, status)
        }
        else -> null
    }

    /** Display names: 1–32 characters and never a phone number (handover §4). */
    fun checkName(name: String): ChatVerdict {
        val n = name.trim()
        return when {
            n.isEmpty() -> ChatVerdict.EMPTY
            n.length > MAX_NAME_CHARS -> ChatVerdict.TOO_LONG
            containsPhoneNumber(n) -> ChatVerdict.PHONE_NUMBER
            else -> ChatVerdict.OK
        }
    }

    /** Private text: not empty and within [MAX_TEXT_BYTES]. */
    fun checkText(text: String): ChatVerdict = when {
        text.isBlank() -> ChatVerdict.EMPTY
        text.encodeToByteArray().size > MAX_TEXT_BYTES -> ChatVerdict.TOO_LONG
        else -> ChatVerdict.OK
    }

    /** Public rooms (area, Wi-Fi) follow the feed's Chatter rule: no phone numbers, ≤ 400 characters. */
    fun checkPublicText(text: String): ChatVerdict = when {
        text.isBlank() -> ChatVerdict.EMPTY
        text.trim().length > 400 -> ChatVerdict.TOO_LONG
        containsPhoneNumber(text) -> ChatVerdict.PHONE_NUMBER
        else -> ChatVerdict.OK
    }

    private val phoneCandidate = Regex("""\+?\d[\d\s().-]{4,}\d""")

    /** Seven or more digits in a phone-like run (Bangla digits count too). */
    fun containsPhoneNumber(text: String): Boolean {
        val ascii = text.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")
        return phoneCandidate.findAll(ascii).any { m -> m.value.count(Char::isDigit) >= 7 }
    }

    private fun nameTag(name: String?): List<String>? =
        name?.trim()?.takeIf { checkName(it) == ChatVerdict.OK }?.let { listOf(NAME_TAG, it) }

    private fun publicTags(base: List<List<String>>, name: String?, createdAt: Long): List<List<String>> = buildList {
        addAll(base)
        nameTag(name)?.let(::add)
        add(listOf("expiration", (createdAt + PUBLIC_TTL_SECONDS).toString()))
    }
}
