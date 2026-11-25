package io.github.tanzir71.harkara.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** NIP-11 relay information: the limits we respect before sending anything. */
data class RelayInfo(
    val name: String = "",
    val supportedNips: Set<Int> = emptySet(),
    val maxMessageLength: Int? = null,
    val maxSubscriptions: Int? = null,
    val maxFilters: Int? = null,
    val maxLimit: Int? = null,
    val maxContentLength: Int? = null,
    val authRequired: Boolean = false,
    val paymentRequired: Boolean = false,
) {
    val supportsNegentropy: Boolean get() = 77 in supportedNips

    /** True when [eventJsonBytes] fits this relay's message limit (the EVENT wrapper adds ~12 bytes). */
    fun accepts(eventJsonBytes: Int): Boolean = maxMessageLength == null || eventJsonBytes + 16 <= maxMessageLength

    companion object {
        val Unknown = RelayInfo()

        fun parse(json: String): RelayInfo = runCatching {
            val obj = Json.parseToJsonElement(json).jsonObject
            val lim = obj["limitation"] as? JsonObject
            fun limInt(key: String) = (lim?.get(key) as? JsonPrimitive)?.intOrNull
            fun limBool(key: String) = (lim?.get(key) as? JsonPrimitive)?.booleanOrNull ?: false
            RelayInfo(
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                supportedNips = (obj["supported_nips"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet() ?: emptySet(),
                maxMessageLength = limInt("max_message_length"),
                maxSubscriptions = limInt("max_subscriptions"),
                maxFilters = limInt("max_filters"),
                maxLimit = limInt("max_limit"),
                maxContentLength = limInt("max_content_length"),
                authRequired = limBool("auth_required"),
                paymentRequired = limBool("payment_required"),
            )
        }.getOrDefault(Unknown)
    }
}

/** Exponential reconnect backoff with full jitter: 1 s, 2 s, 4 s … capped at [maxMs]. */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 5 * 60_000,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    /** Delay before the next attempt, then counts the attempt. */
    fun nextDelayMs(): Long {
        val cap = (baseMs shl attempt.coerceAtMost(20)).coerceAtMost(maxMs)
        attempt++
        return cap / 2 + random.nextLong(cap / 2 + 1)
    }

    fun reset() {
        attempt = 0
    }
}
