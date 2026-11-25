package io.github.tanzir71.harkara.sms

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.sms.DataSmsAssembler
import io.github.tanzir71.harkara.core.sms.DataSmsFraming
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.sms.GatewayPolicy
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.core.wire.SmsCodec
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.LocalSecretEntity
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.Module
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Sends SMS through the SIM (grey area: needs SEND_SMS and spends the user's credit). Faked in tests. */
interface SmsSender {
    /** A binary SMS to [port] on [number]. */
    fun sendData(number: String, port: Short, data: ByteArray): Boolean

    /** A text SMS; the phone splits it into parts. */
    fun sendText(number: String, text: String): Boolean
}

/**
 * The broadcast receivers for incoming SMS (data SMS on our port for "Send SMS directly", text SMS for the
 * volunteer gateway). They are disabled manifest components until their switch is turned on, so nothing
 * SMS-related wakes the app while the switches are off. Faked in tests.
 */
interface DataSmsReceiver {
    enum class Kind { DATA, TEXT }

    fun setEnabled(kind: Kind, enabled: Boolean)

    fun isEnabled(kind: Kind): Boolean
}

/** How many SMS the volunteer gateway relayed on a given Dhaka day. */
interface GatewayCounter {
    fun sentOn(day: String): Int

    fun add(day: String)
}

/** [GatewayCounter] in the app's settings (only the current day is kept). */
class PrefsGatewayCounter(private val settings: AppSettings) : GatewayCounter {
    override fun sentOn(day: String): Int = if (settings.getString(KEY_DAY) == day) settings.getLong(KEY_COUNT).toInt() else 0

    override fun add(day: String) {
        val next = sentOn(day) + 1
        settings.putString(KEY_DAY, day)
        settings.putLong(KEY_COUNT, next.toLong())
    }

    companion object {
        private const val KEY_DAY = "sms_gateway_day"
        private const val KEY_COUNT = "sms_gateway_count"
    }
}

/** The volunteer's member list, kept in the encrypted database (phone numbers never leave the phone). */
class GatewayStore(private val db: HarkaraDatabase) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(): GatewayConfig =
        db.localSecretDao().get(KEY)?.let { runCatching { json.decodeFromString(GatewayConfig.serializer(), it) }.getOrNull() } ?: GatewayConfig()

    suspend fun set(config: GatewayConfig) = db.localSecretDao().put(LocalSecretEntity(KEY, json.encodeToString(GatewayConfig.serializer(), config), System.currentTimeMillis()))

    companion object {
        const val KEY = "sms-gateway"
    }
}

/**
 * The two grey-area SMS features (handover §5.6), both off by default and inert while off:
 *
 * - **Send SMS directly**: an eligible event's payload as binary data SMS on [DataSmsFraming.PORT]
 *   (`SmsManager.sendDataMessage`, spends credit), and incoming data SMS decoded with this phone's known keys.
 * - **Volunteer SMS gateway**: text SMS from the volunteer's members (contacts or one named group) are
 *   checked and published to relays; events for a member are texted to them; at most 100 a day in total.
 */
class GreyAreaSms(
    private val isOn: (Module) -> Boolean,
    private val sender: SmsSender,
    private val receivers: DataSmsReceiver,
    private val counter: GatewayCounter,
    private val config: suspend () -> GatewayConfig,
    private val resolver: () -> PrefixResolver,
    private val deliver: suspend (Event, Carrier) -> Unit,
    private val publish: suspend (Event) -> Unit,
    private val policy: GatewayPolicy = GatewayPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val assembler = DataSmsAssembler(nowMillis)

    sealed interface Sent {
        data class Ok(val frames: Int) : Sent
        data object Off : Sent
        data object NotEligible : Sent
        data object TooLong : Sent
        data object Failed : Sent
    }

    enum class GatewayResult { OFF, NOT_A_MEMBER, CAP_REACHED, NO_PAYLOAD, UNVERIFIED, RELAYED, FAILED, DUPLICATE }

    /** Events already relayed (several relays deliver the same event; one SMS each is enough). */
    private val relayed = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 1000
    }

    private fun firstTime(id: String): Boolean = synchronized(relayed) { relayed.put(id, Unit) == null }

    /** Receivers are enabled exactly when their switch is on. Call at start and after every switch change. */
    fun sync() {
        receivers.setEnabled(DataSmsReceiver.Kind.DATA, isOn(Module.SMS_DIRECT))
        receivers.setEnabled(DataSmsReceiver.Kind.TEXT, isOn(Module.SMS_GATEWAY))
    }

    /** "Send SMS directly": the event's payload as data SMS frames to [number]. */
    fun sendDirect(event: Event, number: String): Sent {
        if (!isOn(Module.SMS_DIRECT)) return Sent.Off
        if (!SmsEligibility.isEligibleType(event)) return Sent.NotEligible
        val text = SmsEligibility.smsText(event) ?: return Sent.TooLong
        val payload = SmsCodec.extract(text) ?: return Sent.Failed
        val frames = DataSmsFraming.split(payload, random.nextInt(256))
        for (frame in frames) if (!sender.sendData(number, DataSmsFraming.PORT, frame)) return Sent.Failed
        return Sent.Ok(frames.size)
    }

    /** One incoming data SMS frame; returns the event once a whole payload arrived and verified. */
    suspend fun onDataSms(from: String, frame: ByteArray): Event? {
        if (!isOn(Module.SMS_DIRECT)) return null
        val payload = assembler.add(from, frame) ?: return null
        val event = runCatching { SmsCodec.decode(payload, resolver()) }.getOrNull() ?: return null
        deliver(event, Carrier.SMS)
        return event
    }

    /** A text SMS that reached the volunteer's phone: relayed only for members, within the daily cap. */
    suspend fun onTextSms(from: String, body: String): GatewayResult {
        if (!isOn(Module.SMS_GATEWAY)) return GatewayResult.OFF
        if (!SmsCodec.looksLikeSms(body)) return GatewayResult.NO_PAYLOAD
        val cfg = config()
        val day = policy.day(nowMillis())
        when (policy.decide(cfg, from, counter.sentOn(day))) {
            GatewayPolicy.Decision.NOT_A_MEMBER -> return GatewayResult.NOT_A_MEMBER
            GatewayPolicy.Decision.CAP_REACHED -> return GatewayResult.CAP_REACHED
            GatewayPolicy.Decision.ALLOW -> Unit
        }
        val event = SmsCodec.decodeText(body, withMembers(cfg)) ?: return GatewayResult.UNVERIFIED
        if (!firstTime(event.id)) return GatewayResult.DUPLICATE
        counter.add(day)
        deliver(event, Carrier.SMS)
        publish(event)
        return GatewayResult.RELAYED
    }

    /** An event from the relays addressed (`p`) to a member: texted to them, within the daily cap. */
    suspend fun forwardToMember(event: Event): GatewayResult {
        if (!isOn(Module.SMS_GATEWAY)) return GatewayResult.OFF
        val cfg = config()
        val member = event.tagValues("p").firstNotNullOfOrNull { policy.memberForKey(cfg, it) } ?: return GatewayResult.NOT_A_MEMBER
        val day = policy.day(nowMillis())
        when (policy.decide(cfg, member.number, counter.sentOn(day))) {
            GatewayPolicy.Decision.NOT_A_MEMBER -> return GatewayResult.NOT_A_MEMBER
            GatewayPolicy.Decision.CAP_REACHED -> return GatewayResult.CAP_REACHED
            GatewayPolicy.Decision.ALLOW -> Unit
        }
        val text = SmsEligibility.smsText(event) ?: return GatewayResult.NO_PAYLOAD
        if (!firstTime(event.id)) return GatewayResult.DUPLICATE
        if (!sender.sendText(member.number, text)) return GatewayResult.FAILED
        counter.add(day)
        return GatewayResult.RELAYED
    }

    /** SMS relayed today (in and out). */
    fun sentToday(): Int = counter.sentOn(policy.day(nowMillis()))

    private fun withMembers(cfg: GatewayConfig): PrefixResolver {
        val base = resolver()
        val keys = cfg.members.mapNotNull { it.pubkey }
        return PrefixResolver { prefix ->
            val p = Hex.encode(prefix)
            (base.resolve(prefix) + keys.filter { it.startsWith(p) }).distinct()
        }
    }
}
