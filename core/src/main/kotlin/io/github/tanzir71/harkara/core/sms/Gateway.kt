package io.github.tanzir71.harkara.core.sms

import io.github.tanzir71.harkara.core.nostr.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Someone a volunteer gateway relays for. The number stays on the volunteer's phone. */
@Serializable
data class GatewayMember(val name: String, val number: String, val pubkey: String? = null)

/** The volunteer's setup: individual contacts, or one named group, never strangers. */
@Serializable
data class GatewayConfig(val groupName: String = "", val members: List<GatewayMember> = emptyList())

/**
 * Rules for the volunteer SMS gateway (handover §5.6): at most [dailyCap] relayed SMS per Dhaka day (in and
 * out together), and only for numbers the volunteer added (contacts, or the one named group). Pure logic;
 * the app keeps the counter and the member list on the phone.
 */
class GatewayPolicy(private val dailyCap: Int = DAILY_CAP, private val zone: ZoneId = ZoneId.of("Asia/Dhaka")) {
    enum class Decision { ALLOW, NOT_A_MEMBER, CAP_REACHED }

    /** The Dhaka calendar day an instant falls on, e.g. "2026-11-25": the counter resets when it changes. */
    fun day(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** Whether one more SMS to or from [number] may be relayed, given [sentToday] already relayed today. */
    fun decide(config: GatewayConfig, number: String, sentToday: Int): Decision = when {
        memberFor(config, number) == null -> Decision.NOT_A_MEMBER
        sentToday >= dailyCap -> Decision.CAP_REACHED
        else -> Decision.ALLOW
    }

    fun memberFor(config: GatewayConfig, number: String): GatewayMember? {
        val key = normalize(number)
        if (key.isEmpty()) return null
        return config.members.firstOrNull { normalize(it.number) == key }
    }

    fun memberForKey(config: GatewayConfig, pubkey: String): GatewayMember? = config.members.firstOrNull { it.pubkey == pubkey }

    companion object {
        const val DAILY_CAP = 100

        /** Compares Bangladeshi numbers in any common form: +8801711…, 8801711…, 01711…. */
        fun normalize(number: String): String {
            val digits = number.filter { it.isDigit() }
            return when {
                digits.startsWith("880") && digits.length == 13 -> digits.substring(2)
                digits.startsWith("0") -> digits
                digits.length == 10 && digits.startsWith("1") -> "0$digits"
                else -> digits
            }
        }
    }
}

/**
 * The volunteer's gateway card, shown only as a QR on their own screen (`harkara:gw:<base64url JSON>`):
 * scanning it is the only way to learn the number. Never published to any relay.
 */
@Serializable
data class GatewayCard(val name: String, val number: String, val pubkey: String? = null) {
    fun toLink(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(serializer(), this).encodeToByteArray())

    companion object {
        const val PREFIX = "harkara:gw:"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GatewayCard? = runCatching {
            val t = text.trim()
            require(t.startsWith(PREFIX))
            val card = json.decodeFromString(serializer(), Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)).decodeToString())
            require(card.name.length <= 40 && GatewayPolicy.normalize(card.number).length in 5..15)
            require(card.pubkey == null || Hex.isLowerHex(card.pubkey, 32))
            card
        }.getOrNull()
    }
}
