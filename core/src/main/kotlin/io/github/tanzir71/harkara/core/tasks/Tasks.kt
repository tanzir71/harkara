package io.github.tanzir71.harkara.core.tasks

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent

/** A NIP-99 task offer (kind 30402), posted by the requester's profile key. */
data class TaskOffer(
    val event: Event,
    val d: String,
    val title: String,
    val type: String,
    /** Price in whole taka. */
    val price: Long,
    val geohash: String?,
    val fields: Map<String, String>,
) {
    val requester: String get() = event.pubkey
    val description: String get() = event.content
    val address: String get() = TaskOffers.address(event.pubkey, d)
    val expiresAt: Long? get() = event.expiration
    val createdAt: Long get() = event.createdAt
    /** Display name the requester chose (never a phone number). */
    val requesterName: String? get() = event.tagValue("name")
}

object TaskOffers {
    const val MARKER = "harkara-task"

    fun address(requester: String, d: String) = "${Kinds.TASK_OFFER}:$requester:$d"

    fun build(
        requester: KeyPair,
        d: String,
        title: String,
        type: String,
        price: Long,
        latitude: Double?,
        longitude: Double?,
        description: String,
        createdAt: Long,
        fields: Map<String, String> = emptyMap(),
        expiresAt: Long? = createdAt + 24 * 3600,
        name: String? = null,
    ): Event {
        require(title.isNotBlank()) { "a task needs a title" }
        require(price >= 0) { "price can't be negative" }
        val tags = buildList {
            add(listOf("d", d))
            add(listOf("title", title.trim()))
            add(listOf("summary", title.trim()))
            add(listOf("published_at", createdAt.toString()))
            add(listOf("price", price.toString(), "BDT"))
            add(listOf("status", "active"))
            add(listOf("t", MARKER))
            add(listOf("t", type))
            if (latitude != null && longitude != null) listOf(7, 6, 5).forEach { add(listOf("g", GeoHash.encode(latitude, longitude, it))) }
            fields.forEach { (k, v) -> if (v.isNotBlank()) add(listOf("field", k, v.trim())) }
            expiresAt?.let { add(listOf("expiration", it.toString())) }
            name?.takeIf { it.isNotBlank() }?.let { add(listOf("name", it.trim().take(40))) }
        }
        return requester.signEvent(Kinds.TASK_OFFER, tags, description.trim(), createdAt)
    }

    fun parse(event: Event): TaskOffer? {
        if (event.kind != Kinds.TASK_OFFER || MARKER !in event.tagValues("t")) return null
        val d = event.tagValue("d") ?: return null
        val title = event.tagValue("title") ?: return null
        val price = event.tags.firstOrNull { it.size > 1 && it[0] == "price" }?.get(1)?.toLongOrNull() ?: 0
        return TaskOffer(
            event = event,
            d = d,
            title = title,
            type = event.tagValues("t").firstOrNull { it != MARKER } ?: "errand",
            price = price,
            geohash = event.tagValues("g").maxByOrNull { it.length },
            fields = event.tags.filter { it.size > 2 && it[0] == "field" }.associate { it[1] to it[2] },
        )
    }
}

/** Steps on a task (Harkara app kind 3471). */
enum class StepType(val wire: String) {
    ACCEPT("accept"),
    /** The requester countersigns one accept; only then does the runner hold the task. */
    CONFIRM("confirm"),
    DECLINE("decline"),
    PICKED_UP("picked_up"),
    DELIVERED("delivered"),
    PAID("paid"),
    PAID_ACK("paid_ack"),
    RATED("rated"),
    CANCEL("cancel"),
    DISPUTE("dispute"),
    ;

    companion object {
        fun of(wire: String?): StepType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Dispute reasons (from litegig). */
enum class DisputeReason(val wire: String) {
    ITEM_ISSUE("item_issue"),
    PAYMENT_ISSUE("payment_issue"),
    DELIVERY_ISSUE("delivery_issue"),
    SAFETY_OR_POLICY("safety_or_policy"),
    OTHER("other"),
}

object TaskSteps {
    /** Signs a step on [offer] by [keys]; [counterparty] goes in a `p` tag so the other side can find it. */
    fun build(
        keys: KeyPair,
        offer: TaskOffer,
        step: StepType,
        createdAt: Long,
        counterparty: String? = null,
        extra: List<List<String>> = emptyList(),
        content: String = "",
    ): Event {
        val tags = buildList {
            add(listOf("a", offer.address))
            add(listOf("e", offer.event.id))
            add(listOf("step", step.wire))
            counterparty?.let { add(listOf("p", it)) }
            addAll(extra)
        }
        return keys.signEvent(Kinds.TASK_STEP, tags, content, createdAt)
    }

    fun accept(runner: KeyPair, offer: TaskOffer, at: Long, name: String? = null) =
        build(runner, offer, StepType.ACCEPT, at, offer.requester, listOfNotNull(name?.takeIf { it.isNotBlank() }?.let { listOf("name", it.trim().take(40)) }))

    /** Countersigns [accept] and commits to the handoff code (see [Handoff.commitment]). */
    fun confirm(requester: KeyPair, offer: TaskOffer, accept: Event, commitment: String, at: Long) =
        build(requester, offer, StepType.CONFIRM, at, accept.pubkey, listOf(listOf("e", accept.id, "", "accept"), listOf("handoff", commitment)))

    fun pickedUp(runner: KeyPair, offer: TaskOffer, at: Long) = build(runner, offer, StepType.PICKED_UP, at, offer.requester)

    /** Delivered with the requester's signed token (QR path). */
    fun deliveredWithToken(runner: KeyPair, offer: TaskOffer, token: Event, at: Long, thumbnail: List<String>? = null) =
        build(runner, offer, StepType.DELIVERED, at, offer.requester, listOfNotNull(listOf("token", token.toJson()), thumbnail))

    /** Delivered with the 6-digit code the requester showed (typed path). */
    fun deliveredWithCode(runner: KeyPair, offer: TaskOffer, code: String, at: Long, thumbnail: List<String>? = null) =
        build(runner, offer, StepType.DELIVERED, at, offer.requester, listOfNotNull(listOf("code", code), thumbnail))

    /** Payment record; [encryptedDetails] is NIP-44 to the runner (method, amount, TrxID). */
    fun paid(requester: KeyPair, offer: TaskOffer, runner: String, encryptedDetails: String, at: Long) =
        build(requester, offer, StepType.PAID, at, runner, content = encryptedDetails)

    fun paidAck(runner: KeyPair, offer: TaskOffer, at: Long) = build(runner, offer, StepType.PAID_ACK, at, offer.requester)

    fun rate(keys: KeyPair, offer: TaskOffer, other: String, stars: Int, at: Long): Event {
        require(stars in 1..5) { "rating is 1 to 5" }
        return build(keys, offer, StepType.RATED, at, other, listOf(listOf("rating", stars.toString())))
    }

    fun cancel(requester: KeyPair, offer: TaskOffer, at: Long, reason: String = "") =
        build(requester, offer, StepType.CANCEL, at, extra = if (reason.isBlank()) emptyList() else listOf(listOf("reason", reason)))

    fun decline(runner: KeyPair, offer: TaskOffer, at: Long) = build(runner, offer, StepType.DECLINE, at, offer.requester)

    fun dispute(keys: KeyPair, offer: TaskOffer, other: String, reason: DisputeReason, at: Long, note: String = "") =
        build(keys, offer, StepType.DISPUTE, at, other, listOf(listOf("reason", reason.wire)), note)

    fun stepOf(event: Event): StepType? = if (event.kind == Kinds.TASK_STEP) StepType.of(event.tagValue("step")) else null
}
