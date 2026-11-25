package io.github.tanzir71.harkara.core.map

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Fixtures written by tools/pmtiles-fixture.py with the reference Python implementation. */
class PmTilesTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/pmtiles/$name")!!.use { it.readBytes() }
    private fun reader(name: String) = PmTilesReader(ByteArrayPmTilesSource(bytes(name)))

    @Test
    fun `tile ids follow the spec's Hilbert order`() {
        val expected = mapOf(
            Triple(0, 0, 0) to 0L, Triple(1, 0, 0) to 1L, Triple(1, 0, 1) to 2L, Triple(1, 1, 1) to 3L,
            Triple(1, 1, 0) to 4L, Triple(2, 0, 0) to 5L, Triple(12, 3423, 1763) to 19_078_479L,
            Triple(14, 12304, 7078) to 303_735_571L,
        )
        expected.forEach { (zxy, id) ->
            assertEquals(id, TileIds.zxyToTileId(zxy.first, zxy.second, zxy.third), "$zxy")
            assertEquals(zxy, TileIds.tileIdToZxy(id))
        }
        for (z in 0..6) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
            assertEquals(Triple(z, x, y), TileIds.tileIdToZxy(TileIds.zxyToTileId(z, x, y)))
        }
        assertThrows<IllegalArgumentException> { TileIds.zxyToTileId(2, 4, 0) }
    }

    @Test
    fun `header of the Dhaka-bounded fixture`() {
        val h = reader("fixture-leaves.pmtiles").header
        assertEquals(PmTileType.MVT, h.tileType)
        assertEquals(PmCompression.GZIP, h.internalCompression)
        assertEquals(PmCompression.GZIP, h.tileCompression)
        assertEquals(0, h.minZoom)
        assertEquals(14, h.maxZoom)
        assertEquals(90.30, h.minLon, 1e-9)
        assertEquals(23.92, h.maxLat, 1e-9)
        assertEquals(94L, h.addressedTiles)
        assertEquals(79L, h.tileEntries)
        assertTrue(h.clustered)
        assertTrue(h.leafDirectoryLength > 0)
        assertTrue(h.contains(23.746, 90.376))
        assertFalse(h.contains(22.35, 91.78))
    }

    @Test
    fun `tiles through gzip leaf directories, runs and misses`() {
        val r = reader("fixture-leaves.pmtiles")
        assertEquals("tile 0/0/0", r.tile(0, 0, 0)!!.decodeToString())
        assertEquals("tile 3/5/2", r.tile(3, 5, 2)!!.decodeToString())
        assertEquals("tile 14/12304/7078", r.tile(14, 12304, 7078)!!.decodeToString())
        // Tile ids 30..45 are one run-length entry pointing at the same bytes.
        for (id in 30L..45L) {
            val (z, x, y) = TileIds.tileIdToZxy(id)
            assertEquals("ocean", r.tile(z, x, y)!!.decodeToString(), "tile id $id")
        }
        assertEquals("tile 3/2/7", r.tile(3, 2, 7)!!.decodeToString())
        assertNull(r.tile(14, 0, 0))
        assertNull(r.tile(4, 1, 1))
        assertNull(r.tile(15, 24608, 14156))
        assertTrue(r.rawTile(0, 0, 0)!!.let { it[0] == 0x1f.toByte() && it[1] == 0x8b.toByte() })
        assertTrue(r.metadata().contains("\"harkara-test-fixture\""))
    }

    @Test
    fun `uncompressed root-only archive reads the same tiles`() {
        val flat = reader("fixture-flat.pmtiles")
        val leaves = reader("fixture-leaves.pmtiles")
        assertEquals(PmCompression.NONE, flat.header.internalCompression)
        assertEquals(0L, flat.header.leafDirectoryLength)
        for (id in 0L..84L) {
            val (z, x, y) = TileIds.tileIdToZxy(id)
            assertArrayEquals(leaves.tile(z, x, y), flat.tile(z, x, y), "tile id $id")
        }
        assertTrue(flat.metadata().contains("vector_layers"))
    }

    @Test
    fun `directory encoding round-trips`() {
        val entries = listOf(PmEntry(0, 0, 10, 1), PmEntry(1, 10, 20, 1), PmEntry(5, 100, 7, 3), PmEntry(9, 0, 10, 0))
        assertEquals(entries, Directories.parse(Directories.serialize(entries)))
        assertEquals(entries[2], PmTilesReader.findTile(entries, 7))
        assertNull(PmTilesReader.findTile(entries, 8))
        assertEquals(entries[3], PmTilesReader.findTile(entries, 1000))
    }

    @Test
    fun `garbage, truncation and the wrong version are refused`() {
        assertThrows<PmTilesException> { PmTilesReader(ByteArrayPmTilesSource("not a map".encodeToByteArray())) }
        val good = bytes("fixture-leaves.pmtiles")
        assertThrows<PmTilesException> { PmTilesReader(ByteArrayPmTilesSource(good.copyOf(good.size - 40))) }
        val v2 = good.copyOf().also { it[7] = 2 }
        assertThrows<PmTilesException> { PmTilesReader(ByteArrayPmTilesSource(v2)) }
    }

    @Test
    fun `file source reads from disk`() {
        val file = File.createTempFile("pack", ".pmtiles").apply { writeBytes(bytes("fixture-leaves.pmtiles")); deleteOnExit() }
        PmTilesReader.open(file).use { assertEquals("tile 1/1/0", it.tile(1, 1, 0)!!.decodeToString()) }
    }
}
