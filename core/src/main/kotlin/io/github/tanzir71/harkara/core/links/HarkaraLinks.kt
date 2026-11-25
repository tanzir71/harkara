package io.github.tanzir71.harkara.core.links

import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouConfirmLink
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.core.wire.SmsCodec
import java.util.Base64

/** Something a `harkara:` link, an `…/harkara/o/#…` web link, a QR or a pasted SMS carries. */
sealed interface HarkaraLink {
    /** A whole signed event (`e:`), already verified. */
    data class EventLink(val event: Event) : HarkaraLink

    /** One code of a multi-QR set (`q:`). */
    data class QrPart(val text: String) : HarkaraLink

    /** An SMS payload (`HK1:`); decoding needs this phone's known keys. */
    data class Sms(val text: String) : HarkaraLink

    /** A whole IOU bundle from the face-to-face QR (`iou:`). */
    data class IouBundle(val text: String) : HarkaraLink

    /** The IOU confirm link for someone without a key (`c:`), signature verified. */
    data class IouConfirm(val link: IouConfirmLink) : HarkaraLink

    /** A task handoff token QR (`handoff:`). */
    data class Handoff(val text: String) : HarkaraLink

    /** Follow a task (`t:`): requester key + `d`. */
    data class Task(val requester: String, val d: String) : HarkaraLink {
        val address: String get() = "30402:$requester:$d"
    }

    /** Read an area's feed (`g:`). */
    data class Area(val geohash: String) : HarkaraLink

    /** A volunteer SMS gateway card (`gw:`). */
    data class Gateway(val card: GatewayCard) : HarkaraLink
}

/**
 * One grammar for every hand-to-hand link. `harkara:<rest>` and `https://tanzir71.github.io/harkara/o/#<rest>`
 * (or `/en/o/`) carry the same `<rest>`; the web page reads the fragment, which browsers never send to a
 * server.
 */
object HarkaraLinks {
    const val SCHEME = "harkara:"
    const val WEB_HOST = "tanzir71.github.io"
    const val WEB_BASE = "https://tanzir71.github.io/harkara/o/"

    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val GEOHASH = Regex("^[0123456789bcdefghjkmnpqrstuvwxyz]{4,9}$")
    private val EMBEDDED = Regex("""(harkara:[a-z]+:\S+|https://tanzir71\.github\.io/harkara/(?:en/)?o/#\S+)""")

    /** The web link for a fragment such as `e:…`. */
    fun web(rest: String): String = WEB_BASE + "#" + rest

    fun eventRest(event: Event): String = "e:" + QrTransport.payload(event)

    fun taskRest(requester: String, d: String): String {
        require(Hex.isLowerHex(requester, 32))
        return "t:" + b64url.encodeToString(Hex.decode(requester) + d.encodeToByteArray())
    }

    fun areaRest(geohash: String): String = "g:$geohash"

    /** Parses a link, a QR text or pasted text that contains one (an SMS with a link or a payload). */
    fun parse(input: String): HarkaraLink? {
        val text = input.trim()
        if (text.isEmpty()) return null
        parseOne(text)?.let { return it }
        EMBEDDED.findAll(text).forEach { m -> parseOne(m.value.trimEnd('.', ',', ')', ']'))?.let { return it } }
        if (SmsCodec.looksLikeSms(text)) return HarkaraLink.Sms(text)
        return null
    }

    private fun parseOne(text: String): HarkaraLink? {
        val rest = when {
            text.startsWith(SCHEME) -> text.removePrefix(SCHEME)
            text.startsWith("https://$WEB_HOST/harkara/") && '#' in text -> {
                val path = text.removePrefix("https://$WEB_HOST").substringBefore('#').substringBefore('?')
                if (path != "/harkara/o/" && path != "/harkara/en/o/" && path != "/harkara/o" && path != "/harkara/en/o") return null
                val fragment = text.substringAfter('#')
                val decoded = if ('%' in fragment) runCatching { java.net.URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }.getOrNull() else fragment
                decoded?.takeIf { it.isNotEmpty() } ?: return null
            }
            else -> return null
        }
        return fromRest(rest)
    }

    /** Parses what follows `harkara:` (or the web fragment). */
    fun fromRest(rest: String): HarkaraLink? {
        val type = rest.substringBefore(':', "")
        val value = rest.substringAfter(':', "")
        return when (type) {
            "e" -> QrTransport.decodeSingle(value)?.let { HarkaraLink.EventLink(it) }
            "q" -> QrTransport.parsePart("harkara:$rest")?.let { HarkaraLink.QrPart("harkara:$rest") }
            "iou" -> Iou.fromQr("harkara:$rest")?.let { HarkaraLink.IouBundle("harkara:$rest") }
            "c" -> IouConfirm.decode(value)?.let { HarkaraLink.IouConfirm(it) }
            "handoff" -> value.takeIf { it.startsWith("{") }?.let { HarkaraLink.Handoff("harkara:$rest") }
            "t" -> runCatching {
                val bytes = Base64.getUrlDecoder().decode(value)
                require(bytes.size in 33..200)
                val d = bytes.decodeToString(32, bytes.size, throwOnInvalidSequence = true)
                require(d.isNotBlank() && ':' !in d)
                HarkaraLink.Task(Hex.encode(bytes.copyOf(32)), d)
            }.getOrNull()
            "g" -> value.takeIf { GEOHASH.matches(it) }?.let { HarkaraLink.Area(it) }
            "gw" -> GatewayCard.parse("harkara:$rest")?.let { HarkaraLink.Gateway(it) }
            else -> null
        }
    }
}
