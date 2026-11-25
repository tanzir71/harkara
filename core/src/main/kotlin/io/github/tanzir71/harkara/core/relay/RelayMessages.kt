package io.github.tanzir71.harkara.core.relay

import io.github.tanzir71.harkara.core.nostr.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** NIP-01 subscription filter. Tag filters are keyed by the single-letter tag name (`g`, `t`, `e`, `p`). */
data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
) {
    fun toJsonElement(): JsonObject = buildJsonObject {
        ids?.let { put("ids", JsonArray(it.map(::JsonPrimitive))) }
        authors?.let { put("authors", JsonArray(it.map(::JsonPrimitive))) }
        kinds?.let { put("kinds", JsonArray(it.map(::JsonPrimitive))) }
        tags.forEach { (name, values) -> put("#$name", JsonArray(values.map(::JsonPrimitive))) }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
    }

    fun toJson(): String = toJsonElement().toString()

    /** NIP-01 matching, used by the in-process relays (LAN room, node, tests) and to route incoming events. */
    fun matches(event: Event): Boolean {
        if (ids != null && event.id !in ids) return false
        if (authors != null && event.pubkey !in authors) return false
        if (kinds != null && event.kind !in kinds) return false
        if (since != null && event.createdAt < since) return false
        if (until != null && event.createdAt > until) return false
        for ((name, values) in tags) {
            if (event.tags.none { it.size > 1 && it[0] == name && it[1] in values }) return false
        }
        return true
    }

    companion object {
        fun fromJson(element: JsonElement): Filter {
            val obj = element as JsonObject
            fun strings(key: String) = (obj[key] as? JsonArray)?.map { it.jsonPrimitive.content }
            return Filter(
                ids = strings("ids"),
                authors = strings("authors"),
                kinds = (obj["kinds"] as? JsonArray)?.map { it.jsonPrimitive.int },
                tags = obj.keys.filter { it.length == 2 && it[0] == '#' }.associate { key ->
                    key.substring(1) to obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }
                },
                since = (obj["since"] as? JsonPrimitive)?.long,
                until = (obj["until"] as? JsonPrimitive)?.long,
                limit = (obj["limit"] as? JsonPrimitive)?.int,
            )
        }
    }
}

/** Messages a client sends to a relay. */
sealed interface ClientMessage {
    fun toJson(): String

    data class Publish(val event: Event) : ClientMessage {
        override fun toJson(): String = "[\"EVENT\",${event.toJson()}]"
    }

    data class Req(val subscriptionId: String, val filters: List<Filter>) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            filters.forEach { add(it.toJsonElement()) }
        }.toString()
    }

    data class Close(val subscriptionId: String) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()
    }

    companion object {
        /** Parses a client message (used by in-process relays). Returns null for anything unknown or malformed. */
        fun parse(text: String): ClientMessage? = runCatching {
            val array = Json.parseToJsonElement(text).jsonArray
            when (array[0].jsonPrimitive.content) {
                "EVENT" -> Publish(Event.fromJson(array[1].toString()))
                "REQ" -> Req(array[1].jsonPrimitive.content, array.drop(2).map(Filter::fromJson))
                "CLOSE" -> Close(array[1].jsonPrimitive.content)
                else -> null
            }
        }.getOrNull()
    }
}

/** Messages a relay sends to a client. */
sealed interface RelayMessage {
    data class EventMessage(val subscriptionId: String, val event: Event) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Eose(val subscriptionId: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data class Auth(val challenge: String) : RelayMessage
    /** NIP-77 negentropy round (hex message). */
    data class NegMsg(val subscriptionId: String, val message: String) : RelayMessage
    data class NegErr(val subscriptionId: String, val reason: String) : RelayMessage

    fun toJson(): String = when (this) {
        is NegMsg -> buildJsonArray { add(JsonPrimitive("NEG-MSG")); add(JsonPrimitive(subscriptionId)); add(JsonPrimitive(message)) }.toString()
        is NegErr -> buildJsonArray { add(JsonPrimitive("NEG-ERR")); add(JsonPrimitive(subscriptionId)); add(JsonPrimitive(reason)) }.toString()
        is EventMessage -> "[\"EVENT\",${JsonPrimitive(subscriptionId)},${event.toJson()}]"
        is Ok -> buildJsonArray {
            add(JsonPrimitive("OK")); add(JsonPrimitive(eventId)); add(JsonPrimitive(accepted)); add(JsonPrimitive(message))
        }.toString()
        is Eose -> buildJsonArray { add(JsonPrimitive("EOSE")); add(JsonPrimitive(subscriptionId)) }.toString()
        is Closed -> buildJsonArray { add(JsonPrimitive("CLOSED")); add(JsonPrimitive(subscriptionId)); add(JsonPrimitive(message)) }.toString()
        is Notice -> buildJsonArray { add(JsonPrimitive("NOTICE")); add(JsonPrimitive(message)) }.toString()
        is Auth -> buildJsonArray { add(JsonPrimitive("AUTH")); add(JsonPrimitive(challenge)) }.toString()
    }

    companion object {
        /** Parses a relay message. Returns null for anything unknown or malformed (relays are untrusted). */
        fun parse(text: String): RelayMessage? = runCatching {
            val array = Json.parseToJsonElement(text).jsonArray
            when (array[0].jsonPrimitive.content) {
                "EVENT" -> EventMessage(array[1].jsonPrimitive.content, Event.fromJson(array[2].toString()))
                "OK" -> Ok(array[1].jsonPrimitive.content, array[2].jsonPrimitive.booleanOrNull ?: false, array.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: "")
                "EOSE" -> Eose(array[1].jsonPrimitive.content)
                "CLOSED" -> Closed(array[1].jsonPrimitive.content, array.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: "")
                "NOTICE" -> Notice(array[1].jsonPrimitive.content)
                "AUTH" -> Auth(array[1].jsonPrimitive.content)
                "NEG-MSG" -> NegMsg(array[1].jsonPrimitive.content, array[2].jsonPrimitive.content)
                "NEG-ERR" -> NegErr(array[1].jsonPrimitive.content, array.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: "")
                else -> null
            }
        }.getOrNull()
    }
}

/** NIP-77 client messages. */
object NegMessages {
    fun open(subscriptionId: String, filter: Filter, initialMessage: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-OPEN")); add(JsonPrimitive(subscriptionId)); add(filter.toJsonElement()); add(JsonPrimitive(initialMessage))
    }.toString()

    fun msg(subscriptionId: String, message: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-MSG")); add(JsonPrimitive(subscriptionId)); add(JsonPrimitive(message))
    }.toString()

    fun close(subscriptionId: String): String = buildJsonArray { add(JsonPrimitive("NEG-CLOSE")); add(JsonPrimitive(subscriptionId)) }.toString()

    /** Parsed `NEG-OPEN` (for in-process relays and tests): subscription id, filter, first message. */
    fun parseOpen(text: String): Triple<String, Filter, String>? = runCatching {
        val a = Json.parseToJsonElement(text).jsonArray
        if (a[0].jsonPrimitive.content != "NEG-OPEN") return null
        Triple(a[1].jsonPrimitive.content, Filter.fromJson(a[2]), a[3].jsonPrimitive.content)
    }.getOrNull()

    fun parseMsg(text: String): Pair<String, String>? = runCatching {
        val a = Json.parseToJsonElement(text).jsonArray
        if (a[0].jsonPrimitive.content != "NEG-MSG") return null
        a[1].jsonPrimitive.content to a[2].jsonPrimitive.content
    }.getOrNull()
}
