package io.github.tanzir71.harkara.links

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.MainActivity
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouBundle
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.core.wire.SmsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deep links with real Intents: what the manifest lets in and what the router makes of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinkRouterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keys = KeyPair.generate()
    private val checkIn = keys.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dkq7")), "Reached the hall", 1_795_586_400)

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun opens(intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.name == MainActivity::class.java.name }

    @Test
    fun `harkara and o page links reach MainActivity, other sites do not`() {
        assertTrue(opens(view("harkara:" + HarkaraLinks.eventRest(checkIn))))
        assertTrue(opens(view(HarkaraLinks.web(HarkaraLinks.eventRest(checkIn)))))
        assertTrue(opens(view("https://tanzir71.github.io/harkara/en/o/#g:wh0r0")))
        assertTrue(opens(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi")))
        assertTrue(!opens(view("https://tanzir71.github.io/harkara/")))
        assertTrue(!opens(view("https://example.com/harkara/o/#e:abc")))
    }

    @Test
    fun `event, confirm, IOU, handoff and SMS links parse`() {
        assertEquals(HarkaraLink.EventLink(checkIn), DeepLinkRouter.parse(view("harkara:" + HarkaraLinks.eventRest(checkIn))))
        assertEquals(HarkaraLink.EventLink(checkIn), DeepLinkRouter.parse(view(QrTransport.encode(checkIn).single())))

        val code = IouConfirm.code(keys, "iou-1")
        val terms = IouTerms("iou-1", Party("Tania", keys.pubkeyHex), Party("Karim"), 500, createdAt = 1_795_586_400, confirmCodeHash = Iou.codeHash(code))
        val sig = Iou.sign(keys, terms, 1_795_586_400)
        val confirm = DeepLinkRouter.parse(view(HarkaraLinks.web(IouConfirm.PREFIX + IouConfirm.encode(terms, sig, code))))
        assertEquals(terms, (confirm as HarkaraLink.IouConfirm).link.terms)

        assertTrue(DeepLinkRouter.parse(view(Iou.toQr(IouBundle(terms, listOf(sig.toJson()))))) is HarkaraLink.IouBundle)
        assertTrue(DeepLinkRouter.parse(view("harkara:handoff:" + checkIn.toJson())) is HarkaraLink.Handoff)

        val forwarded = "Fwd: " + SmsCodec.text(checkIn)
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, forwarded)
        assertEquals(HarkaraLink.Sms(forwarded), DeepLinkRouter.parse(shared))
    }

    @Test
    fun `junk and unrelated intents are ignored`() {
        assertNull(DeepLinkRouter.parse(view("harkara:e:not-an-event")))
        assertNull(DeepLinkRouter.parse(view("https://tanzir71.github.io/harkara/o/")))
        assertNull(DeepLinkRouter.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(DeepLinkRouter.parse(null))
        assertNull(DeepLinkRouter.parse(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "see you at 5")))
    }

    @Test
    fun `MainActivity hands links to the app, on start and on a new intent`() {
        val container = (context as HarkaraApplication).container
        container.pendingLinks.value = null
        val controller = Robolectric.buildActivity(MainActivity::class.java, view("harkara:" + HarkaraLinks.eventRest(checkIn))).create()
        assertEquals(HarkaraLink.EventLink(checkIn), container.pendingLinks.value)

        container.pendingLinks.value = null
        controller.newIntent(view("https://tanzir71.github.io/harkara/o/#g:wh0r0"))
        assertEquals(HarkaraLink.Area("wh0r0"), container.pendingLinks.value)
        controller.destroy()
    }
}
