package io.github.tanzir71.harkara.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import io.github.tanzir71.harkara.core.tools.Morse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The camera flash as a torch. Only touched while the torch screen is visible. */
interface Torch {
    val available: Boolean
    fun set(on: Boolean)
}

/** A loud tone for the siren and alerts. */
interface Siren {
    fun beep(ms: Int)
    fun stop()
}

class CameraTorch(context: Context) : Torch {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = runCatching {
        manager?.cameraIdList?.firstOrNull { id -> manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    override val available: Boolean get() = cameraId != null

    override fun set(on: Boolean) {
        val id = cameraId ?: return
        runCatching { manager?.setTorchMode(id, on) }
    }
}

class ToneSiren : Siren {
    private var tone: ToneGenerator? = null

    override fun beep(ms: Int) {
        val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()?.also { tone = it } ?: return
        t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, ms)
    }

    override fun stop() {
        tone?.stopTone()
        tone?.release()
        tone = null
    }
}

enum class LightMode { OFF, TORCH, STROBE, MORSE_SOS, SIREN }

/**
 * Runs one light/sound mode at a time and stops everything in [stop]. The screen calls [stop] when it
 * leaves, so nothing keeps running in the background (battery rule).
 */
class LightController(private val torch: Torch, private val siren: Siren, private val scope: CoroutineScope) {
    private val mutableMode = MutableStateFlow(LightMode.OFF)
    val mode: StateFlow<LightMode> = mutableMode.asStateFlow()

    /** Screen-flash fallback state when there is no torch (the UI paints white while this is true). */
    private val mutableLit = MutableStateFlow(false)
    val lit: StateFlow<Boolean> = mutableLit.asStateFlow()

    private var job: Job? = null

    fun start(mode: LightMode) {
        stop()
        mutableMode.value = mode
        job = when (mode) {
            LightMode.OFF -> null
            LightMode.TORCH -> { light(true); null }
            LightMode.STROBE -> scope.launch { while (isActive) { light(true); delay(80); light(false); delay(120) } }
            LightMode.MORSE_SOS -> scope.launch {
                while (isActive) Morse.sos().forEachIndexed { i, ms -> light(i % 2 == 0); delay(ms) }
            }
            LightMode.SIREN -> scope.launch { while (isActive) { siren.beep(900); delay(1000) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        light(false)
        siren.stop()
        mutableMode.value = LightMode.OFF
    }

    private fun light(on: Boolean) {
        if (torch.available) torch.set(on)
        mutableLit.value = on
    }
}

@Serializable
data class HandbookText(val en: String, val bn: String) {
    fun pick(language: String) = if (language == "bn") bn else en
}

@Serializable
data class HandbookSection(val id: String, val group: String, val icon: String = "handbook", val title: HandbookText, val file: HandbookText)

@Serializable
data class EmergencyNumber(val number: String, val label: HandbookText, val note: HandbookText? = null)

@Serializable
data class HandbookIndex(val sections: List<HandbookSection>, val emergencyNumbers: List<EmergencyNumber> = emptyList())

/** The bundled bn + en emergency handbook (`data/handbook`, ~365 KB, works offline). */
class Handbook(private val read: (String) -> String) {
    val index: HandbookIndex by lazy { json.decodeFromString(HandbookIndex.serializer(), read("handbook/index.json")) }

    fun page(section: HandbookSection, language: String): String = read("handbook/" + section.file.pick(language))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromAssets(context: Context) = Handbook { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
    }
}
