package io.github.tanzir71.harkara.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import io.github.tanzir71.harkara.HarkaraApplication
import kotlinx.coroutines.launch

/** [SmsSender] over the SIM. Only works after the user turned a grey-area switch on and granted SEND_SMS. */
class AndroidSmsSender(private val context: Context) : SmsSender {
    private fun manager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override fun sendData(number: String, port: Short, data: ByteArray): Boolean {
        if (!allowed()) return false
        val m = manager() ?: return false
        return runCatching { m.sendDataMessage(number, null, port, data, null, null) }.isSuccess
    }

    override fun sendText(number: String, text: String): Boolean {
        if (!allowed()) return false
        val m = manager() ?: return false
        return runCatching { m.sendMultipartTextMessage(number, null, m.divideMessage(text), null, null) }.isSuccess
    }
}

/** Turns the two SMS receivers (disabled in the manifest) on and off as components. */
class AndroidSmsReceivers(private val context: Context) : DataSmsReceiver {
    private fun component(kind: DataSmsReceiver.Kind) = ComponentName(
        context,
        when (kind) {
            DataSmsReceiver.Kind.DATA -> DataSmsBroadcastReceiver::class.java
            DataSmsReceiver.Kind.TEXT -> GatewaySmsBroadcastReceiver::class.java
        },
    )

    override fun setEnabled(kind: DataSmsReceiver.Kind, enabled: Boolean) {
        if (isEnabled(kind) == enabled) return
        context.packageManager.setComponentEnabledSetting(
            component(kind),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** The manifest default is disabled, so "default" counts as off. */
    override fun isEnabled(kind: DataSmsReceiver.Kind): Boolean =
        context.packageManager.getComponentEnabledSetting(component(kind)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}

/** Data SMS on our port ("Send SMS directly"). Enabled only while that switch is on. */
class DataSmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val container = (context.applicationContext as HarkaraApplication).container
        val pending = goAsync()
        container.scope.launch {
            try {
                messages.forEach { m -> m.userData?.let { container.greyAreaSms.onDataSms(m.originatingAddress.orEmpty(), it) } }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Text SMS for the volunteer gateway. Enabled only while that switch is on; non-members are ignored. */
class GatewaySmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives as several parts in one broadcast: join them per sender.
        val bySender = messages.groupBy { it.originatingAddress.orEmpty() }.mapValues { (_, parts) -> parts.joinToString("") { it.messageBody.orEmpty() } }
        val container = (context.applicationContext as HarkaraApplication).container
        val pending = goAsync()
        container.scope.launch {
            try {
                bySender.forEach { (from, body) -> container.greyAreaSms.onTextSms(from, body) }
            } finally {
                pending.finish()
            }
        }
    }
}
