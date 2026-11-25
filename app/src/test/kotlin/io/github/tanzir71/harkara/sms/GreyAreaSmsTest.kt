package io.github.tanzir71.harkara.sms

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.sms.DataSmsFraming
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.sms.GatewayMember
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.core.sms.SmsDm
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.Module
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two grey-area SMS switches: off by default, inert while off, capped and member-only when on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GreyAreaSmsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: AppSettings
    private val sender = FakeSmsSender()
    private val delivered = mutableListOf<Pair<Event, Carrier>>()
    private val published = mutableListOf<Event>()
    private var clock = java.time.Instant.parse("2026-11-25T06:00:00Z").toEpochMilli()
    private val alice = KeyPair.generate()
    private val bob = KeyPair.generate()
    private var config = GatewayConfig(
        groupName = "Hall 3",
        ownNumber = "01711000999",
        members = listOf(GatewayMember("Rafi", "01711000111", alice.pubkeyHex), GatewayMember("Nabila", "+8801822000222", bob.pubkeyHex)),
    )

    class FakeSmsSender : SmsSender {
        val data = mutableListOf<Triple<String, Short, ByteArray>>()
        val texts = mutableListOf<Pair<String, String>>()
        override fun sendData(number: String, port: Short, data: ByteArray): Boolean = this.data.add(Triple(number, port, data))
        override fun sendText(number: String, text: String): Boolean = texts.add(number to text)
    }

    private fun grey(receivers: DataSmsReceiver = AndroidSmsReceivers(context), counter: GatewayCounter = PrefsGatewayCounter(settings)) = GreyAreaSms(
        isOn = { settings.state.value.isOn(it) },
        sender = sender,
        receivers = receivers,
        counter = counter,
        config = { config },
        resolver = { PrefixResolver.of(listOf(alice.pubkeyHex, bob.pubkeyHex)) },
        deliver = { e, via -> delivered += e to via },
        publish = { published += it },
        nowMillis = { clock },
    )

    private val sos get() = alice.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7")), "ছাদে আটকে আছি", clock / 1000)

    @Before
    fun setUp() {
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettings(context)
    }

    private fun component(cls: Class<*>) = ComponentName(context, cls)

    @Test
    fun `both grey-area switches default off and nothing SMS-related runs`() = runBlocking<Unit> {
        // Module defaults, a fresh install's settings, the manifest and the permissions.
        assertFalse(Module.SMS_DIRECT.defaultOn)
        assertFalse(Module.SMS_GATEWAY.defaultOn)
        assertFalse(settings.state.value.isOn(Module.SMS_DIRECT))
        assertFalse(settings.state.value.isOn(Module.SMS_GATEWAY))
        for (cls in listOf(DataSmsBroadcastReceiver::class.java, GatewaySmsBroadcastReceiver::class.java)) {
            val info = context.packageManager.getReceiverInfo(component(cls), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertFalse("${cls.simpleName} ships disabled", info.enabled)
            assertTrue(context.packageManager.getComponentEnabledSetting(component(cls)) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS))
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS))

        // The app's own wiring at start keeps both receivers off.
        (context as HarkaraApplication).container.greyAreaSms.sync()
        val receivers = AndroidSmsReceivers(context)
        assertFalse(receivers.isEnabled(DataSmsReceiver.Kind.DATA))
        assertFalse(receivers.isEnabled(DataSmsReceiver.Kind.TEXT))

        // While off, every entry point is inert: nothing sent, received, relayed or counted.
        val sms = grey()
        sms.sync()
        assertEquals(GreyAreaSms.Sent.Off, sms.sendDirect(sos, "01711000111"))
        val frame = DataSmsFraming.split(io.github.tanzir71.harkara.core.wire.SmsCodec.encode(sos), 1).first()
        assertNull(sms.onDataSms("01711000111", frame))
        assertEquals(GreyAreaSms.GatewayResult.OFF, sms.onTextSms("01711000111", SmsEligibility.smsText(sos)!!))
        assertEquals(GreyAreaSms.GatewayResult.OFF, sms.forwardToMember(SmsDm.build(bob, alice.pubkeyHex, "ok", clock / 1000)))
        assertTrue(sender.data.isEmpty() && sender.texts.isEmpty() && delivered.isEmpty() && published.isEmpty())
        assertEquals(0, sms.sentToday())
    }

    @Test
    fun `send directly frames the payload as data SMS on our port, and the other phone reads it back`() = runBlocking<Unit> {
        settings.setModule(Module.SMS_DIRECT, true)
        val sms = grey()
        sms.sync()
        assertTrue(AndroidSmsReceivers(context).isEnabled(DataSmsReceiver.Kind.DATA))
        assertFalse(AndroidSmsReceivers(context).isEnabled(DataSmsReceiver.Kind.TEXT))

        val event = sos
        val sent = sms.sendDirect(event, "01711000111") as GreyAreaSms.Sent.Ok
        assertEquals(sender.data.size, sent.frames)
        assertTrue(sender.data.all { it.first == "01711000111" && it.second == DataSmsFraming.PORT && it.third.size <= DataSmsFraming.MAX_FRAME })
        val received = sender.data.reversed().map { sms.onDataSms("+8801711000111", it.third) }
        assertEquals(event, received.last())
        assertEquals(listOf(event to Carrier.SMS), delivered)
        assertEquals(GreyAreaSms.Sent.NotEligible, sms.sendDirect(alice.signEvent(Kinds.FEED_POST, emptyList(), "hi", 1), "01711000111"))

        settings.setModule(Module.SMS_DIRECT, false)
        sms.sync()
        assertFalse(AndroidSmsReceivers(context).isEnabled(DataSmsReceiver.Kind.DATA))
    }

    @Test
    fun `the gateway relays only for members and stops at 100 a day`() = runBlocking<Unit> {
        settings.setModule(Module.SMS_GATEWAY, true)
        val counter = PrefsGatewayCounter(settings)
        val sms = grey(counter = counter)
        sms.sync()
        assertTrue(AndroidSmsReceivers(context).isEnabled(DataSmsReceiver.Kind.TEXT))

        val text = SmsEligibility.smsText(sos)!!
        assertEquals(GreyAreaSms.GatewayResult.NOT_A_MEMBER, sms.onTextSms("01999000999", text))
        assertEquals(GreyAreaSms.GatewayResult.NO_PAYLOAD, sms.onTextSms("01711000111", "are you coming?"))
        assertEquals(GreyAreaSms.GatewayResult.RELAYED, sms.onTextSms("+880 1711-000111", text))
        assertEquals(GreyAreaSms.GatewayResult.DUPLICATE, sms.onTextSms("01711000111", text))
        assertEquals(1, published.size)
        assertEquals(1, sms.sentToday())

        // Out: an event for a member (p tag) is texted to their number, once.
        val dm = SmsDm.build(alice, bob.pubkeyHex, "ami asi", clock / 1000)
        assertEquals(GreyAreaSms.GatewayResult.RELAYED, sms.forwardToMember(dm))
        assertEquals(GreyAreaSms.GatewayResult.DUPLICATE, sms.forwardToMember(dm))
        assertEquals("+8801822000222", sender.texts.single().first)
        assertTrue(sender.texts.single().second.startsWith("Harkara msg HK1:"))
        assertEquals(GreyAreaSms.GatewayResult.NOT_A_MEMBER, sms.forwardToMember(SmsDm.build(alice, KeyPair.generate().pubkeyHex, "x", clock / 1000)))
        assertEquals(2, sms.sentToday())

        // The cap counts in and out together, per Dhaka day.
        repeat(98) { counter.add(GatewayPolicyDay.of(clock)) }
        assertEquals(100, sms.sentToday())
        val another = alice.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dk")), "home", clock / 1000 + 5)
        assertEquals(GreyAreaSms.GatewayResult.CAP_REACHED, sms.onTextSms("01711000111", SmsEligibility.smsText(another)!!))
        assertEquals(GreyAreaSms.GatewayResult.CAP_REACHED, sms.forwardToMember(SmsDm.build(alice, bob.pubkeyHex, "again", clock / 1000 + 9)))
        clock += 24 * 3600_000L
        assertEquals(0, sms.sentToday())
        assertEquals(GreyAreaSms.GatewayResult.RELAYED, sms.onTextSms("01711000111", SmsEligibility.smsText(another)!!))
    }

    /** The day key the gateway counter uses. */
    private object GatewayPolicyDay {
        fun of(millis: Long) = io.github.tanzir71.harkara.core.sms.GatewayPolicy().day(millis)
    }
}
