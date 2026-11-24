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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.tanzir71.harkara.settings.AppSettings
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(withLanguageOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HarkaraApplication).container
        setContent { HarkaraApp(container, this) }
    }

    override fun onStart() {
        super.onStart()
        // At most once a day, and only while the app is open and online (§5.5).
        if (isOnline()) {
            lifecycleScope.launch { (application as HarkaraApplication).container.updateChecker.checkIfDue() }
        }
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

    companion object {
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
