package io.github.tanzir71.harkara.core.nostr

import java.security.MessageDigest

/**
 * NIP-13 proof of work: difficulty is the number of leading zero bits of the event id, mined by bumping the
 * second item of a `["nonce", n, target]` tag. Feed posts carry 16-20 bits (tuned so a budget phone stays
 * under about 3 s).
 */
object Nip13 {
    /** Leading zero bits of a hex id. */
    fun difficulty(idHex: String): Int {
        var count = 0
        for (c in idHex) {
            val nibble = Character.digit(c, 16)
            require(nibble >= 0) { "not hex" }
            if (nibble == 0) {
                count += 4
            } else {
                count += Integer.numberOfLeadingZeros(nibble) - 28
                break
            }
        }
        return count
    }

    /** The target committed in the nonce tag, or null when there is none. */
    fun committedTarget(event: Event): Int? =
        event.tags.firstOrNull { it.size >= 3 && it[0] == "nonce" }?.get(2)?.toIntOrNull()

    /**
     * True when the id has at least [minBits] leading zeros and, if a target is committed, the target is at
     * least [minBits] too, so lucky low-target spam is refused.
     */
    fun meets(event: Event, minBits: Int): Boolean {
        if (minBits <= 0) return true
        if (difficulty(event.id) < minBits) return false
        val target = committedTarget(event) ?: return false
        return target >= minBits
    }

    /**
     * Mines [template] until its id has at least [targetBits] leading zero bits. Any existing nonce tag is
     * replaced by `["nonce", n, targetBits]` (kept last so the hash prefix can be reused between tries).
     */
    fun mine(template: UnsignedEvent, targetBits: Int, maxTries: Long = Long.MAX_VALUE): UnsignedEvent {
        require(targetBits in 0..256) { "target out of range" }
        val baseTags = template.tags.filterNot { it.isNotEmpty() && it[0] == "nonce" }
        val marker = "\u0000harkara-nonce\u0000"
        val withMarker = baseTags + listOf(listOf("nonce", marker, targetBits.toString()))
        val serialized = EventId.serialize(template.pubkey, template.createdAt, template.kind, withMarker, template.content)
        val at = serialized.indexOf(NostrJsonMarker.quoted(marker))
        check(at >= 0)
        val prefix = (serialized.substring(0, at) + "\"").encodeToByteArray()
        val suffix = ("\"" + serialized.substring(at + NostrJsonMarker.quoted(marker).length)).encodeToByteArray()
        val base = MessageDigest.getInstance("SHA-256").apply { update(prefix) }
        var nonce = 0L
        while (nonce < maxTries) {
            val digest = cloneOrRebuild(base, prefix)
            digest.update(nonce.toString().encodeToByteArray())
            digest.update(suffix)
            val hash = digest.digest()
            if (leadingZeroBits(hash) >= targetBits) {
                return template.copy(tags = baseTags + listOf(listOf("nonce", nonce.toString(), targetBits.toString())))
            }
            nonce++
        }
        throw IllegalStateException("no nonce found in $maxTries tries")
    }

    private fun cloneOrRebuild(base: MessageDigest, prefix: ByteArray): MessageDigest = try {
        base.clone() as MessageDigest
    } catch (_: CloneNotSupportedException) {
        MessageDigest.getInstance("SHA-256").apply { update(prefix) }
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var count = 0
        for (b in hash) {
            val v = b.toInt() and 0xff
            if (v == 0) {
                count += 8
            } else {
                count += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return count
    }
}

private object NostrJsonMarker {
    fun quoted(value: String): String = StringBuilder().also { NostrJson.quote(value, it) }.toString()
}
