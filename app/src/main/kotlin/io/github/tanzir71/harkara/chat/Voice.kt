package io.github.tanzir71.harkara.chat

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.core.chat.VoiceNotes
import java.io.File

/** Records one voice note at a time; stops by itself at 15 s. A fake in tests (the microphone is hardware). */
interface VoiceRecorder {
    val isRecording: Boolean

    /** Starts recording; false when the microphone can't be used (no permission, busy, no hardware). */
    fun start(): Boolean

    /** Stops and returns the note, or null when it was too short or failed. */
    fun stop(): VoiceClip?

    fun cancel()
}

/** Plays a voice note out loud. */
interface VoicePlayer {
    fun play(clip: VoiceClip)

    fun stop()
}

/**
 * MediaRecorder: Opus in OGG on Android 10+ (16 kbps), AAC in MP4 below (24 kbps); mono, 16 kHz, so 15 s
 * stays around 30–45 KB, which is at most 12 parts of 4 KB. Unverified on hardware (docs/HARDWARE.md).
 */
class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var mime = ""

    override val isRecording: Boolean get() = recorder != null

    override fun start(): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice-${System.nanoTime()}.rec")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else newLegacyRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(16_000)
                mime = "audio/ogg"
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(24_000)
                mime = "audio/mp4"
            }
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setMaxDuration(VoiceNotes.MAX_DURATION_MS.toInt())
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        }.getOrElse {
            r.release()
            out.delete()
            false
        }
    }

    override fun stop(): VoiceClip? {
        val r = recorder ?: return null
        val f = file
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(VoiceNotes.MAX_DURATION_MS)
        recorder = null
        file = null
        // stop() throws when nothing was captured, or after the 15 s limit already stopped it.
        runCatching { r.stop() }
        r.release()
        val bytes = f?.takeIf { it.exists() }?.readBytes()
        f?.delete()
        if (bytes == null || bytes.isEmpty() || duration < MIN_MILLIS || bytes.size > VoiceNotes.MAX_BYTES) return null
        return VoiceClip(bytes, mime, duration)
    }

    override fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }

    @Suppress("DEPRECATION")
    private fun newLegacyRecorder() = MediaRecorder()

    private companion object {
        const val MIN_MILLIS = 400L
    }
}

/** MediaPlayer from a temporary file in the cache (deleted when playback ends). */
class AndroidVoicePlayer(private val context: Context) : VoicePlayer {
    private var player: MediaPlayer? = null
    private var file: File? = null

    override fun play(clip: VoiceClip) {
        stop()
        val f = File(context.cacheDir, "voice-play" + if (clip.mime == "audio/ogg") ".ogg" else ".m4a")
        runCatching {
            f.writeBytes(clip.bytes)
            file = f
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { this@AndroidVoicePlayer.stop() }
                prepare()
                start()
            }
        }.onFailure { stop() }
    }

    override fun stop() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        file?.delete()
        file = null
    }
}
