package io.github.tanzir71.harkara.core.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Thrown when bytes are not a PMTiles v3 archive this reader understands. */
class PmTilesException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** PMTiles compression codes (spec v3). */
enum class PmCompression(val code: Int) {
    UNKNOWN(0), NONE(1), GZIP(2), BROTLI(3), ZSTD(4);

    companion object {
        fun of(code: Int): PmCompression = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** PMTiles tile types (spec v3). */
enum class PmTileType(val code: Int) {
    UNKNOWN(0), MVT(1), PNG(2), JPEG(3), WEBP(4), AVIF(5), MLT(6);

    companion object {
        fun of(code: Int): PmTileType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** The fixed 127-byte PMTiles v3 header. Bounds are in degrees. */
data class PmTilesHeader(
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoryOffset: Long,
    val leafDirectoryLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: PmCompression,
    val tileCompression: PmCompression,
    val tileType: PmTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    /** True when the archive's bounds cover the point. */
    fun contains(lat: Double, lng: Double): Boolean = lat in minLat..maxLat && lng in minLon..maxLon

    companion object {
        const val SIZE = 127
        private val MAGIC = "PMTiles".encodeToByteArray()

        fun parse(bytes: ByteArray): PmTilesHeader {
            if (bytes.size < SIZE) throw PmTilesException("too short for a PMTiles header")
            if (!bytes.copyOfRange(0, 7).contentEquals(MAGIC)) throw PmTilesException("not a PMTiles archive")
            if (bytes[7].toInt() != 3) throw PmTilesException("unsupported PMTiles version ${bytes[7]}")
            val b = ByteBuffer.wrap(bytes, 0, SIZE).order(ByteOrder.LITTLE_ENDIAN)
            fun u64(at: Int): Long = b.getLong(at).also { if (it < 0) throw PmTilesException("offset out of range") }
            fun u8(at: Int): Int = bytes[at].toInt() and 0xFF
            fun e7(at: Int): Double = b.getInt(at) / 1e7
            return PmTilesHeader(
                rootOffset = u64(8), rootLength = u64(16),
                metadataOffset = u64(24), metadataLength = u64(32),
                leafDirectoryOffset = u64(40), leafDirectoryLength = u64(48),
                tileDataOffset = u64(56), tileDataLength = u64(64),
                addressedTiles = u64(72), tileEntries = u64(80), tileContents = u64(88),
                clustered = u8(96) == 1,
                internalCompression = PmCompression.of(u8(97)),
                tileCompression = PmCompression.of(u8(98)),
                tileType = PmTileType.of(u8(99)),
                minZoom = u8(100), maxZoom = u8(101),
                minLon = e7(102), minLat = e7(106), maxLon = e7(110), maxLat = e7(114),
                centerZoom = u8(118), centerLon = e7(119), centerLat = e7(123),
            )
        }
    }
}

/** One directory entry: a run of [runLength] tiles from [tileId], or a leaf directory when [runLength] is 0. */
data class PmEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

/** Random-access bytes of an archive (a file on the phone, or a byte array in tests). */
interface PmTilesSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArrayPmTilesSource(private val bytes: ByteArray) : PmTilesSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) throw PmTilesException("read past the end of the archive")
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
    override fun close() = Unit
}

class FilePmTilesSource(file: File) : PmTilesSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long get() = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > raf.length()) throw PmTilesException("read past the end of the archive")
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/**
 * Pure-Kotlin PMTiles v3 reader: header, root and leaf directories (varint, delta-coded, run-length
 * entries, gzip or uncompressed), and z/x/y lookup through the Hilbert tile id. Used to check map packs
 * before they are installed and to read single tiles; MapLibre reads the installed pack itself.
 */
class PmTilesReader(private val source: PmTilesSource) : Closeable {
    val header: PmTilesHeader = PmTilesHeader.parse(source.read(0, minOf(PmTilesHeader.SIZE.toLong(), source.size).toInt()))
    private val root: List<PmEntry>

    init {
        val h = header
        if (h.rootOffset + h.rootLength > source.size || h.tileDataOffset + h.tileDataLength > source.size) {
            throw PmTilesException("archive is truncated")
        }
        root = readDirectory(h.rootOffset, h.rootLength.toInt())
    }

    /** The JSON metadata block, decompressed. */
    fun metadata(): String {
        if (header.metadataLength == 0L) return "{}"
        return decompress(source.read(header.metadataOffset, header.metadataLength.toInt()), header.internalCompression).decodeToString()
    }

    /** The stored bytes of tile z/x/y (still in [PmTilesHeader.tileCompression]), or null when absent. */
    fun rawTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = TileIds.zxyToTileId(z, x, y)
        var dir = root
        repeat(MAX_DEPTH) {
            val entry = findTile(dir, id) ?: return null
            if (entry.runLength > 0) {
                return source.read(header.tileDataOffset + entry.offset, entry.length)
            }
            dir = readDirectory(header.leafDirectoryOffset + entry.offset, entry.length)
        }
        return null
    }

    /** Tile z/x/y with its compression removed (raw MVT bytes for vector packs), or null when absent. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = rawTile(z, x, y)?.let { decompress(it, header.tileCompression) }

    override fun close() = source.close()

    private fun readDirectory(offset: Long, length: Int): List<PmEntry> =
        Directories.parse(decompress(source.read(offset, length), header.internalCompression))

    companion object {
        private const val MAX_DEPTH = 4

        fun open(file: File): PmTilesReader {
            val source = FilePmTilesSource(file)
            return try {
                PmTilesReader(source)
            } catch (e: Exception) {
                source.close()
                throw e as? PmTilesException ?: PmTilesException("cannot read archive", e)
            }
        }

        /** Spec `find_tile`: the exact entry, or the run / leaf directory that covers [tileId]. */
        fun findTile(entries: List<PmEntry>, tileId: Long): PmEntry? {
            var m = 0
            var n = entries.size - 1
            while (m <= n) {
                val k = (n + m) ushr 1
                val c = tileId.compareTo(entries[k].tileId)
                when {
                    c > 0 -> m = k + 1
                    c < 0 -> n = k - 1
                    else -> return entries[k]
                }
            }
            if (n >= 0) {
                val e = entries[n]
                if (e.runLength == 0) return e
                if (tileId - e.tileId < e.runLength) return e
            }
            return null
        }

        fun decompress(bytes: ByteArray, compression: PmCompression): ByteArray = when (compression) {
            PmCompression.NONE, PmCompression.UNKNOWN -> bytes
            PmCompression.GZIP -> try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (e: IOException) {
                throw PmTilesException("bad gzip data", e)
            }
            else -> throw PmTilesException("unsupported compression $compression")
        }
    }
}

/** PMTiles directory encoding. */
object Directories {
    fun parse(bytes: ByteArray): List<PmEntry> {
        val r = VarintReader(bytes)
        val n = r.next()
        if (n < 0 || n > bytes.size) throw PmTilesException("bad directory size")
        val count = n.toInt()
        val ids = LongArray(count)
        var last = 0L
        for (i in 0 until count) { last += r.next(); ids[i] = last }
        val runs = IntArray(count) { r.next().toInt() }
        val lengths = IntArray(count) { r.next().toInt() }
        val offsets = LongArray(count)
        for (i in 0 until count) {
            val v = r.next()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(count) { PmEntry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    /** The inverse of [parse] (uncompressed); used for building archives in tests and future pack tools. */
    fun serialize(entries: List<PmEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(v: Long) {
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) { out.write(b); return }
                out.write(b or 0x80)
            }
        }
        put(entries.size.toLong())
        var last = 0L
        entries.forEach { put(it.tileId - last); last = it.tileId }
        entries.forEach { put(it.runLength.toLong()) }
        entries.forEach { put(it.length.toLong()) }
        entries.forEachIndexed { i, e ->
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) put(0) else put(e.offset + 1)
        }
        return out.toByteArray()
    }

    private class VarintReader(private val bytes: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= bytes.size) throw PmTilesException("directory ends inside a varint")
                if (shift > 63) throw PmTilesException("varint too long")
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}

/** Tile ids: zoom levels stacked, each level ordered along a Hilbert curve (PMTiles v3). */
object TileIds {
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom out of range" }
        val max = (1L shl z) - 1
        require(x in 0..max && y in 0..max) { "tile x/y outside zoom $z" }
        var acc = ((1L shl (z * 2)) - 1) / 3
        var tx = x.toLong()
        var ty = y.toLong()
        var a = z - 1
        while (a >= 0) {
            val s = 1L shl a
            val rx = s and tx
            val ry = s and ty
            acc += ((3 * rx) xor ry) shl a
            if (ry == 0L) {
                if (rx != 0L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            a--
        }
        return acc
    }

    fun tileIdToZxy(tileId: Long): Triple<Int, Int, Int> {
        require(tileId >= 0) { "negative tile id" }
        val z = (63 - java.lang.Long.numberOfLeadingZeros(3 * tileId + 1)) / 2
        val acc = ((1L shl (z * 2)) - 1) / 3
        var pos = tileId - acc
        var x = 0L
        var y = 0L
        var s = 1L
        val n = 1L shl z
        while (s < n) {
            val rx = (pos / 2) and s
            val ry = (pos xor rx) and s
            if (ry == 0L) {
                if (rx != 0L) { x = s - 1 - x; y = s - 1 - y }
                val t = x; x = y; y = t
            }
            x += rx
            y += ry
            pos = pos shr 1
            s = s shl 1
        }
        return Triple(z, x.toInt(), y.toInt())
    }

    /** Web-Mercator tile containing a point at zoom [z]. */
    fun tileFor(lat: Double, lng: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val r = Math.toRadians(lat)
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / Math.PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }
}
