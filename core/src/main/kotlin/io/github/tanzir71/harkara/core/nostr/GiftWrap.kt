package io.github.tanzir71.harkara.core.nostr

import java.security.SecureRandom

/** Thrown when a gift wrap cannot be opened or fails a check. */
class GiftWrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * NIP-59 gift wraps carrying NIP-17 private messages: rumor (unsigned) → seal (kind 13, signed by the sender,
 * NIP-44 to the recipient) → wrap (kind 1059, signed by a one-time key, NIP-44 to the recipient, `p` tag).
 * Every carrier — relays, LAN, Bluetooth, SMS — moves only the wrap.
 */
object GiftWrap {
    /** Seal and wrap timestamps are pushed up to two days into the past to hide timing. */
    const val MAX_TIME_TWEAK_SECONDS: Long = 2 * 24 * 60 * 60

    /** An unsigned rumor of any kind, authored by [senderPubkey]. */
    fun createRumor(
        senderPubkey: String,
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = nowSeconds(),
    ): Rumor = UnsignedEvent(senderPubkey, createdAt, kind, tags, content)

    /** NIP-17 kind 14 chat message to one or more [recipients]; [replyTo] marks the parent message. */
    fun chatMessage(
        senderPubkey: String,
        recipients: List<String>,
        content: String,
        replyTo: String? = null,
        subject: String? = null,
        extraTags: List<List<String>> = emptyList(),
        createdAt: Long = nowSeconds(),
    ): Rumor {
        val tags = buildList {
            recipients.distinct().forEach { add(listOf("p", it)) }
            if (replyTo != null) add(listOf("e", replyTo))
            if (subject != null) add(listOf("subject", subject))
            addAll(extraTags)
        }
        return createRumor(senderPubkey, Kinds.CHAT_MESSAGE, content, tags, createdAt)
    }

    /** NIP-17 kind 15 file message; [fileUrl] points at an AES-GCM encrypted file described by the tags. */
    fun fileMessage(
        senderPubkey: String,
        recipients: List<String>,
        fileUrl: String,
        mimeType: String,
        decryptionKeyHex: String,
        decryptionNonceHex: String,
        encryptedSha256Hex: String,
        createdAt: Long = nowSeconds(),
    ): Rumor {
        val tags = buildList {
            recipients.distinct().forEach { add(listOf("p", it)) }
            add(listOf("file-type", mimeType))
            add(listOf("encryption-algorithm", "aes-gcm"))
            add(listOf("decryption-key", decryptionKeyHex))
            add(listOf("decryption-nonce", decryptionNonceHex))
            add(listOf("x", encryptedSha256Hex))
        }
        return createRumor(senderPubkey, Kinds.FILE_MESSAGE, fileUrl, tags, createdAt)
    }

    /** Kind 13 seal: the rumor encrypted from [sender] to [recipientPubkey], signed by the sender, no tags. */
    fun seal(
        rumor: Rumor,
        sender: KeyPair,
        recipientPubkey: String,
        createdAt: Long = randomPastTime(),
    ): Event {
        require(rumor.pubkey == sender.pubkeyHex) { "the rumor must be authored by the sealing key" }
        val key = Nip44.conversationKey(sender.privateKey, recipientPubkey)
        val content = Nip44.encrypt(rumor.toJson(), key)
        return UnsignedEvent(sender.pubkeyHex, createdAt, Kinds.SEAL, emptyList(), content).sign(sender)
    }

    /**
     * Kind 1059 gift wrap of a [seal] for [recipientPubkey], signed by a fresh one-time key. Use
     * [Kinds.EPHEMERAL_GIFT_WRAP] for live data that relays must not store (live location).
     */
    fun wrap(
        seal: Event,
        recipientPubkey: String,
        createdAt: Long = randomPastTime(),
        kind: Int = Kinds.GIFT_WRAP,
        extraTags: List<List<String>> = emptyList(),
        wrapperKeys: KeyPair = KeyPair.generate(),
    ): Event {
        require(seal.kind == Kinds.SEAL) { "only seals are wrapped" }
        require(kind == Kinds.GIFT_WRAP || kind == Kinds.EPHEMERAL_GIFT_WRAP) { "wrap kind must be 1059 or 21059" }
        val key = Nip44.conversationKey(wrapperKeys.privateKey, recipientPubkey)
        val content = Nip44.encrypt(seal.toJson(), key)
        val tags = listOf(listOf("p", recipientPubkey)) + extraTags
        return UnsignedEvent(wrapperKeys.pubkeyHex, createdAt, kind, tags, content).sign(wrapperKeys)
    }

    /** Seals and wraps [rumor] for one recipient. */
    fun sealAndWrap(rumor: Rumor, sender: KeyPair, recipientPubkey: String, kind: Int = Kinds.GIFT_WRAP): Event =
        wrap(seal(rumor, sender, recipientPubkey), recipientPubkey, kind = kind)

    /**
     * One wrap per member plus one to the sender (so their other devices and restores see the message).
     * Duplicates in [members] and the sender's own key are folded.
     */
    fun wrapForGroup(rumor: Rumor, sender: KeyPair, members: List<String>, kind: Int = Kinds.GIFT_WRAP): List<Event> =
        (members + sender.pubkeyHex).distinct().map { sealAndWrap(rumor, sender, it, kind) }

    /**
     * Opens a wrap addressed to [recipient]: checks the wrap and seal signatures, decrypts both layers and
     * insists the seal's signer authored the rumor (otherwise anyone could impersonate anyone).
     */
    fun unwrap(wrap: Event, recipient: KeyPair): Rumor {
        if (wrap.kind != Kinds.GIFT_WRAP && wrap.kind != Kinds.EPHEMERAL_GIFT_WRAP) throw GiftWrapException("not a gift wrap")
        if (!wrap.verify()) throw GiftWrapException("gift wrap signature is invalid")
        val sealJson = decrypt(wrap.content, recipient, wrap.pubkey, "gift wrap")
        val seal = try {
            Event.fromJson(sealJson)
        } catch (e: IllegalArgumentException) {
            throw GiftWrapException("seal is not an event", e)
        }
        if (seal.kind != Kinds.SEAL) throw GiftWrapException("inner event is not a seal")
        if (!seal.verify()) throw GiftWrapException("seal signature is invalid")
        val rumorJson = decrypt(seal.content, recipient, seal.pubkey, "seal")
        val rumor = try {
            UnsignedEvent.fromJson(rumorJson)
        } catch (e: IllegalArgumentException) {
            throw GiftWrapException("rumor is malformed", e)
        }
        if (rumor.pubkey != seal.pubkey) throw GiftWrapException("seal signer is not the rumor author")
        return rumor
    }

    private fun decrypt(payload: String, recipient: KeyPair, senderPubkey: String, layer: String): String = try {
        Nip44.decrypt(payload, Nip44.conversationKey(recipient.privateKey, senderPubkey))
    } catch (e: Nip44Exception) {
        throw GiftWrapException("cannot decrypt $layer", e)
    }

    /** A time up to two days in the past. */
    fun randomPastTime(now: Long = nowSeconds(), random: SecureRandom = secureRandom): Long =
        now - (random.nextDouble() * MAX_TIME_TWEAK_SECONDS).toLong()
}
