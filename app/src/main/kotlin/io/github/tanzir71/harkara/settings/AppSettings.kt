package io.github.tanzir71.harkara.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app language override (§5.4): follow the phone, or force English/Bangla. */
enum class AppLanguage(val tag: String?) { SYSTEM(null), EN("en"), BN("bn") }

/** Mesh duty when the mesh module is on. */
enum class MeshMode { SAVER, EVENT }

/** Everything the UI needs from settings, as one immutable snapshot. */
data class SettingsState(
    val onboarded: Boolean = false,
    val areaName: String? = null,
    val areaAuto: Boolean = false,
    /** Centre of the chosen area (or the location fix); null until known. */
    val areaLat: Double? = null,
    val areaLng: Double? = null,
    /** User-edited relay list; null means the bundled defaults. */
    val relays: List<String>? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val modules: Map<Module, Boolean> = Module.entries.associateWith { it.defaultOn },
    val meshMode: MeshMode = MeshMode.SAVER,
    val dismissedUpdateCode: Int = 0,
    /** Name shown on tasks and IOUs; asked only when first needed. Never a phone number. */
    val displayName: String? = null,
) {
    fun isOn(module: Module): Boolean = modules[module] ?: module.defaultOn
}

/** Settings persisted in SharedPreferences and exposed as a [StateFlow]. Never leaves the phone. */
class AppSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    fun completeOnboarding(areaName: String?, auto: Boolean, lat: Double? = null, lng: Double? = null) = update {
        putBoolean(KEY_ONBOARDED, true)
        putArea(areaName, auto, lat, lng)
    }

    fun setArea(areaName: String?, auto: Boolean, lat: Double? = null, lng: Double? = null) = update { putArea(areaName, auto, lat, lng) }

    private fun SharedPreferences.Editor.putArea(areaName: String?, auto: Boolean, lat: Double?, lng: Double?) {
        putString(KEY_AREA, areaName)
        putBoolean(KEY_AREA_AUTO, auto)
        if (lat != null && lng != null) {
            putString(KEY_AREA_LAT, lat.toString())
            putString(KEY_AREA_LNG, lng.toString())
        } else {
            remove(KEY_AREA_LAT)
            remove(KEY_AREA_LNG)
        }
    }

    /** Saves the relay list; null restores the defaults. */
    fun setRelays(relays: List<String>?) = update {
        if (relays == null) remove(KEY_RELAYS) else putString(KEY_RELAYS, relays.joinToString("\n"))
    }

    fun setModule(module: Module, on: Boolean) = update { putBoolean(MODULE_PREFIX + module.key, on) }

    fun setLanguage(language: AppLanguage) = update { putString(KEY_LANGUAGE, language.name) }

    fun setMeshMode(mode: MeshMode) = update { putString(KEY_MESH_MODE, mode.name) }

    fun dismissUpdate(versionCode: Int) = update { putInt(KEY_DISMISSED_UPDATE, versionCode) }

    fun setDisplayName(name: String) = update { putString(KEY_DISPLAY_NAME, name.trim().take(40)) }

    /** Raw access for small internal timestamps (e.g. last update check). */
    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)

    fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }

    /** Removes every setting (panic wipe). */
    fun clear() = update { clear() }

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = block)
        mutableState.value = read()
    }

    private fun read(): SettingsState = SettingsState(
        onboarded = prefs.getBoolean(KEY_ONBOARDED, false),
        areaName = prefs.getString(KEY_AREA, null),
        areaAuto = prefs.getBoolean(KEY_AREA_AUTO, false),
        areaLat = prefs.getString(KEY_AREA_LAT, null)?.toDoubleOrNull(),
        areaLng = prefs.getString(KEY_AREA_LNG, null)?.toDoubleOrNull(),
        relays = prefs.getString(KEY_RELAYS, null)?.split('\n')?.filter { it.isNotBlank() },
        language = prefs.getString(KEY_LANGUAGE, null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.SYSTEM,
        modules = Module.entries.associateWith { prefs.getBoolean(MODULE_PREFIX + it.key, it.defaultOn) },
        meshMode = prefs.getString(KEY_MESH_MODE, null)?.let { runCatching { MeshMode.valueOf(it) }.getOrNull() } ?: MeshMode.SAVER,
        dismissedUpdateCode = prefs.getInt(KEY_DISMISSED_UPDATE, 0),
        displayName = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() },
    )

    companion object {
        const val PREFS_NAME = "harkara_settings"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_AREA = "area"
        private const val KEY_AREA_AUTO = "area_auto"
        private const val KEY_AREA_LAT = "area_lat"
        private const val KEY_AREA_LNG = "area_lng"
        private const val KEY_RELAYS = "relays"
        const val KEY_LANGUAGE = "language"
        private const val KEY_MESH_MODE = "mesh_mode"
        private const val KEY_DISMISSED_UPDATE = "dismissed_update"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val MODULE_PREFIX = "module."

        /** Reads only the language, for `attachBaseContext` before the app container exists. */
        fun readLanguage(context: Context): AppLanguage =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
                ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.SYSTEM
    }
}
