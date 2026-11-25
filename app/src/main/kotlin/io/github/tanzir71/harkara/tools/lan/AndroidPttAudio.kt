package io.github.tanzir71.harkara.tools.lan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.github.tanzir71.harkara.core.tools.PttChunk
import io.github.tanzir71.harkara.core.tools.PttFormat

/** Constructor is inert. The permission gate and AudioRecord allocation are only reached on press. */
class AndroidPttMicrophone(private val context: Context) : PttMicrophone {
    @Volatile private var record: AudioRecord? = null

    @Synchronized
    override fun start(): Boolean {
        if (record != null) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        var audio: AudioRecord? = null
        return try {
            val minimum = AudioRecord.getMinBufferSize(PttFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return false
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, PttFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 2 * PttFormat.CHUNK_BYTES))
            if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); return false }
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) { audio.release(); return false }
            record = audio
            true
        } catch (_: Exception) {
            audio?.release()
            false
        }
    }

    override fun read(buffer: ByteArray): Int {
        require(buffer.size <= PttFormat.CHUNK_BYTES)
        return record?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
    }

    @Synchronized
    override fun close() {
        val audio = record ?: return
        record = null
        runCatching { audio.stop() }
        audio.release()
    }
}

/** Bounded streaming playback. Allocate only after an explicitly enabled PttReceiver accepts audio. */
class AndroidPttSpeaker : AutoCloseable {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(chunk: PttChunk) {
        require(chunk.pcm.size in 2..PttFormat.CHUNK_BYTES && chunk.pcm.size % 2 == 0)
        val audio = track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(PttFormat.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(PttFormat.CHUNK_BYTES, AudioTrack.getMinBufferSize(PttFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it; it.play() }
        // Blocking writes provide backpressure rather than building an unbounded playout queue.
        var offset = 0
        while (offset < chunk.pcm.size) {
            val written = audio.write(chunk.pcm, offset, chunk.pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "PTT playback failed" }
            offset += written
        }
    }

    @Synchronized
    override fun close() {
        track?.let { audio -> runCatching { audio.stop() }; audio.release() }
        track = null
    }
}
