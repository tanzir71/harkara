package io.github.tanzir71.harkara.core.tasks

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds

enum class TaskStatus { OPEN, ACCEPTED, PICKED_UP, DELIVERED, PAID, CANCELLED, DISPUTED, EXPIRED }

/** How the delivery was proven. */
enum class HandoffProof { TOKEN, CODE }

/** One step the state machine accepted. */
data class AppliedStep(val event: Event, val type: StepType, val from: TaskStatus, val to: TaskStatus)

data class TaskState(
    val offer: TaskOffer,
    val status: TaskStatus,
    val runner: String? = null,
    /** Accepts waiting for the requester's countersignature. */
    val pendingAccepts: List<Event> = emptyList(),
    val commitment: String? = null,
    val proof: HandoffProof? = null,
    val requesterPaid: Boolean = false,
    /** Rater pubkey → stars (each side rates once). */
    val ratings: Map<String, Int> = emptyMap(),
    val disputeReason: String? = null,
    val applied: List<AppliedStep> = emptyList(),
    /** Steps dropped, with why: wrong role, wrong state, bad signature, bad proof. */
    val dropped: List<Pair<Event, String>> = emptyList(),
) {
    val requester: String get() = offer.requester
    val done: Boolean get() = status == TaskStatus.DELIVERED || status == TaskStatus.PAID
    fun at(type: StepType): AppliedStep? = applied.lastOrNull { it.type == type }
}

/**
 * Replays the signed steps of one task in time order. Only the allowed role can make each step from the
 * allowed states; anything else is dropped. Every phone computes the same state from the same events,
 * with no server deciding.
 */
object TaskStateMachine {
    fun reduce(offerEvent: Event, steps: List<Event>, nowSeconds: Long): TaskState? {
        val offer = TaskOffers.parse(offerEvent) ?: return null
        if (!offerEvent.verify()) return null
        var state = TaskState(offer, TaskStatus.OPEN)
        val usedProofs = HashSet<String>()
        // Time order first, lifecycle order for ties; then keep retrying steps that weren't valid yet, so a
        // countersign made in the same second as the accept (or on a phone whose clock is behind) still counts.
        var pending = steps.distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { lifecycleRank(it) }, { it.id }))
        val reasons = HashMap<String, String>()
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            val next = ArrayList<Event>()
            for (step in pending) {
                val before = state
                state = apply(state, step, usedProofs)
                if (state.dropped.size > before.dropped.size) {
                    reasons[step.id] = state.dropped.last().second
                    state = state.copy(dropped = before.dropped)
                    next += step
                } else {
                    progress = true
                }
            }
            pending = next
        }
        state = state.copy(dropped = pending.map { it to (reasons[it.id] ?: "dropped") })
        if (state.status == TaskStatus.OPEN && offer.expiresAt?.let { it <= nowSeconds } == true) {
            state = state.copy(status = TaskStatus.EXPIRED)
        }
        return state
    }

    private val lifecycle = listOf(
        StepType.ACCEPT, StepType.CONFIRM, StepType.DECLINE, StepType.PICKED_UP, StepType.DELIVERED,
        StepType.PAID, StepType.PAID_ACK, StepType.RATED, StepType.DISPUTE, StepType.CANCEL,
    )

    private fun lifecycleRank(e: Event): Int = TaskSteps.stepOf(e)?.let { lifecycle.indexOf(it) } ?: lifecycle.size

    private fun apply(s: TaskState, e: Event, usedProofs: MutableSet<String>): TaskState {
        fun drop(why: String) = s.copy(dropped = s.dropped + (e to why))
        if (e.kind != Kinds.TASK_STEP) return drop("not a task step")
        if (e.tagValue("a") != s.offer.address) return drop("other task")
        if (!e.verify()) return drop("bad signature")
        val type = TaskSteps.stepOf(e) ?: return drop("unknown step")
        val author = e.pubkey
        val isRequester = author == s.requester
        val isRunner = s.runner != null && author == s.runner
        fun move(to: TaskStatus, change: TaskState.() -> TaskState = { this }) =
            s.change().copy(status = to, applied = s.applied + AppliedStep(e, type, s.status, to))

        return when (type) {
            StepType.ACCEPT -> when {
                isRequester -> drop("requester can't accept own task")
                s.status != TaskStatus.OPEN -> drop("not open")
                s.pendingAccepts.any { it.pubkey == author } -> drop("already offered")
                else -> s.copy(pendingAccepts = s.pendingAccepts + e, applied = s.applied + AppliedStep(e, type, s.status, s.status))
            }
            StepType.CONFIRM -> {
                val acceptId = e.tags.firstOrNull { it.size > 3 && it[0] == "e" && it[3] == "accept" }?.get(1)
                val accept = s.pendingAccepts.firstOrNull { it.id == acceptId }
                val commitment = e.tagValue("handoff")
                when {
                    !isRequester -> drop("only the requester confirms")
                    s.status != TaskStatus.OPEN -> drop("not open")
                    accept == null -> drop("no such accept")
                    commitment.isNullOrBlank() -> drop("missing handoff commitment")
                    else -> move(TaskStatus.ACCEPTED) { copy(runner = accept.pubkey, commitment = commitment) }
                }
            }
            StepType.DECLINE -> when {
                !isRunner -> drop("only the runner declines")
                s.status != TaskStatus.ACCEPTED -> drop("not accepted")
                else -> move(TaskStatus.OPEN) { copy(runner = null, commitment = null, pendingAccepts = pendingAccepts.filterNot { it.pubkey == author }) }
            }
            StepType.PICKED_UP -> when {
                !isRunner -> drop("only the runner picks up")
                s.status != TaskStatus.ACCEPTED -> drop("not accepted")
                else -> move(TaskStatus.PICKED_UP)
            }
            StepType.DELIVERED -> {
                if (!isRunner) return drop("only the runner delivers")
                if (s.status != TaskStatus.ACCEPTED && s.status != TaskStatus.PICKED_UP) return drop("not in progress")
                val tokenJson = e.tagValue("token")
                val code = e.tagValue("code")
                val proof: HandoffProof
                val proofKey: String
                if (tokenJson != null) {
                    val token = runCatching { Event.fromJson(tokenJson) }.getOrNull() ?: return drop("unreadable token")
                    if (!Handoff.verifyToken(token, s.offer, author)) return drop("token not valid for this task and runner")
                    proof = HandoffProof.TOKEN
                    proofKey = "token:" + token.id
                } else if (code != null) {
                    val commitment = s.commitment ?: return drop("no commitment")
                    if (!Handoff.verifyCode(code, commitment, s.offer, author)) return drop("wrong handoff code")
                    proof = HandoffProof.CODE
                    proofKey = "code:$code"
                } else {
                    return drop("no handoff proof")
                }
                if (!usedProofs.add(proofKey)) return drop("handoff already used")
                move(TaskStatus.DELIVERED) { copy(proof = proof) }
            }
            StepType.PAID -> when {
                !isRequester -> drop("only the requester records payment")
                s.status != TaskStatus.DELIVERED -> drop("not delivered")
                s.requesterPaid -> drop("already recorded")
                else -> s.copy(requesterPaid = true, applied = s.applied + AppliedStep(e, type, s.status, s.status))
            }
            StepType.PAID_ACK -> when {
                !isRunner -> drop("only the runner confirms payment")
                s.status != TaskStatus.DELIVERED || !s.requesterPaid -> drop("no payment to confirm")
                else -> move(TaskStatus.PAID)
            }
            StepType.RATED -> {
                val stars = e.tagValue("rating")?.toIntOrNull()
                when {
                    !isRequester && !isRunner -> drop("only the two sides rate")
                    s.status != TaskStatus.DELIVERED && s.status != TaskStatus.PAID -> drop("not delivered")
                    stars == null || stars !in 1..5 -> drop("bad rating")
                    author in s.ratings -> drop("already rated")
                    else -> s.copy(ratings = s.ratings + (author to stars), applied = s.applied + AppliedStep(e, type, s.status, s.status))
                }
            }
            StepType.CANCEL -> when {
                !isRequester -> drop("only the requester cancels")
                s.status !in setOf(TaskStatus.OPEN, TaskStatus.ACCEPTED, TaskStatus.PICKED_UP) -> drop("can't cancel now")
                else -> move(TaskStatus.CANCELLED)
            }
            StepType.DISPUTE -> {
                val reason = e.tagValue("reason")
                when {
                    !isRequester && !isRunner -> drop("only the two sides dispute")
                    s.status !in setOf(TaskStatus.ACCEPTED, TaskStatus.PICKED_UP, TaskStatus.DELIVERED, TaskStatus.PAID) -> drop("nothing to dispute")
                    DisputeReason.entries.none { it.wire == reason } -> drop("unknown reason")
                    else -> move(TaskStatus.DISPUTED) { copy(disputeReason = reason) }
                }
            }
        }
    }
}

/** A person's standing, counted only from people the viewer trusts. */
data class Reputation(val done: Int, val ratingCount: Int, val averageRating: Double?)

object ReputationRules {
    /**
     * Keys within two steps of the viewer's verified contacts: the viewer, their verified contacts, and
     * those contacts' verified contacts ([verifiedLists]: owner pubkey → keys they verified in person).
     */
    fun trusted(viewer: String, verifiedLists: Map<String, Set<String>>): Set<String> {
        val first = verifiedLists[viewer].orEmpty()
        val second = first.flatMap { verifiedLists[it].orEmpty() }
        return setOf(viewer) + first + second
    }

    /** Tasks [subject] completed as runner and ratings they received, counting only trusted requesters/raters. */
    fun of(subject: String, states: List<TaskState>, trusted: Set<String>): Reputation {
        val asRunner = states.filter { it.runner == subject && it.done && it.requester in trusted }
        val ratings = states.flatMap { st ->
            st.ratings.filter { (rater, _) -> rater != subject && rater in trusted && (st.runner == subject || st.requester == subject) }.values
        }
        return Reputation(asRunner.size, ratings.size, if (ratings.isEmpty()) null else ratings.average())
    }

    fun mutualContacts(viewer: String, other: String, verifiedLists: Map<String, Set<String>>): Int =
        verifiedLists[viewer].orEmpty().intersect(verifiedLists[other].orEmpty()).size
}
