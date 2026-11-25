package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import java.security.SecureRandom
import java.util.Base64

/** A recorded voice note: encoded audio (Opus/OGG or AAC), its MIME type and length. */
class VoiceClip(val bytes: ByteArray, val mime: String, val durationMs: Long) {
    val sha256: String get() = Hex.encode(Hashes.sha256(bytes))
}

/** One part of a voice note as carried in a kind 15 rumor. */
class VoicePart(
    val voiceId: String,
    val index: Int,
    val count: Int,
    val mime: String,
    val durationMs: Long,
    val sha256: String,
    val data: ByteArray,
)

/**
 * Voice notes (≤ 15 s) travel inline, with no file server: the audio is cut into parts of [CHUNK_BYTES]
 * and each part is a NIP-17 kind 15 rumor whose content is the part in Base64 and whose tags are
 * `voice` (note id), `part` (index, count), `m` / `file-type` (MIME), `duration` (ms) and `x` (SHA-256
 * of the whole note). Every part is wrapped on its own, so each gift wrap stays under 16 KB; the receiver
 * puts the note together when every part has arrived and the hash matches.
 */
object VoiceNotes {
    const val MAX_DURATION_MS = 15_000L

    /** Recorders stop at 15 s; a little slack for container rounding. */
    private const val DURATION_SLACK_MS = 500L
    const val CHUNK_BYTES = 4_096
    const val MAX_PARTS = 32
    const val MAX_BYTES = CHUNK_BYTES * MAX_PARTS

    private val ID = Regex("[0-9a-f]{32}")
    private val random = SecureRandom()

    fun newVoiceId(): String = ByteArray(16).also(random::nextBytes).let(Hex::encode)

    /** Kind 15 rumors for [clip], one per part, from [sender] to [members]. */
    fun split(
        clip: VoiceClip,
        sender: String,
        members: Collection<String>,
        createdAt: Long,
        voiceId: String = newVoiceId(),
        name: String? = null,
    ): List<Rumor> {
        require(clip.bytes.isNotEmpty()) { "empty voice note" }
        require(clip.durationMs in 1..MAX_DURATION_MS + DURATION_SLACK_MS) { "voice notes are at most 15 s" }
        require(clip.bytes.size <= MAX_BYTES) { "voice note too large" }
        require(ID.matches(voiceId)) { "voice id must be 16 bytes of hex" }
        val chunks = clip.bytes.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
        val hash = clip.sha256
        val recipients = members.filter { it != sender }.distinct().ifEmpty { listOf(sender) }
        return chunks.mapIndexed { i, chunk ->
            val tags = buildList {
                recipients.forEach { add(listOf("p", it)) }
                add(listOf("voice", voiceId))
                add(listOf("part", i.toString(), chunks.size.toString()))
                add(listOf("m", clip.mime))
                add(listOf("file-type", clip.mime))
                add(listOf("duration", clip.durationMs.toString()))
                add(listOf("x", hash))
                if (name != null && ChatProtocol.checkName(name) == ChatVerdict.OK) add(listOf(ChatProtocol.NAME_TAG, name.trim()))
            }
            GiftWrap.createRumor(sender, Kinds.FILE_MESSAGE, Base64.getEncoder().encodeToString(chunk), tags, createdAt)
        }
    }

    /** The part in a kind 15 rumor, or null when it isn't a well-formed Harkara voice part. */
    fun parsePart(rumor: Rumor): VoicePart? = runCatching {
        if (rumor.kind != Kinds.FILE_MESSAGE) return null
        val id = rumor.tagValue("voice")?.takeIf { ID.matches(it) } ?: return null
        val part = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "part" } ?: return null
        val index = part[1].toInt()
        val count = part[2].toInt()
        if (count !in 1..MAX_PARTS || index !in 0 until count) return null
        val mime = (rumor.tagValue("m") ?: rumor.tagValue("file-type"))?.takeIf { it.startsWith("audio/") && it.length <= 64 } ?: return null
        val duration = rumor.tagValue("duration")?.toLong()?.takeIf { it in 1..MAX_DURATION_MS + DURATION_SLACK_MS } ?: return null
        val hash = rumor.tagValue("x")?.takeIf { Hex.isLowerHex(it, 32) } ?: return null
        val data = Base64.getDecoder().decode(rumor.content)
        if (data.isEmpty() || data.size > CHUNK_BYTES) return null
        VoicePart(id, index, count, mime, duration, hash, data)
    }.getOrNull()

    /**
     * Joins [parts] of one note. Null while parts are missing, or when they disagree (count, MIME, hash)
     * or the joined audio doesn't match its `x` hash.
     */
    fun assemble(parts: Collection<VoicePart>): VoiceClip? {
        if (parts.isEmpty()) return null
        val first = parts.first()
        if (parts.any { it.voiceId != first.voiceId || it.count != first.count || it.mime != first.mime || it.sha256 != first.sha256 }) return null
        val byIndex = parts.associateBy { it.index }
        if (byIndex.size != first.count || (0 until first.count).any { it !in byIndex }) return null
        val bytes = (0 until first.count).fold(ByteArray(0)) { acc, i -> acc + byIndex.getValue(i).data }
        val clip = VoiceClip(bytes, first.mime, first.durationMs)
        return clip.takeIf { it.sha256 == first.sha256 }
    }
}
