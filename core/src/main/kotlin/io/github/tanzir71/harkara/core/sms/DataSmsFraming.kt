package io.github.tanzir71.harkara.core.sms

/**
 * Splits an SMS payload ([io.github.tanzir71.harkara.core.wire.SmsCodec]) into binary data SMS for the
 * "Send SMS directly" switch. `SmsManager.sendDataMessage` has no multipart form and a port-addressed data
 * SMS carries 133 bytes, so each frame is `0x48 'H' ‖ u8 set ‖ u8 (index shl 4 | count) ‖ chunk` with
 * index and count 1..15. Frames reassemble in any order; incomplete sets expire.
 */
object DataSmsFraming {
    /** Port the app listens on for data SMS (a fixed, otherwise unused port). */
    const val PORT: Short = 7471
    const val MAGIC = 0x48
    const val HEADER = 3

    /** User data in one port-addressed data SMS: 140 bytes minus the 7-byte port header. */
    const val MAX_FRAME = 133
    const val MAX_FRAMES = 15

    fun split(payload: ByteArray, set: Int): List<ByteArray> {
        val room = MAX_FRAME - HEADER
        val chunks = payload.toList().chunked(room).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        require(chunks.size <= MAX_FRAMES) { "payload too large for data SMS" }
        return chunks.mapIndexed { i, chunk ->
            byteArrayOf(MAGIC.toByte(), (set and 0xff).toByte(), (((i + 1) shl 4) or chunks.size).toByte()) + chunk
        }
    }

    data class Frame(val set: Int, val index: Int, val count: Int, val chunk: ByteArray)

    fun parse(frame: ByteArray): Frame? {
        if (frame.size < HEADER || frame[0].toInt() != MAGIC) return null
        val pos = frame[2].toInt() and 0xff
        val index = pos ushr 4
        val count = pos and 0x0f
        if (count == 0 || index !in 1..count) return null
        return Frame(frame[1].toInt() and 0xff, index, count, frame.copyOfRange(HEADER, frame.size))
    }
}

/** Reassembles data SMS frames per sender; returns the payload once every frame of a set arrived. */
class DataSmsAssembler(private val nowMillis: () -> Long = System::currentTimeMillis, private val keepMillis: Long = 10 * 60_000L) {
    private class Pending(val count: Int, val parts: MutableMap<Int, ByteArray>, var touched: Long)

    private val pending = LinkedHashMap<String, Pending>()

    fun add(sender: String, frameBytes: ByteArray): ByteArray? {
        val frame = DataSmsFraming.parse(frameBytes) ?: return null
        val now = nowMillis()
        pending.entries.removeAll { now - it.value.touched > keepMillis }
        while (pending.size > 32) pending.remove(pending.keys.first())
        val key = GatewayPolicy.normalize(sender) + "#" + frame.set
        val p = pending.getOrPut(key) { Pending(frame.count, HashMap(), now) }
        if (p.count != frame.count) {
            pending.remove(key)
            return null
        }
        p.parts[frame.index] = frame.chunk
        p.touched = now
        if (p.parts.size < p.count) return null
        pending.remove(key)
        return (1..p.count).fold(ByteArray(0)) { acc, i -> acc + p.parts.getValue(i) }
    }
}
