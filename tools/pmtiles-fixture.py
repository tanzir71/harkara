"""Writes the small PMTiles v3 archives the core PmTilesReader tests read.

Uses the reference Python implementation (`pip install pmtiles`, protomaps/PMTiles, BSD-3-Clause) for
tile ids, directories and the header, so the Kotlin reader is checked against an independent writer.

  python tools/pmtiles-fixture.py

Outputs (committed):
  core/src/test/resources/pmtiles/fixture-leaves.pmtiles  gzip directories, root -> leaf directories
                                                          (8 entries per leaf), run-length entries,
                                                          gzip tiles, Dhaka bounds
  core/src/test/resources/pmtiles/fixture-flat.pmtiles    uncompressed directories and tiles, root only

Every tile's content is the text "tile z/x/y", except a run of tile ids 30..45 that all hold "ocean".
"""

import gzip
import io
import json
import math
import os

from pmtiles.tile import Compression, Entry, TileType, serialize_header, write_varint, zxy_to_tileid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "core", "src", "test", "resources", "pmtiles")


def tile_xy(lat, lng, z):
    n = 2 ** z
    x = int((lng + 180) / 360 * n)
    y = int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def tiles():
    out = {}
    for z in range(0, 4):
        for x in range(2 ** z):
            for y in range(2 ** z):
                out[zxy_to_tileid(z, x, y)] = (z, x, y)
    cx, cy = tile_xy(23.746, 90.376, 14)  # Dhanmondi
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            out[zxy_to_tileid(14, cx + dx, cy + dy)] = (14, cx + dx, cy + dy)
    return dict(sorted(out.items()))


def content(tile_id, zxy):
    if 30 <= tile_id < 46:
        return b"ocean"
    return ("tile %d/%d/%d" % zxy).encode()


def serialize_directory(entries, compress):
    b = io.BytesIO()
    write_varint(b, len(entries))
    last = 0
    for e in entries:
        write_varint(b, e.tile_id - last)
        last = e.tile_id
    for e in entries:
        write_varint(b, e.run_length)
    for e in entries:
        write_varint(b, e.length)
    for i, e in enumerate(entries):
        if i > 0 and e.offset == entries[i - 1].offset + entries[i - 1].length:
            write_varint(b, 0)
        else:
            write_varint(b, e.offset + 1)
    raw = b.getvalue()
    return gzip.compress(raw, mtime=0) if compress else raw


def build(path, compress, leaf_size):
    entries = []
    data = io.BytesIO()
    by_content = {}
    for tile_id, zxy in tiles().items():
        body = content(tile_id, zxy)
        stored = gzip.compress(body, mtime=0) if compress else body
        if body in by_content:
            off, length = by_content[body]
            last = entries[-1]
            if last.offset == off and last.tile_id + last.run_length == tile_id:
                last.run_length += 1
            else:
                entries.append(Entry(tile_id, off, length, 1))
        else:
            off = data.tell()
            data.write(stored)
            by_content[body] = (off, len(stored))
            entries.append(Entry(tile_id, off, len(stored), 1))

    leaves = b""
    if leaf_size:
        roots = []
        for i in range(0, len(entries), leaf_size):
            chunk = serialize_directory(entries[i:i + leaf_size], compress)
            roots.append(Entry(entries[i].tile_id, len(leaves), len(chunk), 0))
            leaves += chunk
        root = serialize_directory(roots, compress)
    else:
        root = serialize_directory(entries, compress)

    meta = json.dumps({
        "name": "harkara-test-fixture",
        "attribution": "test data",
        "vector_layers": [{"id": "places", "fields": {"name": "String"}}],
    }, separators=(",", ":")).encode()
    meta = gzip.compress(meta, mtime=0) if compress else meta
    tile_data = data.getvalue()
    comp = Compression.GZIP if compress else Compression.NONE
    header = {
        "root_offset": 127,
        "root_length": len(root),
        "metadata_offset": 127 + len(root),
        "metadata_length": len(meta),
        "leaf_directory_offset": 127 + len(root) + len(meta),
        "leaf_directory_length": len(leaves),
        "tile_data_offset": 127 + len(root) + len(meta) + len(leaves),
        "tile_data_length": len(tile_data),
        "addressed_tiles_count": len(tiles()),
        "tile_entries_count": len(entries),
        "tile_contents_count": len(by_content),
        "clustered": True,
        "internal_compression": comp,
        "tile_compression": comp,
        "tile_type": TileType.MVT,
        "min_zoom": 0,
        "max_zoom": 14,
        "min_lon_e7": 903000000,
        "min_lat_e7": 236500000,
        "max_lon_e7": 905500000,
        "max_lat_e7": 239200000,
        "center_zoom": 12,
        "center_lon_e7": 904250000,
        "center_lat_e7": 237850000,
    }
    with open(path, "wb") as f:
        f.write(serialize_header(header))
        f.write(root)
        f.write(meta)
        f.write(leaves)
        f.write(tile_data)
    print(path, os.path.getsize(path), "bytes,", len(entries), "entries,", len(by_content), "contents")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build(os.path.join(OUT, "fixture-leaves.pmtiles"), compress=True, leaf_size=8)
    build(os.path.join(OUT, "fixture-flat.pmtiles"), compress=False, leaf_size=0)
