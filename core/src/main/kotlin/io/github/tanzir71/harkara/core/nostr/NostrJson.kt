package io.github.tanzir71.harkara.core.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * JSON writing with the exact NIP-01 rules: no whitespace, UTF-8, and only `\n \" \\ \r \t \b \f` use short
 * escapes. Other control characters and lone surrogates are written as lowercase `\u00xx`, matching
 * `JSON.stringify`, so ids agree with nostr-tools and every other mainstream client.
 */
internal object NostrJson {
    private val parser = Json { isLenient = false }

    fun quote(value: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\n' -> out.append("\\n")
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> unicodeEscape(c, out)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                        out.append(c).append(value[i + 1])
                        i++
                    } else {
                        unicodeEscape(c, out)
                    }
                }
                Character.isLowSurrogate(c) -> unicodeEscape(c, out)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun unicodeEscape(c: Char, out: StringBuilder) {
        out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
    }

    fun tags(tags: List<List<String>>, out: StringBuilder) {
        out.append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) out.append(',')
            out.append('[')
            tag.forEachIndexed { j, item ->
                if (j > 0) out.append(',')
                quote(item, out)
            }
            out.append(']')
        }
        out.append(']')
    }

    fun parseObject(json: String): JsonObject =
        parser.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("event JSON must be an object")

    fun string(obj: JsonObject, key: String): String {
        val p = obj[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing '$key'")
        require(p.isString) { "'$key' must be a string" }
        return p.content
    }

    fun long(obj: JsonObject, key: String): Long {
        val p = obj[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing '$key'")
        require(!p.isString) { "'$key' must be a number" }
        return p.longOrNull ?: throw IllegalArgumentException("'$key' must be an integer")
    }

    fun tags(element: JsonElement?): List<List<String>> {
        val array = element as? JsonArray ?: throw IllegalArgumentException("'tags' must be an array")
        return array.map { tag ->
            val items = tag as? JsonArray ?: throw IllegalArgumentException("each tag must be an array")
            items.map { item ->
                val p = item as? JsonPrimitive
                require(p != null && p.isString) { "tag items must be strings" }
                p.content
            }
        }
    }
}
