package io.github.tanzir71.harkara.sms

import android.content.Intent
import android.net.Uri
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.core.wire.Gsm7
import io.github.tanzir71.harkara.core.wire.SmsCodec
import io.github.tanzir71.harkara.ui.PlatformActions

/**
 * The default SMS rung's intent: the SMS app opens with [body] prefilled and no recipient (or a volunteer
 * gateway's [number] the user scanned). Needs no permission; the user presses send.
 */
object SmsIntents {
    const val EXTRA_SMS_BODY = "sms_body"

    fun compose(body: String, number: String = ""): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra(EXTRA_SMS_BODY, body)
}

/**
 * "Send by SMS" for any SMS-eligible event (check-in, SOS, SOS update, IOU signature, settlement, task steps,
 * SMS DM): the readable Latin line + HK1 payload, opened in the user's own SMS app. The user picks the
 * recipient and presses send, so no number is ever stored and no permission is needed.
 */
object SmsComposer {
    sealed interface Draft {
        /** Ready to send: [text] fits two GSM-7 parts. */
        data class Ready(val text: String, val parts: Int) : Draft

        /** The kind may not go by SMS (gift wraps, offers, posts…): use QR or wait for another rung. */
        data object NotEligible : Draft

        /** Eligible, but over two parts even with references: use QR. */
        data class TooLong(val chars: Int) : Draft
    }

    fun draft(event: Event): Draft {
        if (!SmsEligibility.isEligibleType(event)) return Draft.NotEligible
        val text = SmsEligibility.smsText(event) ?: return Draft.TooLong(SmsCodec.text(event, SmsEligibility.refsFor(event)).length)
        return Draft.Ready(text, Gsm7.parts(text))
    }

    /** Opens the SMS app with [event]'s SMS when it fits; returns the draft either way. Hook for SOS/check-in flows. */
    fun send(event: Event, platform: PlatformActions): Draft = draft(event).also { if (it is Draft.Ready) platform.composeSms(it.text) }
}
