package io.github.tanzir71.harkara.core.sms

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip44
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.tasks.StepType
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import io.github.tanzir71.harkara.core.wire.SmsCodec

/**
 * Field limits that keep each SMS-eligible event inside two GSM-7 parts (checked by `SmsBudgetTest`
 * with every field at its limit, Bangla where Bangla is allowed). Builders of these events should clamp
 * to them; an event over budget still travels by every other rung, just not by SMS.
 */
object SmsLimits {
    /** Check-in place label, characters (Bangla allowed). */
    const val PLACE_LABEL = 40

    /** SOS or SOS-update note, characters (Bangla allowed). */
    const val NOTE = 60

    /** Check-in note ("reached, all fine"), characters (Bangla allowed), next to a place label and an expiry. */
    const val CHECK_IN_NOTE = 40

    /** Display name on a task accept, characters (the app's name limit). */
    const val NAME = 40

    /** bKash/Nagad TrxID, characters (the payment sheet's limit). */
    const val TRXID = 20

    /** Plaintext of an SMS DM, UTF-8 bytes: NIP-44 pads anything up to 32 bytes to one 99-byte payload. */
    const val DM_BYTES = 32

    /** Geohash precision for check-ins and SOS (~4.8 m × 4.8 m at 9). */
    const val GEOHASH = 9
}

/** Which events may travel by SMS, and what the receiver can be expected to know already. */
object SmsEligibility {
    /** Kinds that may ride the SMS rung. Gift wraps, offers, feed posts and pins are too big or not personal. */
    val KINDS: Set<Int> = setOf(
        Kinds.CHECK_IN, Kinds.SOS, Kinds.SOS_UPDATE, Kinds.IOU_SIGN, Kinds.SETTLE, Kinds.TASK_STEP, Kinds.SMS_DM,
    )

    /** Task steps worth texting: the ones a runner makes on the road, plus accept. */
    val TASK_STEPS: Set<StepType> = setOf(StepType.ACCEPT, StepType.PICKED_UP, StepType.DECLINE, StepType.DELIVERED)

    /** True when [event]'s kind (and, for task steps, its step and proof) may go by SMS. Size is checked separately. */
    fun isEligibleType(event: Event): Boolean = when (event.kind) {
        Kinds.TASK_STEP -> {
            val step = TaskSteps.stepOf(event)
            // Delivered only with the typed 6-digit code: a signed token is too big (a proof photo fails the size check).
            step in TASK_STEPS && (step != StepType.DELIVERED || (event.tagValue("code") != null && event.tagValue("token") == null))
        }
        Kinds.SMS_DM -> event.tagValues("p").size == 1
        else -> event.kind in KINDS
    }

    /**
     * 32-byte values the person receiving the SMS already knows: the `p` people (the recipient is one of
     * them), referenced events (`e`), IOU hashes (`h`) and the pubkey inside an `a` address. Sent as 8-byte
     * references only when the full event would not fit.
     */
    fun refsFor(event: Event): Set<String> = buildSet {
        event.tags.forEach { tag ->
            if (tag.size < 2) return@forEach
            when (tag[0]) {
                "p", "e", "h" -> if (Hex.isLowerHex(tag[1], 32)) add(tag[1])
                "a" -> tag[1].split(':').getOrNull(1)?.takeIf { Hex.isLowerHex(it, 32) }?.let(::add)
            }
        }
        remove(event.pubkey)
    }

    /** The SMS text for [event] if it is eligible and fits two parts, else null. */
    fun smsText(event: Event): String? = if (isEligibleType(event)) SmsCodec.textFitting(event, refsFor(event)) else null
}

/** Kind 3479: a DM short enough for the SMS rung, NIP-44 v2 encrypted to one recipient. */
object SmsDm {
    fun build(sender: KeyPair, recipient: String, text: String, createdAt: Long): Event {
        require(Hex.isLowerHex(recipient, 32)) { "recipient must be an x-only pubkey" }
        require(text.isNotEmpty() && text.encodeToByteArray().size <= SmsLimits.DM_BYTES) { "an SMS DM holds at most ${SmsLimits.DM_BYTES} bytes" }
        val content = Nip44.encrypt(text, Nip44.conversationKey(sender.privateKey, recipient))
        return sender.signEvent(Kinds.SMS_DM, listOf(listOf("p", recipient)), content, createdAt)
    }

    /** Opens a DM addressed to [me] (or sent by me); null when it isn't ours or doesn't decrypt. */
    fun open(event: Event, me: KeyPair): String? {
        if (event.kind != Kinds.SMS_DM || !event.verify()) return null
        val recipient = event.tagValue("p") ?: return null
        val other = when (me.pubkeyHex) {
            recipient -> event.pubkey
            event.pubkey -> recipient
            else -> return null
        }
        return runCatching { Nip44.decrypt(event.content, Nip44.conversationKey(me.privateKey, other)) }.getOrNull()
    }
}
