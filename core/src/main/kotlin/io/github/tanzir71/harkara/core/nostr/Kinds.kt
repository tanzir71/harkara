package io.github.tanzir71.harkara.core.nostr

/**
 * Event kinds Harkara uses. Standard NIP kinds first, then Harkara app kinds in a block no NIP uses
 * (3471-3479 regular, 23471 ephemeral, 33471 addressable). See docs/PROTOCOL.md for the tags of each.
 */
object Kinds {
    // Standard NIP kinds
    const val METADATA = 0
    /** Nearby feed post: `g` (geohash-6), `t` (category), `expiration` (NIP-40), NIP-13 `nonce`. Signed by the area key. */
    const val FEED_POST = 1
    const val DELETION = 5
    /** NIP-25 reaction: votes (`+`/`-`) and "still there?" (`yes`/`no` on pins). */
    const val REACTION = 7
    const val SEAL = 13
    const val CHAT_MESSAGE = 14
    const val FILE_MESSAGE = 15
    const val GIFT_WRAP = 1059
    /** NIP-56 report. */
    const val REPORT = 1984
    const val MUTE_LIST = 10000
    const val DM_RELAYS = 10050
    /** NIP-51 follow sets, used for opt-in moderator lists per area. */
    const val FOLLOW_SET = 30000
    /** NIP-78 app data: private pins encrypted to self. */
    const val APP_DATA = 30078
    /** NIP-99 classified listing: a task offer. */
    const val TASK_OFFER = 30402
    /** Ephemeral `g`-tagged area channel message (the bitchat geohash-channel convention). */
    const val AREA_CHAT = 20000
    const val EPHEMERAL_GIFT_WRAP = 21059

    // Harkara app kinds
    /** A step on a task offer (`a`/`e` to the offer, `step` = accept|picked_up|delivered|paid|rated|cancel|dispute). */
    const val TASK_STEP = 3471
    /** Requester-signed single-use handoff token, shown as QR or 6 digits and checked offline. */
    const val HANDOFF_TOKEN = 3472
    /** IOU terms, gift-wrapped to both people. */
    const val IOU = 3473
    /** Co-signature over an IOU hash; an IOU is agreed only when both people sign the same hash. */
    const val IOU_SIGN = 3474
    /** Settlement (optional bKash/Nagad TrxID as text), signed by both. */
    const val SETTLE = 3475
    /** "I got here safe" check-in. */
    const val CHECK_IN = 3476
    /** SOS: top relay priority, one per 30 min per key, active until resolved. */
    const val SOS = 3477
    /** SOS follow-up: `e` to the SOS, `status` = resolved|false_alarm. */
    const val SOS_UPDATE = 3478
    /**
     * Short DM for the SMS rung: NIP-44 v2 content to the single `p` recipient, signed by the sender, not
     * gift-wrapped (a NIP-59 wrap cannot fit two SMS; the carrier sees both numbers anyway).
     */
    const val SMS_DM = 3479
    /** Live location point: ephemeral, gift-wrapped to named people, `seq` + `end`. */
    const val LIVE_LOCATION = 23471
    /** Shared or public pin: addressable (`d`), `g` + `expiration`. */
    const val PIN = 33471

    fun isRegular(kind: Int): Boolean = kind in 1000..9999 || kind in 4..44 || kind == 1 || kind == 2
    fun isReplaceable(kind: Int): Boolean = kind == 0 || kind == 3 || kind in 10000..19999
    fun isEphemeral(kind: Int): Boolean = kind in 20000..29999
    fun isAddressable(kind: Int): Boolean = kind in 30000..39999
}
