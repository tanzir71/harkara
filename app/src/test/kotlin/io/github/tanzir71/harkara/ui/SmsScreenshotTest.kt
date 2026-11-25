package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.sms.GatewayMember
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.sms.SmsComposer
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.screens.EventQrSheetContent
import io.github.tanzir71.harkara.ui.screens.GreyAreaSheetContent
import io.github.tanzir71.harkara.ui.screens.RestrictedSettingsScreen
import io.github.tanzir71.harkara.ui.screens.SendSmsSheetContent
import io.github.tanzir71.harkara.ui.screens.SmsGatewayScreen
import io.github.tanzir71.harkara.ui.screens.PasteLinkSheetContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M10 sheets and screens: send by SMS, share as QR, the grey-area prompt, restricted settings, the gateway. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SmsScreenshotTest {
    private val keys = KeyPair.fromHex("21".repeat(32))

    /** Signed with fixed auxiliary randomness so the SMS text and QR codes are the same on every run. */
    private fun signed(kind: Int, tags: List<List<String>>, content: String): Event {
        val u = UnsignedEvent(keys.pubkeyHex, 1_795_586_400, kind, tags, content)
        return Event(u.id, u.pubkey, u.createdAt, kind, tags, content, Hex.encode(keys.sign(Hex.decode(u.id), ByteArray(32))))
    }

    private val sos = signed(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7"), listOf("place", "Mirpur 10")), "পানি বাড়ছে, ছাদে আটকে আছি")

    @Test
    fun scanOrPaste() = captureDayNight("screen-qr-import", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { PasteLinkSheetContent({}) }
        }
    }

    @Test
    fun sendBySms() = captureDayNight("screen-sms-send", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                SendSmsSheetContent(SmsComposer.draft(sos), {}, {}, directOn = true, gateway = GatewayCard("Nabila", "01822000222"))
            }
        }
    }

    @Test
    fun shareAsQr() = captureDayNight("screen-sms-qr", padded = false) {
        val long = signed(Kinds.FEED_POST, listOf(listOf("g", "wh0r0d")), (1..40).joinToString(" ") { "%08x".format(it * 2_654_435_761L % 4_294_967_296L) })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { EventQrSheetContent(QrTransport.encode(long, maxChars = 300), {}) }
        }
    }

    @Test
    fun greyAreaPrompt() = captureDayNight("screen-sms-grey", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { GreyAreaSheetContent(Module.SMS_GATEWAY, {}, {}) }
        }
    }

    @Test
    fun restrictedSettings() = captureDayNight("screen-sms-restricted", padded = false) {
        RestrictedSettingsScreen({}, {})
    }

    @Test
    fun gateway() = captureDayNight("screen-sms-gateway", padded = false) {
        val config = GatewayConfig(
            "Hall 3, floor 2", "01711000999",
            listOf(GatewayMember("Rafi Hasan", "01711000111"), GatewayMember("Nabila", "01822000222", "ab".repeat(32))),
        )
        SmsGatewayScreen(config, 12, {}, {}, {})
    }
}
