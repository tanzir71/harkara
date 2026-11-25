package io.github.tanzir71.harkara

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.tanzir71.harkara.links.DeepLinkRouter
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.sms.SmsIntents
import io.github.tanzir71.harkara.ui.HarkaraApp
import io.github.tanzir71.harkara.ui.PlatformActions
import kotlinx.coroutines.launch
import java.util.Locale

/** Hosts the Compose app and implements the few Android-only actions it needs. */
class MainActivity : ComponentActivity(), PlatformActions {
    private var pendingLocation: ((Double?, Double?) -> Unit)? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = pendingLocation ?: return@registerForActivityResult
        pendingLocation = null
        if (granted) deliverLastKnownLocation(callback) else callback(null, null)
    }

    private var pendingSms: ((Boolean) -> Unit)? = null

    /** SMS permissions are requested only when the user turns a grey-area switch on (M10). */
    private val smsPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val callback = pendingSms ?: return@registerForActivityResult
        pendingSms = null
        callback(result.isNotEmpty() && result.values.all { it })
    }

    private var pendingMicrophone: ((Boolean) -> Unit)? = null
    private var pendingMesh: ((Boolean) -> Unit)? = null
    private val meshPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingMesh?.invoke(io.github.tanzir71.harkara.mesh.MeshPermissions.granted(this))
        pendingMesh = null
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicrophone?.invoke(granted)
        pendingMicrophone = null
    }

    private var pendingSave: Pair<String, (Boolean) -> Unit>? = null
    private var pendingOpen: ((String?) -> Unit)? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val (text, callback) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult callback(false)
        val saved = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        callback(saved)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        if (uri == null) return@registerForActivityResult callback(null)
        // Backups are a few hundred bytes; refuse to read anything big.
        val text = runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                val bytes = input.readNBytesCompat(MAX_OPEN_BYTES + 1)
                if (bytes.size > MAX_OPEN_BYTES) null else bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull()
        callback(text)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(withLanguageOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HarkaraApplication).container
        setContent { HarkaraApp(container, this) }
        if (savedInstanceState == null) handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** harkara: and …/harkara/o/# links, and text shared to the app (a forwarded SMS, a QR text). */
    private fun handleLink(intent: Intent?) {
        val link = DeepLinkRouter.parse(intent) ?: return
        (application as HarkaraApplication).container.pendingLinks.value = link
    }

    override fun onStart() {
        super.onStart()
        val container = (application as HarkaraApplication).container
        container.foreground = true
        // Relays and the Wi-Fi room run only while the app is visible (or during a WorkManager sync): battery rule, §4.
        if (container.settings.state.value.onboarded) {
            container.startRelays()
            container.startLan()
        }
        // At most once a day, and only while the app is open and online (§5.5).
        if (isOnline()) {
            lifecycleScope.launch { (application as HarkaraApplication).container.updateChecker.checkIfDue() }
        }
    }

    override fun onStop() {
        super.onStop()
        val container = (application as HarkaraApplication).container
        container.foreground = false
        if (!container.mesh.running && !container.node.node.value) container.stopRelays()
        container.stopLan()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    override fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            deliverLastKnownLocation(onResult)
        } else {
            pendingLocation = onResult
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    override fun requestMicrophone(onResult: (granted: Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            pendingMicrophone = onResult
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun requestMesh(onResult: (Boolean) -> Unit) {
        if (io.github.tanzir71.harkara.mesh.MeshPermissions.granted(this)) onResult(true)
        else { pendingMesh = onResult; meshPermissions.launch(io.github.tanzir71.harkara.mesh.MeshPermissions.required()) }
    }

    /** One-off, user-started read of the last fix the phone already has. No GPS is switched on. */
    @Suppress("MissingPermission")
    private fun deliverLastKnownLocation(onResult: (Double?, Double?) -> Unit) {
        val lm = getSystemService(LocationManager::class.java)
        val fix = runCatching {
            lm?.getProviders(true)?.mapNotNull { lm.getLastKnownLocation(it) }?.maxByOrNull { it.time }
        }.getOrNull()
        onResult(fix?.latitude, fix?.longitude)
    }

    override fun applyLanguage() {
        recreate()
    }

    override fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit) {
        pendingSave = text to onResult
        runCatching { createDocument.launch(suggestedName) }.onFailure {
            pendingSave = null
            onResult(false)
        }
    }

    override fun composeSms(body: String) {
        runCatching { startActivity(SmsIntents.compose(body)) }
    }

    override fun composeSmsTo(number: String, body: String) {
        runCatching { startActivity(SmsIntents.compose(body, number)) }
    }

    override fun requestSmsPermissions(onResult: (granted: Boolean) -> Unit) {
        val wanted = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        if (wanted.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) return onResult(true)
        pendingSms = onResult
        runCatching { smsPermissions.launch(wanted) }.onFailure {
            pendingSms = null
            onResult(false)
        }
    }

    override fun openAppInfo() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
    }

    override fun openTextDocument(onResult: (text: String?) -> Unit) {
        pendingOpen = onResult
        runCatching { openDocument.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }.onFailure {
            pendingOpen = null
            onResult(null)
        }
    }

    companion object {
        private const val MAX_OPEN_BYTES = 16 * 1024

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** Wraps [base] with the in-app language override, or returns it unchanged for "phone default". */
        fun withLanguageOverride(base: Context): Context {
            val tag = AppSettings.readLanguage(base).tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
