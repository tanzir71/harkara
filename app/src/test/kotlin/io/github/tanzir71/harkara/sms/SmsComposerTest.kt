package io.github.tanzir71.harkara.sms

import android.content.Intent
import android.net.Uri
import io.github.tanzir71.harkara.MainActivity
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.Gsm7
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.core.wire.SmsCodec
import io.github.tanzir71.harkara.ui.PlatformActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Send by SMS" opens the user's SMS app prefilled (ACTION_SENDTO smsto:, sms_body); nothing is ever sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsComposerTest {
    private val keys = KeyPair.generate()
    private val sos = keys.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7"), listOf("place", "Mirpur 10")), "পানি বাড়ছে, ছাদে আটকে আছি", 1_795_586_400)

    private class Recording : PlatformActions by PlatformActions.None {
        val sms = mutableListOf<String>()
        override fun composeSms(body: String) {
            sms += body
        }
    }

    @Test
    fun `an SOS becomes a two-part GSM-7 SMS that the receiver verifies`() {
        val draft = SmsComposer.draft(sos) as SmsComposer.Draft.Ready
        assertTrue(draft.text.startsWith("Harkara SOS HK1:"))
        assertTrue(Gsm7.isGsm7(draft.text))
        assertTrue(draft.parts <= 2)
        assertEquals(Gsm7.parts(draft.text), draft.parts)
        assertEquals(sos, SmsCodec.decodeText(draft.text, PrefixResolver.of(listOf(keys.pubkeyHex))))
    }

    @Test
    fun `send opens the SMS app only for eligible events that fit`() {
        val platform = Recording()
        SmsComposer.send(sos, platform)
        assertEquals(1, platform.sms.size)
        assertTrue(platform.sms.single().contains("HK1:"))

        val post = keys.signEvent(Kinds.FEED_POST, listOf(listOf("g", "wh0r0d")), "Traffic at Science Lab", 1_795_586_400)
        assertEquals(SmsComposer.Draft.NotEligible, SmsComposer.send(post, platform))
        val longSos = keys.signEvent(Kinds.SOS, emptyList(), "x".repeat(600), 1_795_586_400)
        assertTrue(SmsComposer.send(longSos, platform) is SmsComposer.Draft.TooLong)
        assertEquals(1, platform.sms.size)
    }

    @Test
    fun `the compose intent is ACTION_SENDTO smsto with the body and no recipient`() {
        val intent = SmsIntents.compose("Harkara SOS HK1:abc")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("smsto:"), intent.data)
        assertEquals("Harkara SOS HK1:abc", intent.getStringExtra("sms_body"))
        assertEquals("smsto:01711000111", SmsIntents.compose("x", "01711000111").dataString)
    }

    @Test
    fun `MainActivity starts the SMS app intent and never sends anything itself`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val text = (SmsComposer.draft(sos) as SmsComposer.Draft.Ready).text
        activity.composeSms(text)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto", started.data?.scheme)
        assertEquals("", started.data?.schemeSpecificPart)
        assertEquals(text, started.getStringExtra(SmsIntents.EXTRA_SMS_BODY))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
