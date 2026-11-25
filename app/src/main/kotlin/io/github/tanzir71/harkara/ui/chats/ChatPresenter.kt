package io.github.tanzir71.harkara.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.chat.ChatRepository
import io.github.tanzir71.harkara.chat.ConversationSummary
import io.github.tanzir71.harkara.chat.ConversationType
import io.github.tanzir71.harkara.chat.MessageType
import io.github.tanzir71.harkara.chat.ThreadMessage
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.chat.ChatCard
import io.github.tanzir71.harkara.core.chat.LocationPrecision
import io.github.tanzir71.harkara.core.chat.LocationShare
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.data.ConversationEntity
import io.github.tanzir71.harkara.identity.shortNpub
import io.github.tanzir71.harkara.ui.components.IouUi
import io.github.tanzir71.harkara.ui.components.PinUi
import io.github.tanzir71.harkara.ui.components.TaskUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Turns stored chats into rows, headers and thread items: names, times, cards and the route hint. */
class ChatPresenter(
    private val label: (Int, Array<out Any>) -> String,
    private val plural: (Int, Int) -> String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun kindOf(c: ConversationEntity): ChatKind = when (runCatching { ConversationType.valueOf(c.type) }.getOrDefault(ConversationType.DM)) {
        ConversationType.DM -> ChatKind.DM
        ConversationType.GROUP -> ChatKind.GROUP
        ConversationType.AREA -> ChatKind.AREA
        ConversationType.WIFI -> ChatKind.WIFI
    }

    /** A person's name: ours for them, theirs, or a short key. */
    fun personName(pubkey: String, names: Map<String, String>): String =
        names[pubkey] ?: runCatching { shortNpub(Bech32.encodeNpub(pubkey)) }.getOrDefault(pubkey.take(8))

    fun title(c: ConversationEntity, names: Map<String, String>, me: String?, areaName: String?): String = when (kindOf(c)) {
        ChatKind.DM -> personName(c.memberList().firstOrNull { it != me } ?: me.orEmpty(), names)
        ChatKind.GROUP -> c.subject ?: c.memberList().filter { it != me }.joinToString(", ") { personName(it, names) }
        ChatKind.AREA -> label(R.string.chats_area_title, arrayOf(areaName ?: c.geohash.orEmpty()))
        ChatKind.WIFI -> label(R.string.chats_wifi_title, emptyArray())
    }

    fun row(summary: ConversationSummary, me: String?, areaName: String?): ConversationRowUi {
        val c = summary.conversation
        val title = title(c, summary.names, me, areaName)
        return ConversationRowUi(
            id = c.id,
            kind = kindOf(c),
            title = title,
            initials = initials(title),
            preview = preview(c.lastText),
            time = if (c.lastAt > 0) shortTime(c.lastAt) else "",
            unread = c.unread,
        )
    }

    fun rooms(wifiPeers: Int, areaName: String?, wifiUnread: Int, areaUnread: Int): List<RoomRowUi> = listOf(
        RoomRowUi(
            ChatKind.WIFI,
            label(R.string.chats_wifi_title, emptyArray()),
            if (wifiPeers > 0) plural(R.plurals.chats_wifi_peers, wifiPeers) else label(R.string.chats_wifi_alone, emptyArray()),
            wifiUnread,
        ),
        RoomRowUi(
            ChatKind.AREA,
            label(R.string.chats_area_title, arrayOf(areaName ?: label(R.string.nearby_near_you, emptyArray()))),
            label(R.string.chats_area_sub, emptyArray()),
            areaUnread,
        ),
    )

    fun header(c: ConversationEntity, names: Map<String, String>, me: String?, areaName: String?, wifiPeers: Int): ThreadHeaderUi {
        val kind = kindOf(c)
        val title = when (kind) {
            ChatKind.AREA -> areaName ?: c.geohash.orEmpty()
            else -> title(c, names, me, areaName)
        }
        val subtitle = when (kind) {
            ChatKind.DM -> {
                val other = c.memberList().firstOrNull { it != me } ?: me.orEmpty()
                label(R.string.thread_dm_sub, arrayOf(runCatching { shortNpub(Bech32.encodeNpub(other)) }.getOrDefault("")))
            }
            ChatKind.GROUP -> plural(R.plurals.chats_members, c.memberList().size)
            ChatKind.AREA -> label(R.string.thread_area_sub, emptyArray())
            ChatKind.WIFI -> if (wifiPeers > 0) plural(R.plurals.thread_wifi_peers, wifiPeers) else label(R.string.thread_wifi_alone, emptyArray())
        }
        return ThreadHeaderUi(kind, title, initials(title), subtitle)
    }

    fun items(messages: List<ThreadMessage>, kind: ChatKind): List<ThreadItemUi> = messages.map { t ->
        val m = t.message
        if (m.type == MessageType.SYSTEM.name) return@map ThreadItemUi.System(m.id, m.text)
        val sender = if (m.own || kind == ChatKind.DM) null else t.senderName ?: anonymous(m.author, kind)
        val body: ChatBody = when {
            m.type == MessageType.VOICE.name -> ChatBody.Voice(duration((m.voiceDurationMs ?: 0) / 1000))
            t.card != null -> cardBody(t.card, m.own, t.carrier)
            else -> ChatBody.Text(m.text)
        }
        ThreadItemUi.Message(
            id = m.id,
            body = body,
            time = clock(m.createdAt),
            outgoing = m.own,
            carrier = t.carrier,
            delivery = t.delivery,
            sender = sender,
            copyText = m.text,
            author = m.author,
        )
    }

    fun cardBody(card: ChatCard, own: Boolean, carrier: Carrier): ChatBody = when (card) {
        is ChatCard.Location -> ChatBody.Location(
            card.title,
            listOfNotNull(card.place.ifEmpty { null }, precisionLabel(card.precision)).joinToString(" · "),
            LocationShare.displayCode(card.plusCode),
        )
        is ChatCard.Task -> ChatBody.Task(TaskUi(card.type, card.price, card.title, card.stepsDone, card.status, card.runner.ifEmpty { null }))
        is ChatCard.Iou -> ChatBody.Iou(IouUi(card.amount, incoming = if (own) card.lent else !card.lent, card.what, card.status, card.date, card.agreed))
        is ChatCard.CheckIn -> ChatBody.CheckIn(card.title, card.detail, carrierLabel(carrier))
        is ChatCard.Pin -> ChatBody.Pin(
            PinUi(card.title, card.detail, card.visibility, card.yes, card.no, Category.entries.firstOrNull { it.tag == card.category } ?: Category.STOCK),
        )
    }

    /** "Message · goes by mesh" for the rung this chat would use now. */
    fun hint(carrier: Carrier): String = when (carrier) {
        Carrier.ONLINE -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_internet, emptyArray())))
        Carrier.BRIDGE -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_bridge, emptyArray())))
        Carrier.NODE -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_wifi, emptyArray())))
        Carrier.MESH -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_mesh, emptyArray())))
        Carrier.SMS -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_sms, emptyArray())))
        Carrier.QR -> label(R.string.thread_hint, arrayOf(label(R.string.thread_by_hand, emptyArray())))
        Carrier.SAVED -> label(R.string.thread_hint_saved, emptyArray())
    }

    fun carrierLabel(carrier: Carrier): String = label(
        when (carrier) {
            Carrier.ONLINE -> R.string.via_relays
            Carrier.BRIDGE -> R.string.via_bridge
            Carrier.NODE -> R.string.via_node
            Carrier.MESH -> R.string.via_mesh
            Carrier.SMS -> R.string.via_sms
            Carrier.QR -> R.string.via_qr
            Carrier.SAVED -> R.string.via_saved
        },
        emptyArray(),
    )

    private fun precisionLabel(p: LocationPrecision): String = label(
        when (p) {
            LocationPrecision.EXACT -> R.string.location_detail_exact
            LocationPrecision.APPROX -> R.string.location_detail_100m
            LocationPrecision.AREA -> R.string.location_detail_area
        },
        emptyArray(),
    )

    private fun anonymous(author: String, kind: ChatKind): String =
        if (kind == ChatKind.AREA) "anon·" + author.take(4) else runCatching { shortNpub(Bech32.encodeNpub(author)) }.getOrDefault(author.take(8))

    private fun preview(text: String): String =
        if (text == ChatRepository.PREVIEW_VOICE) label(R.string.chats_voice_preview, emptyArray()) else text.replace('\n', ' ')

    /** "21:14". */
    fun clock(seconds: Long): String {
        val t = Instant.ofEpochSecond(seconds).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }

    /** Today's messages by clock, older ones by age ("3 d"). */
    fun shortTime(seconds: Long): String {
        val day = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochSecond(nowSeconds()).atZone(zone).toLocalDate()
        return if (day == today) clock(seconds) else label(R.string.age_days, arrayOf(maxOf(1, (today.toEpochDay() - day.toEpochDay()).toInt())))
    }

    companion object {
        /** "Road 7 building" → "R7", "Sumon" → "Su", "npub1a3f…" → "a3". */
        fun initials(name: String): String {
            if (name.startsWith("npub1")) return name.removePrefix("npub1").take(2)
            val words = name.split(' ', '·', ',').map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                words.size == 1 -> words[0].take(2)
                else -> "?"
            }
        }

        fun duration(seconds: Long): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

@Composable
fun rememberChatPresenter(): ChatPresenter {
    val context = LocalContext.current
    return ChatPresenter({ id, args -> context.getString(id, *args) }, { id, n -> context.resources.getQuantityString(id, n, n) })
}
